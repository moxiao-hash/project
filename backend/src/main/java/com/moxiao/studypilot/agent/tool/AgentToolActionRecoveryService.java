package com.moxiao.studypilot.agent.tool;

import com.moxiao.studypilot.agent.api.UpdateAgentExecutionRequest;
import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.notification.api.CreateNotificationRequest;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.notification.domain.NotificationType;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 30：孤儿 RUNNING 动作的持久化恢复/对账。
 *
 * <p>执行尝试在进入 RUNNING 时登记带租约的 fencing token；业务成功与 SUCCEEDED 在同一事务提交。
 * 因此进程崩溃或定稿失败后遗留的过期租约 RUNNING 动作，可以被恢复流程安全接管：
 * 通过条件更新抢占租约（两个实例并发只有一个成功），再以 owner 作用域把动作与治理执行收敛为
 * FAILED，并写入通知与审计。已提交的副作用不会遗留 SUCCEEDED 丢失问题；不确定的
 * LOCAL/非事务副作用不会被自动重跑，而是要求人工核对后重新发起。</p>
 */
@Service
public class AgentToolActionRecoveryService {

    private static final Logger LOG =
            LoggerFactory.getLogger(AgentToolActionRecoveryService.class);

    /** 每轮恢复处理的动作上限，保证有界。 */
    static final int BATCH_SIZE = 50;

    /** 恢复接管时使用的短租约，失败后自然过期以便重试。 */
    static final Duration RECOVERY_LEASE = Duration.ofSeconds(30);

    /** 恢复后的用户可见说明；不含堆栈或内部细节。 */
    static final String RECOVERY_MESSAGE =
            "执行进程中断或结果未定，已停止自动执行以避免重复副作用；请人工核对后重新发起。";

    private final AgentToolActionJpaRepository repository;
    private final AgentGovernanceService governanceService;
    private final NotificationService notificationService;
    private final TransactionTemplate requiresNew;
    private final String instanceId = UUID.randomUUID().toString();

    public AgentToolActionRecoveryService(
            AgentToolActionJpaRepository repository,
            AgentGovernanceService governanceService,
            NotificationService notificationService,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.governanceService = governanceService;
        this.notificationService = notificationService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 进程启动即执行一次恢复，处理上次崩溃遗留的 RUNNING 动作。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup(ApplicationReadyEvent event) {
        int recovered = recoverExpiredActions();
        if (recovered > 0) {
            LOG.info("启动恢复接管 {} 个孤儿 Agent 工具动作", recovered);
        }
    }

    /**
     * 扫描并恢复租约已过期的 RUNNING 动作；返回本轮成功接管的数量。
     */
    public int recoverExpiredActions() {
        Instant now = Instant.now();
        List<AgentToolActionEntity> candidates = requiresNew.execute(status ->
                repository.findRecoverable(
                        AgentToolActionStatus.RUNNING, now, PageRequest.of(0, BATCH_SIZE)));
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int recovered = 0;
        for (AgentToolActionEntity candidate : candidates) {
            recovered += recoverOne(candidate.getId(), instanceId);
        }
        return recovered;
    }

    @Scheduled(
            initialDelayString = "${studypilot.agent-tool-recovery-initial-delay-ms:15000}",
            fixedDelayString = "${studypilot.agent-tool-recovery-delay-ms:30000}")
    public void scheduledRecovery() {
        recoverExpiredActions();
    }

    /**
     * 恢复单个动作：条件抢占租约，成功者在同一事务内把动作/执行收敛为 FAILED。
     *
     * <p>瞬时持久化失败整体回滚（动作保持 RUNNING），留待下一轮重试；
     * 两个实例并发时只有一个能抢占成功。</p>
     */
    public int recoverOne(String actionId, String recoveryOwner) {
        try {
            Integer result = requiresNew.execute(
                    status -> recoverInTransaction(actionId, recoveryOwner));
            return result == null ? 0 : result;
        } catch (RuntimeException exception) {
            LOG.warn("Agent 工具恢复失败，留待下次重试: {}", actionId, exception);
            return 0;
        }
    }

    private int recoverInTransaction(String actionId, String recoveryOwner) {
        Instant now = Instant.now();
        int claimed = repository.claimRecoveryLease(
                actionId, recoveryOwner, now.plus(RECOVERY_LEASE), now,
                AgentToolActionStatus.RUNNING);
        if (claimed == 0) {
            return 0;
        }
        AgentToolActionEntity action = repository.findById(actionId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent 工具操作不存在"));
        action.fail(RECOVERY_MESSAGE, Instant.now());
        repository.save(action);
        governanceService.update(action.getExecutionId(), new UpdateAgentExecutionRequest(
                ExecutionStatus.FAILED, null, RECOVERY_MESSAGE,
                null, null, null, null, null));
        notificationService.create(new CreateNotificationRequest(
                action.getOwnerId(), NotificationType.AGENT_FAILED,
                "Agent 操作未完成", RECOVERY_MESSAGE));
        return 1;
    }
}
