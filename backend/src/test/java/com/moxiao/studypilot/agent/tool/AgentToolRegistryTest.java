package com.moxiao.studypilot.agent.tool;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
                new AgentToolInvocationRequest("user-1", null, objectMapper.createObjectNode()));

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
                new AgentToolInvocationRequest("user-1", null, objectMapper.createObjectNode())));
        assertThrows(IllegalArgumentException.class, () -> registry.invoke(
                "learning.context.get",
                new AgentToolInvocationRequest("user-1", null, objectMapper.createObjectNode()
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
                new AgentToolInvocationRequest("user-1", null, objectMapper.createObjectNode())));
        assertThrows(IllegalArgumentException.class, () -> registry.invoke(
                "roadmap.node.get",
                new AgentToolInvocationRequest("user-1", null, objectMapper.createObjectNode()
                        .put("nodeId", 42))));
        assertThrows(IllegalArgumentException.class, () -> registry.invoke(
                "roadmap.node.get",
                new AgentToolInvocationRequest("user-1", null, objectMapper.createObjectNode()
                        .put("nodeId", "node-1").put("extra", true))));

        AgentToolInvocationResponse response = registry.invoke(
                "roadmap.node.get",
                new AgentToolInvocationRequest("user-1", null, objectMapper.createObjectNode()
                        .put("nodeId", "node-1")));
        assertTrue(response.truncated());
        assertTrue(objectMapper.writeValueAsBytes(response.data()).length <= 65_536);
    }

    @Test
    void truncatedOutputIsAnExplicitSchemaValidEnvelope() {
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

        AgentToolInvocationResponse response = registry.invoke(
                "roadmap.node.get",
                new AgentToolInvocationRequest("user-1", null, objectMapper.createObjectNode()
                        .put("nodeId", "node-1")));

        assertTrue(response.truncated());
        // 截断后的最终返回体必须是显式声明、可校验的形状，而不是任意拼出的对象。
        assertTrue(response.data().has("truncated"), "截断输出必须显式标记 truncated");
        assertTrue(response.data().get("truncated").asBoolean());
        assertTrue(response.data().has("warning"));
        assertTrue(response.data().has("originalBytes"));
        assertTrue(AgentToolOutputSchemas.conformsTo(
                AgentToolOutputSchemas.truncatedOutput(objectMapper), response.data()),
                "截断输出必须符合登记的裁剪契约");
    }

    @Test
    void slowHandlerIsInterruptedAtTheDescriptorTimeout() {
        AgentToolHandler slow = new AgentToolHandler() {
            @Override
            public AgentToolDescriptor descriptor() {
                return AgentToolRegistryTest.this.descriptor(
                        "roadmap.node.get", "nodeId", "string", true, 1_000);
            }

            @Override
            public Object invoke(AgentToolContext context, JsonNode arguments) {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("被中断");
                }
                return Map.of("nodeId", "late");
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(slow), objectMapper);

        long startedAt = System.nanoTime();
        RuntimeException thrown = null;
        try {
            registry.invoke("roadmap.node.get",
                    new AgentToolInvocationRequest("user-1", null, objectMapper.createObjectNode()
                            .put("nodeId", "node-1")));
        } catch (RuntimeException exception) {
            thrown = exception;
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertNotNull(thrown, "慢 Handler 必须在运行时被超时中断，而不是阻塞到自然返回");
        assertTrue(thrown instanceof AgentToolTimeoutException,
                "超时必须映射为 AgentToolTimeoutException，实际 " + thrown.getClass().getName());
        assertTrue(elapsedMillis < 2_500,
                "工具调用必须在 descriptor.timeoutMillis 附近返回，实际 " + elapsedMillis + "ms");
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
        return descriptor(name, property, type, required, 15_000);
    }

    private AgentToolDescriptor descriptor(
            String name, String property, String type, boolean required, int timeoutMillis
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
        var output = objectMapper.createObjectNode().put("type", "object");
        return new AgentToolDescriptor(name, 1, "TEST", AgentToolEffect.READ,
                AgentToolRiskLevel.NONE, null, false, schema, output, timeoutMillis);
    }
}
