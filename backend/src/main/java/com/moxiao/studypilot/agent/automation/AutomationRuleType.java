package com.moxiao.studypilot.agent.automation;

import com.moxiao.studypilot.agent.domain.AgentScope;
import com.moxiao.studypilot.agent.domain.RiskLevel;

public enum AutomationRuleType {
    AUTHORIZED_PLAN_ADJUSTMENT(RiskLevel.LOW, AgentScope.SMALL_PLAN_ADJUSTMENT),
    OVERDUE_NODE_ROLLOVER(RiskLevel.LOW, AgentScope.SMALL_PLAN_ADJUSTMENT),
    QUIZ_GENERATION_RETRY(RiskLevel.LOW, AgentScope.QUIZ_GENERATION),
    WEAKNESS_REVIEW_REMINDER(RiskLevel.LOW, AgentScope.LEARNING_MANAGEMENT),
    ARTIFACT_REVIEW_REMINDER(RiskLevel.LOW, AgentScope.ARTIFACT_MANAGEMENT);

    private final RiskLevel riskLevel;
    private final AgentScope requiredScope;

    AutomationRuleType(RiskLevel riskLevel, AgentScope requiredScope) {
        this.riskLevel = riskLevel;
        this.requiredScope = requiredScope;
    }

    public RiskLevel riskLevel() {
        return riskLevel;
    }

    public AgentScope requiredScope() {
        return requiredScope;
    }
}
