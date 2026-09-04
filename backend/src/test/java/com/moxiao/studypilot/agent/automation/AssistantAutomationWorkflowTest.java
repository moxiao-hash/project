package com.moxiao.studypilot.agent.automation;

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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AssistantAutomationWorkflowTest {
    private static final String INTERNAL_TOKEN = "test-internal-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AssistantAutomationJobJpaRepository jobRepository;

    @Test
    void userCanManageRulesWithoutCrossingOwnerBoundary() throws Exception {
        Registration owner = register("automation-owner");
        Registration other = register("automation-other");

        MvcResult created = createRule(owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("AUTHORIZED_PLAN_ADJUSTMENT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.requiredScope").value("SMALL_PLAN_ADJUSTMENT"))
                .andReturn();
        String ruleId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(get("/api/assistant/automation-rules")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/assistant/automation-rules/{id}", ruleId)
                        .header("Authorization", bearer(other.token())))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/assistant/automation-rules/{id}", ruleId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        mockMvc.perform(delete("/api/assistant/automation-rules/{id}", ruleId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/assistant/automation-rules")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void globalPausePreventsClaimAndLeaseUsesWorkerToken() throws Exception {
        Registration owner = register("automation-lease");
        createGrant(owner.token());
        createRule(owner.token()).andExpect(status().isCreated());

        mockMvc.perform(patch("/api/assistant/automation-settings")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paused\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true));
        claim("worker-1").andExpect(status().isNoContent());

        mockMvc.perform(patch("/api/assistant/automation-settings")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paused\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false));

        MvcResult claimed = claim("worker-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(owner.userId()))
                .andExpect(jsonPath("$.type").value("AUTHORIZED_PLAN_ADJUSTMENT"))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.executionId").isNotEmpty())
                .andReturn();
        JsonNode job = objectMapper.readTree(claimed.getResponse().getContentAsString());

        mockMvc.perform(post("/internal/assistant-automation-jobs/{id}/heartbeat",
                                job.get("id").asText())
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"wrong-worker","leaseToken":"%s","leaseSeconds":60}
                                """.formatted(job.get("leaseToken").asText())))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/internal/assistant-automation-jobs/{id}/complete",
                                job.get("id").asText())
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1","leaseToken":"%s","resultSummary":"完成"}
                                """.formatted(job.get("leaseToken").asText())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(get("/api/agent-executions")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].triggerType").value("NIGHTLY_CHECK"));
        claim("worker-2").andExpect(status().isNoContent());
    }

    @Test
    void changingRuleScheduleReschedulesItsPendingJob() throws Exception {
        Registration owner = register("automation-reschedule");
        MvcResult created = createRule(owner.token())
                .andExpect(status().isCreated())
                .andReturn();
        String ruleId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText();
        Instant originalSchedule = pendingJobs(ruleId).get(0).getScheduledFor();

        LocalTime replacement = LocalTime.now(ZoneId.of("Asia/Shanghai"))
                .plusHours(3).withSecond(0).withNano(0);
        mockMvc.perform(patch("/api/assistant/automation-rules/{id}", ruleId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timezone":"Asia/Shanghai","localTime":"%s"}
                                """.formatted(replacement.format(
                                DateTimeFormatter.ofPattern("HH:mm")))))
                .andExpect(status().isOk());

        AssistantAutomationJobEntity rescheduled = pendingJobs(ruleId).get(0);
        org.assertj.core.api.Assertions.assertThat(rescheduled.getScheduledFor())
                .isNotEqualTo(originalSchedule);
        org.assertj.core.api.Assertions.assertThat(
                        rescheduled.getScheduledFor().atZone(ZoneId.of("Asia/Shanghai"))
                                .toLocalTime())
                .isEqualTo(replacement);
    }

    private List<AssistantAutomationJobEntity> pendingJobs(String ruleId) {
        return jobRepository.findAllByRuleIdAndStatus(
                ruleId, AutomationJobStatus.PENDING);
    }

    private org.springframework.test.web.servlet.ResultActions createRule(String token)
            throws Exception {
        String localTime = LocalTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("HH:mm"));
        return mockMvc.perform(post("/api/assistant/automation-rules")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "type":"AUTHORIZED_PLAN_ADJUSTMENT",
                          "timezone":"Asia/Shanghai",
                          "localTime":"%s",
                          "enabled":true
                        }
                        """.formatted(localTime)));
    }

    private void createGrant(String token) throws Exception {
        mockMvc.perform(post("/api/agent-grants")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scopes":["SMALL_PLAN_ADJUSTMENT"],
                                  "expiresAt":"2099-01-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions claim(String workerId)
            throws Exception {
        return mockMvc.perform(post("/internal/assistant-automation-jobs/claim")
                .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"workerId":"%s","leaseSeconds":60}
                        """.formatted(workerId)));
    }

    private Registration register(String prefix) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"%s-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"自动化测试用户"
                                }
                                """.formatted(prefix, System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(body.get("user").get("id").asText(),
                body.get("accessToken").asText());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record Registration(String userId, String token) {
    }
}
