package com.moxiao.studypilot.agent.automation;

import com.moxiao.studypilot.agent.api.CreateAgentExecutionRequest;
import com.moxiao.studypilot.agent.api.UpdateAgentExecutionRequest;
import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.domain.ExecutionType;
import com.moxiao.studypilot.agent.domain.RiskLevel;
import com.moxiao.studypilot.agent.domain.TriggerType;
import com.moxiao.studypilot.agent.infrastructure.AgentGrantJpaRepository;
import com.moxiao.studypilot.agent.infrastructure.AuditLogEntity;
import com.moxiao.studypilot.agent.infrastructure.AuditLogJpaRepository;
import com.moxiao.studypilot.notification.api.CreateNotificationRequest;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.notification.domain.NotificationType;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AssistantAutomationService {
    private static final int MAX_CLAIM_SCAN = 50;

    private final AssistantAutomationRuleJpaRepository ruleRepository;
    private final AssistantAutomationSettingsJpaRepository settingsRepository;
    private final AssistantAutomationJobJpaRepository jobRepository;
    private final AgentGrantJpaRepository grantRepository;
    private final AuditLogJpaRepository auditRepository;
    private final NotificationService notificationService;
    private final AgentGovernanceService governanceService;

    public AssistantAutomationService(
            AssistantAutomationRuleJpaRepository ruleRepository,
            AssistantAutomationSettingsJpaRepository settingsRepository,
            AssistantAutomationJobJpaRepository jobRepository,
            AgentGrantJpaRepository grantRepository,
            AuditLogJpaRepository auditRepository,
            NotificationService notificationService,
            AgentGovernanceService governanceService
    ) {
        this.ruleRepository = ruleRepository;
        this.settingsRepository = settingsRepository;
        this.jobRepository = jobRepository;
        this.grantRepository = grantRepository;
        this.auditRepository = auditRepository;
        this.notificationService = notificationService;
        this.governanceService = governanceService;
    }

    @Transactional
    public AssistantAutomationRuleEntity create(
            String ownerId,
            CreateAutomationRuleRequest request
    ) {
        Instant now = Instant.now();
        ZoneId zone = requireZone(request.timezone());
        AssistantAutomationRuleEntity rule = ruleRepository.save(
                new AssistantAutomationRuleEntity(
                        UUID.randomUUID().toString(),
                        ownerId,
                        request.type(),
                        request.enabled() ? AutomationRuleStatus.ACTIVE : AutomationRuleStatus.PAUSED,
                        zone.getId(),
                        request.localTime(),
                        now
                )
        );
        jobRepository.save(new AssistantAutomationJobEntity(
                UUID.randomUUID().toString(),
                rule.getId(),
                ownerId,
                rule.getType(),
                firstOccurrence(now, zone, request.localTime()),
                now
        ));
        audit(ownerId, "AUTOMATION_RULE_CREATED", rule.getId(), request.type().name());
        return rule;
    }

    @Transactional(readOnly = true)
    public List<AssistantAutomationRuleEntity> list(String ownerId) {
        return ruleRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    @Transactional(readOnly = true)
    public AssistantAutomationRuleEntity get(String ownerId, String ruleId) {
        return requireOwnedRule(ownerId, ruleId);
    }

    @Transactional
    public AssistantAutomationRuleEntity update(
            String ownerId,
            String ruleId,
            UpdateAutomationRuleRequest request
    ) {
        AssistantAutomationRuleEntity rule = requireOwnedRule(ownerId, ruleId);
        String timezone = request.timezone();
        boolean scheduleChanged = request.timezone() != null || request.localTime() != null;
        if (timezone != null && !timezone.isBlank()) {
            timezone = requireZone(timezone).getId();
        } else if (timezone != null) {
            throw new IllegalArgumentException("时区不能为空");
        }
        Instant now = Instant.now();
        rule.update(request.enabled(), timezone, request.localTime(), now);
        if (scheduleChanged) {
            Instant nextSchedule = firstOccurrence(
                    now, requireZone(rule.getTimezone()), rule.getLocalTime());
            jobRepository.findAllByRuleIdAndStatus(ruleId, AutomationJobStatus.PENDING)
                    .forEach(job -> job.reschedule(nextSchedule, now));
        }
        audit(ownerId, "AUTOMATION_RULE_UPDATED", ruleId, rule.getStatus().name());
        return rule;
    }

    @Transactional
    public void delete(String ownerId, String ruleId) {
        AssistantAutomationRuleEntity rule = requireOwnedRule(ownerId, ruleId);
        jobRepository.deleteAllByRuleId(ruleId);
        ruleRepository.delete(rule);
        audit(ownerId, "AUTOMATION_RULE_DELETED", ruleId, rule.getType().name());
    }

    @Transactional(readOnly = true)
    public AssistantAutomationSettingsEntity settings(String ownerId) {
        return settingsRepository.findById(ownerId)
                .orElseGet(() -> new AssistantAutomationSettingsEntity(
                        ownerId, false, Instant.EPOCH));
    }

    @Transactional
    public AssistantAutomationSettingsEntity updateSettings(String ownerId, boolean paused) {
        Instant now = Instant.now();
        AssistantAutomationSettingsEntity settings = settingsRepository.findById(ownerId)
                .orElseGet(() -> new AssistantAutomationSettingsEntity(ownerId, paused, now));
        settings.update(paused, now);
        settingsRepository.save(settings);
        audit(ownerId, paused ? "AUTOMATION_PAUSED" : "AUTOMATION_RESUMED", ownerId,
                "全局主动自动化开关");
        return settings;
    }

    @Transactional
    public AssistantAutomationJobEntity claim(String workerId, int leaseSeconds) {
        Instant now = Instant.now();
        for (AssistantAutomationJobEntity job : jobRepository.findClaimable(
                now, PageRequest.of(0, MAX_CLAIM_SCAN))) {
            if (job.exhaustExpiredLease(now)) {
                governanceService.update(job.getExecutionId(), new UpdateAgentExecutionRequest(
                        ExecutionStatus.FAILED, null, job.getErrorMessage(), null,
                        null, null, null, null));
                notificationService.create(new CreateNotificationRequest(
                        job.getOwnerId(), NotificationType.AGENT_FAILED,
                        "主动学习任务处理失败", job.getErrorMessage()));
                audit(job.getOwnerId(), "AUTOMATION_JOB_FAILED", job.getId(), job.getErrorMessage());
                continue;
            }
            AssistantAutomationRuleEntity rule = ruleRepository.findById(job.getRuleId())
                    .orElse(null);
            if (rule == null || rule.getStatus() != AutomationRuleStatus.ACTIVE) {
                continue;
            }
            if (settingsRepository.findById(job.getOwnerId())
                    .map(AssistantAutomationSettingsEntity::isPaused).orElse(false)) {
                continue;
            }
            // 规则本身不能创建或扩大授权。每次领取前都重新检查长期授权是否仍有效。
            boolean granted = grantRepository
                    .findAllByOwnerIdOrderByCreatedAtDesc(job.getOwnerId())
                    .stream()
                    .anyMatch(grant -> grant.includes(rule.getType().requiredScope(), now));
            if (!granted || rule.getType().riskLevel() == RiskLevel.HIGH) {
                continue;
            }
            var execution = governanceService.createExecution(new CreateAgentExecutionRequest(
                    job.getOwnerId(),
                    "automation-job:" + job.getId(),
                    executionType(job.getType()),
                    TriggerType.NIGHTLY_CHECK,
                    rule.getType().riskLevel(),
                    rule.getType().requiredScope(),
                    "主动自动化：" + rule.getType().name()
            ));
            governanceService.update(execution.getId(), new UpdateAgentExecutionRequest(
                    ExecutionStatus.RUNNING, null, null, null,
                    null, null, null, null));
            job.claim(workerId, UUID.randomUUID().toString(), execution.getId(),
                    now.plusSeconds(leaseSeconds), now);
            audit(job.getOwnerId(), "AUTOMATION_JOB_CLAIMED", job.getId(), workerId);
            return job;
        }
        return null;
    }

    @Transactional
    public AssistantAutomationJobEntity heartbeat(
            String jobId,
            AutomationJobLeaseRequest request
    ) {
        AssistantAutomationJobEntity job = requireJob(jobId);
        Instant now = Instant.now();
        job.heartbeat(request.workerId(), request.leaseToken(),
                now.plusSeconds(request.leaseSeconds()), now);
        return job;
    }

    @Transactional
    public AssistantAutomationJobEntity complete(
            String jobId,
            CompleteAutomationJobRequest request
    ) {
        AssistantAutomationJobEntity job = requireJob(jobId);
        Instant now = Instant.now();
        job.complete(request.workerId(), request.leaseToken(), request.resultSummary(), now);
        governanceService.update(job.getExecutionId(), new UpdateAgentExecutionRequest(
                ExecutionStatus.SUCCEEDED, request.resultSummary(), null, null,
                null, null, null, null));
        AssistantAutomationRuleEntity rule = ruleRepository.findById(job.getRuleId())
                .orElse(null);
        if (rule != null) {
            ZoneId zone = requireZone(rule.getTimezone());
            jobRepository.save(new AssistantAutomationJobEntity(
                    UUID.randomUUID().toString(), rule.getId(), rule.getOwnerId(), rule.getType(),
                    nextOccurrence(now, zone, rule.getLocalTime()), now));
        }
        notificationService.create(new CreateNotificationRequest(
                job.getOwnerId(), NotificationType.AGENT_ACTION_COMPLETED,
                "主动学习任务已处理", request.resultSummary()));
        audit(job.getOwnerId(), "AUTOMATION_JOB_COMPLETED", jobId, request.resultSummary());
        return job;
    }

    @Transactional
    public AssistantAutomationJobEntity fail(String jobId, FailAutomationJobRequest request) {
        AssistantAutomationJobEntity job = requireJob(jobId);
        job.fail(request.workerId(), request.leaseToken(), request.error(), Instant.now());
        governanceService.update(job.getExecutionId(), new UpdateAgentExecutionRequest(
                ExecutionStatus.FAILED, null, request.error(), null,
                null, null, null, null));
        if (job.getStatus() == AutomationJobStatus.FAILED) {
            notificationService.create(new CreateNotificationRequest(
                    job.getOwnerId(), NotificationType.AGENT_FAILED,
                    "主动学习任务处理失败", request.error()));
        }
        audit(job.getOwnerId(), "AUTOMATION_JOB_FAILED", jobId, request.error());
        return job;
    }

    private AssistantAutomationRuleEntity requireOwnedRule(String ownerId, String ruleId) {
        return ruleRepository.findByIdAndOwnerId(ruleId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("主动自动化规则不存在"));
    }

    private AssistantAutomationJobEntity requireJob(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("主动自动化任务不存在"));
    }

    private static ZoneId requireZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("无效时区", exception);
        }
    }

    private static Instant firstOccurrence(Instant now, ZoneId zone, LocalTime time) {
        ZonedDateTime localNow = now.atZone(zone);
        ZonedDateTime candidate = LocalDate.from(localNow).atTime(time).atZone(zone);
        if (!candidate.plusMinutes(1).toInstant().isBefore(now)) {
            return candidate.toInstant();
        }
        return candidate.plusDays(1).toInstant();
    }

    private static Instant nextOccurrence(Instant now, ZoneId zone, LocalTime time) {
        return LocalDate.from(now.atZone(zone)).plusDays(1).atTime(time).atZone(zone).toInstant();
    }

    private static ExecutionType executionType(AutomationRuleType type) {
        return switch (type) {
            case AUTHORIZED_PLAN_ADJUSTMENT, OVERDUE_NODE_ROLLOVER ->
                    ExecutionType.PLAN_ADJUSTMENT;
            case QUIZ_GENERATION_RETRY -> ExecutionType.QUIZ_GENERATION;
            case WEAKNESS_REVIEW_REMINDER, ARTIFACT_REVIEW_REMINDER ->
                    ExecutionType.NOTIFICATION_CHANGE;
        };
    }

    private void audit(String ownerId, String action, String targetId, String details) {
        auditRepository.save(new AuditLogEntity(
                ownerId, action, "ASSISTANT_AUTOMATION", targetId, details, Instant.now()));
    }
}
