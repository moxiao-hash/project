package com.moxiao.studypilot.agent.tool;

import com.moxiao.studypilot.agent.domain.ExecutionType;
import tools.jackson.databind.JsonNode;

public interface GovernedAgentToolHandler extends AgentToolHandler {
    ExecutionType executionType();

    String summary(JsonNode arguments);
}
