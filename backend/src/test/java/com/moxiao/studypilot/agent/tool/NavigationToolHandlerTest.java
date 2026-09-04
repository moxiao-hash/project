package com.moxiao.studypilot.agent.tool;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NavigationToolHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NavigationToolHandler handler = new NavigationToolHandler(objectMapper);

    @Test
    void resolvesOnlyRegisteredRouteKeysAndEntityParameters() {
        var arguments = objectMapper.createObjectNode()
                .put("routeKey", "ROADMAP_NODE");
        arguments.putObject("params").put("nodeId", "node-1");

        NavigationToolHandler.NavigationTarget target =
                (NavigationToolHandler.NavigationTarget) handler.invoke(
                        new AgentToolContext("user-1"), arguments);

        assertEquals("ROADMAP_NODE", target.routeKey());
        assertEquals("node-1", target.params().get("nodeId"));
    }

    @Test
    void rejectsUnknownRoutesMissingParamsAndExecutableValues() {
        assertThrows(IllegalArgumentException.class, () -> handler.invoke(
                new AgentToolContext("user-1"), objectMapper.createObjectNode()
                        .put("routeKey", "https://evil.example")));
        assertThrows(IllegalArgumentException.class, () -> handler.invoke(
                new AgentToolContext("user-1"), objectMapper.createObjectNode()
                        .put("routeKey", "ROADMAP_NODE")));
        assertThrows(IllegalArgumentException.class, () -> handler.invoke(
                new AgentToolContext("user-1"), objectMapper.createObjectNode()
                        .put("routeKey", "ROADMAP_NODE")
                        .putObject("params").put("nodeId", "javascript:alert(1)")));
    }
}
