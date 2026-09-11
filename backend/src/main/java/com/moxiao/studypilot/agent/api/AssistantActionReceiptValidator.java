package com.moxiao.studypilot.agent.api;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Task 30：动作回执的严格输入校验。
 *
 * <p>冻结请求体必须恰好是 {@code actionId/status/error/currentRoute}：拒绝 ownerId、
 * DOM、URL、HTML、JavaScript、CSS 选择器、任意模型字段和任何额外字段。
 * {@code currentRoute} 只能是前端注册的 route name，绝不是 URL。</p>
 */
@Component
public final class AssistantActionReceiptValidator {

    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_REJECTED = "REJECTED";

    private static final Set<String> ALLOWED_STATUSES =
            Set.of(STATUS_SUCCEEDED, STATUS_FAILED, STATUS_REJECTED);

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "actionId", "status", "error", "currentRoute"
    );

    /** 与 {@code web/src/app/router.ts} 的 31 个具名路由一一对应。 */
    private static final Set<String> ALLOWED_ROUTE_NAMES = Set.of(
            "login", "register", "assistant", "dashboard", "roadmap", "roadmap-stage",
            "roadmap-module", "roadmap-node", "goals", "courses", "course-detail",
            "lesson", "plans", "plan-detail", "today", "materials", "material-detail",
            "quiz", "attempt", "wrong-questions", "mastery", "knowledge", "agent-plan",
            "agent-tasks", "activity", "assistant-health", "notifications", "settings",
            "settings-ai", "workspace-artifacts", "not-found"
    );

    private static final String SAFE_IDENTIFIER = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
    private static final int MAX_ERROR_LENGTH = 500;

    private static final Set<String> FORBIDDEN_ERROR_FRAGMENTS = Set.of(
            "authorization", "bearer ", "x-internal-service-token",
            "internal-service-token", "api_key", "apikey", "secret", "password",
            "stacktrace", "stack trace", "traceback", "\nat ", "\tat ",
            "org.springframework.", "java.lang.", "com.moxiao.studypilot."
    );

    public record Receipt(String actionId, String status, String error, String currentRoute) {
    }

    public Receipt validate(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("动作回执必须是 JSON 对象");
        }
        Set<String> unknown = new LinkedHashSet<>();
        body.propertyNames().forEach(name -> {
            if (!ALLOWED_FIELDS.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("动作回执包含未允许字段: " + String.join(",", unknown));
        }
        String actionId = requireText(body, "actionId");
        if (!actionId.matches(SAFE_IDENTIFIER)) {
            throw new IllegalArgumentException("动作标识不合法");
        }
        String status = requireText(body, "status");
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("动作回执状态不合法");
        }
        String currentRoute = requireText(body, "currentRoute");
        if (!ALLOWED_ROUTE_NAMES.contains(currentRoute)) {
            throw new IllegalArgumentException("currentRoute 必须是已注册的前端路由名");
        }
        String error = sanitizeError(body.get("error"));
        return new Receipt(actionId, status, error, currentRoute);
    }

    static String sanitizeError(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("error 必须是文本");
        }
        String normalized = value.asText().trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return null;
        }
        String lowered = normalized.toLowerCase();
        for (String fragment : FORBIDDEN_ERROR_FRAGMENTS) {
            if (lowered.contains(fragment)) {
                return null;
            }
        }
        if (normalized.matches("(?is).*(https?://|javascript:|data:text/html|<[^>]*>|\\bsrc=|\\bhref=).*")) {
            return null;
        }
        StringBuilder printable = new StringBuilder();
        normalized.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .forEach(printable::appendCodePoint);
        String bounded = printable.length() > MAX_ERROR_LENGTH
                ? printable.substring(0, MAX_ERROR_LENGTH) : printable.toString();
        return bounded.isBlank() ? null : bounded;
    }

    private static String requireText(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("动作回执缺少字段: " + name);
        }
        return value.asText();
    }
}
