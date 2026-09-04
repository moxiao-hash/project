package com.moxiao.studypilot.agent.tool;

import com.moxiao.studypilot.agent.api.AgentExecutionResponse;
import com.moxiao.studypilot.agent.api.AuditLogResponse;
import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.aicredential.application.AiCredentialService;
import com.moxiao.studypilot.assessment.api.MasteryResponse;
import com.moxiao.studypilot.assessment.api.QuizResponse;
import com.moxiao.studypilot.assessment.application.QuizService;
import com.moxiao.studypilot.assessment.application.WrongQuestionService;
import com.moxiao.studypilot.learning.api.LearningGoalResponse;
import com.moxiao.studypilot.learning.api.LearningPlanResponse;
import com.moxiao.studypilot.learning.api.LearningTaskResponse;
import com.moxiao.studypilot.learning.application.CreateLearningGoalService;
import com.moxiao.studypilot.learning.application.LearningPlanService;
import com.moxiao.studypilot.learning.application.LearningTaskService;
import com.moxiao.studypilot.material.api.MaterialResponse;
import com.moxiao.studypilot.material.application.MaterialService;
import com.moxiao.studypilot.notification.api.NotificationResponse;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.roadmap.application.RoadmapArtifactService;
import com.moxiao.studypilot.roadmap.application.RoadmapQueryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

@Configuration
public class AgentReadToolConfiguration {

    @Bean
    AgentToolHandler learningContextTool(
            ObjectMapper mapper, AgentLearningContextService service
    ) {
        return read(mapper, "learning.context.get", "CONTEXT", Map.of(), Set.of(),
                (context, arguments) -> service.get(context.ownerId()));
    }

    @Bean
    AgentToolHandler learningGoalsTool(ObjectMapper mapper, CreateLearningGoalService service) {
        return read(mapper, "learning.goals.list", "LEARNING", Map.of(), Set.of(),
                (context, arguments) -> service.list(context.ownerId()).stream()
                        .map(LearningGoalResponse::from).toList());
    }

    @Bean
    AgentToolHandler learningPlansTool(ObjectMapper mapper, LearningPlanService service) {
        return read(mapper, "learning.plans.list", "LEARNING", Map.of(), Set.of(),
                (context, arguments) -> service.list(context.ownerId()).stream()
                        .map(LearningPlanResponse::from).toList());
    }

    @Bean
    AgentToolHandler learningTasksTool(ObjectMapper mapper, LearningTaskService service) {
        return read(mapper, "learning.tasks.list", "LEARNING",
                Map.of("date", "string"), Set.of(),
                (context, arguments) -> service.list(context.ownerId(),
                                optionalDate(arguments, "date"))
                        .stream().map(LearningTaskResponse::from).toList());
    }

    @Bean
    AgentToolHandler roadmapCurrentTool(ObjectMapper mapper, RoadmapQueryService service) {
        return read(mapper, "roadmap.current.get", "ROADMAP", Map.of(), Set.of(),
                (context, arguments) -> service.currentMap(context.ownerId()));
    }

    @Bean
    AgentToolHandler roadmapStageTool(ObjectMapper mapper, RoadmapQueryService service) {
        return read(mapper, "roadmap.stage.get", "ROADMAP",
                Map.of("stageId", "string"), Set.of("stageId"),
                (context, arguments) -> service.currentStage(
                        context.ownerId(), text(arguments, "stageId")));
    }

    @Bean
    AgentToolHandler roadmapModuleTool(ObjectMapper mapper, RoadmapQueryService service) {
        return read(mapper, "roadmap.module.get", "ROADMAP",
                Map.of("moduleId", "string"), Set.of("moduleId"),
                (context, arguments) -> service.currentModule(
                        context.ownerId(), text(arguments, "moduleId")));
    }

    @Bean
    AgentToolHandler roadmapNodeTool(ObjectMapper mapper, RoadmapQueryService service) {
        return read(mapper, "roadmap.node.get", "ROADMAP",
                Map.of("nodeId", "string"), Set.of("nodeId"),
                (context, arguments) -> service.currentNode(
                        context.ownerId(), text(arguments, "nodeId")));
    }

    @Bean
    AgentToolHandler quizTool(ObjectMapper mapper, QuizService service) {
        return read(mapper, "assessment.quiz.get", "ASSESSMENT",
                Map.of("quizId", "string"), Set.of("quizId"),
                (context, arguments) -> {
                    QuizService.QuizBundle bundle = service.get(
                            context.ownerId(), text(arguments, "quizId"));
                    return QuizResponse.from(bundle.quiz(), bundle.questions());
                });
    }

    @Bean
    AgentToolHandler masteryTool(ObjectMapper mapper, QuizService service) {
        return read(mapper, "assessment.mastery.list", "ASSESSMENT", Map.of(), Set.of(),
                (context, arguments) -> service.listMastery(context.ownerId()).stream()
                        .map(MasteryResponse::from).toList());
    }

    @Bean
    AgentToolHandler wrongQuestionSummaryTool(
            ObjectMapper mapper, WrongQuestionService service
    ) {
        return read(mapper, "assessment.wrong_questions.summary", "ASSESSMENT",
                Map.of(), Set.of(),
                (context, arguments) -> service.summary(context.ownerId()));
    }

    @Bean
    AgentToolHandler materialsTool(ObjectMapper mapper, MaterialService service) {
        return read(mapper, "materials.list", "MATERIAL", Map.of(), Set.of(),
                (context, arguments) -> service.list(context.ownerId()).stream()
                        .map(MaterialResponse::from).toList());
    }

    @Bean
    AgentToolHandler materialTool(ObjectMapper mapper, MaterialService service) {
        return read(mapper, "materials.get", "MATERIAL",
                Map.of("materialId", "string"), Set.of("materialId"),
                (context, arguments) -> MaterialResponse.from(service.get(
                        context.ownerId(), text(arguments, "materialId"))));
    }

    @Bean
    AgentToolHandler notificationsTool(ObjectMapper mapper, NotificationService service) {
        return read(mapper, "notifications.list", "NOTIFICATION", Map.of(), Set.of(),
                (context, arguments) -> service.list(context.ownerId()).stream()
                        .map(NotificationResponse::from).toList());
    }

    @Bean
    AgentToolHandler executionsTool(ObjectMapper mapper, AgentGovernanceService service) {
        return read(mapper, "governance.executions.list", "GOVERNANCE", Map.of(), Set.of(),
                (context, arguments) -> service.listExecutions(context.ownerId()).stream()
                        .map(AgentExecutionResponse::from).toList());
    }

    @Bean
    AgentToolHandler auditLogsTool(ObjectMapper mapper, AgentGovernanceService service) {
        return read(mapper, "governance.audit.list", "GOVERNANCE", Map.of(), Set.of(),
                (context, arguments) -> service.listAuditLogs(context.ownerId()).stream()
                        .map(AuditLogResponse::from).toList());
    }

    @Bean
    AgentToolHandler workspacesTool(ObjectMapper mapper, RoadmapArtifactService service) {
        return read(mapper, "workspaces.list", "WORKSPACE", Map.of(), Set.of(),
                (context, arguments) -> service.workspaces(context.ownerId()));
    }

    @Bean
    AgentToolHandler aiSettingsStatusTool(ObjectMapper mapper, AiCredentialService service) {
        return read(mapper, "settings.ai_status.get", "SETTINGS", Map.of(), Set.of(),
                (context, arguments) -> service.settings(context.ownerId()));
    }

    private static AgentToolHandler read(
            ObjectMapper mapper,
            String name,
            String category,
            Map<String, String> properties,
            Set<String> required,
            java.util.function.BiFunction<AgentToolContext, JsonNode, Object> function
    ) {
        ObjectNode input = mapper.createObjectNode().put("type", "object")
                .put("additionalProperties", false);
        ObjectNode schemaProperties = input.putObject("properties");
        properties.forEach((property, type) -> schemaProperties
                .putObject(property).put("type", type));
        var requiredArray = input.putArray("required");
        required.forEach(requiredArray::add);
        ObjectNode output = mapper.createObjectNode().put("type", "object");
        return new FunctionalAgentToolHandler(new AgentToolDescriptor(
                name, 1, category, AgentToolEffect.READ, AgentToolRiskLevel.NONE,
                null, false, input, output), function);
    }

    private static String text(JsonNode arguments, String name) {
        return arguments.get(name).asText();
    }

    private static LocalDate optionalDate(JsonNode arguments, String name) {
        return arguments.hasNonNull(name) ? LocalDate.parse(arguments.get(name).asText()) : null;
    }
}
