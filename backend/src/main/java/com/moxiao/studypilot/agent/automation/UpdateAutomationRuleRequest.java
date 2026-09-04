package com.moxiao.studypilot.agent.automation;

import java.time.LocalTime;

public record UpdateAutomationRuleRequest(
        Boolean enabled,
        String timezone,
        LocalTime localTime
) {
}
