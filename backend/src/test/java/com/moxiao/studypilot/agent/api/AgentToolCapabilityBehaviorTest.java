package com.moxiao.studypilot.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 30：[UNIT/H2] 优先能力必须通过统一工具产生真实业务行为。
 *
 * <p>覆盖继续未完成节点、任务与计划查询、资料查询、通知、执行审计、工作区登记、
 * 目标创建与今日容量刷新；并验证跨用户隔离。H2 + MockMvc，不代表真实 MySQL 或浏览器 E2E。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentToolCapabilityBehaviorTest {

    private static final String INTERNAL_TOKEN = "test-internal-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void priorityCapabilitiesProduceRealBusinessBehaviorThroughTheUnifiedTools() throws Exception {
        Registration owner = registerUser();
        String goalId = createGoal(owner.token(), "行为覆盖目标");
        LocalDate date = LocalDate.now().plusDays(1);
        String planId = createPlanWithTask(owner, goalId, date);
        String workspaceId = createWorkspace(owner.token());
        initializeSettings(owner.token());

        // 继续未完成节点 / 今日任务：learning.context.get 与 learning.tasks.list 返回真实数据
        invokeRead(owner.userId(), "learning.context.get", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.learning.goals[0].id").value(goalId));
        invokeRead(owner.userId(), "learning.tasks.list", "{\"date\":\"" + date + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("行为覆盖任务"))
                .andExpect(jsonPath("$.data[0].status").value("TODO"));

        // 目标与计划查询
        invokeRead(owner.userId(), "learning.goals.list", "{}")
                .andExpect(jsonPath("$.data[0].id").value(goalId));
        invokeRead(owner.userId(), "learning.plans.list", "{}")
                .andExpect(jsonPath("$.data[0].id").value(planId));
        invokeRead(owner.userId(), "learning.plan.get", "{\"planId\":\"" + planId + "\"}")
                .andExpect(jsonPath("$.data.title").value("行为覆盖计划"));

        // 今日容量调整：先读取当前设置，再以 HIGH 风险动作卡 + 专用确认调整每日时长
        invokeRead(owner.userId(), "settings.learning.get", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailyStudyLimitMinutes").isNumber());
        MvcResult capacity = invokeWrite(owner.userId(), "settings.learning.update",
                "capacity-1", "{\"dailyStudyLimitMinutes\":75}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action.status").value("WAITING_CONFIRMATION"))
                .andReturn();
        String capacityActionId = objectMapper
                .readTree(capacity.getResponse().getContentAsString())
                .get("action").get("actionId").asText();
        mockMvc.perform(post("/internal/agent-tool-actions/{id}/confirm", capacityActionId)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"" + owner.userId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        invokeRead(owner.userId(), "settings.learning.get", "{}")
                .andExpect(jsonPath("$.data.dailyStudyLimitMinutes").value(75));

        // 资料查询与错题汇总（空集合也必须返回真实结构）
        invokeRead(owner.userId(), "materials.list", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        invokeRead(owner.userId(), "assessment.wrong_questions.summary", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeCount").value(0));

        // 执行审计：上文工具调用必须留下可回溯记录
        invokeRead(owner.userId(), "governance.executions.list", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        invokeRead(owner.userId(), "governance.audit.list", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].action").value(hasItem("EXECUTION_CREATED")));

        // 通知处理：读取当前用户通知
        invokeRead(owner.userId(), "notifications.list", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        // 工作区登记（HIGH 风险）：先动作卡，再专用确认，最后真实落库
        MvcResult preview = invokeWrite(owner.userId(), "workspaces.register",
                "workspace-register-1",
                "{\"name\":\"行为覆盖工作区\",\"rootPath\":\"" + workspaceId + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action.status").value("WAITING_CONFIRMATION"))
                .andReturn();
        String actionId = objectMapper.readTree(preview.getResponse().getContentAsString())
                .get("action").get("actionId").asText();
        mockMvc.perform(post("/internal/agent-tool-actions/{id}/confirm", actionId)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"" + owner.userId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        invokeRead(owner.userId(), "workspaces.list", "{}")
                .andExpect(jsonPath("$.data[*].name").value(hasItem("行为覆盖工作区")));

        // 目标创建（LOW 风险）：无授权时仍生成动作卡，专用确认后真实写入
        MvcResult goalCreate = invokeWrite(owner.userId(), "learning.goal.create", "goal-create-1",
                "{\"title\":\"工具创建的目标\",\"targetDate\":\""
                        + LocalDate.now().plusMonths(1) + "\",\"weeklyStudyHours\":6}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action.status").exists())
                .andReturn();
        String goalActionId = objectMapper
                .readTree(goalCreate.getResponse().getContentAsString())
                .get("action").get("actionId").asText();
        mockMvc.perform(post("/internal/agent-tool-actions/{id}/confirm", goalActionId)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"" + owner.userId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.title").value("工具创建的目标"));
    }

    @Test
    void toolsNeverCrossUserBoundariesForReadsOrWrites() throws Exception {
        Registration owner = registerUser();
        Registration attacker = registerUser();
        String goalId = createGoal(owner.token(), "隔离目标");
        LocalDate date = LocalDate.now().plusDays(1);
        String planId = createPlanWithTask(owner, goalId, date);
        String workspaceRoot = createWorkspace(owner.token());

        // 跨用户读取另一个用户的计划必须失败，绝不返回数据。
        invokeRead(attacker.userId(), "learning.plan.get", "{\"planId\":\"" + planId + "\"}")
                .andExpect(status().isNotFound());

        // 跨用户登记同一物理目录仍必须经过攻击者本人的专用确认；
        // 未确认前不得出现在任何人的工作区列表中，且所有者的工作区不可见。
        invokeWrite(attacker.userId(), "workspaces.register", "attacker-register-1",
                "{\"name\":\"攻击者工作区\",\"rootPath\":\"" + workspaceRoot + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action.status").value("WAITING_CONFIRMATION"));
        invokeRead(attacker.userId(), "workspaces.list", "{}")
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private org.springframework.test.web.servlet.ResultActions invokeRead(
            String ownerId, String tool, String arguments
    ) throws Exception {
        return mockMvc.perform(post("/internal/agent-tools/{tool}/invoke", tool)
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerId\":\"" + ownerId + "\",\"arguments\":" + arguments + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions invokeWrite(
            String ownerId, String tool, String idempotencyKey, String arguments
    ) throws Exception {
        return mockMvc.perform(post("/internal/agent-tools/{tool}/invoke", tool)
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerId\":\"" + ownerId + "\",\"idempotencyKey\":\""
                        + idempotencyKey + "\",\"arguments\":" + arguments + "}"));
    }

    private void initializeSettings(String token) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/user-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timeZone": "Asia/Shanghai",
                                  "dailyStudyLimitMinutes": 120,
                                  "weekendPreference": "MORE",
                                  "defaultPrivacyLevel": "NORMAL",
                                  "weeklyAvailability": [
                                    {"dayOfWeek": "MONDAY", "startTime": "19:00", "endTime": "21:00"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());
    }

    private String createGoal(String token, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/learning-goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","targetDate":"%s","weeklyStudyHours":8}
                                """.formatted(title, LocalDate.now().plusMonths(2))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createPlanWithTask(
            Registration owner, String goalId, LocalDate date
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/internal/confirmed-learning-plans")
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId":"%s",
                                  "goalId":"%s",
                                  "idempotencyKey":"behavior-plan-%d",
                                  "title":"行为覆盖计划",
                                  "startDate":"%s",
                                  "endDate":"%s",
                                  "tasks":[{
                                    "title":"行为覆盖任务",
                                    "scheduledDate":"%s",
                                    "estimatedMinutes":45
                                  }]
                                }
                                """.formatted(owner.userId(), goalId, System.nanoTime(),
                                date, date.plusDays(5), date)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("plan").get("id").asText();
    }

    private String createWorkspace(String token) throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("behavior-tool-ws");
        MvcResult result = mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"行为覆盖工作区","rootPath":"%s"}
                                """.formatted(tempDir.toRealPath())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("rootPath").asText();
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"capability-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"能力覆盖用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(body.get("user").get("id").asText(),
                body.get("accessToken").asText());
    }

    private record Registration(String userId, String token) {
    }
}
