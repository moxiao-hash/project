package com.moxiao.studypilot.agent.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentGrantJpaRepository extends JpaRepository<AgentGrantEntity, String> {

    List<AgentGrantEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
