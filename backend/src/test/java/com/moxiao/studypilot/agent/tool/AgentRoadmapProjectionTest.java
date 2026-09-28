package com.moxiao.studypilot.agent.tool;

import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import com.moxiao.studypilot.roadmap.application.RoadmapCatalogImporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent 路线投影：已加入 125 节点模板的用户必须得到**未截断**且**事实正确**的路线。
 *
 * <p>缺陷复现：`roadmap.current.get` 与 `learning.context.get` 的路线字段超过
 * {@code AgentToolRegistry} 的 65536 字节上限，被替换为截断信封（只含
 * {@code warning/originalBytes/truncated}），Python 回复编排因此看不到路线标题，
 * 误称“尚未加入学习路线”。</p>
 *
 * <p>本测试先于生产代码编写，因此 RED。修复要求是**类型化紧凑投影**，
 * 不是提高全局上限、也不是序列化时静默省略。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentRoadmapProjectionTest {

    private static final String INTERNAL_TOKEN = "test-internal-token";
    private static final String ROADMAP_TOOL = "roadmap.current.get";
    private static final String CONTEXT_TOOL = "learning.context.get";
    /** 与 AgentToolRegistry.MAX_OUTPUT_BYTES 一致。 */
    private static final int AGENT_OUTPUT_LIMIT_BYTES = 65_536;
    private static final int EXPECTED_STAGES = 12;
    private static final int EXPECTED_NODES = 125;
    private static final int EXPECTED_REQUIRED_NODES = 123;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CourseCatalogImporter courseCatalogImporter;
    @Autowired RoadmapCatalogImporter roadmapCatalogImporter;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        courseCatalogImporter.importCatalog();
        roadmapCatalogImporter.importCatalog();
    }

    @Test
    void enrolledLargeRoadmapIsNotTruncatedAndKeepsFactualCounts() throws Exception {
        Registration owner = registerAndEnrollV2();
        completeFirstRequiredNodes(owner, 4);

        MvcResult result = invokeTool(ROADMAP_TOOL, owner.id());
        JsonNode body = read(result);

        assertFalse(body.path("truncated").asBoolean(true),
                "已加入 125 节点模板时路线不得被截断");
        int bytes = objectMapper.writeValueAsBytes(body.get("data")).length;
        assertTrue(bytes < AGENT_OUTPUT_LIMIT_BYTES,
                "路线投影必须在 65536 字节以内，实际 " + bytes);

        JsonNode roadmap = body.get("data");
        assertEquals(4, roadmap.path("completedRequiredNodes").asInt(),
                "进度必须与真实完成节点数一致");
        assertEquals(EXPECTED_REQUIRED_NODES, roadmap.path("totalRequiredNodes").asInt());
        assertEquals(EXPECTED_STAGES, roadmap.path("stages").size());
        assertNotNull(roadmap.path("title").asText());
        assertFalse(roadmap.path("title").asText().isBlank(), "路线标题必须存在");
        assertEquals(EXPECTED_NODES, allNodes(roadmap).size(),
                "投影必须保留全部 125 个节点，供下一步选择");
        assertEquals(4, allNodes(roadmap).stream()
                .filter(node -> "COMPLETED".equals(node.path("displayStatus").asText()))
                .count());
    }

    @Test
    void learningContextRoadmapIsNotTruncatedForTheSameEnrollment() throws Exception {
        Registration owner = registerAndEnrollV2();
        completeFirstRequiredNodes(owner, 4);

        MvcResult result = invokeTool(CONTEXT_TOOL, owner.id());
        JsonNode body = read(result);

        assertFalse(body.path("truncated").asBoolean(true),
                "聚合上下文不得因为路线体积被截断");
        int bytes = objectMapper.writeValueAsBytes(body.get("data")).length;
        assertTrue(bytes < AGENT_OUTPUT_LIMIT_BYTES,
                "聚合上下文必须在 65536 字节以内，实际 " + bytes);

        JsonNode roadmap = body.path("data").path("roadmap");
        assertFalse(roadmap.isMissingNode() || roadmap.isNull(),
                "已加入路线时上下文必须携带路线");
        assertEquals(4, roadmap.path("completedRequiredNodes").asInt());
        assertEquals(EXPECTED_REQUIRED_NODES, roadmap.path("totalRequiredNodes").asInt());
        assertEquals(EXPECTED_NODES, allNodes(roadmap).size());
    }

    @Test
    void projectionIsCompactAndDropsVerboseNodeContent() throws Exception {
        Registration owner = registerAndEnrollV2();

        JsonNode roadmap = read(invokeTool(ROADMAP_TOOL, owner.id())).get("data");

        assertEquals(Set.of("enrollmentId", "roadmapCode", "templateVersion", "title",
                        "completedRequiredNodes", "totalRequiredNodes", "stages"),
                fieldNames(roadmap), "路线顶层字段必须与冻结投影一致");

        JsonNode stage = roadmap.path("stages").get(0);
        assertEquals(Set.of("id", "code", "order", "title", "completedRequiredNodes",
                        "totalRequiredNodes", "nodes"),
                fieldNames(stage), "阶段字段必须与冻结投影一致");
        assertFalse(stage.has("description"), "阶段描述属于冗长文本，必须移除");
        assertFalse(stage.has("graduationProjectTitle"), "毕业项目标题必须移除");
        assertFalse(stage.has("modules"), "Agent 不读取 modules，可以移除");

        JsonNode node = stage.path("nodes").get(0);
        assertEquals(Set.of("id", "code", "title", "displayStatus"),
                fieldNames(node), "节点只保留下一步选择所需字段");
        for (String verbose : List.of("objectives", "highFrequency", "commonMistakes",
                "searchKeywords", "prerequisiteCodes", "estimatedMinutes", "practiceMinutes",
                "difficulty", "quizBlueprint", "required", "availabilityStatus",
                "learningStatus", "checkInStatus", "quizStatus", "artifactStatus",
                "completionStatus", "diagnosticMastered", "version")) {
            assertFalse(node.has(verbose), "Agent 投影必须移除冗长节点字段: " + verbose);
        }

        // 每个节点都必须带可选的下一步身份与状态。
        for (JsonNode item : allNodes(roadmap)) {
            assertFalse(item.path("id").asText().isBlank());
            assertFalse(item.path("code").asText().isBlank());
            assertFalse(item.path("displayStatus").asText().isBlank());
        }
    }

    @Test
    void noEnrollmentKeepsAnExplicitNullRoadmapInContext() throws Exception {
        Registration owner = register();

        JsonNode context = read(invokeTool(CONTEXT_TOOL, owner.id())).get("data");

        assertTrue(context.path("roadmap").isNull(),
                "未加入路线时上下文必须显式给出 null 路线");
        assertTrue(context.path("warnings").toString().contains("路线"),
                "未加入路线时必须保留人工提示");
    }

    @Test
    void roadmapProjectionIsOwnerScoped() throws Exception {
        Registration completed = registerAndEnrollV2();
        Registration untouched = registerAndEnrollV2();
        completeFirstRequiredNodes(completed, 4);

        JsonNode completedRoadmap = read(invokeTool(ROADMAP_TOOL, completed.id())).get("data");
        JsonNode untouchedRoadmap = read(invokeTool(ROADMAP_TOOL, untouched.id())).get("data");

        assertEquals(4, completedRoadmap.path("completedRequiredNodes").asInt());
        assertEquals(0, untouchedRoadmap.path("completedRequiredNodes").asInt(),
                "其他用户的进度不得泄漏");
        assertFalse(completedRoadmap.path("enrollmentId").asText()
                        .equals(untouchedRoadmap.path("enrollmentId").asText()),
                "每个用户必须看到自己的登记");

        // Agent 投影必须与同一用户的公开接口进度一致。
        JsonNode publicMap = read(mockMvc.perform(get("/api/roadmaps/current/map")
                        .header("Authorization", bearer(completed)))
                .andExpect(status().isOk()).andReturn());
        assertEquals(publicMap.path("completedRequiredNodes").asInt(),
                completedRoadmap.path("completedRequiredNodes").asInt());
        assertEquals(publicMap.path("totalRequiredNodes").asInt(),
                completedRoadmap.path("totalRequiredNodes").asInt());

        JsonNode untouchedContext = read(invokeTool(CONTEXT_TOOL, untouched.id()))
                .get("data").path("roadmap");
        assertEquals(0, untouchedContext.path("completedRequiredNodes").asInt());
        assertEquals(EXPECTED_REQUIRED_NODES, untouchedContext.path("totalRequiredNodes").asInt());
    }

    @Test
    void publicRoadmapMapEndpointKeepsFullDetail() throws Exception {
        Registration owner = registerAndEnrollV2();

        JsonNode publicMap = read(mockMvc.perform(get("/api/roadmaps/current/map")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages[0].modules").isArray())
                .andReturn());

        assertEquals(EXPECTED_NODES, allNodes(publicMap).size());
        JsonNode node = allNodes(publicMap).get(0);
        assertTrue(node.has("objectives"), "公开接口必须保留节点目标");
        assertTrue(node.has("highFrequency"), "公开接口必须保留高频点");
        assertTrue(node.has("commonMistakes"), "公开接口必须保留常见错误");
        assertTrue(node.has("searchKeywords"), "公开接口必须保留检索关键词");
        assertTrue(node.has("estimatedMinutes"), "公开接口必须保留预计时长");
        assertTrue(publicMap.path("stages").get(0).has("modules"),
                "公开接口必须保留模块结构");
    }

    // ---------- helpers ----------

    private List<JsonNode> allNodes(JsonNode roadmap) {
        List<JsonNode> nodes = new ArrayList<>();
        roadmap.path("stages").forEach(stage -> stage.path("nodes")
                .forEach(nodes::add));
        return nodes;
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private void completeFirstRequiredNodes(Registration owner, int count) throws Exception {
        JsonNode map = read(mockMvc.perform(get("/api/roadmaps/current/map")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn());
        String enrollmentId = map.path("enrollmentId").asText();
        List<String> nodeIds = new ArrayList<>();
        for (JsonNode stage : map.path("stages")) {
            for (JsonNode node : stage.path("nodes")) {
                if (node.path("required").asBoolean(false) && nodeIds.size() < count) {
                    nodeIds.add(node.path("id").asText());
                }
            }
            if (nodeIds.size() >= count) {
                break;
            }
        }
        assertEquals(count, nodeIds.size(), "模板必须存在足够的必修节点");
        for (String nodeId : nodeIds) {
            jdbcTemplate.update("""
                    UPDATE user_roadmap_nodes
                    SET completion_status = 'COMPLETED', availability_status = 'AVAILABLE',
                        completed_at = ?
                    WHERE user_roadmap_id = ? AND node_id = ?
                    """, Instant.now(), enrollmentId, nodeId);
        }
    }

    private MvcResult invokeTool(String toolName, String ownerId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("ownerId", ownerId)
                .set("arguments", objectMapper.createObjectNode());
        return mockMvc.perform(post("/internal/agent-tools/{toolName}/invoke", toolName)
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(Registration owner) {
        return "Bearer " + owner.token();
    }

    private Registration register() throws Exception {
        MvcResult registrationResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"agent-roadmap-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"路线投影测试"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode registration = read(registrationResult);
        return new Registration(registration.path("user").path("id").asText(),
                registration.path("accessToken").asText());
    }

    private Registration registerAndEnrollV2() throws Exception {
        Registration owner = register();
        mockMvc.perform(post("/api/roadmap-enrollments")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roadmapCode":"studypilot-java-ai","templateVersion":2}
                                """))
                .andExpect(status().isCreated());
        return owner;
    }

    private record Registration(String id, String token) { }
}
