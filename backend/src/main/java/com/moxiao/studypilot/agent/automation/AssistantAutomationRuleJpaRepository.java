package com.moxiao.studypilot.agent.automation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssistantAutomationRuleJpaRepository
        extends JpaRepository<AssistantAutomationRuleEntity, String> {

    List<AssistantAutomationRuleEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);

    Optional<AssistantAutomationRuleEntity> findByIdAndOwnerId(String id, String ownerId);
}
