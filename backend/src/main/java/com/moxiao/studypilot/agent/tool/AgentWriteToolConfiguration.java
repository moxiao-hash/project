package com.moxiao.studypilot.agent.tool;

import com.moxiao.studypilot.agent.domain.ExecutionType;
import com.moxiao.studypilot.assessment.api.CreateWrongQuestionReviewRequest;
import com.moxiao.studypilot.assessment.application.WrongQuestionReviewService;
import com.moxiao.studypilot.learning.api.InternalChangeTaskStatusRequest;
import com.moxiao.studypilot.learning.api.LearningTaskResponse;
import com.moxiao.studypilot.learning.application.LearningTaskService;
import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import com.moxiao.studypilot.material.api.CreateTextMaterialRequest;
import com.moxiao.studypilot.material.api.CreateWebMaterialRequest;
import com.moxiao.studypilot.material.api.MaterialResponse;
import com.moxiao.studypilot.material.application.MaterialService;
import com.moxiao.studypilot.material.domain.MaterialCategory;
import com.moxiao.studypilot.roadmap.api.RetryRoadmapQuizRequest;
import com.moxiao.studypilot.roadmap.application.RoadmapLearningLoopService;
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
