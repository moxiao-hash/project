package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import com.moxiao.studypilot.roadmap.application.ArtifactReviewAiClient;
import com.moxiao.studypilot.roadmap.application.ArtifactRunnerEvidenceVerifier;
import com.moxiao.studypilot.roadmap.application.RoadmapCatalogImporter;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapArtifactEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapArtifactJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapArtifactReviewJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 33 §8：`GET /api/roadmap-artifacts` 只读成果列表入口。
 *
 * <p>冻结契约要求该入口只能从 Java 登录态取得 ownerId（不接受调用方 ownerId），
 * 只读、不做任何写副作用，并且面向浏览器结果面板的响应不得包含绝对路径、
 * 测试证据、成果内容描述或敏感扫描明细。</p>
 *
 * <p>本测试先于生产代码编写：列表入口尚未实现时请求不可用，因此为 RED。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoadmapArtifactListApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CourseCatalogImporter courseCatalogImporter;
    @Autowired RoadmapCatalogImporter roadmapCatalogImporter;
    @Autowired RoadmapArtifactJpaRepository artifactRepository;
    @Autowired RoadmapArtifactReviewJpaRepository reviewRepository;

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
    void listsOnlyTheAuthenticatedOwnersArtifacts() throws Exception {
        Registration owner = registerAndEnroll();
        Registration other = registerAndEnroll();
        String ownerWorkspace = createWorkspace(owner, prepareSubmission("owner-a"));
        String otherWorkspace = createWorkspace(other, prepareSubmission("owner-b"));
        String ownerNode = firstMilestone(owner);
        String otherNode = firstMilestone(other);

        String ownerFirst = submitArtifact(owner, ownerWorkspace, ownerNode, "list-owner-1")
                .get("id").asText();
        String ownerSecond = submitArtifact(owner, ownerWorkspace, ownerNode, "list-owner-2")
                .get("id").asText();
        String otherArtifact = submitArtifact(other, otherWorkspace, otherNode, "list-other-1")
                .get("id").asText();

        JsonNode ownerList = read(mockMvc.perform(get("/api/roadmap-artifacts")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn());
        Set<String> ownerIds = ids(ownerList);
        assertEquals(Set.of(ownerFirst, ownerSecond), ownerIds);
        assertFalse(ownerIds.contains(otherArtifact), "不得返回其他用户的成果");

        JsonNode otherList = read(mockMvc.perform(get("/api/roadmap-artifacts")
                        .header("Authorization", bearer(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn());
        assertEquals(Set.of(otherArtifact), ids(otherList));
    }

    @Test
    void returnsARealEmptyListForAnOwnerWithoutArtifacts() throws Exception {
        Registration owner = registerAndEnroll();

        mockMvc.perform(get("/api/roadmap-artifacts")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void neverAcceptsCallerSuppliedOwnerIdFromQueryOrHeaders() throws Exception {
        Registration owner = registerAndEnroll();
        Registration other = registerAndEnroll();
        String ownerWorkspace = createWorkspace(owner, prepareSubmission("isolated"));
        String otherWorkspace = createWorkspace(other, prepareSubmission("intruder"));
        String ownerArtifact = submitArtifact(owner, ownerWorkspace, firstMilestone(owner),
                "list-owner-isolated").get("id").asText();
        String otherArtifact = submitArtifact(other, otherWorkspace, firstMilestone(other),
                "list-other-intruder").get("id").asText();
        assertFalse(ownerArtifact.equals(otherArtifact));

        // owner 用自己的令牌请求，但尝试用 query/header 冒充 other。
        JsonNode list = read(mockMvc.perform(get("/api/roadmap-artifacts")
                        .param("ownerId", other.id())
                        .param("owner_id", other.id())
                        .header("Authorization", bearer(owner))
                        .header("X-Owner-Id", other.id())
                        .header("X-Owner-Id-Override", other.id())
                        .header("ownerId", other.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn());

        assertEquals(Set.of(ownerArtifact), ids(list),
                "调用方提供的 ownerId 必须被完全忽略，只能使用登录态 owner");
    }

    @Test
    void requiresAnAuthenticatedPrincipal() throws Exception {
        mockMvc.perform(get("/api/roadmap-artifacts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listingHasNoWriteSideEffectOnArtifactOrReviewState() throws Exception {
        Registration owner = registerAndEnroll();
        String workspaceId = createWorkspace(owner, prepareSubmission("no-write"));
        String nodeId = firstMilestone(owner);
        String artifactId = submitArtifact(owner, workspaceId, nodeId, "list-no-write")
                .get("id").asText();

        RoadmapArtifactEntity before = artifactRepository.findById(artifactId).orElseThrow();
        long reviewCountBefore = reviewRepository
                .findAllByArtifactIdOrderByCreatedAtAsc(artifactId).size();
        JsonNode beforeView = read(mockMvc.perform(
                        get("/api/roadmap-artifacts/{id}", artifactId)
                                .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn());

        mockMvc.perform(get("/api/roadmap-artifacts")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        RoadmapArtifactEntity after = artifactRepository.findById(artifactId).orElseThrow();
        long reviewCountAfter = reviewRepository
                .findAllByArtifactIdOrderByCreatedAtAsc(artifactId).size();
        JsonNode afterView = read(mockMvc.perform(
                        get("/api/roadmap-artifacts/{id}", artifactId)
                                .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn());

        assertEquals(before.getStatus(), after.getStatus());
        assertEquals(before.getSubmissionVersion(), after.getSubmissionVersion());
        assertEquals(before.getAcceptedAt(), after.getAcceptedAt());
        assertEquals(reviewCountBefore, reviewCountAfter, "列表入口不得新增评审事件");
        assertEquals(beforeView, afterView, "列表入口不得改变成果读模型");
    }

    @Test
    void resultPanelPayloadExcludesPathsEvidenceAndRawReviewPayloads() throws Exception {
        Registration owner = registerAndEnroll();
        Path workspace = prepareSubmission("privacy");
        Files.writeString(workspace.resolve("submission/App.java"), "public class App { }");
        String workspaceId = createWorkspace(owner, workspace);
        submitArtifact(owner, workspaceId, firstMilestone(owner), "list-privacy");

        JsonNode list = read(mockMvc.perform(get("/api/roadmap-artifacts")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn());
        JsonNode item = list.get(0);
        String raw = list.toString();

        // 面板需要的只读字段必须存在。
        for (String requiredField : List.of("id", "workspaceId", "evaluationMode", "status",
                "submissionVersion", "roadmapNode", "createdAt", "reviewHistory")) {
            assertTrue(item.hasNonNull(requiredField),
                    "结果面板必需字段缺失: " + requiredField);
        }
        assertTrue(item.get("roadmapNode").hasNonNull("title"));

        // 绝对路径、测试证据、成果内容描述、敏感扫描明细与原始评审载荷一律不得外发。
        for (String forbiddenField : List.of(
                "canonicalPath", "relativePath", "testEvidence", "description",
                "sensitiveFindings", "rubricBreakdownJson", "details", "ownerId")) {
            assertFalse(item.has(forbiddenField), "面板响应不得包含字段: " + forbiddenField);
        }
        assertFalse(raw.contains(tempDir.toAbsolutePath().toString()),
                "响应不得包含工作区绝对路径");
        assertFalse(raw.contains("public class App"), "响应不得包含成果文件内容");
        assertFalse(raw.contains("等待 Runner 验证"), "响应不得包含测试证据文本");

        // 实际出现的字段只能是冻结的最小只读集合内的字段（可空字段允许被省略）。
        Set<String> fields = new LinkedHashSet<>();
        item.propertyNames().forEach(fields::add);
        Set<String> allowed = Set.of("id", "workspaceId", "evaluationMode", "status",
                "submissionVersion", "roadmapNode", "rubricScore", "rubricFeedback",
                "sensitiveScanPassed", "acceptedAt", "createdAt", "reviewHistory");
        assertTrue(allowed.containsAll(fields), "出现未允许字段: " + fields);
    }

    @Test
    void reviewedArtifactExposesEvaluationInfoWithoutLeakingRawPayloads() throws Exception {
        Registration owner = registerAndEnroll();
        String workspaceId = createWorkspace(owner, prepareSubmission("reviewed"));
        String artifactId = submitArtifact(owner, workspaceId, firstMilestone(owner),
                "list-reviewed").get("id").asText();

        when(evidenceVerifier.verify(eq(owner.id()), eq(workspaceId), eq("list-runner"), any()))
                .thenReturn(new ArtifactRunnerEvidenceVerifier.VerifiedRunnerEvidence(
                        "list-runner", "MAVEN_TEST", "Tests run: 8, Failures: 0",
                        java.time.Instant.now()));
        when(aiClient.evaluate(eq(owner.id()), any()))
                .thenReturn(new ArtifactReviewAiClient.ArtifactAiReviewResult(
                        88, 36, 22, 17, 13, "实现完整，建议补充边界测试。",
                        List.of("职责清晰"), List.of("边界测试不足"), "deepseek-v4-flash"));

        MvcResult previewResult = mockMvc.perform(post(
                            "/api/roadmap-artifacts/{id}/review-previews", artifactId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runnerExecutionId":"list-runner","idempotencyKey":"list-review-1"}
                                """))
                .andExpect(status().isCreated()).andReturn();
        String previewId = read(previewResult).get("id").asText();
        mockMvc.perform(post(
                            "/api/roadmap-artifacts/{artifactId}/review-previews/{previewId}/confirm",
                            artifactId, previewId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rubricScore").value(88));

        // 评审后的面板响应必须带评测信息，但仍不得回传原始评审载荷或路径。
        JsonNode item = read(mockMvc.perform(get("/api/roadmap-artifacts")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn()).get(0);

        assertEquals("SUBMITTED", item.get("status").asText());
        assertEquals(88, item.get("rubricScore").asInt());
        assertTrue(item.hasNonNull("rubricFeedback"));
        assertTrue(item.get("reviewHistory").size() >= 2, "评审事件应进入最小展示投影");
        assertTrue(item.get("reviewHistory").get(1).hasNonNull("eventType"));
        for (String forbiddenField : List.of("canonicalPath", "relativePath", "testEvidence",
                "description", "sensitiveFindings", "rubricBreakdownJson", "details")) {
            assertFalse(item.has(forbiddenField), "面板响应不得包含字段: " + forbiddenField);
        }
        assertFalse(item.get("reviewHistory").get(1).has("details"));
        assertFalse(item.get("reviewHistory").get(1).has("rubricBreakdownJson"));
        assertFalse(item.toString().contains(tempDir.toAbsolutePath().toString()));
    }

    // ---------- helpers ----------

    private Set<String> ids(JsonNode list) {
        Set<String> result = new LinkedHashSet<>();
        list.forEach(item -> result.add(item.get("id").asText()));
        return result;
    }

    private Path prepareSubmission(String name) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve(name));
        Path submission = Files.createDirectories(workspace.resolve("submission"));
        Files.writeString(submission.resolve("App.java"), "public class App { }");
        return workspace;
    }

    private String createWorkspace(Registration owner, Path root) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"成果列表工作区","rootPath":"%s"}
                                """.formatted(root)))
                .andExpect(status().isCreated()).andReturn();
        return read(result).get("id").asText();
    }

    private JsonNode submitArtifact(
            Registration owner, String workspaceId, String nodeId, String idempotencyKey
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/roadmap-artifacts")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId":"%s",
                                  "roadmapNodeId":"%s",
                                  "relativePath":"submission",
                                  "description":"私有成果描述，不应出现在结果面板响应中。",
                                  "testEvidence":"等待 Runner 验证。",
                                  "idempotencyKey":"%s"
                                }
                                """.formatted(workspaceId, nodeId, idempotencyKey)))
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
                                  "email":"artifact-list-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"成果列表测试"
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

    private record Registration(String id, String token) { }
}
