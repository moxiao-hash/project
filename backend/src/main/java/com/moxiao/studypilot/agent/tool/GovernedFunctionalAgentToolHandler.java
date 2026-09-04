package com.moxiao.studypilot.agent.tool;

import com.moxiao.studypilot.agent.domain.ExecutionType;
import tools.jackson.databind.JsonNode;

import java.util.function.BiFunction;
import java.util.function.Function;

public final class GovernedFunctionalAgentToolHandler implements GovernedAgentToolHandler {
    private final AgentToolDescriptor descriptor;
    private final ExecutionType executionType;
    private final Function<JsonNode, String> summary;
    private final BiFunction<AgentToolContext, JsonNode, Object> function;

    public GovernedFunctionalAgentToolHandler(
            AgentToolDescriptor descriptor,
            ExecutionType executionType,
            Function<JsonNode, String> summary,
            BiFunction<AgentToolContext, JsonNode, Object> function
    ) {
        this.descriptor = descriptor;
        this.executionType = executionType;
        this.summary = summary;
        this.function = function;
    }

    @Override
    public AgentToolDescriptor descriptor() { return descriptor; }

    @Override
    public ExecutionType executionType() { return executionType; }

    @Override
    public String summary(JsonNode arguments) { return summary.apply(arguments); }

    @Override
    public Object invoke(AgentToolContext context, JsonNode arguments) {
        return function.apply(context, arguments);
    }
}
