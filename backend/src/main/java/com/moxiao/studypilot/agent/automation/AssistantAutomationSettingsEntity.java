package com.moxiao.studypilot.agent.automation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "assistant_automation_settings")
public class AssistantAutomationSettingsEntity {

    @Id
    @Column(name = "owner_id")
    private String ownerId;

    @Column(nullable = false)
    private boolean paused;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AssistantAutomationSettingsEntity() {
    }

    public AssistantAutomationSettingsEntity(String ownerId, boolean paused, Instant updatedAt) {
        this.ownerId = ownerId;
        this.paused = paused;
        this.updatedAt = updatedAt;
    }

    public void update(boolean paused, Instant now) {
        this.paused = paused;
        this.updatedAt = now;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public boolean isPaused() {
        return paused;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
