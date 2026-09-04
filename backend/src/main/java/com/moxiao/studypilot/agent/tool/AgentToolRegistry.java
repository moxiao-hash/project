package com.moxiao.studypilot.agent.tool;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;

@Service
public class AgentToolRegistry {
    private static final int MAX_OUTPUT_BYTES = 65_536;
    private static final int MAX_ARGUMENT_BYTES = 32_768;
    private static final String OWNER_ID = "ownerId";

    private final Map<String, AgentToolHandler> handlers;
    private final ObjectMapper objectMapper;

    public AgentToolRegistry(List<AgentToolHandler> handlers, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        Map<String, AgentToolHandler> byName = new TreeMap<>();
        for (AgentToolHandler handler : handlers) {
            AgentToolDescriptor descriptor = handler.descriptor();
            if (byName.putIfAbsent(descriptor.name(), handler) != null) {
                throw new IllegalStateException("工具名称重复: " + descriptor.name());
            }
        }
        this.handlers = Collections.unmodifiableMap(new LinkedHashMap<>(byName));
    }

    public List<AgentToolDescriptor> catalog() {
        return handlers.values().stream().map(AgentToolHandler::descriptor).toList();
    }

    public AgentToolInvocationResponse invoke(String toolName, AgentToolInvocationRequest request) {
        AgentToolHandler handler = handlers.get(toolName);
        if (handler == null) {
            throw new IllegalArgumentException("未注册的 Agent 工具: " + toolName);
        }
        JsonNode arguments = request.arguments() == null
                ? objectMapper.createObjectNode() : request.arguments();
        validateArguments(arguments, handler.descriptor().inputSchema());
        JsonNode data = objectMapper.valueToTree(
                handler.invoke(new AgentToolContext(request.ownerId()), arguments));
        int bytes = objectMapper.writeValueAsBytes(data).length;
        if (bytes <= MAX_OUTPUT_BYTES) {
            return new AgentToolInvocationResponse(
                    toolName, handler.descriptor().version(), data, false);
        }
        JsonNode clipped = objectMapper.createObjectNode()
                .put("warning", "工具输出超过安全上限，已裁剪；请使用更具体的查询参数")
                .put("originalBytes", bytes);
        return new AgentToolInvocationResponse(
                toolName, handler.descriptor().version(), clipped, true);
    }

    private void validateArguments(JsonNode arguments, JsonNode schema) {
        if (!arguments.isObject()) {
            throw new IllegalArgumentException("工具参数必须是 JSON 对象");
        }
        if (objectMapper.writeValueAsBytes(arguments).length > MAX_ARGUMENT_BYTES) {
            throw new IllegalArgumentException("工具参数超过安全上限");
        }
        if (arguments.has(OWNER_ID)) {
            throw new IllegalArgumentException("ownerId 只能由 Java 注入");
        }
        JsonNode properties = schema.path("properties");
        if (!schema.path("additionalProperties").asBoolean(true)) {
            arguments.propertyNames().forEach(name -> {
                if (!properties.has(name)) {
                    throw new IllegalArgumentException("工具参数未声明: " + name);
                }
            });
        }
        schema.path("required").forEach(required -> {
            if (!arguments.hasNonNull(required.asText())) {
                throw new IllegalArgumentException("缺少工具参数: " + required.asText());
            }
        });
        arguments.properties().forEach(property -> validateType(
                property.getKey(), property.getValue(), properties.path(property.getKey()).path("type").asText()));
    }

    private void validateType(String name, JsonNode value, String type) {
        boolean valid = switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "" -> true;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("工具参数类型错误: " + name);
        }
    }
}
