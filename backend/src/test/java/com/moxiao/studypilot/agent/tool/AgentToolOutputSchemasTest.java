package com.moxiao.studypilot.agent.tool;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 30 复审：输出 Schema 必须是可拒绝真实坏数据的严格契约。
 *
 * <p>覆盖空对象、缺失必填键、嵌套字段/类型错误、任意数组元素，以及全量 64 个登记
 * Schema 的闭合性/必填/数组元素类型。</p>
 */
class AgentToolOutputSchemasTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private void expectReject(String tool, String json, String reason) {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> AgentToolOutputValidator.validate(
                        tool, AgentToolOutputSchemas.schemaFor(tool, mapper),
                        mapper.readTree(json)),
                reason);
        assertFalse(exception.getMessage().isBlank());
    }

    private void expectAccept(String tool, String json) {
        AgentToolOutputValidator.validate(
                tool, AgentToolOutputSchemas.schemaFor(tool, mapper), mapper.readTree(json));
    }

    @Test
    void emptyObjectsAndMissingRequiredKeysAreRejectedAcrossRepresentativeTools() {
        expectReject("learning.context.get", "{}", "空对象必须被拒绝");
        expectReject("learning.context.get",
                "{\"learning\":{},\"wrongQuestions\":{},\"workspaces\":[],\"warnings\":[]}",
                "缺少 always-present 键必须被拒绝");
        expectReject("learning.goals.list", "[{}]", "列表空元素必须被拒绝");
        expectReject("learning.goals.list",
                "[{\"title\":\"缺少 id\",\"targetDate\":\"2026-01-01\","
                        + "\"weeklyStudyHours\":3,\"status\":\"ACTIVE\"}]",
                "缺少必填 id 必须被拒绝");
        expectReject("roadmap.node.get", "{}", "缺少必填节点字段必须被拒绝");
        expectReject("assessment.wrong_questions.summary", "{}",
                "缺少必填统计字段必须被拒绝");
    }

    @Test
    void nestedObjectsAreClosedAndTyped() {
        // learning 是嵌套对象：陌生字段与错误类型都必须被拒绝。
        expectReject("learning.context.get",
                "{\"generatedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"learning\":{\"timeZone\":\"Asia/Shanghai\","
                        + "\"goals\":[],\"plans\":[],\"tasks\":[],\"materials\":[],"
                        + "\"mastery\":[],\"injected\":true},"
                        + "\"wrongQuestions\":{\"activeCount\":0,\"masteredCount\":0,"
                        + "\"chapters\":[]},"
                        + "\"unreadNotificationCount\":0,\"pendingConfirmationCount\":0,"
                        + "\"workspaces\":[],\"warnings\":[]}",
                "嵌套对象陌生字段必须被拒绝");
        expectReject("learning.context.get",
                "{\"generatedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"learning\":{\"timeZone\":\"Asia/Shanghai\",\"goals\":\"bad\","
                        + "\"plans\":[],\"tasks\":[],\"materials\":[],\"mastery\":[]},"
                        + "\"wrongQuestions\":{\"activeCount\":0,\"masteredCount\":0,"
                        + "\"chapters\":[]},"
                        + "\"unreadNotificationCount\":0,\"pendingConfirmationCount\":0,"
                        + "\"workspaces\":[],\"warnings\":[]}",
                "嵌套字段类型错误必须被拒绝");
    }

    @Test
    void arrayElementsAreTypedAndRejectedWhenArbitrary() {
        // workspaces 必须是对象数组，标量元素与陌生字段都要拒绝。
        expectReject("learning.context.get",
                "{\"generatedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"learning\":{\"timeZone\":\"Asia/Shanghai\",\"goals\":[],"
                        + "\"plans\":[],\"tasks\":[],\"materials\":[],\"mastery\":[]},"
                        + "\"wrongQuestions\":{\"activeCount\":0,\"masteredCount\":0,"
                        + "\"chapters\":[]},"
                        + "\"unreadNotificationCount\":0,\"pendingConfirmationCount\":0,"
                        + "\"workspaces\":[\"junk\"],\"warnings\":[]}",
                "任意数组元素必须被拒绝");
        // objectives 必须是字符串数组。
        expectReject("roadmap.node.get",
                "{\"id\":\"n\",\"code\":\"N\",\"order\":1,\"title\":\"t\","
                        + "\"objectives\":[1,2],\"highFrequency\":[],\"commonMistakes\":[],"
                        + "\"searchKeywords\":[],\"estimatedMinutes\":30,"
                        + "\"practiceMinutes\":10,\"difficulty\":\"EASY\",\"required\":true,"
                        + "\"prerequisiteCodes\":[],\"availabilityStatus\":\"AVAILABLE\","
                        + "\"learningStatus\":\"NOT_STARTED\",\"checkInStatus\":\"NONE\","
                        + "\"quizStatus\":\"NONE\",\"artifactStatus\":\"NONE\","
                        + "\"completionStatus\":\"NONE\",\"diagnosticMastered\":false,"
                        + "\"displayStatus\":\"AVAILABLE\",\"version\":1}",
                "数组元素类型错误必须被拒绝");
    }

    @Test
    void everyRegisteredSchemaIsStrictlyClosedWithRequiredKeysAndTypedItems() {
        assertTrue(AgentToolOutputSchemas.registeredTools().size() == 64,
                "登记输出契约的工具数必须与冻结目录一致");
        for (String tool : AgentToolOutputSchemas.registeredTools()) {
            assertStrictObject(tool, AgentToolOutputSchemas.schemaFor(tool, mapper), true);
        }
    }

    private void assertStrictObject(String tool, JsonNode schema, boolean root) {
        String type = typeOf(schema);
        if ("array".equals(type)) {
            assertTrue(root, tool + " 只允许根节点为数组");
            JsonNode items = schema.path("items");
            assertTrue(items.isObject() && !items.isEmpty(),
                    tool + " 数组元素必须声明类型");
            assertStrictObject(tool, items, false);
            return;
        }
        assertTrue("object".equals(type), tool + " 必须声明对象类型");
        assertStrictObjectBody(tool, schema);
    }

    private void assertStrictObjectBody(String tool, JsonNode schema) {
        JsonNode additional = schema.path("additionalProperties");
        if (additional.isObject()) {
            // 受控映射：键不固定，但值类型必须严格（空对象表示不透明 any 值）。
            assertStrictValueSchema(tool + "{}", additional);
            return;
        }
        assertFalse(additional.asBoolean(true),
                tool + " 必须封闭 additionalProperties");
        assertTrue(schema.path("required").isArray() && !schema.path("required").isEmpty(),
                tool + " 必须声明 always-present 必填键");
        JsonNode properties = schema.path("properties");
        assertTrue(properties.isObject() && !properties.isEmpty(),
                tool + " 必须声明字段");
        properties.properties().forEach(property -> {
            assertStrictValueSchema(tool + "." + property.getKey(), property.getValue());
        });
    }

    private void assertStrictValueSchema(String path, JsonNode child) {
        String childType = typeOf(child);
        if ("array".equals(childType)) {
            JsonNode items = child.path("items");
            assertTrue(items.isObject() && !items.isEmpty(), path + " 数组元素必须声明类型");
            if ("object".equals(typeOf(items))) {
                assertStrictObjectBody(path + "[]", items);
            } else {
                assertFalse(typeOf(items).isBlank(), path + " 数组元素必须声明类型");
            }
            return;
        }
        if ("object".equals(childType)) {
            assertStrictObjectBody(path, child);
            return;
        }
        // 标量类型，或显式 any（无 type）用于不透明的 JSON 值。
        if (!childType.isBlank()) {
            assertTrue(Set.of("string", "integer", "number", "boolean").contains(childType),
                    path + " 类型不合法: " + childType);
        }
    }

    private String typeOf(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isTextual()) {
            return type.asText();
        }
        if (type.isArray()) {
            for (JsonNode candidate : type) {
                if (candidate.isTextual() && !"null".equals(candidate.asText())) {
                    return candidate.asText();
                }
            }
        }
        return "";
    }
}
