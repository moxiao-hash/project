package com.moxiao.studypilot.agent.tool;

import tools.jackson.databind.JsonNode;

import java.util.function.BiFunction;

public final class FunctionalAgentToolHandler implements AgentToolHandler {
    private final AgentToolDescriptor descriptor;
    private final BiFunction<AgentToolContext, JsonNode, Object> function;

    public FunctionalAgentToolHandler(
            AgentToolDescriptor descriptor,
            BiFunction<AgentToolContext, JsonNode, Object> function
    ) {
        this.descriptor = descriptor;
        this.function = function;
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Object invoke(AgentToolContext context, JsonNode arguments) {
        return function.apply(context, arguments);
    }
}
