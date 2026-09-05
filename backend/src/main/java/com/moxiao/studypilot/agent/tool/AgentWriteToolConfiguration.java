package com.moxiao.studypilot.agent.tool;

import com.moxiao.studypilot.agent.domain.ExecutionType;
import com.moxiao.studypilot.assessment.api.CreateWrongQuestionReviewRequest;
import com.moxiao.studypilot.assessment.application.WrongQuestionReviewService;
import com.moxiao.studypilot.learning.api.CreateLearningGoalRequest;
import com.moxiao.studypilot.learning.api.CreateLearningPlanRequest;
import com.moxiao.studypilot.learning.api.InternalChangeTaskStatusRequest;
import com.moxiao.studypilot.learning.api.LearningGoalResponse;
import com.moxiao.studypilot.learning.api.LearningPlanResponse;
import com.moxiao.studypilot.learning.api.LearningTaskResponse;
import com.moxiao.studypilot.learning.application.CreateLearningGoalService;
import com.moxiao.studypilot.learning.application.LearningPlanService;
import com.moxiao.studypilot.learning.application.LearningTaskService;
import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import com.moxiao.studypilot.material.api.CreateTextMaterialRequest;
import com.moxiao.studypilot.material.api.CreateWebMaterialRequest;
import com.moxiao.studypilot.material.api.MaterialResponse;
import com.moxiao.studypilot.material.application.MaterialService;
import com.moxiao.studypilot.material.domain.MaterialCategory;
import com.moxiao.studypilot.notification.api.NotificationResponse;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.roadmap.api.CreateProjectWorkspaceRequest;
import com.moxiao.studypilot.roadmap.api.CreateRoadmapArtifactRequest;
import com.moxiao.studypilot.roadmap.api.RetryRoadmapQuizRequest;
import com.moxiao.studypilot.roadmap.application.RoadmapArtifactService;
import com.moxiao.studypilot.roadmap.application.RoadmapEnrollmentService;
import com.moxiao.studypilot.roadmap.application.RoadmapLearningLoopService;
import com.moxiao.studypilot.roadmap.application.RoadmapScheduleService;
import com.moxiao.studypilot.roadmap.application.RoadmapUpgradeService;
import com.moxiao.studypilot.user.api.AvailabilitySlotRequest;
import com.moxiao.studypilot.user.api.UpdateUserSettingsRequest;
import com.moxiao.studypilot.user.api.UserSettingsResponse;
import com.moxiao.studypilot.user.application.UserSettingsService;
import com.moxiao.studypilot.user.domain.PrivacyLevel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

@Configuration
public class AgentWriteToolConfiguration {

    @Bean
    AgentToolHandler roadmapEnrollTool(ObjectMapper mapper, RoadmapEnrollmentService service) {
        return write(mapper, "roadmap.enroll", "ROADMAP", AgentToolRiskLevel.HIGH,
                "ROADMAP_MANAGEMENT", ExecutionType.ROADMAP_CHANGE,
                Map.of("roadmapCode", "string", "templateVersion", "integer"),
                Set.of("roadmapCode", "templateVersion"),
                arguments -> "切换到路线 " + text(arguments, "roadmapCode")
                        + " V" + arguments.path("templateVersion").asInt(),
                (context, arguments) -> service.enroll(
                        context.ownerId(), text(arguments, "roadmapCode"),
                        arguments.get("templateVersion").asInt()));
    }

    @Bean
    AgentToolHandler roadmapUpgradeTool(ObjectMapper mapper, RoadmapUpgradeService service) {
        return write(mapper, "roadmap.upgrade", "ROADMAP", AgentToolRiskLevel.HIGH,
                "ROADMAP_MANAGEMENT", ExecutionType.ROADMAP_CHANGE,
                Map.of("upgradeId", "string"), Set.of("upgradeId"),
                arguments -> "确认升级当前学习路线",
                (context, arguments) -> service.confirm(
                        context.ownerId(), text(arguments, "upgradeId")));
    }

    @Bean
    AgentToolHandler learningGoalCreateTool(
            ObjectMapper mapper, CreateLearningGoalService service,
            AgentToolRequestValidator validator
    ) {
        return write(mapper, "learning.goal.create", "LEARNING", AgentToolRiskLevel.LOW,
                "LEARNING_MANAGEMENT", ExecutionType.LEARNING_GOAL_CHANGE,
                Map.of("title", "string", "targetDate", "string",
                        "weeklyStudyHours", "integer"),
                Set.of("title", "targetDate", "weeklyStudyHours"),
                arguments -> "创建学习目标《" + text(arguments, "title") + "》",
                (context, arguments) -> {
                    var request = validator.requireValid(new CreateLearningGoalRequest(
                            text(arguments, "title"),
                            LocalDate.parse(text(arguments, "targetDate")),
                            arguments.get("weeklyStudyHours").asInt()));
                    return LearningGoalResponse.from(service.create(
                            context.ownerId(), request.title(), request.targetDate(),
                            request.weeklyStudyHours()));
                });
    }

    @Bean
    AgentToolHandler learningPlanCreateTool(
            ObjectMapper mapper, LearningPlanService service,
            AgentToolRequestValidator validator
    ) {
        return write(mapper, "learning.plan.create", "LEARNING", AgentToolRiskLevel.LOW,
                "PLAN_GENERATION", ExecutionType.PLAN_GENERATION,
                Map.of("goalId", "string", "title", "string", "startDate", "string",
                        "endDate", "string"), Set.of("goalId", "title", "startDate", "endDate"),
                arguments -> "创建学习计划《" + text(arguments, "title") + "》",
                (context, arguments) -> LearningPlanResponse.from(service.create(
                        context.ownerId(), validator.requireValid(new CreateLearningPlanRequest(
                                text(arguments, "goalId"), text(arguments, "title"),
                                LocalDate.parse(text(arguments, "startDate")),
                                LocalDate.parse(text(arguments, "endDate")))))));
    }

    @Bean
    AgentToolHandler scheduleRefreshTool(ObjectMapper mapper, RoadmapScheduleService service) {
        return write(mapper, "schedule.refresh", "SCHEDULE", AgentToolRiskLevel.LOW,
                "SMALL_PLAN_ADJUSTMENT", ExecutionType.PLAN_ADJUSTMENT,
                Map.of("from", "string", "to", "string"), Set.of("from", "to"),
                arguments -> "重新整理指定日期范围内的路线日程",
                (context, arguments) -> service.refresh(
                        context.ownerId(), LocalDate.parse(text(arguments, "from")),
                        LocalDate.parse(text(arguments, "to"))));
    }

    @Bean
    AgentToolHandler taskStatusWriteTool(
            ObjectMapper mapper, LearningTaskService service, AgentToolRequestValidator validator
    ) {
        return write(mapper, "learning.task.update", "LEARNING", AgentToolRiskLevel.HIGH,
                "TASK_MANAGEMENT", ExecutionType.TASK_STATUS_CHANGE,
                Map.of("taskId", "string", "expectedVersion", "integer", "status", "string",
                        "scheduledDate", "string", "reason", "string", "actualMinutes", "integer"),
                Set.of("taskId", "expectedVersion", "status"),
                arguments -> "更新学习任务状态为 " + arguments.path("status").asText(),
                (context, arguments) -> LearningTaskResponse.from(service.changeStatusIdempotently(
                        text(arguments, "taskId"), validator.requireValid(new InternalChangeTaskStatusRequest(
                                context.ownerId(), context.operationIdempotencyKey(),
                                arguments.get("expectedVersion").asInt(),
                                LearningTaskStatus.valueOf(text(arguments, "status")),
                                optionalDate(arguments, "scheduledDate"),
                                optionalText(arguments, "reason"),
                                optionalInt(arguments, "actualMinutes"))))));
    }

    @Bean
    AgentToolHandler wrongQuestionReviewWriteTool(
            ObjectMapper mapper, WrongQuestionReviewService service,
            AgentToolRequestValidator validator
    ) {
        return write(mapper, "assessment.wrong_question_review.create", "ASSESSMENT",
                AgentToolRiskLevel.LOW, "QUIZ_GENERATION", ExecutionType.QUIZ_GENERATION,
                Map.of("chapterKey", "string"), Set.of(),
                arguments -> "创建最多五题的错题重做批次",
                (context, arguments) -> service.create(context.ownerId(),
                        validator.requireValid(new CreateWrongQuestionReviewRequest(
                                optionalText(arguments, "chapterKey"),
                                context.operationIdempotencyKey()))));
    }

    @Bean
    AgentToolHandler roadmapQuizRetryWriteTool(
            ObjectMapper mapper, RoadmapLearningLoopService service,
            AgentToolRequestValidator validator
    ) {
        return write(mapper, "assessment.node_quiz.retry", "ASSESSMENT",
                AgentToolRiskLevel.LOW, "QUIZ_GENERATION", ExecutionType.QUIZ_GENERATION,
                Map.of("nodeId", "string"), Set.of("nodeId"),
                arguments -> "为路线节点重新生成五题测验",
                (context, arguments) -> service.retryQuiz(
                        context.ownerId(), text(arguments, "nodeId"),
                        validator.requireValid(new RetryRoadmapQuizRequest(
                                context.operationIdempotencyKey()))));
    }

    @Bean
    AgentToolHandler roadmapQuizGenerateWriteTool(
            ObjectMapper mapper, RoadmapLearningLoopService service,
            AgentToolRequestValidator validator
    ) {
        return write(mapper, "assessment.node_quiz.generate", "ASSESSMENT",
                AgentToolRiskLevel.LOW, "QUIZ_GENERATION", ExecutionType.QUIZ_GENERATION,
                Map.of("nodeId", "string"), Set.of("nodeId"),
                arguments -> "为路线节点生成五题测验",
                (context, arguments) -> service.retryQuiz(
                        context.ownerId(), text(arguments, "nodeId"),
                        validator.requireValid(new RetryRoadmapQuizRequest(
                                context.operationIdempotencyKey()))));
    }

    @Bean
    AgentToolHandler notificationMarkReadTool(
            ObjectMapper mapper, NotificationService service
    ) {
        return write(mapper, "notifications.mark_read", "NOTIFICATION",
                AgentToolRiskLevel.LOW, "NOTIFICATION_MANAGEMENT",
                ExecutionType.NOTIFICATION_CHANGE,
                Map.of("notificationId", "string"), Set.of("notificationId"),
                arguments -> "将通知标记为已读",
                (context, arguments) -> NotificationResponse.from(service.markRead(
                        context.ownerId(), text(arguments, "notificationId"))));
    }

    @Bean
    AgentToolHandler learningSettingsUpdateTool(
            ObjectMapper mapper, UserSettingsService service,
            AgentToolRequestValidator validator
    ) {
        return write(mapper, "settings.learning.update", "SETTINGS",
                AgentToolRiskLevel.HIGH, "SETTINGS_MANAGEMENT",
                ExecutionType.USER_SETTINGS_CHANGE,
                Map.of("dailyStudyLimitMinutes", "integer"),
                Set.of("dailyStudyLimitMinutes"),
                arguments -> "将每日学习时长调整为 "
                        + arguments.path("dailyStudyLimitMinutes").asInt() + " 分钟",
                (context, arguments) -> {
                    var current = service.get(context.ownerId());
                    var slots = current.getWeeklyAvailability().stream()
                            .map(slot -> new AvailabilitySlotRequest(
                                    slot.getDayOfWeek(), slot.getStartTime(), slot.getEndTime()))
                            .toList();
                    var request = validator.requireValid(new UpdateUserSettingsRequest(
                            current.getTimeZone(),
                            arguments.get("dailyStudyLimitMinutes").asInt(),
                            current.getWeekendPreference(), current.getDefaultPrivacyLevel(), slots));
                    return UserSettingsResponse.from(service.save(context.ownerId(), request));
                });
    }

    @Bean
    AgentToolHandler workspaceRegisterTool(
            ObjectMapper mapper, RoadmapArtifactService service,
            AgentToolRequestValidator validator
    ) {
        return write(mapper, "workspaces.register", "WORKSPACE", AgentToolRiskLevel.HIGH,
                "WORKSPACE_MANAGEMENT", ExecutionType.WORKSPACE_REGISTRATION,
                Map.of("name", "string", "rootPath", "string"), Set.of("name", "rootPath"),
                arguments -> "登记本地代码工作区《" + text(arguments, "name") + "》",
                (context, arguments) -> service.createWorkspace(
                        context.ownerId(), validator.requireValid(new CreateProjectWorkspaceRequest(
                                text(arguments, "name"), text(arguments, "rootPath")))));
    }

    @Bean
    AgentToolHandler artifactSubmitTool(
            ObjectMapper mapper, RoadmapArtifactService service,
            AgentToolRequestValidator validator
    ) {
        return write(mapper, "artifacts.submit", "WORKSPACE", AgentToolRiskLevel.HIGH,
                "ARTIFACT_MANAGEMENT", ExecutionType.ARTIFACT_SUBMISSION,
                Map.of("workspaceId", "string", "roadmapNodeId", "string",
                        "relativePath", "string", "description", "string",
                        "testEvidence", "string"),
                Set.of("workspaceId", "roadmapNodeId", "relativePath",
                        "description", "testEvidence"),
                arguments -> "提交路线实践成果等待用户验收",
                (context, arguments) -> service.submit(context.ownerId(),
                        validator.requireValid(new CreateRoadmapArtifactRequest(
                                text(arguments, "workspaceId"),
                                text(arguments, "roadmapNodeId"),
                                text(arguments, "relativePath"),
                                text(arguments, "description"),
                                text(arguments, "testEvidence"),
                                context.operationIdempotencyKey()))));
    }

    @Bean
    AgentToolHandler textMaterialImportWriteTool(
            ObjectMapper mapper, MaterialService service, AgentToolRequestValidator validator
    ) {
        return write(mapper, "materials.text.import", "MATERIAL",
                AgentToolRiskLevel.LOW, "MATERIAL_PROCESSING", ExecutionType.MATERIAL_PROCESSING,
                Map.of("title", "string", "content", "string", "category", "string",
                        "privacyLevel", "string"),
                Set.of("title", "content", "category", "privacyLevel"),
                arguments -> "导入文本学习资料《" + text(arguments, "title") + "》",
                (context, arguments) -> MaterialResponse.from(service.createText(
                        context.ownerId(), validator.requireValid(new CreateTextMaterialRequest(
                                text(arguments, "title"), text(arguments, "content"),
                                MaterialCategory.valueOf(text(arguments, "category")),
                                PrivacyLevel.valueOf(text(arguments, "privacyLevel")))))));
    }

    @Bean
    AgentToolHandler webMaterialImportWriteTool(
            ObjectMapper mapper, MaterialService service, AgentToolRequestValidator validator
    ) {
        return write(mapper, "materials.web.import", "MATERIAL",
                AgentToolRiskLevel.LOW, "MATERIAL_PROCESSING", ExecutionType.MATERIAL_PROCESSING,
                Map.of("title", "string", "sourceUrl", "string", "category", "string",
                        "privacyLevel", "string"),
                Set.of("title", "sourceUrl", "category", "privacyLevel"),
                arguments -> "导入网页学习资料《" + text(arguments, "title") + "》",
                (context, arguments) -> MaterialResponse.from(service.createWeb(
                        context.ownerId(), validator.requireValid(new CreateWebMaterialRequest(
                                text(arguments, "title"), text(arguments, "sourceUrl"),
                                MaterialCategory.valueOf(text(arguments, "category")),
                                PrivacyLevel.valueOf(text(arguments, "privacyLevel")))))));
    }


    @Bean
    AgentToolHandler runnerCheckRunTool(
            ObjectMapper mapper,
            com.moxiao.studypilot.agent.runner.RunnerGovernanceService service
    ) {
        return write(mapper, "runner.check.run", "RUNNER", AgentToolRiskLevel.LOW,
                "RUNNER_MANAGEMENT", ExecutionType.RUNNER_EXECUTION,
                Map.of("workspaceId", "string", "templateType", "string"),
                Set.of("workspaceId", "templateType"),
                arguments -> "执行项目本地检查任务（" + text(arguments, "templateType") + "）",
                (context, arguments) -> service.submitExecution(
                        context.ownerId(),
                        new com.moxiao.studypilot.agent.runner.RunnerExecutionRequest(
                                text(arguments, "workspaceId"),
                                com.moxiao.studypilot.agent.runner.RunnerTemplateType.valueOf(
                                        text(arguments, "templateType")),
                                optionalText(arguments, "targetPattern"),
                                optionalText(arguments, "explanation"))));
    }

    @Bean
    AgentToolHandler runnerDependenciesPrepareTool(
            ObjectMapper mapper,
            com.moxiao.studypilot.agent.runner.RunnerGovernanceService service
    ) {
        return write(mapper, "runner.dependencies.prepare", "RUNNER", AgentToolRiskLevel.HIGH,
                "RUNNER_MANAGEMENT", ExecutionType.RUNNER_EXECUTION,
                Map.of("workspaceId", "string", "templateType", "string"),
                Set.of("workspaceId", "templateType"),
                arguments -> "准备并安装项目运行环境依赖（" + text(arguments, "templateType") + "）",
                (context, arguments) -> service.submitExecution(
                        context.ownerId(),
                        new com.moxiao.studypilot.agent.runner.RunnerExecutionRequest(
                                text(arguments, "workspaceId"),
                                com.moxiao.studypilot.agent.runner.RunnerTemplateType.valueOf(
                                        text(arguments, "templateType")),
                                optionalText(arguments, "targetPattern"),
                                optionalText(arguments, "explanation"))));
    }

    private static AgentToolHandler write(
            ObjectMapper mapper,
            String name,
            String category,
            AgentToolRiskLevel risk,
            String scope,
            ExecutionType executionType,
            Map<String, String> properties,
            Set<String> required,
            Function<JsonNode, String> summary,
            BiFunction<AgentToolContext, JsonNode, Object> function
    ) {
        ObjectNode input = mapper.createObjectNode().put("type", "object")
                .put("additionalProperties", false);
        ObjectNode schemaProperties = input.putObject("properties");
        properties.forEach((property, type) -> schemaProperties
                .putObject(property).put("type", type));
        var requiredArray = input.putArray("required");
        required.forEach(requiredArray::add);
        ObjectNode output = mapper.createObjectNode().put("type", "object");
        return new GovernedFunctionalAgentToolHandler(new AgentToolDescriptor(
                name, 1, category, AgentToolEffect.WRITE, risk, scope, true, input, output),
                executionType, summary, function);
    }

    private static String text(JsonNode arguments, String name) {
        String value = arguments.get(name).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("工具参数不能为空: " + name);
        }
        return value;
    }

    private static String optionalText(JsonNode arguments, String name) {
        return arguments.hasNonNull(name) ? arguments.get(name).asText() : null;
    }

    private static Integer optionalInt(JsonNode arguments, String name) {
        return arguments.hasNonNull(name) ? arguments.get(name).asInt() : null;
    }

    private static LocalDate optionalDate(JsonNode arguments, String name) {
        return arguments.hasNonNull(name) ? LocalDate.parse(arguments.get(name).asText()) : null;
    }
}
