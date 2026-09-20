package com.moxiao.studypilot.agent.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 模型调用预占许可的持久化访问。 */
public interface AssistantUsageReservationJpaRepository
        extends JpaRepository<AssistantUsageReservationEntity, String> {

    /** 仍占用配额、尚未过期的预占；过期预占由 TTL 兜底释放，不参与计数。 */
    List<AssistantUsageReservationEntity> findAllByOwnerIdAndStateAndExpiresAtAfter(
            String ownerId, String state, Instant at);

    /**
     * 按实际用量终结预占；只有仍处于 RESERVED 的行会被更新，重复回调是无操作。
     *
     * <p>该方法自带事务：调用方 {@code record} 刻意不包事务，避免插入冲突后无法回读。</p>
     */
    @Modifying
    @Transactional
    @Query("""
            update AssistantUsageReservationEntity r
               set r.state = 'FINALIZED', r.finalizedAt = :finalizedAt, r.actualCost = :actualCost
             where r.id = :id and r.state = 'RESERVED'
            """)
    int finalizeReservation(
            @Param("id") String id,
            @Param("actualCost") BigDecimal actualCost,
            @Param("finalizedAt") Instant finalizedAt);

    /** 释放预占；只有仍处于 RESERVED 的行会被更新，重复释放是无操作。 */
    @Modifying
    @Transactional
    @Query("""
            update AssistantUsageReservationEntity r
               set r.state = 'RELEASED', r.finalizedAt = :releasedAt
             where r.id = :id and r.state = 'RESERVED'
            """)
    int releaseReservation(
            @Param("id") String id,
            @Param("releasedAt") Instant releasedAt);
}
