package com.moxiao.studypilot.agent.runner;

import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.infrastructure.AuditLogEntity;
import com.moxiao.studypilot.agent.infrastructure.AuditLogJpaRepository;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.notification.infrastructure.NotificationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RunnerGovernanceWorkflowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RunnerGovernanceService runnerService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private AuditLogJpaRepository auditLogRepository;

    private String ownerId;
    private String token;
    private String workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        MvcResult authResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"runner-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"Runner测试用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode authBody = objectMapper.readTree(authResult.getResponse().getContentAsString());
        ownerId = authBody.get("user").get("id").asText();
        token = authBody.get("accessToken").asText();

        String validDirPath = new File(".").getAbsolutePath();
        MvcResult wsResult = mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"测试本地项目",
                                  "rootPath":"%s"
                                }
                                """.formatted(validDirPath)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode wsBody = objectMapper.readTree(wsResult.getResponse().getContentAsString());
        workspaceId = wsBody.get("id").asText();
        mockMvc.perform(post("/api/agent-grants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scopes":["RUNNER_MANAGEMENT"],
                                  "expiresAt":"2099-01-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated());

    }

    @Test
    void shouldPreviewMavenTestExecutionSuccessfully() {
        RunnerExecutionPreview preview = runnerService.preview(ownerId, new RunnerExecutionRequest(
                workspaceId,
                RunnerTemplateType.MAVEN_TEST,
                "SampleTest",
                "运行单元测试"
        ));

        assertNotNull(preview);
        assertEquals(workspaceId, preview.workspaceId());
        assertEquals("测试本地项目", preview.workspaceName());
        assertEquals(RunnerTemplateType.MAVEN_TEST, preview.templateType());
        assertFalse(preview.confirmationRequired());
        assertTrue(preview.renderedCommand().contains("-Dtest=SampleTest"));
    }

    @Test
    void shouldRejectCommandInjectionInPattern() {
        assertThrows(IllegalArgumentException.class, () ->
                runnerService.preview(ownerId, new RunnerExecutionRequest(
                        workspaceId,
                        RunnerTemplateType.MAVEN_TEST,
                        "Test; rm -rf /",
                        "尝试命令注入"
                ))
        );
    }

    @Test
    void shouldSubmitLowRiskExecutionDirectly() {
        RunnerExecutionResult result = runnerService.submit(ownerId, new RunnerExecutionRequest(
                workspaceId,
                RunnerTemplateType.MAVEN_COMPILE,
                null,
                "测试编译"
        ));

        assertNotNull(result);
        assertEquals("SUCCEEDED", result.status());
        assertTrue(result.success());

        List<NotificationEntity> notifications = notificationService.list(ownerId);
        assertFalse(notifications.isEmpty());
        assertTrue(notifications.stream().anyMatch(n -> n.getTitle().contains("Runner")));

        List<AuditLogEntity> logs = auditLogRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
        assertFalse(logs.isEmpty());
        assertTrue(logs.stream().anyMatch(l -> "RUNNER_EXECUTION_PREPARED".equals(l.getAction())));
    }

    @Test
    void shouldRequireConfirmationForHighRiskDependencies() {
        RunnerExecutionResult pendingResult = runnerService.submit(ownerId, new RunnerExecutionRequest(
                workspaceId,
                RunnerTemplateType.PREPARE_DEPENDENCIES,
                null,
                "安装本地依赖"
        ));

        assertNotNull(pendingResult);
        assertEquals(ExecutionStatus.WAITING_CONFIRMATION.name(), pendingResult.status());

        List<NotificationEntity> notifications = notificationService.list(ownerId);
        assertTrue(notifications.stream().anyMatch(n -> n.getTitle().contains("待确认")));

        RunnerExecutionResult confirmedResult = runnerService.confirm(ownerId, pendingResult.executionId());
        assertNotNull(confirmedResult);
        assertEquals("SUCCEEDED", confirmedResult.status());
        assertTrue(confirmedResult.success());

        List<AuditLogEntity> logs = auditLogRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
        assertTrue(logs.stream().anyMatch(l -> "RUNNER_EXECUTION_CONFIRMED".equals(l.getAction())));
    }

    @Test
    void shouldAllowUserToRejectExecution() {
        RunnerExecutionResult pendingResult = runnerService.submit(ownerId, new RunnerExecutionRequest(
                workspaceId,
                RunnerTemplateType.PREPARE_DEPENDENCIES,
                null,
                "安装本地依赖"
        ));

        assertEquals(ExecutionStatus.WAITING_CONFIRMATION.name(), pendingResult.status());

        RunnerExecutionResult rejectedResult = runnerService.reject(ownerId, pendingResult.executionId());
        assertEquals("REJECTED", rejectedResult.status());
        assertFalse(rejectedResult.success());

        List<AuditLogEntity> logs = auditLogRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
        assertTrue(logs.stream().anyMatch(l -> "RUNNER_EXECUTION_REJECTED".equals(l.getAction())));
    }
}
