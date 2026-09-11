package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.domain.AgentScope;
import com.moxiao.studypilot.agent.domain.ExecutionType;
import com.moxiao.studypilot.agent.tool.AgentToolEffect;
import com.moxiao.studypilot.agent.tool.AgentToolHandler;
import com.moxiao.studypilot.agent.tool.AgentToolRiskLevel;
import com.moxiao.studypilot.agent.tool.AgentToolDescriptor;
import com.moxiao.studypilot.agent.tool.GovernedFunctionalAgentToolHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 30 第二次复审：[UNIT/H2] 受治理写超时必须具备“安全完成/围栏”语义。
 *
 * <p>事务体可能忽略线程中断并继续执行到提交。因此一旦超时，绝不能立即把动作标记为
 * 终态 FAILED；必须先确认事务结果，或在结果未知时保持 RUNNING，并禁止重复执行。
 * 本测试通过真实 Registry → AgentToolActionService → AgentToolBusinessExecutor(REQUIRES_NEW)
 * 链路验证：终态与真实提交之间不存在“先报 FAILED、后落副作用”的窗口。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "studypilot.agent-tool-timeout-grace-millis=800")
class GovernedWriteTimeoutSafetyTest {

    private static final String INTERNAL_TOKEN = "test-internal-token";
    private static final String RESOLVED_TOOL = "test.write_timeout_resolved";
    private static final String UNRESOLVED_TOOL = "test.write_timeout_unresolved";

    private static final AtomicBoolean RESOLVED_SIDE_EFFECT = new AtomicBoolean();
    private static final AtomicBoolean UNRESOLVED_SIDE_EFFECT = new AtomicBoolean();
    private static final AtomicInteger RESOLVED_INVOCATIONS = new AtomicInteger();
    private static final AtomicInteger UNRESOLVED_INVOCATIONS = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetProbes() {
        RESOLVED_SIDE_EFFECT.set(false);
        UNRESOLVED_SIDE_EFFECT.set(false);
        RESOLVED_INVOCATIONS.set(0);
        UNRESOLVED_INVOCATIONS.set(0);
    }

    @TestConfiguration
    static class SlowWriteTools {
        @Bean
        AgentToolHandler slowResolvedWriteTool(ObjectMapper mapper) {
            return slowWriteTool(mapper, RESOLVED_TOOL, 1_200,
                    RESOLVED_SIDE_EFFECT, RESOLVED_INVOCATIONS);
        }

        @Bean
        AgentToolHandler slowUnresolvedWriteTool(ObjectMapper mapper) {
            return slowWriteTool(mapper, UNRESOLVED_TOOL, 3_000,
                    UNRESOLVED_SIDE_EFFECT, UNRESOLVED_INVOCATIONS);
        }

        private static AgentToolHandler slowWriteTool(
                ObjectMapper mapper,
                String name,
                long busyMillis,
                AtomicBoolean sideEffect,
                AtomicInteger invocations
        ) {
            ObjectNode input = mapper.createObjectNode().put("type", "object")
                    .put("additionalProperties", false);
            input.putObject("properties");
            input.putArray("required");
            ObjectNode output = mapper.createObjectNode().put("type", "object")
                    .put("additionalProperties", false);
            output.putObject("properties").putObject("done").put("type", "boolean");
            output.putArray("required").add("done");
            AgentToolDescriptor descriptor = new AgentToolDescriptor(
                    name, 1, "TEST", AgentToolEffect.WRITE, AgentToolRiskLevel.HIGH,
                    AgentScope.TASK_MANAGEMENT.name(), true, input, output, 1_000);
            return new GovernedFunctionalAgentToolHandler(
                    descriptor,
                    ExecutionType.TASK_STATUS_CHANGE,
                    arguments -> "受治理写超时测试",
                    (context, arguments) -> {
                        invocations.incrementAndGet();
                        long deadline = System.nanoTime() + busyMillis * 1_000_000L;
                        while (System.nanoTime() < deadline) {
                            try {
                                Thread.sleep(25);
                            } catch (InterruptedException ignoredInterrupt) {
                                // 故意模拟忽略中断的 JDBC/阻塞调用。
                            }
                        }
                        sideEffect.set(true);
                        return Map.of("done", true);
                    });
        }
    }

    @Test
    void writeThatCommitsWithinGraceIsReportedSucceededNeverFailed() throws Exception {
        Registration owner = registerUser();
        String actionId = previewAction(owner.userId(), RESOLVED_TOOL, "write-timeout-resolved");

        MvcResult result = confirm(owner.userId(), actionId)
                .andExpect(status().isOk())
                .andReturn();
        String actionStatus = actionStatus(result);

        assertEquals("SUCCEEDED", actionStatus,
                "事务在宽限期内提交时必须报告 SUCCEEDED，而不是先报 FAILED");
        assertTrue(RESOLVED_SIDE_EFFECT.get(), "提交成功后副作用必须已生效");
        assertEquals(1, RESOLVED_INVOCATIONS.get());
    }

    @Test
    void unresolvedWriteIsNeverTerminalFailedAndPreventsDuplicateEffects() throws Exception {
        Registration owner = registerUser();
        String actionId = previewAction(owner.userId(), UNRESOLVED_TOOL, "write-timeout-unresolved");

        MvcResult first = confirm(owner.userId(), actionId)
                .andExpect(status().isOk())
                .andReturn();
        String firstStatus = actionStatus(first);

        assertNotEquals("FAILED", firstStatus,
                "事务结果未知时绝不能报告终态 FAILED，否则之后仍可能提交");
        assertEquals("RUNNING", firstStatus,
                "超时且事务尚未结束时必须保持 RUNNING 等待结果");
        assertFalse(UNRESOLVED_SIDE_EFFECT.get(), "此刻副作用尚未提交");

        // 结果未知期间重复确认不得再次执行写操作。
        String secondStatus = actionStatus(confirm(owner.userId(), actionId)
                .andExpect(status().isOk()).andReturn());
        assertEquals("RUNNING", secondStatus);
        assertEquals(1, UNRESOLVED_INVOCATIONS.get(),
                "结果未知期间重复确认不得产生重复业务执行");

        // 事务最终提交后，动作只能以 SUCCEEDED 定稿，且副作用恰好一次。
        String finalStatus = pollUntilTerminal(owner.userId(), actionId);
        assertEquals("SUCCEEDED", finalStatus, "最终必须按真实提交结果定稿为 SUCCEEDED");
        assertTrue(UNRESOLVED_SIDE_EFFECT.get());
        assertEquals(1, UNRESOLVED_INVOCATIONS.get());
    }

    private String pollUntilTerminal(String ownerId, String actionId) throws Exception {
        String status = "RUNNING";
        for (int attempt = 0; attempt < 100; attempt++) {
            status = actionStatus(confirm(ownerId, actionId)
                    .andExpect(status().isOk()).andReturn());
            if (!"RUNNING".equals(status)) {
                return status;
            }
            Thread.sleep(100);
        }
        return status;
    }

    private String previewAction(String ownerId, String tool, String key) throws Exception {
        MvcResult result = mockMvc.perform(post("/internal/agent-tools/{tool}/invoke", tool)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":"%s","idempotencyKey":"%s","arguments":{}}
                                """.formatted(ownerId, key)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.action.status").value("WAITING_CONFIRMATION"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("action").get("actionId").asText();
    }

    private org.springframework.test.web.servlet.ResultActions confirm(
            String ownerId, String actionId
    ) throws Exception {
        return mockMvc.perform(post("/internal/agent-tool-actions/{id}/confirm", actionId)
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerId\":\"" + ownerId + "\"}"));
    }

    private String actionStatus(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("status").asText();
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"write-timeout-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"写超时测试用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(body.get("user").get("id").asText());
    }

    private record Registration(String userId) {
    }
}
