package com.moxiao.studypilot.agent.tool;

import com.moxiao.studypilot.agent.api.CreateAgentExecutionRequest;
import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.agent.domain.AgentScope;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.domain.ExecutionType;
import com.moxiao.studypilot.agent.domain.RiskLevel;
import com.moxiao.studypilot.agent.domain.TriggerType;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.notification.infrastructure.NotificationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 30 第三次复审：[UNIT/H2] 孤儿 RUNNING 动作的持久化恢复。
 *
 * <p>模拟 JVM 在 RUNNING 期间退出：动作在数据库里保持 RUNNING、租约已过期。
 * 启动/定时恢复必须在租约过期后接管，并在不重复副作用的前提下收敛为终态；
 * 两个恢复实例并发时只能有一个成功接管。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentToolActionRecoveryTest {

    private static final String RECOVERY_MESSAGE_FRAGMENT = "未完成";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentToolActionJpaRepository actionRepository;

    @Autowired
    private AgentToolActionRecoveryService recoveryService;

    @Autowired
    private AgentGovernanceService governanceService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private String ownerId;
    private String executionId;
    private String actionId;

    @BeforeEach
    void createOrphanRunningAction() throws Exception {
        ownerId = registerUser();
        executionId = governanceService.createExecution(new CreateAgentExecutionRequest(
                ownerId,
                "recovery-execution-" + System.nanoTime(),
                ExecutionType.TASK_STATUS_CHANGE,
                TriggerType.USER_REQUEST,
                RiskLevel.HIGH,
                AgentScope.TASK_MANAGEMENT,
                "恢复测试执行"
        )).getId();

        AgentToolActionEntity action = new AgentToolActionEntity(
                UUID.randomUUID().toString(), ownerId, executionId,
                "recovery-action-" + System.nanoTime(), "learning.task.update", 1,
                AgentToolRiskLevel.HIGH, AgentToolActionStatus.READY, "恢复测试动作",
                "{}", Instant.now().plusSeconds(600), Instant.now());
        // 模拟崩溃前已进入 RUNNING，且租约早已过期。
        action.startAttempt("crashed-lease", Instant.now().minusSeconds(300), Instant.now());
        actionRepository.save(action);
        actionId = action.getId();
    }

    @Test
    void restartRecoveryConvergesOrphanRunningActionToFailedExactlyOnce() {
        int recovered = recoveryService.recoverExpiredActions();

        assertTrue(recovered >= 1, "启动恢复必须接管过期租约的 RUNNING 动作");
        AgentToolActionEntity recoveredAction = actionRepository.findById(actionId).orElseThrow();
        assertEquals(AgentToolActionStatus.FAILED, recoveredAction.getStatus());
        assertNotNull(recoveredAction.getErrorMessage());
        assertTrue(recoveredAction.getErrorMessage().contains("结果未定"),
                "恢复错误必须是面向用户的说明");
        assertNull(recoveredAction.getLeaseToken(), "恢复后必须释放租约");
        assertNull(recoveredAction.getLeaseExpiresAt());
        assertEquals(1, recoveredAction.getAttemptCount(), "同一次执行尝试不得被重复计数");

        // 治理执行必须与动作一起收敛为 FAILED，并留下审计。
        assertTrue(governanceService.listExecutions(ownerId).stream()
                        .anyMatch(execution -> execution.getId().equals(executionId)
                                && execution.getStatus() == ExecutionStatus.FAILED),
                "执行记录必须与动作一致地变为 FAILED");
        assertTrue(governanceService.listAuditLogs(ownerId).stream()
                        .anyMatch(log -> "EXECUTION_STATUS_CHANGED".equals(log.getAction())
                                && executionId.equals(log.getTargetId())),
                "恢复必须留下审计记录");

        assertEquals(1, recoveryNotifications().size(), "恢复通知必须恰好一条");

        // 迟到的旧租约完成回调不得复活已恢复的动作。
        Integer lateCompletion = new org.springframework.transaction.support.TransactionTemplate(
                transactionManager)
                .execute(status -> actionRepository.completeIfLeaseHeld(
                        actionId, "crashed-lease", "{}", Instant.now(),
                        AgentToolActionStatus.SUCCEEDED, AgentToolActionStatus.RUNNING));
        assertEquals(0, lateCompletion, "旧租约不得再写入 SUCCEEDED");
        assertEquals(AgentToolActionStatus.FAILED,
                actionRepository.findById(actionId).orElseThrow().getStatus());
    }

    @Test
    void twoConcurrentReconcilersOnlyOneTakesOver() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = pool.submit(() -> {
                start.await();
                return recoveryService.recoverOne(actionId, "instance-a");
            });
            Future<Integer> second = pool.submit(() -> {
                start.await();
                return recoveryService.recoverOne(actionId, "instance-b");
            });
            start.countDown();
            int firstResult = first.get(30, TimeUnit.SECONDS);
            int secondResult = second.get(30, TimeUnit.SECONDS);

            assertEquals(1, firstResult + secondResult,
                    "两个恢复实例并发时只能有一个成功接管同一动作");
            assertEquals(AgentToolActionStatus.FAILED,
                    actionRepository.findById(actionId).orElseThrow().getStatus());
            assertEquals(1, recoveryNotifications().size(),
                    "并发恢复不得产生重复通知/副作用");
        } finally {
            pool.shutdownNow();
        }
    }

    private List<NotificationEntity> recoveryNotifications() {
        return notificationService.list(ownerId).stream()
                .filter(notification -> notification.getTitle().contains(RECOVERY_MESSAGE_FRAGMENT))
                .toList();
    }

    private String registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"recovery-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"恢复测试用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("user").get("id").asText();
    }
}
