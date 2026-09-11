package com.moxiao.studypilot.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class AgentToolCoverageTest {

    @Autowired
    private AgentToolRegistry registry;

    @Autowired
    private List<AgentToolHandler> handlers;

    /** Task 27 矩阵 §3.1 的 43 个只读工具。 */
    private static final Set<String> READ_TOOLS = Set.of(
            "learning.context.get",
            "learning.goals.list",
            "learning.plans.list",
            "learning.plan.get",
            "roadmap.current.get",
            "roadmap.stage.get",
            "roadmap.module.get",
            "roadmap.node.get",
            "schedule.today.get",
            "schedule.unfinished.get",
            "assessment.node_quiz_status.get",
            "assessment.quiz.get",
            "assessment.attempt.get",
            "assessment.wrong_questions.list",
            "assessment.mastery.list",
            "materials.list",
            "materials.get",
            "notifications.list",
            "governance.executions.list",
            "governance.audit.list",
            "settings.learning.get",
            "settings.ai_status.get",
            "automation.settings.get",
            "automation.rules.list",
            "workspaces.list",
            "artifacts.list",
            "artifacts.get",
            "artifacts.evaluate",
            "runner.execution.preview",
            "developer.file_tree.get",
            "developer.file.read",
            "developer.code.search",
            "developer.git.status",
            "developer.git.diff",
            "developer.git.log",
            "developer.patch.preview",
            "developer.tests.recommend",
            "developer.git.commit.preview",
            "developer.git.push.preview",
            "developer.interface_fallback.preview",
            "governance.health.get",
            "learning.tasks.list",
            "assessment.wrong_questions.summary"
    );

    /** Task 27 矩阵 §3.2 的 20 个写入/本地执行工具。 */
    private static final Set<String> WRITE_TOOLS = Set.of(
            "roadmap.enroll",
            "roadmap.upgrade",
            "learning.goal.create",
            "learning.plan.create",
            "schedule.refresh",
            "assessment.node_quiz.generate",
            "assessment.wrong_question_review.create",
            "learning.task.update",
            "materials.text.import",
            "materials.web.import",
            "notifications.mark_read",
            "settings.learning.update",
            "workspaces.register",
            "artifacts.submit",
            "runner.check.run",
            "runner.dependencies.prepare",
            "developer.patch.apply",
            "developer.git.commit",
            "developer.git.push",
            "assessment.node_quiz.retry"
    );

    /** 页面能力矩阵 §2 声明的风险级别（HIGH 必须专用确认）。 */
    private static final Map<String, AgentToolRiskLevel> MATRIX_RISK = Map.ofEntries(
            Map.entry("roadmap.enroll", AgentToolRiskLevel.HIGH),
            Map.entry("roadmap.upgrade", AgentToolRiskLevel.HIGH),
            Map.entry("learning.task.update", AgentToolRiskLevel.HIGH),
            Map.entry("settings.learning.update", AgentToolRiskLevel.HIGH),
            Map.entry("workspaces.register", AgentToolRiskLevel.HIGH),
            Map.entry("artifacts.submit", AgentToolRiskLevel.HIGH),
            Map.entry("runner.dependencies.prepare", AgentToolRiskLevel.HIGH),
            Map.entry("developer.patch.apply", AgentToolRiskLevel.HIGH),
            Map.entry("developer.git.commit", AgentToolRiskLevel.HIGH),
            Map.entry("developer.git.push", AgentToolRiskLevel.HIGH),
            Map.entry("learning.goal.create", AgentToolRiskLevel.LOW),
            Map.entry("learning.plan.create", AgentToolRiskLevel.LOW),
            Map.entry("schedule.refresh", AgentToolRiskLevel.LOW),
            Map.entry("assessment.node_quiz.generate", AgentToolRiskLevel.LOW),
            Map.entry("assessment.node_quiz.retry", AgentToolRiskLevel.LOW),
            Map.entry("assessment.wrong_question_review.create", AgentToolRiskLevel.LOW),
            Map.entry("materials.text.import", AgentToolRiskLevel.LOW),
            Map.entry("materials.web.import", AgentToolRiskLevel.LOW),
            Map.entry("notifications.mark_read", AgentToolRiskLevel.LOW),
            Map.entry("runner.check.run", AgentToolRiskLevel.LOW)
    );

    @Test
    void everyTraditionalPageHasARealReadTool() {
        Set<String> names = names();
        assertTrue(names.containsAll(READ_TOOLS));
    }

    @Test
    void delegatedWritesUseGovernedBusinessTools() {
        Set<String> names = names();
        assertTrue(names.containsAll(WRITE_TOOLS));
        for (String tool : WRITE_TOOLS) {
            AgentToolHandler handler = handler(tool);
            assertTrue(handler instanceof GovernedAgentToolHandler,
                    "写工具必须接入治理执行: " + tool);
        }
    }

    @Test
    void frozenCatalogContainsEveryProductionToolExactlyOnce() {
        assertEquals(64, registry.catalog().size());
        assertEquals(64, names().size());
        assertEquals(64, READ_TOOLS.size() + WRITE_TOOLS.size() + 1);
        assertTrue(names().contains("navigation.resolve"));
    }

    @Test
    void everyToolDeclaresStrictInputTimeoutRiskAndClosedOutputSchema() {
        for (AgentToolDescriptor descriptor : registry.catalog()) {
            String name = descriptor.name();
            assertNotNull(descriptor.effect(), name);
            assertNotNull(descriptor.riskLevel(), name);
            assertTrue(descriptor.version() >= 1, name);
            assertTrue(descriptor.timeoutMillis() >= 1_000, "工具缺少有效超时: " + name);

            JsonNode input = descriptor.inputSchema();
            assertEquals("object", input.path("type").asText(), "输入 schema 必须是对象: " + name);
            assertTrue(input.path("additionalProperties").isBoolean(), name);
            assertFalse(input.path("additionalProperties").asBoolean(true),
                    "输入 schema 必须封闭: " + name);

            assertTrue(AgentToolOutputSchemas.isRegistered(name),
                    "缺少输出 schema 登记: " + name);
            assertClosedOutput(descriptor.outputSchema(), name);

            if (descriptor.effect() == AgentToolEffect.WRITE
                    || descriptor.effect() == AgentToolEffect.LOCAL) {
                assertTrue(descriptor.idempotencyRequired(), "写工具必须要求幂等键: " + name);
                assertNotNull(descriptor.requiredScope(), "写工具必须声明授权范围: " + name);
                assertFalse(descriptor.requiredScope().isBlank(), name);
            } else {
                assertFalse(descriptor.idempotencyRequired(), "只读工具不应要求幂等键: " + name);
            }
        }
    }

    @Test
    void matrixReadWriteEffectsAndRiskLevelsMatchProduction() {
        for (String tool : READ_TOOLS) {
            assertEquals(AgentToolEffect.READ, handler(tool).descriptor().effect(), tool);
        }
        for (String tool : WRITE_TOOLS) {
            assertEquals(AgentToolEffect.WRITE, handler(tool).descriptor().effect(), tool);
        }
        assertEquals(AgentToolEffect.NAVIGATE, handler("navigation.resolve").descriptor().effect());

        MATRIX_RISK.forEach((tool, expected) ->
                assertEquals(expected, handler(tool).descriptor().riskLevel(), tool));
        // 只读工具一律无副作用风险。
        for (String tool : READ_TOOLS) {
            assertEquals(AgentToolRiskLevel.NONE, handler(tool).descriptor().riskLevel(), tool);
        }
    }

    @Test
    void writeToolsWithoutUserConfirmationStillGoThroughGovernanceExecutor() {
        for (String tool : WRITE_TOOLS) {
            AgentToolHandler handler = handler(tool);
            GovernedAgentToolHandler governed = (GovernedAgentToolHandler) handler;
            assertNotNull(governed.executionType(), "缺少执行类型: " + tool);
        }
    }

    private void assertClosedOutput(JsonNode schema, String tool) {
        String type = schema.path("type").asText();
        assertTrue(Set.of("object", "array").contains(type),
                "输出 schema 必须有明确类型: " + tool);
        JsonNode object = "array".equals(type) ? schema.path("items") : schema;
        assertEquals("object", object.path("type").asText(), "输出条目必须是对象: " + tool);
        assertFalse(object.path("additionalProperties").asBoolean(true),
                "输出 schema 必须封闭: " + tool);
        assertTrue(object.path("properties").isObject() && !object.path("properties").isEmpty(),
                "输出 schema 必须声明真实字段: " + tool);
    }

    private Set<String> names() {
        return registry.catalog().stream()
                .map(AgentToolDescriptor::name)
                .collect(Collectors.toSet());
    }

    private AgentToolHandler handler(String name) {
        List<AgentToolHandler> matches = handlers.stream()
                .filter(handler -> handler.descriptor().name().equals(name))
                .toList();
        assertEquals(1, matches.size(), "工具必须且只能注册一次: " + name);
        return matches.get(0);
    }
}
