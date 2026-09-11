package com.moxiao.studypilot.agent.tool;

import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Task 30：工具输出的最小严格 JSON Schema 校验器。
 *
 * <p>只支持契约实际使用的子集：{@code type}、{@code properties}、
 * {@code required}、{@code additionalProperties}、{@code items}、{@code enum}。
 * 校验失败只暴露字段名与期望类型，绝不回显真实业务数据，避免把用户内容写进日志或响应。</p>
 */
public final class AgentToolOutputValidator {

    private AgentToolOutputValidator() {
    }

    public static void validate(String toolName, JsonNode schema, JsonNode value) {
        if (schema == null || schema.isMissingNode() || schema.isEmpty()) {
            return;
        }
        validateNode(schema, value, "$");
        if (toolName == null || toolName.isBlank()) {
            return;
        }
    }

    private static void validateNode(JsonNode schema, JsonNode value, String path) {
        JsonNode enumValues = schema.path("enum");
        if (enumValues.isArray() && !enumValues.isEmpty()) {
            boolean matched = false;
            for (JsonNode allowed : enumValues) {
                if (allowed.equals(value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                throw violation(path, "枚举值不在契约内");
            }
        }
        String expected = expectedType(schema);
        if (expected == null) {
            // 没有声明类型：只做 enum 校验，其余不限制。
            return;
        }
        if (expected.equals("object")) {
            if (!value.isObject()) {
                throw violation(path, "期望 JSON 对象");
            }
            validateObject(schema, value, path);
            return;
        }
        if (expected.equals("array")) {
            if (!value.isArray()) {
                throw violation(path, "期望 JSON 数组");
            }
            JsonNode items = schema.path("items");
            if (items.isObject() && !items.isEmpty()) {
                int index = 0;
                for (JsonNode item : value) {
                    validateNode(items, item, path + "[" + index + "]");
                    index++;
                }
            }
            return;
        }
        if (!isScalarType(value, expected)) {
            throw violation(path, "期望类型 " + expected);
        }
    }

    private static void validateObject(JsonNode schema, JsonNode value, String path) {
        JsonNode properties = schema.path("properties");
        if (schema.path("additionalProperties").isBoolean()
                && !schema.path("additionalProperties").asBoolean(true)) {
            Set<String> allowed = new java.util.HashSet<>();
            properties.propertyNames().forEach(allowed::add);
            value.propertyNames().forEach(name -> {
                if (!allowed.contains(name)) {
                    throw violation(path, "存在未声明的输出字段: " + sanitizeFieldName(name));
                }
            });
        }
        schema.path("required").forEach(required -> {
            String name = required.asText();
            if (!value.hasNonNull(name)) {
                throw violation(path, "缺少必需的输出字段: " + sanitizeFieldName(name));
            }
        });
        if (properties.isObject()) {
            properties.properties().forEach(property -> {
                String name = property.getKey();
                if (value.has(name) && !value.get(name).isNull()) {
                    validateNode(property.getValue(), value.get(name),
                            path + "." + sanitizeFieldName(name));
                }
            });
        }
        JsonNode mapValues = schema.path("additionalProperties");
        if (mapValues.isObject() && !mapValues.isEmpty()) {
            // 受控映射：键不固定，但每个值都必须满足声明的值类型。
            value.properties().forEach(entry -> validateNode(
                    mapValues, entry.getValue(),
                    path + "." + sanitizeFieldName(entry.getKey())));
        }
    }

    private static String expectedType(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isTextual()) {
            return type.asText();
        }
        if (type.isArray()) {
            // 形如 ["string","null"]：null 已被上层短路，取第一个具体类型。
            for (JsonNode candidate : type) {
                if (candidate.isTextual() && !"null".equals(candidate.asText())) {
                    return candidate.asText();
                }
            }
        }
        return null;
    }

    private static boolean isScalarType(JsonNode value, String expected) {
        return switch (expected) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private static IllegalStateException violation(String path, String reason) {
        return new IllegalStateException("工具输出不符合契约 " + path + "：" + reason);
    }

    private static String sanitizeFieldName(String name) {
        if (name == null) {
            return "?";
        }
        String trimmed = name.length() > 64 ? name.substring(0, 64) : name;
        StringBuilder builder = new StringBuilder(trimmed.length());
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            builder.append(Character.isLetterOrDigit(character) || character == '_'
                    || character == '-' || character == '.' ? character : '?');
        }
        return builder.toString();
    }
}
