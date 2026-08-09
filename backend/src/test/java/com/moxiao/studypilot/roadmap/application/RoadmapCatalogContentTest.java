package com.moxiao.studypilot.roadmap.application;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class RoadmapCatalogContentTest {

    private static final String FINAL_V1_CHECKSUM =
            "51df147a13b8bac4cc3b8869228df60ca3ebf1f5eb5b9228009232be7c9f0817";

    private static final List<String> FORBIDDEN_TEMPLATES = List.of(
            "关键机制",
            "常用配置与核心 API",
            "边界条件与适用范围",
            "典型代码与配置判断",
            "项目中的应用与取舍"
    );
    private static final Pattern MECHANICAL_SECOND_OBJECTIVE = Pattern.compile(
            "能够为.+场景编写.+验证并解释.+取舍");
    private static final Pattern STUCK_CHINESE_TECH_TERM = Pattern.compile(
            ".*([\\p{IsHan}][A-Za-z0-9@]|[A-Za-z0-9][\\p{IsHan}]).*");

    private final JsonNode catalog = readCatalog();

    @Test
    void everyNodeHasPracticalLearningMetadataWithoutGenericTemplates() {
        List<JsonNode> nodes = nodes();
        assertThat(nodes).hasSize(64);
        assertThat(nodes.stream().map(node -> node.get("objectives").toString()))
                .doesNotHaveDuplicates();
        assertThat(nodes.stream().map(node -> node.get("artifactRequirement").get("description").asString()))
                .doesNotHaveDuplicates();

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

            for (JsonNode objective : node.get("objectives")) {
                String text = objective.asString();
                assertThat(text).as(code + " objective")
                        .doesNotStartWith("能够实现并演示")
                        .doesNotMatch(MECHANICAL_SECOND_OBJECTIVE)
                        .doesNotMatch(STUCK_CHINESE_TECH_TERM);
            }
        }

        Map<String, Long> prefixCounts = nodes.stream()
                .flatMap(node -> StreamSupport.stream(node.get("objectives").spliterator(), false))
                .map(JsonNode::asString)
                .map(RoadmapCatalogContentTest::normalizedPrefix)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(prefixCounts).allSatisfy((prefix, count) ->
                assertThat(count).as("objective prefix: " + prefix).isLessThanOrEqualTo(8));
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

    @Test
    void versionOneHasImmutableIdentityGraphAndChecksum() {
        Set<String> actualCodes = nodes().stream()
                .map(node -> node.get("code").asString())
                .collect(Collectors.toSet());
        Set<String> actualEdges = nodes().stream()
                .flatMap(node -> StreamSupport.stream(node.get("prerequisites").spliterator(), false)
                        .map(prerequisite -> node.get("code").asString() + "<-" + prerequisite.asString()))
                .collect(Collectors.toSet());
        Set<String> optionalCodes = nodes().stream()
                .filter(node -> !node.get("required").asBoolean())
                .map(node -> node.get("code").asString())
                .collect(Collectors.toSet());

        assertThat(actualCodes).isEqualTo(lines("""
                java-syntax-oop java-collections-generics java-exceptions-io java-concurrency-jvm java-maven-testing
                spring-ioc-di spring-mvc-rest spring-validation-errors spring-config-logging spring-files-scheduling
                mysql-sql-modeling mysql-index-transaction mybatis-core mybatis-plus jpa-core data-access-comparison
                redis-cache auth-jwt-security api-docs-integration-test idempotency-concurrency-audit monitoring-observability
                vue-ts-basics vue-router-pinia frontend-api-integration git-linux-nginx docker-delivery
                python-engineering pydantic fastapi-rest python-async-http java-python-contract
                llm-api-basics prompt-engineering structured-output model-cost-retry llm-security
                langgraph-state langgraph-memory tool-calling human-in-loop mcp spring-ai-elective
                document-parsing embedding-qdrant hybrid-retrieval tavily-search grounded-answer adaptive-assessment
                business-tool-contracts intent-preview authorization-governance idempotent-execution business-agent-e2e
                repo-read-search patch-diff test-build-tools git-tools playwright-dom accessibility-desktop
                runner-sandbox secret-redaction network-firewall observability-recovery release-e2e
                """));
        assertThat(actualEdges).isEqualTo(lines("""
                java-collections-generics<-java-syntax-oop java-exceptions-io<-java-collections-generics
                java-concurrency-jvm<-java-exceptions-io java-maven-testing<-java-exceptions-io
                spring-ioc-di<-java-maven-testing spring-mvc-rest<-spring-ioc-di
                spring-validation-errors<-spring-mvc-rest spring-config-logging<-spring-mvc-rest
                spring-files-scheduling<-spring-validation-errors mysql-sql-modeling<-spring-mvc-rest
                mysql-index-transaction<-mysql-sql-modeling mybatis-core<-mysql-sql-modeling
                mybatis-plus<-mybatis-core jpa-core<-mysql-index-transaction
                data-access-comparison<-mybatis-plus data-access-comparison<-jpa-core
                redis-cache<-data-access-comparison auth-jwt-security<-spring-validation-errors
                api-docs-integration-test<-spring-validation-errors idempotency-concurrency-audit<-mysql-index-transaction
                idempotency-concurrency-audit<-auth-jwt-security monitoring-observability<-spring-config-logging
                vue-ts-basics<-spring-mvc-rest vue-router-pinia<-vue-ts-basics
                frontend-api-integration<-vue-router-pinia frontend-api-integration<-auth-jwt-security
                git-linux-nginx<-java-maven-testing docker-delivery<-frontend-api-integration
                docker-delivery<-git-linux-nginx python-engineering<-java-maven-testing
                pydantic<-python-engineering fastapi-rest<-pydantic python-async-http<-fastapi-rest
                java-python-contract<-python-async-http java-python-contract<-spring-mvc-rest
                llm-api-basics<-java-python-contract prompt-engineering<-llm-api-basics
                structured-output<-prompt-engineering structured-output<-pydantic
                model-cost-retry<-llm-api-basics llm-security<-structured-output llm-security<-auth-jwt-security
                langgraph-state<-structured-output langgraph-memory<-langgraph-state tool-calling<-langgraph-state
                human-in-loop<-tool-calling mcp<-tool-calling spring-ai-elective<-tool-calling
                document-parsing<-java-python-contract embedding-qdrant<-document-parsing
                hybrid-retrieval<-embedding-qdrant tavily-search<-model-cost-retry
                grounded-answer<-hybrid-retrieval grounded-answer<-tavily-search adaptive-assessment<-grounded-answer
                business-tool-contracts<-human-in-loop business-tool-contracts<-idempotency-concurrency-audit
                intent-preview<-business-tool-contracts authorization-governance<-intent-preview
                authorization-governance<-llm-security idempotent-execution<-authorization-governance
                business-agent-e2e<-idempotent-execution repo-read-search<-business-agent-e2e
                patch-diff<-repo-read-search test-build-tools<-patch-diff git-tools<-test-build-tools
                playwright-dom<-frontend-api-integration playwright-dom<-test-build-tools
                accessibility-desktop<-playwright-dom runner-sandbox<-test-build-tools runner-sandbox<-llm-security
                secret-redaction<-runner-sandbox network-firewall<-runner-sandbox network-firewall<-docker-delivery
                observability-recovery<-secret-redaction observability-recovery<-monitoring-observability
                release-e2e<-network-firewall release-e2e<-observability-recovery release-e2e<-git-tools
                """));
        assertThat(optionalCodes).containsExactlyInAnyOrder("spring-ai-elective", "accessibility-desktop");
        assertThat(RoadmapCatalogChecksum.sha256(new ObjectMapper(), catalog)).isEqualTo(FINAL_V1_CHECKSUM);
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

    private static String normalizedPrefix(String objective) {
        String normalized = objective.replaceAll("[\\s，。；：、]", "");
        return normalized.substring(0, Math.min(7, normalized.length()));
    }

    private static Set<String> lines(String values) {
        return Set.of(values.strip().split("\\s+"));
    }

    private static JsonNode readCatalog() {
        try (var input = new ClassPathResource("roadmaps/studypilot-java-ai-v1.json").getInputStream()) {
            return new ObjectMapper().readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
