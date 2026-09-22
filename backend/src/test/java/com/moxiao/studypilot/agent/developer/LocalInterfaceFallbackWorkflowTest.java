package com.moxiao.studypilot.agent.developer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 33：本地界面兜底动作的 Java 执行路径、治理、幂等与用户隔离。
 *
 * <p>本地适配器以测试替身替换（ZCode 的真实服务尚未就绪）。治理、审计、通知、幂等与
 * owner 隔离使用真实 H2 数据库与真实 Spring 接线，不使用模拟存储。</p>
 */
@SpringBootTest(properties = {
        "studypilot.local-automation.ide-targets="
                + "OPEN_REGISTERED_FILE:SOURCE_PRIMARY,"
                + "FOCUS_RUN_CONFIGURATION:RUN_DEFAULT,"
                + "SHOW_TEST_RESULT:TEST_LATEST"})
@AutoConfigureMockMvc
class LocalInterfaceFallbackWorkflowTest {

    private static final String INTERNAL_TOKEN = "test-internal-token";
    private static final String EXECUTE_TOOL = "developer.interface_fallback.execute";
    private static final String PREVIEW_TOOL = "developer.interface_fallback.preview";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    LocalAutomationClient localAutomationClient;

    @BeforeEach
    void setUp() {
        reset(localAutomationClient);
        stubReceipt(LocalAutomationStatus.SUCCEEDED, null, "已打开已注册的本地目标");
    }

    private void stubReceipt(LocalAutomationStatus status, String errorCode, String message) {
        // 使用 doAnswer：when(...) 会先执行已注册的 Answer，导致参数为 null。
        doAnswer(invocation -> receipt(invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2), status, errorCode, message))
                .when(localAutomationClient).invoke(any(), any(), any(), any());
    }

    @Test
    void localAdapterIsReachedOnlyAfterDedicatedConfirmation() throws Exception {
        Registration owner = registerUser();

        MvcResult prepared = invoke(owner.userId(), arguments(false, "PLAYWRIGHT_DOM",
                "OPEN_STUDYPILOT_ROUTE", "ASSISTANT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.action.riskLevel").value("HIGH"))
                .andReturn();
        String actionId = actionId(prepared);
        verify(localAutomationClient, never()).invoke(any(), any(), any(), any());

        confirm(owner.userId(), actionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.localAdapterInvoked").value(true))
                .andExpect(jsonPath("$.result.channel").value("PLAYWRIGHT_DOM"))
                .andExpect(jsonPath("$.result.actionKey").value("OPEN_STUDYPILOT_ROUTE"))
                .andExpect(jsonPath("$.result.targetKey").value("ASSISTANT"))
                .andExpect(jsonPath("$.result.receiptStatus").value("SUCCEEDED"));

        verify(localAutomationClient, times(1)).invoke(
                eq(InterfaceAutomationChannel.PLAYWRIGHT_DOM),
                eq(LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE), eq("ASSISTANT"),
                eq(owner.userId()));
    }

    @Test
    void repeatedConfirmationNeverRepeatsTheLocalAction() throws Exception {
        Registration owner = registerUser();
        String actionId = actionId(invoke(owner.userId(), arguments(false, "PLAYWRIGHT_DOM",
                "FOCUS_AGENT_INPUT", "ASSISTANT_INPUT")).andReturn());

        confirm(owner.userId(), actionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        confirm(owner.userId(), actionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        verify(localAutomationClient, times(1)).invoke(any(), any(), any(), any());
    }

    @Test
    void businessApiPriorityNeverTouchesTheLocalAdapter() throws Exception {
        Registration owner = registerUser();
        String actionId = actionId(invoke(owner.userId(), arguments(true, "PLAYWRIGHT_DOM",
                "OPEN_STUDYPILOT_ROUTE", "ASSISTANT")).andReturn());

        confirm(owner.userId(), actionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.channel").value("BUSINESS_API"))
                .andExpect(jsonPath("$.result.localAdapterInvoked").value(false));

        verify(localAutomationClient, never()).invoke(any(), any(), any(), any());
    }

    @Test
    void everyFrozenIdeActionIsAllowedWhenNoBusinessApiExists() throws Exception {
        Registration owner = registerUser();
        record Ide(String action, String target) { }
        for (Ide ide : new Ide[]{
                new Ide("OPEN_REGISTERED_FILE", "SOURCE_PRIMARY"),
                new Ide("FOCUS_RUN_CONFIGURATION", "RUN_DEFAULT"),
                new Ide("SHOW_TEST_RESULT", "TEST_LATEST")}) {
            String actionId = actionId(invoke(owner.userId(),
                    arguments(false, "IDEA_ACCESSIBILITY", ide.action(), ide.target())).andReturn());
            confirm(owner.userId(), actionId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.result.channel").value("IDEA_ACCESSIBILITY"))
                    .andExpect(jsonPath("$.result.actionKey").value(ide.action()))
                    .andExpect(jsonPath("$.result.targetKey").value(ide.target()));
        }
        verify(localAutomationClient, times(3)).invoke(
                eq(InterfaceAutomationChannel.IDEA_ACCESSIBILITY), any(), any(), eq(owner.userId()));
    }

    @Test
    void failedAndRejectedReceiptsAreNeverRecordedAsSuccess() throws Exception {
        Registration owner = registerUser();

        stubReceipt(LocalAutomationStatus.FAILED, "ADAPTER_FAILED", "目标窗口未就绪");
        String failedId = actionId(invoke(owner.userId(), arguments(false, "PLAYWRIGHT_DOM",
                "OPEN_STUDYPILOT_ROUTE", "ASSISTANT")).andReturn());
        confirm(owner.userId(), failedId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.result").doesNotExist());
        assertTrue(introspect(owner.userId(), failedId).path("error").asText()
                .contains("ADAPTER_FAILED"));

        reset(localAutomationClient);
        stubReceipt(LocalAutomationStatus.REJECTED, "NONCE_REPLAY", "检测到重放请求");
        String rejectedId = actionId(invoke(owner.userId(), arguments(false, "PLAYWRIGHT_DOM",
                "OPEN_STUDYPILOT_ROUTE", "ASSISTANT")).andReturn());
        confirm(owner.userId(), rejectedId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.result").doesNotExist());
        assertTrue(introspect(owner.userId(), rejectedId).path("error").asText()
                .contains("NONCE_REPLAY"));
    }

    @Test
    void unavailableAdapterFailsHonestlyWithManualRecoveryGuidance() throws Exception {
        Registration owner = registerUser();
        reset(localAutomationClient);
        when(localAutomationClient.invoke(any(), any(), any(), any())).thenThrow(
                new LocalAutomationException(LocalAutomationClient.ERROR_UNAVAILABLE,
                        "本地界面适配器不可用；请手动打开目标后重试"));

        String actionId = actionId(invoke(owner.userId(), arguments(false, "PLAYWRIGHT_DOM",
                "OPEN_STUDYPILOT_ROUTE", "ASSISTANT")).andReturn());
        confirm(owner.userId(), actionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.result").doesNotExist());

        String error = introspect(owner.userId(), actionId).path("error").asText();
        assertTrue(error.contains("手动"), "失败必须给出人工恢复提示: " + error);
        assertTrue(error.contains(LocalAutomationClient.ERROR_UNAVAILABLE));
    }

    @Test
    void arbitraryLegacyAndExtraFieldsAreRejectedBeforeAnyActionIsCreated() throws Exception {
        Registration owner = registerUser();
        ObjectNode legacy = arguments(false, "PLAYWRIGHT_DOM", "OPEN_LOGIN", "");
        invoke(owner.userId(), legacy).andExpect(status().isBadRequest());

        ObjectNode mistyped = arguments(false, "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE",
                "WORKSPACE_RESULTS");
        invoke(owner.userId(), mistyped).andExpect(status().isBadRequest());

        ObjectNode wrongChannel = arguments(false, "IDEA_ACCESSIBILITY", "OPEN_STUDYPILOT_ROUTE",
                "ASSISTANT");
        invoke(owner.userId(), wrongChannel).andExpect(status().isBadRequest());

        ObjectNode unregisteredIde = arguments(false, "IDEA_ACCESSIBILITY",
                "OPEN_REGISTERED_FILE", "SOURCE_PRIMARY");
        unregisteredIde.put("url", "https://evil.example.com");
        invoke(owner.userId(), unregisteredIde).andExpect(status().isBadRequest());

        ObjectNode pathTarget = arguments(false, "IDEA_ACCESSIBILITY", "OPEN_REGISTERED_FILE",
                "../../.env");
        invoke(owner.userId(), pathTarget).andExpect(status().isBadRequest());

        ObjectNode arbitrary = arguments(false, "PLAYWRIGHT_DOM", "OPEN_RESULT_PANEL",
                "#results-panel");
        invoke(owner.userId(), arbitrary).andExpect(status().isBadRequest());

        verify(localAutomationClient, never()).invoke(any(), any(), any(), any());
    }

    @Test
    void anotherOwnerCanNeitherConfirmNorExecuteThisOwnersAction() throws Exception {
        Registration owner = registerUser();
        Registration intruder = registerUser();
        String actionId = actionId(invoke(owner.userId(), arguments(false, "PLAYWRIGHT_DOM",
                "OPEN_STUDYPILOT_ROUTE", "ASSISTANT")).andReturn());

        confirm(intruder.userId(), actionId).andExpect(status().isNotFound());
        verify(localAutomationClient, never()).invoke(any(), any(), any(), any());

        confirm(owner.userId(), actionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        verify(localAutomationClient, times(1)).invoke(any(), any(), any(), any());
    }

    @Test
    void previewToolUsesTheSameFrozenAllowlistWithoutSideEffects() throws Exception {
        ObjectNode arguments = objectMapper.createObjectNode()
                .put("businessApiAvailable", false)
                .put("channel", "PLAYWRIGHT_DOM")
                .put("actionKey", "OPEN_STUDYPILOT_ROUTE")
                .put("targetKey", "ASSISTANT_HEALTH");
        ObjectNode body = objectMapper.createObjectNode()
                .put("ownerId", "preview-owner")
                .set("arguments", arguments);

        mockMvc.perform(post("/internal/agent-tools/" + PREVIEW_TOOL + "/invoke")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channel").value("PLAYWRIGHT_DOM"))
                .andExpect(jsonPath("$.data.actionKey").value("OPEN_STUDYPILOT_ROUTE"))
                .andExpect(jsonPath("$.data.targetKey").value("ASSISTANT_HEALTH"))
                .andExpect(jsonPath("$.data.fallbackRequired").value(true));

        verify(localAutomationClient, never()).invoke(any(), any(), any(), any());
    }

    // ---------- helpers ----------

    private static LocalAutomationReceipt receipt(
            InterfaceAutomationChannel channel, LocalInterfaceAction action, String targetKey,
            LocalAutomationStatus status, String errorCode, String message
    ) {
        Instant now = Instant.parse("2026-09-22T00:00:00Z");
        return new LocalAutomationReceipt(1, UUID.randomUUID().toString(), channel.name(),
                action.name(),
                LocalAutomationSigning.targetDigest(channel.name(), action.name(), targetKey),
                now, now.plusSeconds(1), status, errorCode, message);
    }

    private ObjectNode arguments(
            boolean businessApiAvailable, String channel, String actionKey, String targetKey
    ) {
        return objectMapper.createObjectNode()
                .put("businessApiAvailable", businessApiAvailable)
                .put("channel", channel)
                .put("actionKey", actionKey)
                .put("targetKey", targetKey);
    }

    private ResultActions invoke(String ownerId, ObjectNode arguments) throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("ownerId", ownerId)
                .put("idempotencyKey", "interface-fallback:" + UUID.randomUUID())
                .set("arguments", arguments);
        return mockMvc.perform(post("/internal/agent-tools/" + EXECUTE_TOOL + "/invoke")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)));
    }

    private ResultActions confirm(String ownerId, String actionId) throws Exception {
        return mockMvc.perform(post("/internal/agent-tool-actions/{id}/confirm", actionId)
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerId\":\"" + ownerId + "\"}"));
    }

    private String actionId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("action").path("actionId").asText();
    }

    private JsonNode introspect(String ownerId, String actionId) throws Exception {
        // 通过再次确认读取既有终态（幂等只读路径），不新增副作用。
        MvcResult result = confirm(ownerId, actionId).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record Registration(String userId, String token) { }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"interface-fallback-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"界面兜底用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(body.path("user").path("id").asText(),
                body.path("accessToken").asText());
    }
}
