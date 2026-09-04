package com.moxiao.studypilot.agent.tool;

import tools.jackson.databind.JsonNode;

public interface AgentToolHandler {
    AgentToolDescriptor descriptor();

    Object invoke(AgentToolContext context, JsonNode arguments);
}
