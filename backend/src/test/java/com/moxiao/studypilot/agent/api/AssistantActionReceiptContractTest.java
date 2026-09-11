package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.application.AgentGatewayException;
import com.moxiao.studypilot.agent.application.AgentGatewayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 30：[UNIT/MOCK] 动作回执入口的安全边界。
 *
 * <p>用 Mockito 替换 Java→Python 网关，只验证 Java 侧校验、会话归属确认与转发契约；
 * 不代表真实 Python 状态机或浏览器联调，明确标注为 MOCK。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssistantActionReceiptContractTest {

    private static final String CONVERSATION_ID = "11111111-2222-3333-4444-555555555555";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AgentGatewayService gateway;

    private String token;
    private String userId;

    @BeforeEach
    void setUp() throws Exception {
        Registration registration = registerUser();
        this.token = registration.token();
        this.userId = registration.userId();
        when(gateway.get(anyString(), anyString()))
                .thenReturn(new AgentGatewayService.GatewayResponse(
                        HttpStatusCode.valueOf(200), objectMapper.createObjectNode()));
        ObjectNode result = objectMapper.createObjectNode()
                .put("actionId", "action-1")
                .put("status", "SUCCEEDED")
                .put("decision", "FINISHED")
                .put("retryCount", 0)
                .put("message", "界面动作已完成");
        when(gateway.post(anyString(), any(), anyString()))
                .thenReturn(new AgentGatewayService.GatewayResponse(
                        HttpStatusCode.valueOf(200), result));
    }

    @Test
    void forwardsExactlyFourFrozenFieldsWithSessionOwner() throws Exception {
        mockMvc.perform(post("/api/assistant/conversations/{id}/actions/receipt", CONVERSATION_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actionId": "action-1",
                                  "status": "SUCCEEDED",
                                  "error": null,
                                  "currentRoute": "wrong-questions"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("FINISHED"));

        // 会话归属用登录用户校验，绝不使用请求体里的任何身份字段。
        verify(gateway).get(
                eq("/internal/assistant/conversations/" + CONVERSATION_ID), eq(userId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(gateway).post(
                eq("/internal/assistant/conversations/" + CONVERSATION_ID + "/actions/receipt"),
                body.capture(), eq(userId));
        JsonNode forwarded = body.getValue();
        assertEquals(3, forwarded.size(), "只转发 actionId/status/currentRoute，ownerId 由网关注入");
        assertFalse(forwarded.has("ownerId"));
        assertEquals("action-1", forwarded.get("actionId").asText());
        assertEquals("SUCCEEDED", forwarded.get("status").asText());
        assertEquals("wrong-questions", forwarded.get("currentRoute").asText());
    }

    @Test
    void rejectsOwnerIdDomUrlHtmlScriptSelectorAndModelFields() throws Exception {
        String[] forbiddenBodies = {
                "{\"actionId\":\"a\",\"status\":\"SUCCEEDED\",\"currentRoute\":\"dashboard\",\"ownerId\":\"attacker\"}",
                "{\"actionId\":\"a\",\"status\":\"SUCCEEDED\",\"currentRoute\":\"dashboard\",\"dom\":\"<div/>\"}",
                "{\"actionId\":\"a\",\"status\":\"SUCCEEDED\",\"currentRoute\":\"dashboard\",\"url\":\"https://evil\"}",
                "{\"actionId\":\"a\",\"status\":\"SUCCEEDED\",\"currentRoute\":\"dashboard\",\"html\":\"<b>x</b>\"}",
                "{\"actionId\":\"a\",\"status\":\"SUCCEEDED\",\"currentRoute\":\"dashboard\",\"script\":\"javascript:1\"}",
                "{\"actionId\":\"a\",\"status\":\"SUCCEEDED\",\"currentRoute\":\"dashboard\",\"selector\":\"#root\"}",
                "{\"actionId\":\"a\",\"status\":\"SUCCEEDED\",\"currentRoute\":\"dashboard\",\"model\":\"gpt\"}",
                "{\"actionId\":\"a\",\"status\":\"SUCCEEDED\",\"currentRoute\":\"dashboard\",\"extra\":1}"
        };
        for (String body : forbiddenBodies) {
            mockMvc.perform(post("/api/assistant/conversations/{id}/actions/receipt", CONVERSATION_ID)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(gateway, never()).post(anyString(), any(), anyString());
        verify(gateway, never()).get(anyString(), anyString());
    }

    @Test
    void rejectsUnknownStatusUrlRouteAndUnknownFieldsInRoute() throws Exception {
        mockMvc.perform(post("/api/assistant/conversations/{id}/actions/receipt", CONVERSATION_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actionId":"a","status":"UNKNOWN","currentRoute":"dashboard"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/assistant/conversations/{id}/actions/receipt", CONVERSATION_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actionId":"a","status":"SUCCEEDED","currentRoute":"https://evil.example/x"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/assistant/conversations/{id}/actions/receipt", CONVERSATION_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actionId":"a","status":"SUCCEEDED","currentRoute":"DASHBOARD"}
                                """))
                .andExpect(status().isBadRequest());
        verify(gateway, never()).post(anyString(), any(), anyString());
    }

    @Test
    void sanitizesAndBoundsUserVisibleErrorBeforeForwarding() throws Exception {
        mockMvc.perform(post("/api/assistant/conversations/{id}/actions/receipt", CONVERSATION_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actionId": "action-1",
                                  "status": "FAILED",
                                  "currentRoute": "dashboard",
                                  "error": "java.lang.IllegalStateException at com.moxiao.studypilot.Api (Authorization: Bearer abc)"
                                }
                                """))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(gateway).post(anyString(), body.capture(), anyString());
        assertNull(
                body.getValue().get("error"),
                "堆栈/包名/凭据错误必须被裁剪掉，不得进入跨端回执"
        );
    }

    @Test
    void crossUserOrUnknownConversationIsRejectedBeforeAnyReceiptIsRecorded() throws Exception {
        when(gateway.get(anyString(), anyString())).thenThrow(
                new AgentGatewayException(HttpStatus.NOT_FOUND, "统一 Agent 会话不存在"));

        mockMvc.perform(post("/api/assistant/conversations/{id}/actions/receipt", CONVERSATION_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actionId":"action-1","status":"SUCCEEDED","currentRoute":"dashboard"}
                                """))
                .andExpect(status().isNotFound());

        verify(gateway, never()).post(anyString(), any(), anyString());
    }

    @Test
    void conflictingTerminalReceiptPropagates409WithoutOverwriting() throws Exception {
        when(gateway.post(anyString(), any(), anyString())).thenThrow(
                new AgentGatewayException(HttpStatus.CONFLICT, "该动作已存在不同的终态回执"));

        mockMvc.perform(post("/api/assistant/conversations/{id}/actions/receipt", CONVERSATION_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actionId":"action-1","status":"FAILED","currentRoute":"dashboard"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void receiptEndpointRequiresAuthenticatedUser() throws Exception {
        mockMvc.perform(post("/api/assistant/conversations/{id}/actions/receipt", CONVERSATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actionId":"action-1","status":"SUCCEEDED","currentRoute":"dashboard"}
                                """))
                .andExpect(status().isUnauthorized());
        verify(gateway, never()).post(anyString(), any(), anyString());
    }

    @Test
    void validatorDropsUnsafeErrorAndAcceptsCleanMessage() {
        AssistantActionReceiptValidator validator = new AssistantActionReceiptValidator();
        JsonNode unsafe = objectMapper.createObjectNode().put("error",
                "Traceback at org.springframework.web (X-Internal-Service-Token: t)");
        assertNull(AssistantActionReceiptValidator.sanitizeError(unsafe.get("error")));
        JsonNode safe = objectMapper.createObjectNode().put("error", "  自动打开页面失败  ");
        assertEquals("自动打开页面失败",
                AssistantActionReceiptValidator.sanitizeError(safe.get("error")));
        StringBuilder longError = new StringBuilder();
        longError.append("错误".repeat(1000));
        String sanitized = AssistantActionReceiptValidator.sanitizeError(
                objectMapper.createObjectNode().put("error", longError.toString()).get("error"));
        assertTrue(sanitized.length() <= 500);
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"receipt-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"回执测试用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(
                body.get("accessToken").asText(),
                body.get("user").get("id").asText());
    }

    @SuppressWarnings("unused")
    private static String randomConversationId() {
        return UUID.randomUUID().toString();
    }

    private record Registration(String token, String userId) {
    }
}
