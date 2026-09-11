package com.moxiao.studypilot.agent.tool;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Task 30：全部已注册工具的严格输出 JSON Schema 目录。
 *
 * <p>每个工具都登记递归闭合的输出契约：对象声明 always-present 必填键，嵌套对象同样闭合，
 * 数组声明元素类型。可空字段在序列化时按 {@code spring.jackson.default-property-inclusion=non_null}
 * 被省略，因此只作为可空类型声明、不进入 {@code required}；固定存在的键必须进入
 * {@code required}。</p>
 *
 * <p>该目录是 Java 与 Python 的共同事实来源之一：Java 在调用边界校验真实输出，
 * Python 通过 {@code GET /internal/agent-tools/catalog} 读取同一 Schema。</p>
 */
public final class AgentToolOutputSchemas {

    private AgentToolOutputSchemas() {
    }

    /** 递归 Schema 规格。 */
    private record Spec(
            String type,
            boolean nullable,
            Spec items,
            Spec mapValues,
            Map<String, Spec> properties
    ) {
    }

    private static Spec scalar(String type) {
        return new Spec(type, false, null, null, null);
    }

    private static Spec nullable(Spec spec) {
        return new Spec(spec.type(), true, spec.items(), spec.mapValues(), spec.properties());
    }

    private static Spec str() {
        return scalar("string");
    }

    private static Spec strN() {
        return nullable(str());
    }

    private static Spec integer() {
        return scalar("integer");
    }

    private static Spec integerN() {
        return nullable(integer());
    }

    private static Spec number() {
        return scalar("number");
    }

    private static Spec numberN() {
        return nullable(number());
    }

    private static Spec bool() {
        return scalar("boolean");
    }

    private static Spec boolN() {
        return nullable(bool());
    }

    private static Spec object(Object... nameSpecPairs) {
        Map<String, Spec> properties = new LinkedHashMap<>();
        for (int index = 0; index < nameSpecPairs.length; index += 2) {
            properties.put((String) nameSpecPairs[index], (Spec) nameSpecPairs[index + 1]);
        }
        return new Spec("object", false, null, null, properties);
    }

    /** 键不固定的受控映射：值类型仍然必须声明。 */
    private static Spec map(Spec values) {
        return new Spec("object", false, null, values, null);
    }

    private static Spec array(Spec items) {
        return new Spec("array", false, items, null, null);
    }

    // ---------- 共享响应 DTO ----------
    private static final Spec GOAL = object(
            "id", str(), "title", str(), "targetDate", str(),
            "weeklyStudyHours", integer(), "status", str());
    private static final Spec PLAN = object(
            "id", str(), "goalId", str(), "title", str(), "startDate", str(),
            "endDate", str(), "status", str(), "version", integer());
    private static final Spec TASK = object(
            "id", str(), "planId", strN(), "title", str(), "scheduledDate", str(),
            "estimatedMinutes", integer(), "status", str(), "version", integer(),
            "completedAt", strN(), "actualMinutes", integerN(), "taskKind", strN(),
            "knowledgePoint", strN(), "sourceAttemptId", strN());
    private static final Spec MASTERY = object(
            "knowledgePoint", str(), "score", number(), "quizScore", numberN(),
            "taskScore", numberN(), "selfAssessmentScore", numberN(),
            "evidenceCount", integer(), "attemptCount", integer(), "updatedAt", str());
    private static final Spec MATERIAL = object(
            "id", str(), "title", str(), "materialType", str(), "category", str(),
            "privacyLevel", str(), "sourceUrl", strN(), "originalFilename", strN(),
            "mediaType", strN(), "contentLength", integerN(), "processingStatus", str(),
            "summary", strN(), "tags", array(str()), "knowledgePoints", array(str()),
            "processingWarnings", array(str()), "contentReference", strN(),
            "failureReason", strN());
    private static final Spec NOTIFICATION = object(
            "id", str(), "type", str(), "title", str(), "content", str(), "read", bool(),
            "createdAt", str(), "readAt", strN());
    private static final Spec EXECUTION = object(
            "id", str(), "idempotencyKey", strN(), "executionType", str(),
            "triggerType", str(), "riskLevel", str(), "requiredScope", str(), "status", str(),
            "summary", str(), "resultSummary", strN(), "errorMessage", strN(),
            "modelName", strN(), "promptTokens", integerN(), "completionTokens", integerN(),
            "latencyMs", integerN(), "estimatedCost", numberN(), "createdAt", str());
    private static final Spec AUDIT = object(
            "id", integer(), "action", str(), "targetType", str(), "targetId", strN(),
            "details", strN(), "createdAt", str());
    private static final Spec HEALTH = object(
            "totalExecutions", integer(), "successfulExecutions", integer(),
            "failedExecutions", integer(), "successRate", number(), "promptTokens", integer(),
            "completionTokens", integer(), "estimatedCost", number(),
            "averageLatencyMs", integer(), "pendingConfirmations", integer(),
            "costSamples", integer(), "tokenSamples", integer(), "latencySamples", integer());
    private static final Spec AVAILABILITY_SLOT = object(
            "dayOfWeek", str(), "startTime", str(), "endTime", str());
    private static final Spec SETTINGS = object(
            "timeZone", str(), "dailyStudyLimitMinutes", integer(), "weekendPreference", str(),
            "defaultPrivacyLevel", str(), "weeklyAvailability", array(AVAILABILITY_SLOT));
    private static final Spec AI_PROVIDER = object(
            "configured", bool(), "source", strN(), "maskedSuffix", strN(), "available", bool());
    private static final Spec AI_SETTINGS = object(
            "modelProvider", str(), "modelName", str(), "deepseek", AI_PROVIDER,
            "tavily", AI_PROVIDER, "deepseekConfigured", bool(),
            "deepseekMaskedSuffix", strN(), "tavilyConfigured", bool(),
            "tavilyMaskedSuffix", strN(), "warning", strN());
    private static final Spec AUTOMATION_SETTINGS = object(
            "paused", bool(), "updatedAt", str());
    private static final Spec AUTOMATION_RULE = object(
            "id", str(), "type", str(), "status", str(), "timezone", str(), "localTime", str(),
            "riskLevel", str(), "requiredScope", str(), "createdAt", str(), "updatedAt", str());
    private static final Spec WORKSPACE = object(
            "id", str(), "name", str(), "rootPath", str(), "status", str(), "createdAt", str());

    // ---------- Roadmap ----------
    private static final Spec NODE = object(
            "id", str(), "code", str(), "order", integer(), "title", str(),
            "objectives", array(str()), "highFrequency", array(str()),
            "commonMistakes", array(str()), "searchKeywords", array(str()),
            "estimatedMinutes", integer(), "practiceMinutes", integer(), "difficulty", str(),
            "required", bool(), "prerequisiteCodes", array(str()),
            "availabilityStatus", str(), "learningStatus", str(), "checkInStatus", str(),
            "quizStatus", str(), "artifactStatus", str(), "completionStatus", str(),
            "diagnosticMastered", bool(), "displayStatus", str(), "version", integer());
    private static final Spec MODULE_SUMMARY = object(
            "id", str(), "code", str(), "order", integer(), "title", str(),
            "description", strN(), "completedRequiredNodes", integer(),
            "totalRequiredNodes", integer(), "milestoneNodeId", strN(),
            "milestoneNodeCode", strN(), "displayStatus", str());
    private static final Spec MODULE = object(
            "id", str(), "stageId", str(), "code", str(), "order", integer(), "title", str(),
            "description", strN(), "completedRequiredNodes", integer(),
            "totalRequiredNodes", integer(), "displayStatus", str(),
            "milestoneNode", nullable(NODE), "nodes", array(NODE));
    private static final Spec STAGE = object(
            "id", str(), "code", str(), "order", integer(), "title", str(),
            "description", strN(), "graduationProjectTitle", strN(),
            "completedRequiredNodes", integer(), "totalRequiredNodes", integer(),
            "modules", array(MODULE_SUMMARY), "nodes", array(NODE));
    private static final Spec ROADMAP_MAP = object(
            "enrollmentId", str(), "roadmapCode", str(), "templateVersion", integer(),
            "title", str(), "description", strN(), "completedRequiredNodes", integer(),
            "totalRequiredNodes", integer(), "stages", array(STAGE));
    private static final Spec SCHEDULE_ITEM = object(
            "id", str(), "nodeId", str(), "nodeCode", str(), "title", str(),
            "plannedMinutes", integer(), "status", str());
    private static final Spec SCHEDULE_DAY = object(
            "date", str(), "plannedMinutes", integer(), "items", array(SCHEDULE_ITEM));
    private static final Spec SCHEDULE = object(
            "scheduleId", str(), "timeZone", str(), "dailyCapacityMinutes", integer(),
            "weekendsEnabled", bool(), "days", array(SCHEDULE_DAY));
    private static final Spec UNFINISHED_ITEM = object(
            "date", str(), "nodeId", str(), "title", str(), "status", str());
    private static final Spec ENROLLMENT = object(
            "id", str(), "roadmapCode", str(), "templateVersion", integer(), "title", str(),
            "status", str(), "enrolledAt", str());
    private static final Spec UPGRADE = object(
            "id", str(), "sourceVersion", integer(), "targetVersion", integer(), "status", str(),
            "unchangedNodeCodes", array(str()), "addedNodeCodes", array(str()),
            "removedNodeCodes", array(str()), "manualReviewNodeCodes", array(str()),
            "addedModuleCount", integer(), "removedModuleCount", integer(),
            "changedModuleCount", integer());
    private static final Spec QUIZ_GENERATION = object(
            "jobId", str(), "purpose", str(), "status", str(), "retrySequence", integer(),
            "attemptCount", integer(), "quizId", strN(), "lastError", strN(),
            "leaseUntil", strN(), "updatedAt", str());
    private static final Spec NODE_QUIZ = object(
            "nodeId", str(), "status", str(), "quizId", strN(), "latestAttemptId", strN(),
            "generation", nullable(QUIZ_GENERATION));

    // ---------- Assessment ----------
    private static final Spec QUIZ_SOURCE = object(
            "sourceType", str(), "materialId", strN(), "webResultId", strN(), "title", str(),
            "locator", strN(), "snippet", strN());
    private static final Spec QUIZ_QUESTION = object(
            "id", str(), "type", str(), "difficulty", str(), "codingKind", strN(),
            "language", strN(), "knowledgePoint", strN(), "questionText", str(),
            "options", nullable(array(str())), "starterCode", strN(),
            "sources", array(QUIZ_SOURCE));
    private static final Spec QUIZ = object(
            "id", str(), "materialId", strN(), "taskId", strN(), "lessonId", strN(),
            "kind", str(), "title", str(), "modelName", strN(),
            "questions", array(QUIZ_QUESTION));
    private static final Spec QUESTION_RESULT = object(
            "questionId", str(), "type", str(), "questionText", str(),
            "options", nullable(array(str())), "selectedAnswers", array(str()),
            "codeAnswer", strN(), "correctAnswers", array(str()), "referenceAnswer", strN(),
            "correct", bool(), "knowledgePoint", strN(), "explanation", strN(),
            "evaluationMethod", strN(), "score", numberN(),
            "evaluation", nullable(map(scalar("any"))));
    private static final Spec REVIEW_PROGRESS = object(
            "clearedCount", integer(), "remainingCount", integer());
    private static final Spec ATTEMPT = object(
            "id", str(), "quizId", str(), "score", number(), "status", str(),
            "warning", strN(), "results", array(QUESTION_RESULT),
            "reviewProgress", REVIEW_PROGRESS);
    private static final Spec CHAPTER_SUMMARY = object(
            "chapterKey", str(), "chapterTitle", str(), "activeCount", integer(),
            "masteredCount", integer());
    private static final Spec WRONG_REVIEW = object(
            "id", str(), "quizId", str(), "status", str(), "questionCount", integer(),
            "remainingCount", integer());
    private static final Spec WRONG_QUESTION = object(
            "id", str(), "status", str(), "chapterKey", str(), "chapterTitle", str(),
            "type", str(), "difficulty", str(), "codingKind", strN(), "language", strN(),
            "knowledgePoint", strN(), "questionText", str(),
            "options", nullable(array(str())), "latestSelectedAnswers", array(str()),
            "latestCodeAnswer", strN(), "correctAnswers", array(str()),
            "referenceAnswer", strN(), "explanation", strN(),
            "sources", array(QUIZ_SOURCE), "wrongCount", integer(), "redoCount", integer(),
            "firstWrongAt", str(), "lastWrongAt", str(), "masteredAt", strN());
    private static final Spec WRONG_SUMMARY = object(
            "activeCount", integer(), "masteredCount", integer(),
            "chapters", array(CHAPTER_SUMMARY), "currentReview", nullable(WRONG_REVIEW));

    // ---------- Workspace artifacts ----------
    private static final Spec ARTIFACT_NODE = object(
            "id", str(), "moduleId", str(), "stageId", str(), "title", str(),
            "moduleTitle", strN(), "stageTitle", strN());
    private static final Spec REVIEW_EVENT = object(
            "id", str(), "fromStatus", strN(), "toStatus", str(), "eventType", str(),
            "details", strN(), "score", integerN(), "rubricBreakdownJson", strN(),
            "createdAt", str());
    private static final Spec ARTIFACT = object(
            "id", str(), "workspaceId", str(), "relativePath", str(), "canonicalPath", str(),
            "description", str(), "testEvidence", str(), "evaluationMode", str(), "status", str(),
            "submissionVersion", integer(), "roadmapNode", nullable(ARTIFACT_NODE),
            "reviewHistory", array(REVIEW_EVENT), "rubricScore", integerN(),
            "rubricFeedback", strN(), "sensitiveScanPassed", boolN(),
            "sensitiveFindings", strN(), "acceptedAt", strN(), "createdAt", str());

    // ---------- Runner ----------
    private static final Spec RUNNER_PREVIEW = object(
            "workspaceId", str(), "workspaceName", str(), "workspacePath", str(),
            "templateType", str(), "templateDescription", str(), "riskLevel", str(),
            "commandTokens", array(str()), "renderedCommand", str(), "timeoutSeconds", integer(),
            "confirmationRequired", bool(), "explanation", strN());
    private static final Spec RUNNER_RESULT = object(
            "executionId", str(), "governanceExecutionId", str(), "workspaceId", str(),
            "templateType", str(), "status", str(), "exitCode", integer(),
            "commandTokens", array(str()), "stdoutSummary", strN(), "stderrSummary", strN(),
            "success", bool(), "durationMillis", integer(), "executedAt", str());

    // ---------- Developer ----------
    private static final Spec FILE_ENTRY = object(
            "relativePath", str(), "name", str(), "directory", bool(), "sizeBytes", integer());
    private static final Spec FILE_TREE = object(
            "workspaceId", str(), "rootPath", str(), "entries", array(FILE_ENTRY));
    private static final Spec FILE_READ = object(
            "workspaceId", str(), "relativePath", str(), "content", str(),
            "sizeBytes", integer(), "truncated", bool());
    private static final Spec MATCH_ENTRY = object(
            "relativePath", str(), "lineNumber", integer(), "lineContent", str());
    private static final Spec CODE_SEARCH = object(
            "workspaceId", str(), "query", str(), "matches", array(MATCH_ENTRY));
    private static final Spec GIT_STATUS = object(
            "workspaceId", str(), "gitRepository", bool(), "branch", strN(),
            "currentCommit", strN(), "clean", bool(), "modifiedFiles", array(str()),
            "untrackedFiles", array(str()));
    private static final Spec GIT_TEXT = object(
            "workspaceId", str(), "content", str(), "truncated", bool());
    private static final Spec PATCH_PREVIEW = object(
            "workspaceId", str(), "targetFile", str(), "unifiedDiff", str(),
            "expectedSha256", str(), "resultSha256", strN(), "conflict", bool(),
            "conflictReason", strN(), "affectedLines", array(str()), "safeToApply", bool());
    private static final Spec TEST_RECOMMENDATION = object(
            "workspaceId", str(), "templates", array(str()),
            "requiresDependencyPreparation", bool(), "reasons", array(str()));
    private static final Spec COMMIT_PREVIEW = object(
            "workspaceId", str(), "branch", str(), "expectedHead", str(),
            "changeFingerprint", str(), "paths", array(str()), "message", str());
    private static final Spec PUSH_PREVIEW = object(
            "workspaceId", str(), "remoteName", str(), "branch", str(),
            "expectedHead", str(), "aheadCount", integer());
    private static final Spec INTERFACE_FALLBACK = object(
            "channel", str(), "actionKey", str(), "fallbackRequired", bool(), "reason", str());
    private static final Spec COMMIT_RESULT = object(
            "workspaceId", str(), "commitId", str(), "message", str(),
            "paths", array(str()), "committedAt", str());
    private static final Spec PUSH_RESULT = object(
            "workspaceId", str(), "remoteName", str(), "branch", str(),
            "pushedCommit", str(), "pushedAt", str());

    // ---------- Context / navigation ----------
    private static final Spec NAVIGATION = object(
            "routeKey", str(), "params", map(str()));
    private static final Spec LEARNING_CONTEXT_INNER = object(
            "timeZone", str(), "goals", array(GOAL), "plans", array(PLAN),
            "tasks", array(TASK), "materials", array(MATERIAL), "mastery", array(MASTERY));
    private static final Spec AGENT_LEARNING_CONTEXT = object(
            "generatedAt", str(), "learning", LEARNING_CONTEXT_INNER,
            "roadmap", nullable(ROADMAP_MAP), "wrongQuestions", WRONG_SUMMARY,
            "unreadNotificationCount", integer(), "pendingConfirmationCount", integer(),
            "workspaces", array(WORKSPACE), "warnings", array(str()));

    private static final Map<String, Spec> CATALOG = new LinkedHashMap<>();

    static {
        // 查询与无副作用预览工具（43）
        object("learning.context.get", AGENT_LEARNING_CONTEXT);
        array("learning.goals.list", GOAL);
        array("learning.plans.list", PLAN);
        object("learning.plan.get", PLAN);
        object("roadmap.current.get", ROADMAP_MAP);
        object("roadmap.stage.get", STAGE);
        object("roadmap.module.get", MODULE);
        object("roadmap.node.get", NODE);
        object("schedule.today.get", SCHEDULE);
        array("schedule.unfinished.get", UNFINISHED_ITEM);
        object("assessment.node_quiz_status.get", NODE_QUIZ);
        object("assessment.quiz.get", QUIZ);
        object("assessment.attempt.get", ATTEMPT);
        array("assessment.wrong_questions.list", WRONG_QUESTION);
        array("assessment.mastery.list", MASTERY);
        array("materials.list", MATERIAL);
        object("materials.get", MATERIAL);
        array("notifications.list", NOTIFICATION);
        array("governance.executions.list", EXECUTION);
        array("governance.audit.list", AUDIT);
        object("settings.learning.get", SETTINGS);
        object("settings.ai_status.get", AI_SETTINGS);
        object("automation.settings.get", AUTOMATION_SETTINGS);
        array("automation.rules.list", AUTOMATION_RULE);
        array("workspaces.list", WORKSPACE);
        array("artifacts.list", ARTIFACT);
        object("artifacts.get", ARTIFACT);
        object("artifacts.evaluate", ARTIFACT);
        object("runner.execution.preview", RUNNER_PREVIEW);
        object("developer.file_tree.get", FILE_TREE);
        object("developer.file.read", FILE_READ);
        object("developer.code.search", CODE_SEARCH);
        object("developer.git.status", GIT_STATUS);
        object("developer.git.diff", GIT_TEXT);
        object("developer.git.log", GIT_TEXT);
        object("developer.patch.preview", PATCH_PREVIEW);
        object("developer.tests.recommend", TEST_RECOMMENDATION);
        object("developer.git.commit.preview", COMMIT_PREVIEW);
        object("developer.git.push.preview", PUSH_PREVIEW);
        object("developer.interface_fallback.preview", INTERFACE_FALLBACK);
        object("governance.health.get", HEALTH);
        array("learning.tasks.list", TASK);
        object("assessment.wrong_questions.summary", WRONG_SUMMARY);

        // 写入与本地执行工具（20）
        object("roadmap.enroll", ENROLLMENT);
        object("roadmap.upgrade", UPGRADE);
        object("learning.goal.create", GOAL);
        object("learning.plan.create", PLAN);
        object("schedule.refresh", SCHEDULE);
        object("assessment.node_quiz.generate", NODE_QUIZ);
        object("assessment.wrong_question_review.create", WRONG_REVIEW);
        object("learning.task.update", TASK);
        object("materials.text.import", MATERIAL);
        object("materials.web.import", MATERIAL);
        object("notifications.mark_read", NOTIFICATION);
        object("settings.learning.update", SETTINGS);
        object("workspaces.register", WORKSPACE);
        object("artifacts.submit", ARTIFACT);
        object("runner.check.run", RUNNER_RESULT);
        object("runner.dependencies.prepare", RUNNER_RESULT);
        object("developer.patch.apply", PATCH_PREVIEW);
        object("developer.git.commit", COMMIT_RESULT);
        object("developer.git.push", PUSH_RESULT);
        object("assessment.node_quiz.retry", NODE_QUIZ);

        // 导航解析工具（1）
        object("navigation.resolve", NAVIGATION);
    }

    private static void object(String tool, Spec spec) {
        CATALOG.put(tool, spec);
    }

    private static void array(String tool, Spec item) {
        CATALOG.put(tool, array(item));
    }

    public static JsonNode schemaFor(String toolName, ObjectMapper mapper) {
        Spec spec = CATALOG.get(toolName);
        if (spec == null) {
            throw new IllegalStateException("缺少工具输出契约: " + toolName);
        }
        return node(mapper, spec);
    }

    public static boolean isRegistered(String toolName) {
        return CATALOG.containsKey(toolName);
    }

    /** 已登记输出契约的工具名；供覆盖测试与审计枚举。 */
    public static Set<String> registeredTools() {
        return Collections.unmodifiableSet(new TreeSet<>(CATALOG.keySet()));
    }

    /** 超过输出上限时返回的显式裁剪信封字段。 */
    static final String[] TRUNCATED_OUTPUT_FIELDS = {
            "warning:string", "originalBytes:integer", "truncated:boolean"
    };

    /** 裁剪信封的登记契约：调用方永远拿到同一个封闭形状。 */
    public static JsonNode truncatedOutput(ObjectMapper mapper) {
        return node(mapper, object(
                "warning", str(), "originalBytes", integer(), "truncated", bool()));
    }

    /** 调用边界与测试共用的契约符合性检查。 */
    public static boolean conformsTo(JsonNode schema, JsonNode value) {
        try {
            AgentToolOutputValidator.validate("output.contract", schema, value);
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private static JsonNode node(ObjectMapper mapper, Spec spec) {
        if ("any".equals(spec.type())) {
            return mapper.createObjectNode();
        }
        ObjectNode schema = mapper.createObjectNode();
        if (spec.nullable()) {
            ArrayNode types = schema.putArray("type");
            types.add(spec.type());
            types.add("null");
        } else {
            schema.put("type", spec.type());
        }
        if ("object".equals(spec.type())) {
            if (spec.properties() != null) {
                schema.put("additionalProperties", false);
                ObjectNode properties = schema.putObject("properties");
                ArrayNode required = schema.putArray("required");
                spec.properties().forEach((name, child) -> {
                    properties.set(name, node(mapper, child));
                    if (!child.nullable()) {
                        required.add(name);
                    }
                });
            } else if (spec.mapValues() != null) {
                schema.set("additionalProperties", node(mapper, spec.mapValues()));
            } else {
                schema.put("additionalProperties", false);
                schema.putArray("required");
                schema.putObject("properties");
            }
        } else if ("array".equals(spec.type())) {
            schema.set("items", node(mapper, spec.items()));
        }
        return schema;
    }
}
