package com.moxiao.studypilot.agent.tool;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentToolOutputValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode closedObjectSchema() {
        var properties = mapper.createObjectNode();
        properties.putObject("id").put("type", "string");
        properties.putObject("title").put("type", "string");
        properties.putObject("count").put("type", "integer");
        var itemSchema = mapper.createObjectNode().put("type", "string");
        var arraySchema = mapper.createObjectNode().put("type", "array");
        arraySchema.set("items", itemSchema);
        properties.set("items", arraySchema);
        var schema = mapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        schema.set("properties", properties);
        schema.set("required", mapper.createArrayNode().add("id"));
        return schema;
    }

    @Test
    void acceptsOutputThatMatchesTheDeclaredContract() {
        JsonNode output = mapper.readTree("""
                {"id":"node-1","title":"变量","count":2,"items":["a","b"]}
                """);
        assertDoesNotThrow(() -> AgentToolOutputValidator.validate(
                "roadmap.node.get", closedObjectSchema(), output));
    }

    @Test
    void rejectsUndeclaredFieldsMissingRequiredFieldsAndWrongTypes() {
        assertThrows(IllegalStateException.class, () -> AgentToolOutputValidator.validate(
                "roadmap.node.get", closedObjectSchema(),
                mapper.readTree("{\"id\":\"node-1\",\"secret\":\"leak\"}")));
        assertThrows(IllegalStateException.class, () -> AgentToolOutputValidator.validate(
                "roadmap.node.get", closedObjectSchema(),
                mapper.readTree("{\"title\":\"没有 id\"}")));
        assertThrows(IllegalStateException.class, () -> AgentToolOutputValidator.validate(
                "roadmap.node.get", closedObjectSchema(),
                mapper.readTree("{\"id\":\"node-1\",\"count\":\"不是数字\"}")));
        assertThrows(IllegalStateException.class, () -> AgentToolOutputValidator.validate(
                "roadmap.node.get", closedObjectSchema(),
                mapper.readTree("{\"id\":\"node-1\",\"items\":[1,2]}")));
    }

    @Test
    void validatesArrayItemContractsAndNullableTypes() {
        JsonNode arraySchema = mapper.readTree("""
                {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "properties": {"id": {"type": "string"}, "note": {"type": ["string","null"]}}
                  }
                }
                """);
        assertDoesNotThrow(() -> AgentToolOutputValidator.validate(
                "learning.tasks.list", arraySchema,
                mapper.readTree("[{\"id\":\"t1\",\"note\":null},{\"id\":\"t2\",\"note\":\"x\"}]")));
        assertThrows(IllegalStateException.class, () -> AgentToolOutputValidator.validate(
                "learning.tasks.list", arraySchema,
                mapper.readTree("[{\"id\":\"t1\",\"extra\":true}]")));
    }

    @Test
    void validationErrorsNeverLeakActualBusinessValues() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> AgentToolOutputValidator.validate(
                        "materials.get", closedObjectSchema(),
                        mapper.readTree("{\"id\":\"node-1\",\"secret\":\"<script>alert(1)</script>\"}")));
        assertFalse(exception.getMessage().contains("alert"),
                "输出契约错误不得回显真实业务数据");
    }
}
