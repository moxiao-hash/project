package com.moxiao.studypilot.agent.automation;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AssistantAutomationJobJpaRepository
        extends JpaRepository<AssistantAutomationJobEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from AssistantAutomationJobEntity job
            where (job.status = com.moxiao.studypilot.agent.automation.AutomationJobStatus.PENDING
                    and job.scheduledFor <= :now)
               or (job.status = com.moxiao.studypilot.agent.automation.AutomationJobStatus.PROCESSING
                    and job.leaseUntil < :now)
            order by job.scheduledFor asc, job.createdAt asc
            """)
    List<AssistantAutomationJobEntity> findClaimable(
            @Param("now") Instant now,
            Pageable pageable
    );

    void deleteAllByRuleId(String ruleId);

    List<AssistantAutomationJobEntity> findAllByRuleIdAndStatus(
            String ruleId,
            AutomationJobStatus status
    );
}
