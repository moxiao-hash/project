package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.agent.application.AgentGatewayException;
import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import com.moxiao.studypilot.roadmap.application.ArtifactReviewAiClient;
import com.moxiao.studypilot.roadmap.application.ArtifactRunnerEvidenceVerifier;
import com.moxiao.studypilot.roadmap.application.RoadmapCatalogImporter;
import com.moxiao.studypilot.roadmap.domain.ArtifactReviewPreviewStatus;
import com.moxiao.studypilot.roadmap.infrastructure.ArtifactReviewPreviewJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ArtifactReviewWorkflowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CourseCatalogImporter courseCatalogImporter;
    @Autowired RoadmapCatalogImporter roadmapCatalogImporter;
    @Autowired ArtifactReviewPreviewJpaRepository previewRepository;

    @MockitoBean ArtifactRunnerEvidenceVerifier evidenceVerifier;
    @MockitoBean ArtifactReviewAiClient aiClient;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        reset(evidenceVerifier, aiClient);
        courseCatalogImporter.importCatalog();
        roadmapCatalogImporter.importCatalog();
    }

    @Test
    void sendsOnlyConfirmedSnapshotAndRequiresFinalUserAcceptance() throws Exception {
        Registration owner = registerAndEnroll();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path source = Files.createDirectories(workspace.resolve("submission/src"));
        Files.writeString(source.resolve("App.java"), "public class App {}");
        Files.writeString(workspace.resolve("submission/.env"), "API_KEY=do-not-send");
        String workspaceId = createWorkspace(owner, workspace);
        String nodeId = firstMilestone(owner);
        String artifactId = submitArtifact(owner, workspaceId, nodeId).get("id").asText();

        when(evidenceVerifier.verify(eq(owner.id()), eq(workspaceId), eq("runner-1"), any()))
                .thenReturn(new ArtifactRunnerEvidenceVerifier.VerifiedRunnerEvidence(
                        "runner-1", "MAVEN_TEST", "Tests run: 8, Failures: 0", Instant.now()));
        when(aiClient.evaluate(eq(owner.id()), any()))
                .thenReturn(new ArtifactReviewAiClient.ArtifactAiReviewResult(
                        88, 36, 22, 17, 13, "实现完整，建议补充边界测试。",
                        List.of("职责清晰"), List.of("边界测试不足"),
                        "deepseek-v4-flash"));

        MvcResult previewResult = mockMvc.perform(post(
                            "/api/roadmap-artifacts/{id}/review-previews", artifactId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "runnerExecutionId":"runner-1",
                                  "idempotencyKey":"artifact-review-1"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.files.length()").value(1))
                .andExpect(jsonPath("$.files[0].relativePath").value("src/App.java"))
                .andExpect(jsonPath("$.excludedPaths[0]").value(".env"))
                .andReturn();
        String previewId = read(previewResult).get("id").asText();
        verify(aiClient, never()).evaluate(any(), any());

        mockMvc.perform(post(
                            "/api/roadmap-artifacts/{artifactId}/review-previews/{previewId}/confirm",
                            artifactId, previewId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.rubricScore").value(88))
                .andExpect(jsonPath("$.sensitiveScanPassed").value(true))
                .andExpect(jsonPath("$.reviewHistory[1].eventType")
                        .value("AI_RUBRIC_PASSED"));

        // 确认接口幂等，刷新或重复点击不会再次发送源码给模型。
        mockMvc.perform(post(
                            "/api/roadmap-artifacts/{artifactId}/review-previews/{previewId}/confirm",
                            artifactId, previewId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rubricScore").value(88));
        verify(aiClient, times(1)).evaluate(eq(owner.id()), any());

        JsonNode auditLogs = read(mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn());
        assertTrue(containsText(auditLogs, "ARTIFACT_REVIEW_PREVIEW_CREATED"));
        assertTrue(containsText(auditLogs, "ARTIFACT_REVIEW_CONFIRMED"));
        assertTrue(containsText(auditLogs, "ARTIFACT_REVIEW_SUCCEEDED"));

        JsonNode notifications = read(mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn());
        assertTrue(containsText(notifications, "成果 AI 评审已完成"));

        // AI 通过仍不等于用户验收；只有专用 accept 接口才完成最终状态变更。
        mockMvc.perform(post("/api/roadmap-artifacts/{id}/accept", artifactId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void recordsFailedReviewWithoutInventingScore() throws Exception {
        Registration owner = registerAndEnroll();
        Path workspace = Files.createDirectory(tempDir.resolve("failed-workspace"));
        Path submission = Files.createDirectory(workspace.resolve("submission"));
        Files.writeString(submission.resolve("App.java"), "public class App {}");
        String workspaceId = createWorkspace(owner, workspace);
        String artifactId = submitArtifact(owner, workspaceId, firstMilestone(owner))
                .get("id").asText();

        when(evidenceVerifier.verify(eq(owner.id()), eq(workspaceId), eq("runner-failed"), any()))
                .thenReturn(new ArtifactRunnerEvidenceVerifier.VerifiedRunnerEvidence(
                        "runner-failed", "MAVEN_TEST", "BUILD SUCCESS", Instant.now()));
        when(aiClient.evaluate(eq(owner.id()), any()))
                .thenThrow(new AgentGatewayException(
                        HttpStatus.SERVICE_UNAVAILABLE, "AI 服务暂时不可用"));

        MvcResult previewResult = mockMvc.perform(post(
                            "/api/roadmap-artifacts/{id}/review-previews", artifactId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "runnerExecutionId":"runner-failed",
                                  "idempotencyKey":"artifact-review-failed"
                                }
                                """))
                .andExpect(status().isCreated()).andReturn();
        String previewId = read(previewResult).get("id").asText();

        mockMvc.perform(post(
                            "/api/roadmap-artifacts/{artifactId}/review-previews/{previewId}/confirm",
                            artifactId, previewId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isServiceUnavailable());

        assertTrue(previewRepository.findById(previewId).orElseThrow().getStatus()
                == ArtifactReviewPreviewStatus.FAILED);
        JsonNode artifact = read(mockMvc.perform(get("/api/roadmap-artifacts/{id}", artifactId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn());
        assertTrue(artifact.get("rubricScore").isNull());
        JsonNode auditLogs = read(mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn());
        assertTrue(containsText(auditLogs, "ARTIFACT_REVIEW_FAILED"));
        JsonNode notifications = read(mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn());
        assertTrue(containsText(notifications, "成果 AI 评审失败"));
    }

    private String createWorkspace(Registration owner, Path root) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"评审工作区","rootPath":"%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated()).andReturn();
        return read(result).get("id").asText();
    }

    private JsonNode submitArtifact(
            Registration owner, String workspaceId, String nodeId
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/roadmap-artifacts")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId":"%s",
                                  "roadmapNodeId":"%s",
                                  "relativePath":"submission",
                                  "description":"实现模块里程碑服务并通过测试。",
                                  "testEvidence":"等待 Runner 验证。",
                                  "idempotencyKey":"artifact-submit-review"
                                }
                                """.formatted(workspaceId, nodeId)))
                .andExpect(status().isCreated()).andReturn();
        return read(result);
    }

    private String firstMilestone(Registration owner) throws Exception {
        JsonNode map = read(mockMvc.perform(get("/api/roadmaps/current/map")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn());
        String moduleId = map.get("stages").get(0).get("modules").get(0).get("id").asText();
        JsonNode module = read(mockMvc.perform(get(
                            "/api/roadmaps/current/modules/{id}", moduleId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn());
        return module.get("milestoneNode").get("id").asText();
    }

    private Registration registerAndEnroll() throws Exception {
        MvcResult registrationResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"review-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"成果评审测试"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode registration = read(registrationResult);
        Registration owner = new Registration(
                registration.get("user").get("id").asText(),
                registration.get("accessToken").asText());
        mockMvc.perform(post("/api/roadmap-enrollments")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roadmapCode":"studypilot-java-ai","templateVersion":2}
                                """))
                .andExpect(status().isCreated());
        return owner;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(Registration owner) {
        return "Bearer " + owner.token();
    }

    private boolean containsText(JsonNode value, String expected) {
        return value.toString().contains(expected);
    }

    private record Registration(String id, String token) { }
}
