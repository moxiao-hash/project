package com.moxiao.studypilot.agent.tool;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentToolActionJpaRepository extends JpaRepository<AgentToolActionEntity, String> {
    Optional<AgentToolActionEntity> findByOwnerIdAndIdempotencyKey(
            String ownerId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select action from AgentToolActionEntity action "
            + "where action.id = :id and action.ownerId = :ownerId")
    Optional<AgentToolActionEntity> findOwnedForUpdate(
            @Param("id") String id, @Param("ownerId") String ownerId);

    /** Task 30：租约已过期（或缺失）的 RUNNING 动作，用于恢复。 */
    @Query("select action from AgentToolActionEntity action "
            + "where action.status = :status "
            + "and (action.leaseExpiresAt is null or action.leaseExpiresAt < :now) "
            + "order by action.createdAt asc")
    List<AgentToolActionEntity> findRecoverable(
            @Param("status") AgentToolActionStatus status,
            @Param("now") Instant now,
            Pageable pageable);

    /**
     * Task 30：条件接管过期租约；两个恢复实例并发时只有一个能更新成功。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AgentToolActionEntity action "
            + "set action.leaseToken = :token, action.leaseExpiresAt = :expires, "
            + "action.updatedAt = :now "
            + "where action.id = :id and action.status = :status "
            + "and (action.leaseExpiresAt is null or action.leaseExpiresAt < :now)")
    int claimRecoveryLease(
            @Param("id") String id,
            @Param("token") String token,
            @Param("expires") Instant expires,
            @Param("now") Instant now,
            @Param("status") AgentToolActionStatus status);

    /**
     * Task 30：仅当仍持有本次执行租约时才写入 SUCCEEDED。
     *
     * <p>与业务变更处于同一事务，更新为 0 时调用方必须抛出并回滚，
     * 保证“已提交的业务副作用一定有 SUCCEEDED 一致记录”。</p>
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AgentToolActionEntity action "
            + "set action.status = :succeeded, action.resultJson = :result, "
            + "action.errorMessage = null, action.leaseToken = null, "
            + "action.leaseExpiresAt = null, action.updatedAt = :now "
            + "where action.id = :id and action.status = :running "
            + "and action.leaseToken = :token")
    int completeIfLeaseHeld(
            @Param("id") String id,
            @Param("token") String token,
            @Param("result") String result,
            @Param("now") Instant now,
            @Param("succeeded") AgentToolActionStatus succeeded,
            @Param("running") AgentToolActionStatus running);
}
