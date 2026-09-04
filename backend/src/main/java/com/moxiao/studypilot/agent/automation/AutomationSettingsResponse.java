package com.moxiao.studypilot.agent.automation;

import java.time.Instant;

public record AutomationSettingsResponse(boolean paused, Instant updatedAt) {
    public static AutomationSettingsResponse from(AssistantAutomationSettingsEntity entity) {
        return new AutomationSettingsResponse(entity.isPaused(), entity.getUpdatedAt());
    }
}
