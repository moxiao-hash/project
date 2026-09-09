package com.moxiao.studypilot.agent.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 29 事件帧解析与校验单元测试。
 *
 * <p>这些断言只覆盖确定性解析/过滤逻辑；真实 SSE 代理由
 * {@code AssistantFacadeContractTest} 通过 MockMvc 异步分发验证。</p>
 */
class AssistantEventStreamServiceTest {

    private static final UUID CONVERSATION_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AssistantEventStreamService service =
            new AssistantEventStreamService(null, MAPPER);

    @Test
    void decodesIdEventAndDataFields() {
        AssistantEventStreamService.ParsedFrame frame = AssistantEventStreamService.FrameParser.decode(
                List.of(
                        "id: 7",
                        "event: TOOL_SUCCEEDED",
                        "data: {\"sequence\":7,\"type\":\"TOOL_SUCCEEDED\"}",
                        "retry: 5000"
                )
        );

        assertNotNull(frame);
        assertEquals("7", frame.id());
        assertEquals("TOOL_SUCCEEDED", frame.event());
        assertEquals("{\"sequence\":7,\"type\":\"TOOL_SUCCEEDED\"}", frame.data());
        assertFalse(frame.comment());
    }

    @Test
    void decodesCommentFrameAsHeartbeat() {
        AssistantEventStreamService.ParsedFrame frame = AssistantEventStreamService.FrameParser.decode(
                List.of(": heartbeat")
        );

        assertNotNull(frame);
        assertTrue(frame.comment());
    }

    @Test
    void rejectsFrameWithoutIdOrEvent() {
        assertNull(AssistantEventStreamService.FrameParser.decode(
                List.of("data: {\"sequence\":1}")));
        assertNull(AssistantEventStreamService.FrameParser.decode(
                List.of("id: 1", "data: {\"sequence\":1}")));
        assertNull(AssistantEventStreamService.FrameParser.decode(List.of()));
    }

    @Test
    void acceptsWellFormedEventAndStripsOwnerId() {
        AssistantEventStreamService.ParsedFrame frame = frame(2, "TURN_STARTED", CONVERSATION_ID, 2);

        AssistantEventStreamService.StreamEvent event = service.validate(frame, CONVERSATION_ID, 1);

        assertNotNull(event);
        assertEquals(2, event.sequence());
        assertEquals("TURN_STARTED", event.type());
        assertFalse(event.payload().has("ownerId"), "下游事件不得携带 ownerId");
    }

    @Test
    void rejectsUnknownEventType() {
        AssistantEventStreamService.ParsedFrame frame = frame(2, "MODEL_THOUGHTS", CONVERSATION_ID, 2);

        assertNull(service.validate(frame, CONVERSATION_ID, 1));
    }

    @Test
    void rejectsNonMonotonicOrDuplicateSequence() {
        AssistantEventStreamService.ParsedFrame frame = frame(3, "TURN_STARTED", CONVERSATION_ID, 3);

        assertNull(service.validate(frame, CONVERSATION_ID, 3), "已消费序号不得重复下发");
        assertNotNull(service.validate(frame, CONVERSATION_ID, 2));
    }

    @Test
    void rejectsEventForAnotherConversation() {
        AssistantEventStreamService.ParsedFrame frame = frame(
                2, "TURN_STARTED", UUID.randomUUID(), 2);

        assertNull(service.validate(frame, CONVERSATION_ID, 1));
    }

    @Test
    void rejectsMismatchedOrMalformedPayload() {
        AssistantEventStreamService.ParsedFrame mismatched =
                new AssistantEventStreamService.ParsedFrame(
                        "2",
                        "TURN_STARTED",
                        "{\"sequence\":5,\"type\":\"TURN_STARTED\",\"conversationId\":\""
                                + CONVERSATION_ID + "\"}",
                        false
                );
        AssistantEventStreamService.ParsedFrame malformed =
                new AssistantEventStreamService.ParsedFrame(
                        "2", "TURN_STARTED", "{not-json", false);

        assertNull(service.validate(mismatched, CONVERSATION_ID, 1));
        assertNull(service.validate(malformed, CONVERSATION_ID, 1));
    }

    @Test
    void rejectsPayloadTypeThatDiffersFromSseEventName() {
        AssistantEventStreamService.ParsedFrame frame = new AssistantEventStreamService.ParsedFrame(
                "2",
                "TOOL_STARTED",
                "{\"sequence\":2,\"type\":\"TURN_COMPLETED\",\"conversationId\":\""
                        + CONVERSATION_ID + "\"}",
                false
        );

        assertNull(service.validate(frame, CONVERSATION_ID, 1));
    }

    private static AssistantEventStreamService.ParsedFrame frame(
            long sequence,
            String type,
            UUID conversationId,
            long payloadSequence
    ) {
        return new AssistantEventStreamService.ParsedFrame(
                Long.toString(sequence),
                type,
                "{\"sequence\":" + payloadSequence
                        + ",\"type\":\"" + type
                        + "\",\"conversationId\":\"" + conversationId
                        + "\",\"ownerId\":\"attacker\",\"payload\":{}}",
                false
        );
    }
}
