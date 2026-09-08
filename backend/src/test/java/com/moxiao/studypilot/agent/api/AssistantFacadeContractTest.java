package com.moxiao.studypilot.agent.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
// H2 + 模拟 Python HTTP 上游：只验证 Java 门面契约，不代表真实模型或 Runner E2E。
class AssistantFacadeContractTest {

    private static final HttpServer AI_SERVER = createServer();
    private static final AtomicReference<CapturedRequest> LAST_REQUEST = new AtomicReference<>();
    private static volatile int upstreamStatus = 200;
    private static volatile String upstreamBody;
    private static final String CONVERSATION_ID = "11111111-2222-3333-4444-555555555555";
    private static final String ACTION_ID = "action-task-finish-1";

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
        AI_SERVER.createContext("/", AssistantFacadeContractTest::handle);
        AI_SERVER.start();
    }

    @AfterAll
    static void stopServer() {
        AI_SERVER.stop(0);
    }

    @Test
    void forwardsSnapshotsConfirmationAndReplayCursorWithoutClaimingBusinessExecution() throws Exception {
        // 1. 注册新用户，验证仅生成受控 Bearer Token，前端不可伪造 ownerId
        Registration user = registerUser();
        String authHeader = "Bearer " + user.token();

        // 2. 检查 AI 凭据状态，验证后端脱敏，不得返回明文 API Key
        mockMvc.perform(get("/api/ai-settings")
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deepseekConfigured").exists())
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        // 3. 创建学习目标与待办任务
        String goalId = createGoal(authHeader);
        String taskId = createTask(user, goalId);

        // 4. 统一助手创建会话：POST /api/assistant/conversations
        // 即使客户端伪造 ownerId="attacker"，Facade 也必须强制替换为登录用户的真实 userId
        upstreamStatus = 201;
        upstreamBody = """
                {
                  "conversationId": "%s",
                  "ownerId": "%s",
                  "status": "READY",
                  "reply": "你好！我是你的 StudyPilot 专属导师，已准备好协助你学习。",
                  "modelName": "deepseek-v4-flash"
                }
                """.formatted(CONVERSATION_ID, user.userId());

        mockMvc.perform(post("/api/assistant/conversations")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "attacker"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conversationId").value(CONVERSATION_ID))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.reply").isNotEmpty());

        CapturedRequest createReq = LAST_REQUEST.get();
        assertEquals("POST", createReq.method());
        assertEquals("/internal/assistant/conversations", createReq.path());
        JsonNode createPayload = objectMapper.readTree(createReq.body());
        assertEquals(user.userId(), createPayload.get("ownerId").asText());

        // 5. 发送首轮自然语言消息，生成任务完成动作卡 (WAITING_CONFIRMATION) 与前端白名单路由动作
        upstreamStatus = 200;
        upstreamBody = """
                {
                  "conversationId": "%s",
                  "ownerId": "%s",
                  "status": "WAITING_CONFIRMATION",
                  "reply": "我已经为你准备好了完成该任务的申请，请在下方确认卡片中核对并点击确认。",
                  "modelName": "deepseek-v4-flash",
                  "pendingAction": {
                    "actionId": "%s",
                    "executionId": "execution-task-finish-1",
                    "toolName": "learning.task.update",
                    "toolVersion": 1,
                    "expiresAt": "2099-01-01T00:00:00Z",
                    "riskLevel": "HIGH",
                    "status": "WAITING_CONFIRMATION",
                    "summary": "将任务状态更新为已完成",
                    "arguments": {
                      "taskId": "%s",
                      "expectedVersion": 1,
                      "status": "COMPLETED"
                    }
                  },
                  "uiActions": [{
                    "type": "NAVIGATE",
                    "routeKey": "TODAY",
                    "params": {},
                    "reason": "查看今日任务"
                  }]
                }
                """.formatted(CONVERSATION_ID, user.userId(), ACTION_ID, taskId);

        mockMvc.perform(post("/api/assistant/conversations/{id}/messages", CONVERSATION_ID)
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "帮我把今天的 Spring Boot 任务标记为完成",
                                  "idempotencyKey": "msg-turn-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.pendingAction.actionId").value(ACTION_ID))
                .andExpect(jsonPath("$.pendingAction.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.uiActions[0].type").value("NAVIGATE"))
                .andExpect(jsonPath("$.uiActions[0].routeKey").value("TODAY"));

        // 模拟上游不会修改 Java 数据。真实写入、版本和幂等由 GovernedAgentToolWorkflowTest 验证。
        mockMvc.perform(get("/api/learning-tasks")
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("TODO"));

        // 6. SSE 事件流与断线恢复续传验证：通过 Last-Event-ID 重放未消费事件
        upstreamBody = """
                [
                  {
                    "sequence": 1,
                    "type": "TURN_STARTED",
                    "conversationId": "%s",
                    "payload": {}
                  },
                  {
                    "sequence": 2,
                    "type": "ACTION_PREVIEW",
                    "conversationId": "%s",
                    "payload": {"actionId": "%s"}
                  },
                  {
                    "sequence": 3,
                    "type": "TURN_COMPLETED",
                    "conversationId": "%s",
                    "payload": {"reply": "操作等待确认"}
                  }
                ]
                """.formatted(CONVERSATION_ID, CONVERSATION_ID, ACTION_ID, CONVERSATION_ID);

        MvcResult replay = mockMvc.perform(get("/api/assistant/conversations/{id}/events", CONVERSATION_ID)
                        .header("Authorization", authHeader)
                        .header("Last-Event-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("id: 2")))
                .andExpect(content().string(containsString("id: 3")))
                .andReturn();
        String stream = replay.getResponse().getContentAsString();
        assertFalse(stream.contains("id: 1\n"), "已消费事件不得重放");
        assertTrue(stream.indexOf("id: 2\n") < stream.indexOf("id: 3\n"));
        assertTrue(LAST_REQUEST.get().path().contains("afterSequence=1"));
        assertTrue(LAST_REQUEST.get().path().contains("ownerId=" + user.userId()));
        assertEquals(INTERNAL_TOKEN, LAST_REQUEST.get().token());

        // 7. 用户点击专用确认接口：POST /api/assistant/conversations/{id}/actions/{actionId}/confirm
        upstreamStatus = 200;
        upstreamBody = """
                {
                  "conversationId": "%s",
                  "ownerId": "%s",
                  "status": "COMPLETED",
                  "reply": "已成功为你将任务更新为已完成状态！",
                  "modelName": "deepseek-v4-flash"
                }
                """.formatted(CONVERSATION_ID, user.userId());

        mockMvc.perform(post("/api/assistant/conversations/{id}/actions/{actionId}/confirm",
                        CONVERSATION_ID, ACTION_ID)
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        CapturedRequest confirmReq = LAST_REQUEST.get();
        assertEquals("POST", confirmReq.method());
        assertEquals("/internal/assistant/conversations/" + CONVERSATION_ID + "/actions/" + ACTION_ID + "/confirm",
                confirmReq.path());
        assertEquals(user.userId(), objectMapper.readTree(confirmReq.body()).get("ownerId").asText());
        // COMPLETED 是模拟上游的返回值，不等于任务已执行；显式断言，避免再次误报 E2E。
        mockMvc.perform(get("/api/learning-tasks").header("Authorization", authHeader))
                .andExpect(jsonPath("$[0].status").value("TODO"))
                .andExpect(jsonPath("$[0].version").value(1));

        // 8. 验证 Local Runner 执行预览与安全边界
        String workspaceId = createWorkspace(authHeader);
        mockMvc.perform(post("/api/runner/preview")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId": "%s",
                                  "templateType": "MAVEN_TEST",
                                  "idempotencyKey": "runner-preview-%d",
                                  "explanation": "端到端联调测试预览"
                                }
                                """.formatted(workspaceId, System.nanoTime())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateType").value("MAVEN_TEST"))
                .andExpect(jsonPath("$.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.confirmationRequired").value(false));

        // 9. 验证运行健康与审计指标 API
        mockMvc.perform(get("/api/assistant/health")
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExecutions").exists())
                .andExpect(jsonPath("$.successfulExecutions").exists())
                .andExpect(jsonPath("$.failedExecutions").exists())
                .andExpect(jsonPath("$.pendingConfirmations").exists())
                .andExpect(jsonPath("$.successRate").exists());
    }

    private String createWorkspace(String authHeader) throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("agent-native-e2e-ws");
        MvcResult result = mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "E2E Workspace",
                                  "rootPath": "%s"
                                }
                                """.formatted(tempDir.toRealPath())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createGoal(String authHeader) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/learning-goals")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Agent 原生全栈实战目标",
                                  "targetDate": "%s",
                                  "weeklyStudyHours": 10
                                }
                                """.formatted(LocalDate.now().plusMonths(2))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private static final String INTERNAL_TOKEN = "test-internal-token";

    private String createTask(Registration user, String goalId) throws Exception {
        LocalDate date = LocalDate.now().plusDays(1);
        MvcResult result = mockMvc.perform(post("/internal/confirmed-learning-plans")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "%s",
                                  "goalId": "%s",
                                  "idempotencyKey": "agent-native-e2e-plan-%d",
                                  "title": "Agent 原生全栈实战计划",
                                  "startDate": "%s",
                                  "endDate": "%s",
                                  "tasks": [
                                    {
                                      "title": "完成 Spring Boot Agent 集成测试",
                                      "scheduledDate": "%s",
                                      "estimatedMinutes": 60
                                    }
                                  ]
                                }
                                """.formatted(user.userId(), goalId, System.nanoTime(),
                                date, date.plusDays(7), date)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("tasks").get(0).get("id").asText();
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "agent-native-e2e-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "Agent Native E2E"
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
        String response = upstreamBody != null ? upstreamBody : "{}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(upstreamStatus, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Registration(String token, String userId) {
    }

    private record CapturedRequest(String method, String path, String body, String token) {
    }
}
