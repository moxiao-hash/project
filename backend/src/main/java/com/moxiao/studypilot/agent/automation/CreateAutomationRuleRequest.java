package com.moxiao.studypilot.agent.automation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record CreateAutomationRuleRequest(
        @NotNull AutomationRuleType type,
        @NotBlank String timezone,
        @NotNull LocalTime localTime,
        boolean enabled
) {
}
