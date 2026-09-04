package com.moxiao.studypilot.agent.tool;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;

@Component
public class NavigationToolHandler implements AgentToolHandler {
    private static final Map<String, Set<String>> ROUTES = Map.ofEntries(
            Map.entry("DASHBOARD", Set.of()),
            Map.entry("ROADMAP", Set.of()),
            Map.entry("ROADMAP_STAGE", Set.of("stageId")),
            Map.entry("ROADMAP_MODULE", Set.of("moduleId")),
            Map.entry("ROADMAP_NODE", Set.of("nodeId")),
            Map.entry("LEARNING_GOALS", Set.of()),
            Map.entry("LEARNING_PLANS", Set.of()),
            Map.entry("LEARNING_PLAN", Set.of("planId")),
            Map.entry("TODAY", Set.of()),
            Map.entry("MATERIALS", Set.of()),
            Map.entry("MATERIAL_DETAIL", Set.of("materialId")),
            Map.entry("QUIZ", Set.of("quizId")),
            Map.entry("QUIZ_ATTEMPT", Set.of("attemptId")),
            Map.entry("WRONG_QUESTIONS", Set.of()),
            Map.entry("MASTERY", Set.of()),
            Map.entry("KNOWLEDGE", Set.of()),
            Map.entry("AGENT_ACTIVITY", Set.of()),
            Map.entry("NOTIFICATIONS", Set.of()),
            Map.entry("LEARNING_SETTINGS", Set.of()),
            Map.entry("AI_SETTINGS", Set.of()),
            Map.entry("WORKSPACE_ARTIFACTS", Set.of())
    );
    private static final String SAFE_IDENTIFIER = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";

    private final AgentToolDescriptor descriptor;

    public NavigationToolHandler(ObjectMapper objectMapper) {
        var input = objectMapper.createObjectNode().put("type", "object")
                .put("additionalProperties", false);
        input.putObject("properties").putObject("routeKey").put("type", "string");
        input.withObject("properties").putObject("params").put("type", "object");
        input.putArray("required").add("routeKey");
        var output = objectMapper.createObjectNode().put("type", "object");
        descriptor = new AgentToolDescriptor(
                "navigation.resolve", 1, "NAVIGATION", AgentToolEffect.NAVIGATE,
                AgentToolRiskLevel.NONE, null, false, input, output);
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Object invoke(AgentToolContext context, JsonNode arguments) {
        String routeKey = arguments.path("routeKey").asText();
        Set<String> required = ROUTES.get(routeKey);
        if (required == null) {
            throw new IllegalArgumentException("未注册的前端路由: " + routeKey);
        }
        JsonNode rawParams = arguments.path("params");
        if (!rawParams.isMissingNode() && !rawParams.isObject()) {
            throw new IllegalArgumentException("路由参数必须是对象");
        }
        Map<String, String> params = new java.util.LinkedHashMap<>();
        if (rawParams.isObject()) {
            rawParams.properties().forEach(property -> {
                if (!required.contains(property.getKey()) || !property.getValue().isTextual()
                        || !property.getValue().asText().matches(SAFE_IDENTIFIER)) {
                    throw new IllegalArgumentException("路由参数不合法: " + property.getKey());
                }
                params.put(property.getKey(), property.getValue().asText());
            });
        }
        if (!params.keySet().equals(required)) {
            throw new IllegalArgumentException("路由参数不完整");
        }
        return new NavigationTarget(routeKey, Map.copyOf(params));
    }

    public record NavigationTarget(String routeKey, Map<String, String> params) {
    }
}
