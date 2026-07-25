package com.moxiao.studypilot.agent.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, Long> {

    List<AuditLogEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
