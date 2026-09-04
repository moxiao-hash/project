package com.moxiao.studypilot.agent.tool;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesStableSortedCatalogAndInvokesOnlyRegisteredHandler() {
        AgentToolHandler beta = handler("roadmap.node.get", Map.of("nodeId", "node-1"));
        AgentToolHandler alpha = handler("learning.context.get", Map.of("owner", "server-derived"));
        AgentToolRegistry registry = new AgentToolRegistry(List.of(beta, alpha), objectMapper);

        assertEquals(List.of("learning.context.get", "roadmap.node.get"),
                registry.catalog().stream().map(AgentToolDescriptor::name).toList());
        AgentToolInvocationResponse response = registry.invoke(
                "learning.context.get",
                new AgentToolInvocationRequest("user-1", objectMapper.createObjectNode()));

        assertEquals("learning.context.get", response.toolName());
        assertEquals("server-derived", response.data().get("owner").asText());
    }

    @Test
    void rejectsUnknownToolsDuplicateNamesAndModelSuppliedOwner() {
        AgentToolHandler handler = handler("learning.context.get", Map.of("ok", true));
        assertThrows(IllegalStateException.class,
                () -> new AgentToolRegistry(List.of(handler, handler), objectMapper));

        AgentToolRegistry registry = new AgentToolRegistry(List.of(handler), objectMapper);
        assertThrows(IllegalArgumentException.class, () -> registry.invoke(
                "missing.tool",
                new AgentToolInvocationRequest("user-1", objectMapper.createObjectNode())));
        assertThrows(IllegalArgumentException.class, () -> registry.invoke(
                "learning.context.get",
                new AgentToolInvocationRequest("user-1", objectMapper.createObjectNode()
                        .put("ownerId", "attacker"))));
    }

    @Test
    void validatesDeclaredArgumentsAndTruncatesOversizedOutput() {
        AgentToolHandler handler = new AgentToolHandler() {
            @Override
            public AgentToolDescriptor descriptor() {
                return AgentToolRegistryTest.this.descriptor(
                        "roadmap.node.get", "nodeId", "string", true);
            }

            @Override
            public Object invoke(AgentToolContext context, JsonNode arguments) {
                return Map.of("nodeId", arguments.get("nodeId").asText(),
                        "content", "x".repeat(80_000));
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(handler), objectMapper);

        assertThrows(IllegalArgumentException.class, () -> registry.invoke(
                "roadmap.node.get",
                new AgentToolInvocationRequest("user-1", objectMapper.createObjectNode())));
        assertThrows(IllegalArgumentException.class, () -> registry.invoke(
                "roadmap.node.get",
                new AgentToolInvocationRequest("user-1", objectMapper.createObjectNode()
                        .put("nodeId", 42))));
        assertThrows(IllegalArgumentException.class, () -> registry.invoke(
                "roadmap.node.get",
                new AgentToolInvocationRequest("user-1", objectMapper.createObjectNode()
                        .put("nodeId", "node-1").put("extra", true))));

        AgentToolInvocationResponse response = registry.invoke(
                "roadmap.node.get",
                new AgentToolInvocationRequest("user-1", objectMapper.createObjectNode()
                        .put("nodeId", "node-1")));
        assertTrue(response.truncated());
        assertTrue(objectMapper.writeValueAsBytes(response.data()).length <= 65_536);
    }

    private AgentToolHandler handler(String name, Object result) {
        return new AgentToolHandler() {
            @Override
            public AgentToolDescriptor descriptor() {
                return AgentToolRegistryTest.this.descriptor(name, null, null, false);
            }

            @Override
            public Object invoke(AgentToolContext context, JsonNode arguments) {
                return result;
            }
        };
    }

    private AgentToolDescriptor descriptor(
            String name, String property, String type, boolean required
    ) {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        var properties = schema.putObject("properties");
        if (property != null) {
            properties.putObject(property).put("type", type);
            if (required) {
                schema.putArray("required").add(property);
            }
        }
        return new AgentToolDescriptor(name, 1, "TEST", AgentToolEffect.READ,
                AgentToolRiskLevel.NONE, null, false, schema, schema);
    }
}
