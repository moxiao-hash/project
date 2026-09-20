package com.moxiao.studypilot.agent.usage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部用量上报与已认证健康接口的端到端行为：幂等、未知价格、失败计数与严格 owner 隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalAssistantUsageContractTest {

    private static final String INTERNAL_TOKEN = "test-internal-token";
    private static final String PEAK_TIME = "2026-09-21T02:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AssistantUsageBudgetJpaRepository budgetRepository;

    @Test
    void recordsIdempotentlyAndIsolatesOwnersWithExplicitPriceStatus() throws Exception {
        Registration owner = registerUser("usage-owner");
        Registration other = registerUser("usage-other");

        // 高峰时段（周一 02:00 UTC）的 Flash 调用：缓存输入、非缓存输入与 reasoning 明细。
        record(usage(owner.userId(), "usage-a-1", "deepseek-flash", "SUCCEEDED", 1000, 400, 200, 50))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceStatus").value("KNOWN"))
                .andExpect(jsonPath("$.priceWindow").value("PEAK"))
                .andExpect(jsonPath("$.estimatedCost").isNumber())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.duplicate").value(false));

        // 同一 usageId 重复回调：不重复计费。
        record(usage(owner.userId(), "usage-a-1", "deepseek-flash", "SUCCEEDED", 1000, 400, 200, 50))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicate").value(true));

        // 一次失败调用也计入调用量与失败率。
        record(usage(owner.userId(), "usage-a-2", "deepseek-flash", "FAILED", 0, 0, 0, null))
                .andExpect(status().isCreated());

        // 未列入官方目录的模型保持 UNKNOWN，绝不当成零成本。
        record(usage(other.userId(), "usage-b-1", "unlisted-model", "SUCCEEDED", 100, 0, 10, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.estimatedCost").value(nullValue()));

        // 未带内部令牌必须被拒绝。
        mockMvc.perform(post("/internal/assistant-usage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(usage(owner.userId(), "usage-a-3", "deepseek-flash", "SUCCEEDED",
                                1, 0, 1, null)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/assistant/health")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelCalls").value(2))
                .andExpect(jsonPath("$.failedModelCalls").value(1))
                .andExpect(jsonPath("$.modelFailureRate").value(0.5))
                .andExpect(jsonPath("$.modelPromptTokens").value(1000))
                .andExpect(jsonPath("$.modelCachedPromptTokens").value(400))
                .andExpect(jsonPath("$.modelUncachedPromptTokens").value(600))
                .andExpect(jsonPath("$.modelCompletionTokens").value(200))
                .andExpect(jsonPath("$.modelReasoningTokens").value(50))
                .andExpect(jsonPath("$.modelTotalTokens").value(1200))
                .andExpect(jsonPath("$.unknownPriceCalls").value(0))
                .andExpect(jsonPath("$.priceStatus").value("KNOWN"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.usageEstimatedCost").isNumber())
                .andExpect(jsonPath("$.p50LatencyMs").isNumber())
                .andExpect(jsonPath("$.p95LatencyMs").isNumber())
                .andExpect(jsonPath("$.models.length()").value(1))
                .andExpect(jsonPath("$.models[0].modelName").value("deepseek-flash"))
                .andExpect(jsonPath("$.models[0].calls").value(2))
                .andExpect(jsonPath("$.models[0].failedCalls").value(1));

        mockMvc.perform(get("/api/assistant/health")
                        .header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelCalls").value(1))
                .andExpect(jsonPath("$.unknownPriceCalls").value(1))
                .andExpect(jsonPath("$.priceStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.usageEstimatedCost").value(nullValue()))
                .andExpect(jsonPath("$.models[0].modelName").value("unlisted-model"))
                .andExpect(jsonPath("$.models[0].priceStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.models[0].estimatedCost").value(nullValue()));
    }

    @Test
    void budgetEndpointReportsConfiguredTimezoneAndTurnOutputCap() throws Exception {
        Registration owner = registerUser("budget-owner");
        mockMvc.perform(get("/internal/assistant-usage/budget")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .param("ownerId", owner.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.reason").value("NO_BUDGET_CONFIGURED"))
                .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"));
    }

    @Test
    void reservationEndpointIsIdempotentAndEnforcesOneRemainingCall() throws Exception {
        Registration owner = registerUser("reservation-owner");
        budgetRepository.saveAndFlush(new AssistantUsageBudgetEntity(
                owner.userId(), 1, null, 512, "USD", Instant.now()));

        // 同一 usageId 的重试返回同一预占，不额外消耗名额。
        reserve(owner.userId(), "reserve-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.reservationId").value("reserve-1"))
                .andExpect(jsonPath("$.maxOutputTokensPerTurn").value(512))
                .andExpect(jsonPath("$.reason").value("WITHIN_BUDGET"));
        reserve(owner.userId(), "reserve-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.reservationId").value("reserve-1"));

        // 剩余名额已被预占，第二个不同调用被拒绝。
        reserve(owner.userId(), "reserve-2")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("DAILY_MODEL_CALLS_EXHAUSTED"));

        // 释放后名额重新可用。
        mockMvc.perform(post("/internal/assistant-usage/reservations/reserve-1/release")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true));
        mockMvc.perform(post("/internal/assistant-usage/reservations/reserve-1/release")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(false));
        reserve(owner.userId(), "reserve-3")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowed").value(true));

        // 缺少内部令牌的预占被拒绝，不能绕过预算。
        mockMvc.perform(post("/internal/assistant-usage/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationPayload(owner.userId(), "reserve-4")))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions reserve(String ownerId, String usageId)
            throws Exception {
        return mockMvc.perform(post("/internal/assistant-usage/reservations")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reservationPayload(ownerId, usageId)));
    }

    private String reservationPayload(String ownerId, String usageId) {
        return """
                {
                  "usageId": "%s",
                  "ownerId": "%s",
                  "conversationId": "conversation-1",
                  "turnId": "turn-1",
                  "purpose": "KNOWLEDGE_QA",
                  "provider": "deepseek",
                  "modelName": "deepseek-flash"
                }
                """.formatted(usageId, ownerId);
    }

    private org.springframework.test.web.servlet.ResultActions record(String payload) throws Exception {
        return mockMvc.perform(post("/internal/assistant-usage")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isCreated());
    }

    private String usage(
            String ownerId, String usageId, String model, String status,
            int prompt, int cached, int completion, Integer reasoning) {
        return """
                {
                  "usageId": "%s",
                  "ownerId": "%s",
                  "conversationId": "conversation-1",
                  "turnId": "turn-1",
                  "provider": "deepseek",
                  "modelName": "%s",
                  "purpose": "KNOWLEDGE_QA",
                  "status": "%s",
                  "promptTokens": %d,
                  "cachedPromptTokens": %d,
                  "completionTokens": %d,
                  "reasoningTokens": %s,
                  "latencyMs": 120,
                  "occurredAt": "%s"
                }
                """.formatted(
                usageId, ownerId, model, status, prompt, cached, completion,
                reasoning == null ? "null" : reasoning.toString(), PEAK_TIME);
    }

    private Registration registerUser(String label) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "用量合同用户"
                                }
                                """.formatted(label, System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(
                response.get("user").get("id").asText(),
                response.get("accessToken").asText());
    }

    private record Registration(String userId, String token) {
    }
}
