package com.moxiao.studypilot.agent.automation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalTime;

@Entity
@Table(name = "assistant_automation_rules")
public class AssistantAutomationRuleEntity {

    @Id
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AutomationRuleType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AutomationRuleStatus status;

    @Column(nullable = false, length = 60)
    private String timezone;

    @Column(name = "local_time", nullable = false)
    private LocalTime localTime;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AssistantAutomationRuleEntity() {
    }

    public AssistantAutomationRuleEntity(
            String id,
            String ownerId,
            AutomationRuleType type,
            AutomationRuleStatus status,
            String timezone,
            LocalTime localTime,
            Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.type = type;
        this.status = status;
        this.timezone = timezone;
        this.localTime = localTime;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(Boolean enabled, String timezone, LocalTime localTime, Instant now) {
        if (enabled != null) {
            status = enabled ? AutomationRuleStatus.ACTIVE : AutomationRuleStatus.PAUSED;
        }
        if (timezone != null) {
            this.timezone = timezone;
        }
        if (localTime != null) {
            this.localTime = localTime;
        }
        updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public AutomationRuleType getType() {
        return type;
    }

    public AutomationRuleStatus getStatus() {
        return status;
    }

    public String getTimezone() {
        return timezone;
    }

    public LocalTime getLocalTime() {
        return localTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
