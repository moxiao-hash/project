package com.moxiao.studypilot.agent.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AgentFacadeControllerTest {

    private static final HttpServer AI_SERVER = createServer();
    private static final AtomicReference<CapturedRequest> LAST_REQUEST = new AtomicReference<>();
    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    private static volatile int upstreamStatus = 200;
    private static volatile String retryAfterResponse;
    private static volatile String upstreamBody;
    private static final String PLAN_CONVERSATION_ID =
            "11111111-1111-1111-1111-111111111111";
    private static final String TASK_CONVERSATION_ID =
            "22222222-2222-2222-2222-222222222222";
    private static final String KNOWLEDGE_CONVERSATION_ID =
            "33333333-3333-3333-3333-333333333333";
    private static final String TEACHING_CONVERSATION_ID =
            "55555555-5555-5555-5555-555555555555";
    private static final String ADJUSTMENT_ID =
            "44444444-4444-4444-4444-444444444444";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void agentProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "studypilot.ai-service-base-url",
                () -> "http://127.0.0.1:" + AI_SERVER.getAddress().getPort()
        );
    }

    @BeforeAll
    static void startServer() {
        AI_SERVER.createContext("/", AgentFacadeControllerTest::handle);
        AI_SERVER.start();
    }

    @AfterAll
    static void stopServer() {
        AI_SERVER.stop(0);
    }

    @Test
    void facadeRequiresBearerAuthentication() throws Exception {
        mockMvc.perform(post("/api/agent/plan-conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goalId\":\"goal-1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void injectsAuthenticatedOwnerAndInternalTokenWithoutTrustingBrowserOwner() throws Exception {
        Registration registration = registerUser();

        upstreamStatus = 201;
        mockMvc.perform(post("/api/agent/plan-conversations")
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goalId":"goal-1","ownerId":"attacker"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conversationId").value("conversation-1"))
                .andExpect(jsonPath("$.ownerId").doesNotExist());

        CapturedRequest captured = LAST_REQUEST.get();
        JsonNode forwarded = objectMapper.readTree(captured.body());
        org.junit.jupiter.api.Assertions.assertEquals(
                "/internal/agent/conversations",
                captured.path()
        );
        org.junit.jupiter.api.Assertions.assertEquals(
                "test-internal-token",
                captured.internalToken()
        );
        org.junit.jupiter.api.Assertions.assertEquals(registration.userId(), forwarded.get("ownerId").asText());
        org.junit.jupiter.api.Assertions.assertEquals("goal-1", forwarded.get("goalId").asText());
    }

    @Test
    void mapsAllPublicRoutesToTheirInternalCounterparts() throws Exception {
        Registration registration = registerUser();
        String authorization = "Bearer " + registration.token();
        upstreamStatus = 200;

        assertForwarded(post("/api/agent/plan-conversations/{id}/messages", PLAN_CONVERSATION_ID)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"),
                "/internal/agent/conversations/" + PLAN_CONVERSATION_ID + "/messages", "POST");
        assertForwarded(get("/api/agent/plan-conversations/{id}", PLAN_CONVERSATION_ID)
                        .header("Authorization", authorization),
                "/internal/agent/conversations/" + PLAN_CONVERSATION_ID
                        + "?ownerId=" + registration.userId(), "GET");
        assertForwarded(post("/api/agent/plan-conversations/{id}/confirm", PLAN_CONVERSATION_ID)
                        .header("Authorization", authorization),
                "/internal/agent/conversations/" + PLAN_CONVERSATION_ID + "/confirm", "POST");

        assertForwarded(post("/api/agent/task-conversations")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetDate\":\"2026-07-28\"}"),
                "/internal/agent/task-conversations", "POST");
        assertForwarded(post("/api/agent/task-conversations/{id}/messages", TASK_CONVERSATION_ID)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"done\"}"),
                "/internal/agent/task-conversations/" + TASK_CONVERSATION_ID + "/messages", "POST");
        assertForwarded(get("/api/agent/task-conversations/{id}", TASK_CONVERSATION_ID)
                        .header("Authorization", authorization),
                "/internal/agent/task-conversations/" + TASK_CONVERSATION_ID
                        + "?ownerId=" + registration.userId(), "GET");
        assertForwarded(post("/api/agent/task-conversations/{id}/confirm", TASK_CONVERSATION_ID)
                        .header("Authorization", authorization),
                "/internal/agent/task-conversations/" + TASK_CONVERSATION_ID + "/confirm", "POST");

        assertForwarded(post("/api/agent/knowledge-conversations")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"AUTO\"}"),
                "/internal/knowledge/conversations", "POST");
        assertForwarded(post("/api/agent/knowledge-conversations/{id}/messages",
                        KNOWLEDGE_CONVERSATION_ID)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"question\",\"webSearch\":\"AUTO\"}"),
                "/internal/knowledge/conversations/" + KNOWLEDGE_CONVERSATION_ID + "/messages",
                "POST");
        assertForwarded(get("/api/agent/knowledge-conversations/{id}",
                        KNOWLEDGE_CONVERSATION_ID)
                        .header("Authorization", authorization),
                "/internal/knowledge/conversations/" + KNOWLEDGE_CONVERSATION_ID
                        + "?ownerId=" + registration.userId(), "GET");

        assertForwarded(post("/api/agent/teaching-conversations")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lessonId\":\"lesson-rest-controller\"}"),
                "/internal/teaching/conversations", "POST");
        assertForwarded(post("/api/agent/teaching-conversations/{id}/messages",
                        TEACHING_CONVERSATION_ID)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么需要 DTO？\"}"),
                "/internal/teaching/conversations/" + TEACHING_CONVERSATION_ID
                        + "/messages", "POST");
        assertForwarded(get("/api/agent/teaching-conversations/{id}",
                        TEACHING_CONVERSATION_ID)
                        .header("Authorization", authorization),
                "/internal/teaching/conversations/" + TEACHING_CONVERSATION_ID
                        + "?ownerId=" + registration.userId(), "GET");

        assertForwarded(post("/api/agent/plan-adjustments/analyze")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"analysisDate\":\"2026-07-28\"}"),
                "/internal/agent/plan-adjustments/analyze", "POST");
        assertForwarded(get("/api/agent/plan-adjustments/{id}", ADJUSTMENT_ID)
                        .header("Authorization", authorization),
                "/internal/agent/plan-adjustments/" + ADJUSTMENT_ID
                        + "?ownerId=" + registration.userId(), "GET");
        assertForwarded(post("/api/agent/plan-adjustments/{id}/confirm", ADJUSTMENT_ID)
                        .header("Authorization", authorization),
                "/internal/agent/plan-adjustments/" + ADJUSTMENT_ID + "/confirm", "POST");

        upstreamBody = "{\"id\":\"quiz-route-test\"}";
        try {
            assertForwarded(post("/api/agent/quizzes/generate")
                            .header("Authorization", authorization)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"taskId\":\"task-1\",\"webSearch\":\"AUTO\"}"),
                    "/internal/assessment/quizzes/generate", "POST");
        } finally {
            upstreamBody = null;
        }
    }

    @Test
    void messageAndConfirmAlwaysForwardTheAuthenticatedOwner() throws Exception {
        Registration registration = registerUser();
        String authorization = "Bearer " + registration.token();
        upstreamStatus = 200;

        mockMvc.perform(post("/api/agent/plan-conversations/{id}/messages",
                        PLAN_CONVERSATION_ID)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"hello","ownerId":"forged-owner"}
                                """))
                .andExpect(status().isOk());
        JsonNode messageBody = objectMapper.readTree(LAST_REQUEST.get().body());
        org.junit.jupiter.api.Assertions.assertEquals(
                registration.userId(),
                messageBody.get("ownerId").asText()
        );
        org.junit.jupiter.api.Assertions.assertNotEquals(
                "forged-owner",
                messageBody.get("ownerId").asText()
        );

        mockMvc.perform(post("/api/agent/plan-conversations/{id}/confirm",
                        PLAN_CONVERSATION_ID)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"forged-owner\"}"))
                .andExpect(status().isOk());
        JsonNode confirmBody = objectMapper.readTree(LAST_REQUEST.get().body());
        org.junit.jupiter.api.Assertions.assertEquals(
                registration.userId(),
                confirmBody.get("ownerId").asText()
        );
    }

    @Test
    void rateLimitPreservesRetryAfterAndGatewayDoesNotRetry() throws Exception {
        Registration registration = registerUser();
        upstreamStatus = 429;
        retryAfterResponse = "17";
        REQUEST_COUNT.set(0);
        try {
            mockMvc.perform(post("/api/agent/quizzes/generate")
                            .header("Authorization", "Bearer " + registration.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"taskId\":\"task-1\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .header().string("Retry-After", "17"));
            org.junit.jupiter.api.Assertions.assertEquals(1, REQUEST_COUNT.get());
        } finally {
            retryAfterResponse = null;
        }
    }

    @Test
    void upstreamFailureIsSentOnlyOnceWithoutAutomaticRetry() throws Exception {
        Registration registration = registerUser();
        upstreamStatus = 503;
        REQUEST_COUNT.set(0);

        mockMvc.perform(post("/api/agent/plan-conversations")
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goalId\":\"goal-1\"}"))
                .andExpect(status().isServiceUnavailable());

        org.junit.jupiter.api.Assertions.assertEquals(1, REQUEST_COUNT.get());
    }

    @Test
    void quizGenerationReturnsOnlyThePublicQuizIdentifier() throws Exception {
        Registration registration = registerUser();
        upstreamStatus = 201;
        upstreamBody = """
                {
                  "id": "quiz-1",
                  "ownerId": "internal-owner",
                  "questions": [
                    {
                      "id": "question-1",
                      "correctAnswers": ["secret answer"],
                      "referenceAnswer": "secret implementation"
                    }
                  ]
                }
                """;
        try {
            mockMvc.perform(post("/api/agent/quizzes/generate")
                            .header("Authorization", "Bearer " + registration.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"taskId\":\"task-1\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(org.springframework.test.web.servlet.result
                            .MockMvcResultMatchers.content().json("{\"quizId\":\"quiz-1\"}"))
                    .andExpect(jsonPath("$.questions").doesNotExist())
                    .andExpect(jsonPath("$.correctAnswers").doesNotExist())
                    .andExpect(jsonPath("$.referenceAnswer").doesNotExist())
                    .andExpect(jsonPath("$.ownerId").doesNotExist());
        } finally {
            upstreamBody = null;
        }
    }

    @Test
    void malformedQuizGenerationResponseBecomesSafeBadGateway() throws Exception {
        Registration registration = registerUser();
        upstreamStatus = 201;
        upstreamBody = "{\"questions\":[{\"correctAnswers\":[\"secret\"]}]}";
        try {
            mockMvc.perform(post("/api/agent/quizzes/generate")
                            .header("Authorization", "Bearer " + registration.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"taskId\":\"task-1\"}"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.message").value("AI 服务返回了无效的测验标识"))
                    .andExpect(jsonPath("$.questions").doesNotExist());
        } finally {
            upstreamBody = null;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-uuid",
            "contains%3Fquery",
            "contains%23fragment",
            "%25",
            ".."
    })
    void invalidResourceIdentifiersAreRejectedBeforeCallingPython(String maliciousId)
            throws Exception {
        Registration registration = registerUser();
        REQUEST_COUNT.set(0);

        mockMvc.perform(get("/api/agent/plan-conversations/" + maliciousId)
                        .header("Authorization", "Bearer " + registration.token()))
                .andExpect(status().isBadRequest());

        org.junit.jupiter.api.Assertions.assertEquals(0, REQUEST_COUNT.get());
    }

    @ParameterizedTest
    @CsvSource({
            "400,400",
            "404,404",
            "409,409",
            "422,422",
            "429,429",
            "500,502",
            "502,502",
            "503,503",
            "504,504"
    })
    void mapsUpstreamErrorStatusAndSafeDetail(
            int upstream,
            int expected
    ) throws Exception {
        Registration registration = registerUser();
        upstreamStatus = upstream;

        mockMvc.perform(post("/api/agent/quizzes/generate")
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":\"task-1\"}"))
                .andExpect(status().is(expected))
                .andExpect(jsonPath("$.message").value("上游请求无法处理"));
    }

    private void assertForwarded(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String expectedPath,
            String expectedMethod
    ) throws Exception {
        upstreamStatus = expectedMethod.equals("POST") && !expectedPath.contains("/messages")
                && !expectedPath.contains("/confirm") ? 201 : 200;
        mockMvc.perform(request).andExpect(status().is(upstreamStatus));
        CapturedRequest captured = LAST_REQUEST.get();
        org.junit.jupiter.api.Assertions.assertEquals(expectedPath, captured.path());
        org.junit.jupiter.api.Assertions.assertEquals(expectedMethod, captured.method());
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "agent-facade-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "Agent facade"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(
                response.get("accessToken").asText(),
                response.get("user").get("id").asText()
        );
    }

    private static HttpServer createServer() {
        try {
            return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        REQUEST_COUNT.incrementAndGet();
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        String query = exchange.getRequestURI().getRawQuery();
        String path = exchange.getRequestURI().getRawPath()
                + (query == null ? "" : "?" + query);
        LAST_REQUEST.set(new CapturedRequest(
                exchange.getRequestMethod(),
                path,
                new String(requestBody, StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("X-Internal-Service-Token")
        ));
        String response = upstreamBody != null
                ? upstreamBody
                : upstreamStatus >= 400
                ? "{\"detail\":\"上游请求无法处理\",\"secret\":\"must-not-leak\"}"
                : """
                  {"conversationId":"conversation-1","ownerId":"internal-owner","status":"COLLECTING"}
                  """;
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (retryAfterResponse != null) {
            exchange.getResponseHeaders().add("Retry-After", retryAfterResponse);
        }
        exchange.sendResponseHeaders(upstreamStatus, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CapturedRequest(
            String method,
            String path,
            String body,
            String internalToken
    ) {
    }

    private record Registration(String token, String userId) {
    }
}
