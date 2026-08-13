package com.moxiao.studypilot.roadmap.application;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class RoadmapV2CatalogContentTest {

    private static final List<String> MODULE_CODES = List.of(
            "java-language-start", "java-oop-foundations", "java-core-collections", "java-modern-engineering",
            "spring-business-backend", "mysql-foundations", "java-persistence", "cache-security",
            "backend-quality", "frontend-delivery", "python-engineering", "fastapi-integration",
            "llm-foundations", "model-engineering", "langgraph-agent", "tool-governance-mcp",
            "rag-retrieval", "search-assessment", "business-tools", "business-agent-loop",
            "repository-agent", "developer-automation", "runner-security", "release-recovery");

    private final JsonNode catalog = readCatalog();

    @Test
    void publishesTheGranularV2ShapeAndModuleAllocation() {
        assertThat(catalog.get("version").asInt()).isEqualTo(2);
        assertThat(catalog.get("stages")).hasSize(12);
        List<JsonNode> modules = modules();
        List<JsonNode> nodes = nodes();
        assertThat(modules).hasSize(24);
        assertThat(nodes).hasSize(125);
        assertThat(modules).extracting(module -> module.get("moduleCode").asString())
                .containsExactlyElementsOf(MODULE_CODES);
        assertThat(modules).extracting(module -> module.get("nodes").size())
                .containsExactly(7, 7, 6, 5, 10, 5, 5, 5, 5, 8, 4, 5,
                        4, 4, 5, 5, 5, 5, 4, 4, 4, 4, 5, 4);
        assertThat(catalog.get("stages")).extracting(stage -> nodeCount(stage))
                .containsExactly(25, 10, 10, 10, 8, 9, 8, 10, 10, 8, 8, 9);
    }

    @Test
    void everyModuleEndsWithItsOnlyMilestoneAndEveryNodeHasSpecificTeachingContent() {
        Set<String> objectiveArrays = new HashSet<>();
        Set<String> highFrequencyArrays = new HashSet<>();
        Set<String> mistakeArrays = new HashSet<>();
        Set<String> keywordArrays = new HashSet<>();
        Set<String> quizArrays = new HashSet<>();
        Set<String> codes = new HashSet<>();
        Set<String> optional = new HashSet<>();
        Set<String> forbidden = Set.of("掌握本主题核心概念", "完成对应练习", "常见错误一", "主题关键词");

        for (JsonNode module : modules()) {
            List<JsonNode> moduleNodes = stream(module.get("nodes"));
            assertThat(moduleNodes).isNotEmpty();
            for (int index = 0; index < moduleNodes.size(); index++) {
                JsonNode node = moduleNodes.get(index);
                String code = node.get("code").asString();
                JsonNode artifact = node.get("artifactRequirement");
                boolean milestone = index == moduleNodes.size() - 1;
                assertThat(codes.add(code)).as("unique node code %s", code).isTrue();
                assertThat(node.get("estimatedMinutes").asInt()).isBetween(30, 60);
                assertThat(node.get("practiceMinutes").asInt()).isBetween(15, 60);
                assertThat(node.get("objectives")).as(code).hasSizeGreaterThanOrEqualTo(2);
                assertThat(node.get("highFrequency")).as(code).hasSizeGreaterThanOrEqualTo(2);
                assertThat(node.get("commonMistakes")).as(code).hasSizeGreaterThanOrEqualTo(2);
                assertThat(node.get("searchKeywords")).as(code).hasSizeGreaterThanOrEqualTo(3);
                assertThat(node.get("quizBlueprint")).as(code).hasSize(5);
                assertThat(artifact.get("required").asBoolean()).as(code).isEqualTo(milestone);
                assertThat(artifact.get("evaluationMode").asString()).as(code)
                        .isEqualTo(milestone ? "AI_RUBRIC" : "PRESENCE");
                if (!milestone) assertThat(artifact.get("description").asString()).contains("无需提交模块里程碑成果");
                if (!node.get("required").asBoolean()) optional.add(code);
                String serialized = node.toString();
                forbidden.forEach(phrase -> assertThat(serialized).as(code).doesNotContain(phrase));
                assertThat(objectiveArrays.add(node.get("objectives").toString())).as(code).isTrue();
                assertThat(highFrequencyArrays.add(node.get("highFrequency").toString())).as(code).isTrue();
                assertThat(mistakeArrays.add(node.get("commonMistakes").toString())).as(code).isTrue();
                assertThat(keywordArrays.add(node.get("searchKeywords").toString())).as(code).isTrue();
                assertThat(quizArrays.add(node.get("quizBlueprint").toString())).as(code).isTrue();
            }
        }
        assertThat(optional).containsExactlyInAnyOrder("spring-ai-elective", "accessibility-desktop");
    }

    @Test
    void representativeNodesUseTheirConcreteTechnologyVocabulary() {
        assertTerms("java-environment-first-program", "JDK", "javac");
        assertTerms("variables-types-conversion", "基本类型", "类型转换");
        assertTerms("conditions-if-switch", "if", "switch");
        assertTerms("classes-objects", "class", "对象");
        assertTerms("encapsulation-access", "private", "封装");
        assertTerms("polymorphism", "多态", "动态绑定");
        assertTerms("string-content-comparison", "String", "equals");
        assertTerms("set-map-deduplication", "Set", "Map");
        assertTerms("exceptions-custom", "异常", "try");
        assertTerms("files-nio-streams", "NIO", "Path");
        assertTerms("record-sealed-maven-junit-checkstyle", "record", "sealed", "Maven", "JUnit", "Checkstyle");
        assertTerms("spring-ioc-di", "IoC", "构造器注入");
        assertTerms("spring-mvc-rest", "RestController", "HTTP");
        assertTerms("spring-validation-errors", "Valid", "ProblemDetail");
        assertTerms("mysql-index-transaction", "索引", "事务");
        assertTerms("mybatis-core", "MyBatis", "Mapper");
        assertTerms("data-access-comparison", "MyBatis-Plus", "JPA");
        assertTerms("redis-cache", "Redis", "缓存");
        assertTerms("auth-jwt-security", "JWT", "授权");
        assertTerms("vue-router-pinia", "Vue Router", "Pinia");
        assertTerms("fastapi-rest", "FastAPI", "Pydantic");
        assertTerms("structured-output", "JSON Schema", "结构化输出");
        assertTerms("langgraph-state", "LangGraph", "StateGraph");
        assertTerms("mcp", "MCP", "JSON-RPC");
        assertTerms("embedding-qdrant", "embedding", "Qdrant");
        assertTerms("hybrid-retrieval", "BM25", "向量");
        assertTerms("business-tool-contracts", "幂等", "工具契约");
        assertTerms("patch-diff", "diff", "补丁");
        assertTerms("runner-sandbox", "沙箱", "CPU/内存限制");
        assertTerms("release-e2e", "回滚", "端到端");
    }

    private void assertTerms(String code, String... terms) {
        JsonNode node = nodes().stream().filter(it -> code.equals(it.get("code").asString())).findFirst().orElseThrow();
        assertThat(node.toString()).as(code).contains(terms);
    }

    private List<JsonNode> modules() {
        return stream(catalog.get("stages")).stream().flatMap(stage -> stream(stage.get("modules")).stream()).toList();
    }

    private List<JsonNode> nodes() {
        return modules().stream().flatMap(module -> stream(module.get("nodes")).stream()).toList();
    }

    private static int nodeCount(JsonNode stage) {
        return stream(stage.get("modules")).stream().mapToInt(module -> module.get("nodes").size()).sum();
    }

    private static List<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }

    private static JsonNode readCatalog() {
        try (var input = new ClassPathResource("roadmaps/studypilot-java-ai-v2.json").getInputStream()) {
            return new ObjectMapper().readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
