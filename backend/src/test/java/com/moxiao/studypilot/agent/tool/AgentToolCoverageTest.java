package com.moxiao.studypilot.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class AgentToolCoverageTest {

    @Autowired
    private AgentToolRegistry registry;

    @Test
    void everyTraditionalPageHasARealReadTool() {
        Set<String> names = registry.catalog().stream()
                .map(AgentToolDescriptor::name)
                .collect(Collectors.toSet());

        assertTrue(names.containsAll(Set.of(
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
        )));
    }

    @Test
    void delegatedWritesUseGovernedBusinessTools() {
        Set<String> names = registry.catalog().stream()
                .map(AgentToolDescriptor::name)
                .collect(Collectors.toSet());

        assertTrue(names.containsAll(Set.of(
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
        )));
    }

    /**
     * Task 27 补充：Java 注册表与能力矩阵必须精确一致。
     * 新增或删除工具后，必须先更新 docs/agent-capability-matrix-v2.md，再同步本清单。
     */
    private static final Set<String> INVENTORIED_TOOL_NAMES = Set.of(
            "artifacts.evaluate", "artifacts.get", "artifacts.list", "artifacts.submit",
            "assessment.attempt.get", "assessment.mastery.list", "assessment.node_quiz.generate", "assessment.node_quiz_status.get",
            "assessment.quiz.get", "assessment.wrong_question_review.create", "assessment.wrong_questions.list", "automation.rules.list",
            "automation.settings.get", "developer.code.search", "developer.file.read", "developer.file_tree.get",
            "developer.git.commit", "developer.git.commit.preview", "developer.git.diff", "developer.git.log",
            "developer.git.push", "developer.git.push.preview", "developer.git.status", "developer.interface_fallback.preview",
            "developer.patch.apply", "developer.patch.preview", "developer.tests.recommend", "governance.audit.list",
            "governance.executions.list", "learning.context.get", "learning.goal.create", "learning.goals.list",
            "learning.plan.create", "learning.plan.get", "learning.plans.list", "learning.task.update",
            "materials.get", "materials.list", "materials.text.import", "materials.web.import",
            "notifications.list", "notifications.mark_read", "roadmap.current.get", "roadmap.enroll",
            "roadmap.module.get", "roadmap.node.get", "roadmap.stage.get", "roadmap.upgrade",
            "runner.check.run", "runner.dependencies.prepare", "runner.execution.preview", "schedule.refresh",
            "schedule.today.get", "schedule.unfinished.get", "settings.ai_status.get", "settings.learning.get",
            "settings.learning.update", "workspaces.list", "workspaces.register",
            "assessment.node_quiz.retry", "assessment.wrong_questions.summary",
            "governance.health.get", "learning.tasks.list", "navigation.resolve"
    );

    @Test
    void everyRegisteredToolIsInventoriedInTheCapabilityMatrix() {
        Set<String> names = registry.catalog().stream()
                .map(AgentToolDescriptor::name)
                .collect(Collectors.toSet());

        assertEquals(INVENTORIED_TOOL_NAMES, names,
                "注册表与能力矩阵不一致：请同步 docs/agent-capability-matrix-v2.md 与本清单");
    }
}
