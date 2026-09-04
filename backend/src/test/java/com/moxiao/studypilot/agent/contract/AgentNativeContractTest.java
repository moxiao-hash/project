package com.moxiao.studypilot.agent.contract;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentNativeContractTest {

    private static final String CONTRACT_ROOT = "agent-contracts/";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toolContractFixesEffectsRisksAndTurnLimits() throws IOException {
        JsonNode contract = readContract("tool-contract.json");

        assertTextSet(contract.get("effects"), "READ", "NAVIGATE", "WRITE", "LOCAL");
        assertTextSet(contract.get("riskLevels"), "NONE", "LOW", "HIGH");
        assertEquals(8, contract.get("turnLimits").get("maxToolCalls").asInt());
        assertEquals(1, contract.get("turnLimits").get("maxWebSearches").asInt());
        assertEquals(1, contract.get("turnLimits").get("maxWriteTransactions").asInt());
        assertEquals(1, contract.get("turnLimits").get("maxPendingHighRiskActions").asInt());
        assertTrue(namedTextSet(contract.get("prohibitedArguments")).contains("ownerId"));
        assertTrue(namedTextSet(contract.get("prohibitedArguments")).contains("url"));
    }

    @Test
    void uiActionContractIsAClosedWhitelistWithoutExecutableTargets() throws IOException {
        JsonNode contract = readContract("ui-action-contract.json");

        assertTextSet(contract.get("types"),
                "NAVIGATE", "OPEN_MODAL", "PREFILL_FORM", "REFRESH_RESOURCE", "FOCUS_ELEMENT");
        assertTrue(namedTextSet(contract.get("forbiddenFields")).contains("url"));
        assertTrue(namedTextSet(contract.get("forbiddenFields")).contains("javascript"));
        assertTrue(namedTextSet(contract.get("forbiddenFields")).contains("cssSelector"));
    }

    @Test
    void eventContractSupportsOrderedReplayWithoutExposingReasoning() throws IOException {
        JsonNode contract = readContract("assistant-event-contract.json");

        assertTextSet(contract.get("types"),
                "TURN_STARTED", "CONTEXT_LOADED", "TOOL_STARTED", "TOOL_SUCCEEDED",
                "ACTION_PREVIEW", "ASSISTANT_DELTA", "UI_ACTION", "TURN_COMPLETED",
                "TURN_FAILED", "HEARTBEAT");
        assertTrue(namedTextSet(contract.get("requiredFields")).contains("sequence"));
        assertFalse(contract.toString().toLowerCase().contains("chainofthought"));
        assertFalse(contract.toString().toLowerCase().contains("chain_of_thought"));
    }

    @Test
    void capabilityMatrixCoversEveryAuthenticatedCurrentPage() throws IOException {
        JsonNode matrix = readContract("capability-matrix.json");
        Set<String> expectedRoutes = Set.of(
                "dashboard", "roadmap", "roadmap-stage", "roadmap-module", "roadmap-node",
                "goals", "plans", "plan-detail", "today", "materials", "material-detail",
                "quiz", "attempt", "wrong-questions", "mastery", "knowledge", "agent-plan",
                "agent-tasks", "activity", "notifications", "settings", "settings-ai",
                "workspace-artifacts");
        Set<String> actualRoutes = new HashSet<>();
        matrix.get("pages").forEach(page -> actualRoutes.add(page.get("routeName").asText()));

        assertEquals(expectedRoutes, actualRoutes);
        matrix.get("pages").forEach(page -> {
            assertTrue(page.hasNonNull("routeKey"));
            assertTrue(page.has("readCapabilities"));
            assertTrue(page.has("writeCapabilities"));
            assertTrue(page.hasNonNull("learningIntegrity"));
        });
    }

    @Test
    void learningIntegrityAndHighRiskBoundariesCannotBeDelegatedToTheModel() throws IOException {
        JsonNode matrix = readContract("capability-matrix.json");

        assertTextSet(matrix.get("userOnlyDecisions"),
                "SUBMIT_QUIZ_ANSWERS", "SUBMIT_CHECK_IN_SUMMARY", "ACCEPT_ARTIFACT", "EXPAND_GRANT");
        assertTextSet(matrix.get("alwaysConfirmActions"),
                "DELETE_AI_CREDENTIAL", "PREPARE_RUNNER_DEPENDENCIES", "APPLY_CODE_PATCH",
                "GIT_COMMIT", "GIT_PUSH");
    }

    private JsonNode readContract(String name) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(CONTRACT_ROOT + name)) {
            assertNotNull(stream, "Missing agent contract: " + name);
            return objectMapper.readTree(stream);
        }
    }

    private static void assertTextSet(JsonNode values, String... expected) {
        assertNotNull(values);
        assertEquals(Set.of(expected), namedTextSet(values));
    }

    private static Set<String> namedTextSet(JsonNode values) {
        Set<String> actual = new HashSet<>();
        values.forEach(value -> actual.add(value.isTextual() ? value.asText() : value.get("name").asText()));
        return actual;
    }
}
