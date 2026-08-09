package com.moxiao.studypilot.roadmap.application;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class RoadmapCatalogContentTest {

    private static final List<String> FORBIDDEN_TEMPLATES = List.of(
            "关键机制",
            "常用配置与核心 API",
            "边界条件与适用范围",
            "典型代码与配置判断",
            "项目中的应用与取舍"
    );

    private final JsonNode catalog = readCatalog();

    @Test
    void everyNodeHasPracticalLearningMetadataWithoutGenericTemplates() {
        List<JsonNode> nodes = nodes();
        assertThat(nodes).hasSize(64);

        for (JsonNode node : nodes) {
            String code = node.get("code").asString();
            assertThat(node.get("objectives")).as(code + " objectives").hasSizeGreaterThanOrEqualTo(2);
            assertThat(node.get("highFrequency")).as(code + " highFrequency").hasSizeGreaterThanOrEqualTo(2);
            assertThat(node.get("commonMistakes")).as(code + " commonMistakes").hasSizeGreaterThanOrEqualTo(2);
            assertThat(node.get("searchKeywords")).as(code + " searchKeywords").hasSizeGreaterThanOrEqualTo(2);
            assertThat(node.get("quizBlueprint")).as(code + " quizBlueprint").hasSize(5);
            assertThat(node.get("artifactRequirement").get("description").asString())
                    .as(code + " artifactRequirement description")
                    .isNotBlank();

            String metadata = node.get("objectives") + " "
                    + node.get("highFrequency") + " "
                    + node.get("commonMistakes") + " "
                    + node.get("searchKeywords") + " "
                    + node.get("quizBlueprint");
            assertThat(metadata).as(code + " metadata").doesNotContain(FORBIDDEN_TEMPLATES.toArray(String[]::new));
        }
    }

    @Test
    void representativeNodesNameTopicSpecificDailyEngineeringConcerns() {
        assertNodeContains("java-collections-generics", "HashMap", "泛型");
        assertNodeContains("spring-mvc-rest", "@RestController");
        assertNodeContains("mybatis-core", "resultMap");
        assertNodeContains("redis-cache", "缓存穿透");
        assertNodeContains("vue-router-pinia", "Pinia");
        assertNodeContains("fastapi-rest", "Pydantic");
        assertNodeContains("structured-output", "structured output");
        assertNodeContains("human-in-loop", "interrupt");
        assertNodeContains("hybrid-retrieval", "RRF");
        assertNodeContains("idempotent-execution", "幂等键");
        assertNodeContains("repo-read-search", "ripgrep");
        assertNodeContains("runner-sandbox", "realpath");
        assertNodeContains("network-firewall", "loopback");
    }

    private void assertNodeContains(String code, String... expectedTerms) {
        JsonNode node = nodes().stream()
                .filter(candidate -> code.equals(candidate.get("code").asString()))
                .findFirst()
                .orElseThrow();
        assertThat(node.toString()).as(code).contains(expectedTerms);
    }

    private List<JsonNode> nodes() {
        return StreamSupport.stream(catalog.get("stages").spliterator(), false)
                .flatMap(stage -> StreamSupport.stream(stage.get("nodes").spliterator(), false))
                .toList();
    }

    private static JsonNode readCatalog() {
        try (var input = new ClassPathResource("roadmaps/studypilot-java-ai-v1.json").getInputStream()) {
            return new ObjectMapper().readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
