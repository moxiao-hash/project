package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import com.moxiao.studypilot.roadmap.application.RoadmapCatalogImporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RoadmapArtifactWorkflowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CourseCatalogImporter courseCatalogImporter;
    @Autowired RoadmapCatalogImporter roadmapCatalogImporter;

    @TempDir
    Path tempDir;

    @BeforeEach
    void importV2Catalog() {
        courseCatalogImporter.importCatalog();
        roadmapCatalogImporter.importCatalog();
    }

    @Test
    void registersCanonicalWorkspaceAndKeepsItOwnerScoped() throws Exception {
        Registration owner = registerAndEnroll();
        Path workspaceRoot = Files.createDirectory(tempDir.resolve("project"));

        MvcResult created = createWorkspace(owner, "StudyPilot", workspaceRoot.toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("StudyPilot"))
                .andExpect(jsonPath("$.rootPath").value(workspaceRoot.toRealPath().toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String workspaceId = read(created).get("id").asText();

        mockMvc.perform(get("/api/workspaces")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(workspaceId));

        Registration stranger = registerAndEnroll();
        mockMvc.perform(get("/api/workspaces")
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void rejectsRelativeAndSymbolicLinkWorkspaceRoots() throws Exception {
        Registration owner = registerAndEnroll();

        createWorkspace(owner, "Relative", "relative/project")
                .andExpect(status().isBadRequest());

        Path actual = Files.createDirectory(tempDir.resolve("actual"));
        Path link = tempDir.resolve("linked-project");
        Files.createSymbolicLink(link, actual);
        createWorkspace(owner, "Linked", link.toString())
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitsImmutableNodeArtifactSnapshotAndUpdatesNodeState() throws Exception {
        Registration owner = registerAndEnroll();
        RoadmapTarget target = firstMilestone(owner);
        Path workspaceRoot = Files.createDirectory(tempDir.resolve("workspace"));
        Path evidence = Files.createDirectories(workspaceRoot.resolve("submission"))
                .resolve("README.md");
        Files.writeString(evidence, "# milestone evidence");
        String workspaceId = read(createWorkspace(
                owner, "Milestone", workspaceRoot.toString()).andReturn()).get("id").asText();

        String request = """
                {
                  "workspaceId": "%s",
                  "roadmapNodeId": "%s",
                  "relativePath": "submission/README.md",
                  "description": "完成模块实践并记录运行步骤。",
                  "testEvidence": "尚未由安全 Runner 验证。",
                  "idempotencyKey": "artifact-submit-001"
                }
                """.formatted(workspaceId, target.nodeId());
        MvcResult created = mockMvc.perform(post("/api/roadmap-artifacts")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submissionVersion").value(1))
                .andExpect(jsonPath("$.roadmapNode.id").value(target.nodeId()))
                .andExpect(jsonPath("$.roadmapNode.moduleId").value(target.moduleId()))
                .andExpect(jsonPath("$.roadmapNode.stageId").value(target.stageId()))
                .andExpect(jsonPath("$.reviewHistory[0].toStatus").value("SUBMITTED"))
                .andReturn();
        String artifactId = read(created).get("id").asText();

        mockMvc.perform(post("/api/roadmap-artifacts")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(artifactId));

        mockMvc.perform(get("/api/roadmap-artifacts/{id}", artifactId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalPath").value(evidence.toRealPath().toString()))
                .andExpect(jsonPath("$.reviewHistory.length()").value(1));

        mockMvc.perform(get("/api/roadmaps/current/nodes/{id}", target.nodeId())
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactStatus").value("SUBMITTED"));

        Registration stranger = registerAndEnroll();
        mockMvc.perform(get("/api/roadmap-artifacts/{id}", artifactId)
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsTraversalAndSymbolicLinkEscapeForArtifacts() throws Exception {
        Registration owner = registerAndEnroll();
        RoadmapTarget target = firstMilestone(owner);
        Path workspaceRoot = Files.createDirectory(tempDir.resolve("safe-workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "do not read");
        String workspaceId = read(createWorkspace(
                owner, "Safe", workspaceRoot.toString()).andReturn()).get("id").asText();

        submitArtifact(owner, workspaceId, target.nodeId(), "../outside/secret.txt", "traversal-key")
                .andExpect(status().isBadRequest());

        Files.createSymbolicLink(workspaceRoot.resolve("escape"), outside);
        submitArtifact(owner, workspaceId, target.nodeId(), "escape/secret.txt", "symlink-key")
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions createWorkspace(
            Registration owner, String name, String rootPath
    ) throws Exception {
        return mockMvc.perform(post("/api/workspaces")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"%s","rootPath":"%s"}
                        """.formatted(name, rootPath)));
    }

    private org.springframework.test.web.servlet.ResultActions submitArtifact(
            Registration owner, String workspaceId, String nodeId,
            String relativePath, String idempotencyKey
    ) throws Exception {
        return mockMvc.perform(post("/api/roadmap-artifacts")
                .header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "workspaceId":"%s",
                          "roadmapNodeId":"%s",
                          "relativePath":"%s",
                          "description":"提交模块实践成果。",
                          "testEvidence":"等待 Runner 验证。",
                          "idempotencyKey":"%s"
                        }
                        """.formatted(workspaceId, nodeId, relativePath, idempotencyKey)));
    }

    private RoadmapTarget firstMilestone(Registration owner) throws Exception {
        JsonNode map = read(mockMvc.perform(get("/api/roadmaps/current/map")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn());
        String moduleId = map.get("stages").get(0).get("modules").get(0).get("id").asText();
        JsonNode module = read(mockMvc.perform(get("/api/roadmaps/current/modules/{id}", moduleId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn());
        JsonNode milestone = module.get("milestoneNode");
        return new RoadmapTarget(
                milestone.get("id").asText(), moduleId, module.get("stageId").asText());
    }

    private Registration registerAndEnroll() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"artifact-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"成果测试"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode registration = read(result);
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

    private String bearer(Registration registration) {
        return "Bearer " + registration.token();
    }

    private record Registration(String userId, String token) { }

    private record RoadmapTarget(String nodeId, String moduleId, String stageId) { }
}
