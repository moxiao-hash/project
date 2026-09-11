package com.moxiao.studypilot.agent.tool;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Task 30：全部已注册工具的严格输出 JSON Schema 目录。
 *
 * <p>每个工具都必须在此登记封闭且带类型的输出契约；缺省的空 {@code {"type":"object"}}
 * 不再被视为合格输出 Schema。字段规格为 {@code "name:type"}，类型支持
 * {@code string/integer/number/boolean/object/array}，以 {@code "?"} 结尾表示可空。</p>
 *
 * <p>该目录是 Java 与 Python 的共同事实来源之一：Java 在调用边界校验真实输出，
 * Python 通过 {@code GET /internal/agent-tools/catalog} 读取同一 Schema。</p>
 */
public final class AgentToolOutputSchemas {

    private static final Map<String, String[]> OBJECT_OUTPUTS = new LinkedHashMap<>();
    private static final Map<String, String[]> ARRAY_OUTPUTS = new LinkedHashMap<>();

    private AgentToolOutputSchemas() {
    }

    public static JsonNode schemaFor(String toolName, ObjectMapper mapper) {
        String[] objectFields = OBJECT_OUTPUTS.get(toolName);
        if (objectFields != null) {
            return closedObject(mapper, objectFields);
        }
        String[] arrayFields = ARRAY_OUTPUTS.get(toolName);
        if (arrayFields != null) {
            ObjectNode schema = mapper.createObjectNode().put("type", "array");
            schema.set("items", closedObject(mapper, arrayFields));
            return schema;
        }
        throw new IllegalStateException("缺少工具输出契约: " + toolName);
    }

    public static boolean isRegistered(String toolName) {
        return OBJECT_OUTPUTS.containsKey(toolName) || ARRAY_OUTPUTS.containsKey(toolName);
    }

    private static ObjectNode closedObject(ObjectMapper mapper, String[] fields) {
        ObjectNode schema = mapper.createObjectNode().put("type", "object")
                .put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        for (String field : fields) {
            int separator = field.indexOf(':');
            String name = separator < 0 ? field : field.substring(0, separator);
            String type = separator < 0 ? "object" : field.substring(separator + 1);
            boolean nullable = type.endsWith("?");
            if (nullable) {
                type = type.substring(0, type.length() - 1);
            }
            ObjectNode property = properties.putObject(name);
            if (nullable) {
                ArrayNode types = property.putArray("type");
                types.add(type);
                types.add("null");
            } else {
                property.put("type", type);
            }
            if ("array".equals(type)) {
                property.set("items", mapper.createObjectNode());
            } else if ("object".equals(type)) {
                property.put("additionalProperties", true);
            }
        }
        schema.putArray("required");
        return schema;
    }

    private static void object(String tool, String... fields) {
        OBJECT_OUTPUTS.put(tool, fields);
    }

    private static void array(String tool, String... itemFields) {
        ARRAY_OUTPUTS.put(tool, itemFields);
    }

    static {
        // ---------- 查询与无副作用预览工具 ----------
        object("learning.context.get",
                "generatedAt:string", "learning:object", "roadmap:object?",
                "wrongQuestions:object", "unreadNotificationCount:integer",
                "pendingConfirmationCount:integer", "workspaces:array", "warnings:array");
        array("learning.goals.list",
                "id:string", "title:string", "targetDate:string",
                "weeklyStudyHours:integer", "status:string");
        array("learning.plans.list",
                "id:string", "goalId:string", "title:string", "startDate:string",
                "endDate:string", "status:string", "version:integer");
        object("learning.plan.get",
                "id:string", "goalId:string", "title:string", "startDate:string",
                "endDate:string", "status:string", "version:integer");
        array("learning.tasks.list",
                "id:string", "planId:string?", "title:string", "scheduledDate:string",
                "estimatedMinutes:integer", "status:string", "version:integer",
                "completedAt:string?", "actualMinutes:integer?", "taskKind:string",
                "knowledgePoint:string?", "sourceAttemptId:string?");
        object("roadmap.current.get",
                "enrollmentId:string", "roadmapCode:string", "templateVersion:integer",
                "title:string", "description:string?", "completedRequiredNodes:integer",
                "totalRequiredNodes:integer", "stages:array");
        object("roadmap.stage.get",
                "id:string", "code:string", "order:integer", "title:string",
                "description:string?", "graduationProjectTitle:string?",
                "completedRequiredNodes:integer", "totalRequiredNodes:integer",
                "modules:array", "nodes:array");
        object("roadmap.module.get",
                "id:string", "stageId:string", "code:string", "order:integer",
                "title:string", "description:string?", "completedRequiredNodes:integer",
                "totalRequiredNodes:integer", "displayStatus:string",
                "milestoneNode:object?", "nodes:array");
        object("roadmap.node.get",
                "id:string", "code:string", "order:integer", "title:string",
                "objectives:array", "highFrequency:array", "commonMistakes:array",
                "searchKeywords:array", "estimatedMinutes:integer",
                "practiceMinutes:integer", "difficulty:string", "required:boolean",
                "prerequisiteCodes:array", "availabilityStatus:string",
                "learningStatus:string", "checkInStatus:string", "quizStatus:string",
                "artifactStatus:string", "completionStatus:string",
                "diagnosticMastered:boolean", "displayStatus:string", "version:integer");
        object("schedule.today.get",
                "scheduleId:string", "timeZone:string", "dailyCapacityMinutes:integer",
                "weekendsEnabled:boolean", "days:array");
        array("schedule.unfinished.get",
                "date:string", "nodeId:string", "title:string", "status:string");
        object("assessment.node_quiz_status.get",
                "nodeId:string", "status:string", "quizId:string?",
                "latestAttemptId:string?", "generation:object?");
        object("assessment.quiz.get",
                "id:string", "materialId:string?", "taskId:string?", "lessonId:string?",
                "kind:string", "title:string", "modelName:string?", "questions:array");
        object("assessment.attempt.get",
                "id:string", "quizId:string", "score:number", "status:string",
                "warning:string?", "results:array", "reviewProgress:object");
        array("assessment.wrong_questions.list",
                "id:string", "status:string", "chapterKey:string", "chapterTitle:string",
                "type:string", "difficulty:string", "codingKind:string?",
                "language:string?", "knowledgePoint:string?", "questionText:string",
                "options:array", "latestSelectedAnswers:array", "latestCodeAnswer:string?",
                "correctAnswers:array", "referenceAnswer:string?", "explanation:string?",
                "sources:array", "wrongCount:integer", "redoCount:integer",
                "firstWrongAt:string", "lastWrongAt:string", "masteredAt:string?");
        object("assessment.wrong_questions.summary",
                "activeCount:integer", "masteredCount:integer", "chapters:array",
                "currentReview:object?");
        array("assessment.mastery.list",
                "knowledgePoint:string", "score:number", "quizScore:number?",
                "taskScore:number?", "selfAssessmentScore:number?",
                "evidenceCount:integer", "attemptCount:integer", "updatedAt:string");
        array("materials.list",
                "id:string", "title:string", "materialType:string", "category:string",
                "privacyLevel:string", "sourceUrl:string?", "originalFilename:string?",
                "mediaType:string?", "contentLength:integer?", "processingStatus:string",
                "summary:string?", "tags:array", "knowledgePoints:array",
                "processingWarnings:array", "contentReference:string?", "failureReason:string?");
        object("materials.get",
                "id:string", "title:string", "materialType:string", "category:string",
                "privacyLevel:string", "sourceUrl:string?", "originalFilename:string?",
                "mediaType:string?", "contentLength:integer?", "processingStatus:string",
                "summary:string?", "tags:array", "knowledgePoints:array",
                "processingWarnings:array", "contentReference:string?", "failureReason:string?");
        array("notifications.list",
                "id:string", "type:string", "title:string", "content:string",
                "read:boolean", "createdAt:string", "readAt:string?");
        array("governance.executions.list",
                "id:string", "idempotencyKey:string?", "executionType:string",
                "triggerType:string", "riskLevel:string", "requiredScope:string",
                "status:string", "summary:string", "resultSummary:string?",
                "errorMessage:string?", "modelName:string?", "promptTokens:integer?",
                "completionTokens:integer?", "latencyMs:integer?", "estimatedCost:number?",
                "createdAt:string");
        array("governance.audit.list",
                "id:integer", "action:string", "targetType:string", "targetId:string?",
                "details:string?", "createdAt:string");
        object("governance.health.get",
                "totalExecutions:integer", "successfulExecutions:integer",
                "failedExecutions:integer", "successRate:number", "promptTokens:integer",
                "completionTokens:integer", "estimatedCost:number",
                "averageLatencyMs:integer", "pendingConfirmations:integer",
                "costSamples:integer", "tokenSamples:integer", "latencySamples:integer");
        object("settings.learning.get",
                "timeZone:string", "dailyStudyLimitMinutes:integer",
                "weekendPreference:string", "defaultPrivacyLevel:string",
                "weeklyAvailability:array");
        object("settings.ai_status.get",
                "modelProvider:string", "modelName:string", "deepseek:object",
                "tavily:object", "deepseekConfigured:boolean",
                "deepseekMaskedSuffix:string?", "tavilyConfigured:boolean",
                "tavilyMaskedSuffix:string?", "warning:string?");
        object("automation.settings.get", "paused:boolean", "updatedAt:string?");
        array("automation.rules.list",
                "id:string", "type:string", "status:string", "timezone:string",
                "localTime:string", "riskLevel:string", "requiredScope:string",
                "createdAt:string", "updatedAt:string");
        array("workspaces.list",
                "id:string", "name:string", "rootPath:string", "status:string",
                "createdAt:string");
        array("artifacts.list", artifactFields());
        object("artifacts.get", artifactFields());
        object("artifacts.evaluate", artifactFields());
        object("runner.execution.preview",
                "workspaceId:string", "workspaceName:string", "workspacePath:string",
                "templateType:string", "templateDescription:string", "riskLevel:string",
                "commandTokens:array", "renderedCommand:string", "timeoutSeconds:integer",
                "confirmationRequired:boolean", "explanation:string?");
        object("developer.file_tree.get",
                "workspaceId:string", "rootPath:string", "entries:array");
        object("developer.file.read",
                "workspaceId:string", "relativePath:string", "content:string",
                "sizeBytes:integer", "truncated:boolean");
        object("developer.code.search",
                "workspaceId:string", "query:string", "matches:array");
        object("developer.git.status",
                "workspaceId:string", "gitRepository:boolean", "branch:string?",
                "currentCommit:string?", "clean:boolean", "modifiedFiles:array",
                "untrackedFiles:array");
        object("developer.git.diff",
                "workspaceId:string", "content:string", "truncated:boolean");
        object("developer.git.log",
                "workspaceId:string", "content:string", "truncated:boolean");
        object("developer.patch.preview",
                "workspaceId:string", "targetFile:string", "unifiedDiff:string",
                "expectedSha256:string", "resultSha256:string?", "conflict:boolean",
                "conflictReason:string?", "affectedLines:array", "safeToApply:boolean");
        object("developer.tests.recommend",
                "workspaceId:string", "templates:array",
                "requiresDependencyPreparation:boolean", "reasons:array");
        object("developer.git.commit.preview",
                "workspaceId:string", "branch:string", "expectedHead:string",
                "changeFingerprint:string", "paths:array", "message:string");
        object("developer.git.push.preview",
                "workspaceId:string", "remoteName:string", "branch:string",
                "expectedHead:string", "aheadCount:integer");
        object("developer.interface_fallback.preview",
                "channel:string", "actionKey:string", "fallbackRequired:boolean",
                "reason:string");
        object("navigation.resolve", "routeKey:string", "params:object");

        // ---------- 写入与本地执行工具 ----------
        object("roadmap.enroll",
                "id:string", "roadmapCode:string", "templateVersion:integer",
                "title:string", "status:string", "enrolledAt:string");
        object("roadmap.upgrade",
                "id:string", "sourceVersion:integer", "targetVersion:integer",
                "status:string", "unchangedNodeCodes:array", "addedNodeCodes:array",
                "removedNodeCodes:array", "manualReviewNodeCodes:array",
                "addedModuleCount:integer", "removedModuleCount:integer",
                "changedModuleCount:integer");
        object("learning.goal.create",
                "id:string", "title:string", "targetDate:string",
                "weeklyStudyHours:integer", "status:string");
        object("learning.plan.create",
                "id:string", "goalId:string", "title:string", "startDate:string",
                "endDate:string", "status:string", "version:integer");
        object("schedule.refresh",
                "scheduleId:string", "timeZone:string", "dailyCapacityMinutes:integer",
                "weekendsEnabled:boolean", "days:array");
        object("assessment.node_quiz.generate",
                "nodeId:string", "status:string", "quizId:string?",
                "latestAttemptId:string?", "generation:object?");
        object("assessment.node_quiz.retry",
                "nodeId:string", "status:string", "quizId:string?",
                "latestAttemptId:string?", "generation:object?");
        object("assessment.wrong_question_review.create",
                "id:string", "quizId:string", "status:string",
                "questionCount:integer", "remainingCount:integer");
        object("learning.task.update",
                "id:string", "planId:string?", "title:string", "scheduledDate:string",
                "estimatedMinutes:integer", "status:string", "version:integer",
                "completedAt:string?", "actualMinutes:integer?", "taskKind:string",
                "knowledgePoint:string?", "sourceAttemptId:string?");
        object("materials.text.import", materialFields());
        object("materials.web.import", materialFields());
        object("notifications.mark_read",
                "id:string", "type:string", "title:string", "content:string",
                "read:boolean", "createdAt:string", "readAt:string?");
        object("settings.learning.update",
                "timeZone:string", "dailyStudyLimitMinutes:integer",
                "weekendPreference:string", "defaultPrivacyLevel:string",
                "weeklyAvailability:array");
        object("workspaces.register",
                "id:string", "name:string", "rootPath:string", "status:string",
                "createdAt:string");
        object("artifacts.submit", artifactFields());
        object("runner.check.run",
                "executionId:string", "governanceExecutionId:string",
                "workspaceId:string", "templateType:string", "status:string",
                "exitCode:integer", "commandTokens:array", "stdoutSummary:string?",
                "stderrSummary:string?", "success:boolean", "durationMillis:integer",
                "executedAt:string");
        object("runner.dependencies.prepare",
                "executionId:string", "governanceExecutionId:string",
                "workspaceId:string", "templateType:string", "status:string",
                "exitCode:integer", "commandTokens:array", "stdoutSummary:string?",
                "stderrSummary:string?", "success:boolean", "durationMillis:integer",
                "executedAt:string");
        object("developer.patch.apply",
                "workspaceId:string", "targetFile:string", "unifiedDiff:string",
                "expectedSha256:string", "resultSha256:string?", "conflict:boolean",
                "conflictReason:string?", "affectedLines:array", "safeToApply:boolean");
        object("developer.git.commit",
                "workspaceId:string", "commitId:string", "message:string",
                "paths:array", "committedAt:string");
        object("developer.git.push",
                "workspaceId:string", "remoteName:string", "branch:string",
                "pushedCommit:string", "pushedAt:string");
    }

    private static String[] materialFields() {
        return new String[]{
                "id:string", "title:string", "materialType:string", "category:string",
                "privacyLevel:string", "sourceUrl:string?", "originalFilename:string?",
                "mediaType:string?", "contentLength:integer?", "processingStatus:string",
                "summary:string?", "tags:array", "knowledgePoints:array",
                "processingWarnings:array", "contentReference:string?", "failureReason:string?"
        };
    }

    private static String[] artifactFields() {
        return new String[]{
                "id:string", "workspaceId:string", "relativePath:string",
                "canonicalPath:string", "description:string", "testEvidence:string",
                "evaluationMode:string", "status:string", "submissionVersion:integer",
                "roadmapNode:object", "reviewHistory:array", "rubricScore:integer?",
                "rubricFeedback:string?", "sensitiveScanPassed:boolean?",
                "sensitiveFindings:string?", "acceptedAt:string?", "createdAt:string"
        };
    }
}
