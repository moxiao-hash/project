package com.moxiao.studypilot.agent.usage;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AssistantUsageBudgetJpaRepository extends JpaRepository<AssistantUsageBudgetEntity, String> {

    /**
     * 读取并锁定预算行，用于串行化同一 owner 的并发预占。
     *
     * <p>没有这一步时，两个并发请求会各自读到"还剩一个名额"并同时放行，
     * 从而突破每日调用/费用上限。锁在预占事务提交时才释放。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AssistantUsageBudgetEntity b where b.ownerId = :ownerId")
    Optional<AssistantUsageBudgetEntity> findByOwnerIdForUpdate(@Param("ownerId") String ownerId);
}
