package com.moxiao.studypilot.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.stream.Collectors;

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
                "runner.execution.preview"
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
                "runner.dependencies.prepare"
        )));
    }
}
