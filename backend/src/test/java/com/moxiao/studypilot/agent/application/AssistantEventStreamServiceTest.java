package com.moxiao.studypilot.agent.application;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSession;

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

    @Test
    void nonSuccessUpstreamStatusClosesBodyInsteadOfLeaking() throws Exception {
        TrackingInputStream body = new TrackingInputStream("upstream down");
        AssistantEventStreamService streamService = new AssistantEventStreamService(
                gatewayReturning(503, body), MAPPER, Executors.newSingleThreadExecutor());

        SseEmitter emitter = streamService.open(CONVERSATION_ID, "user-1", 0);

        assertNotNull(emitter);
        awaitClosed(body);
        assertTrue(body.isClosed(), "非 2xx 上游响应体必须被关闭");
    }

    @Test
    void successfulUpstreamStreamClosesBodyAfterEof() throws Exception {
        TrackingInputStream body = new TrackingInputStream("""
                id: 2
                event: TURN_STARTED
                data: {"sequence":2,"type":"TURN_STARTED","conversationId":"%s","payload":{}}

                """.formatted(CONVERSATION_ID));
        AssistantEventStreamService streamService = new AssistantEventStreamService(
                gatewayReturning(200, body), MAPPER, Executors.newSingleThreadExecutor());

        SseEmitter emitter = streamService.open(CONVERSATION_ID, "user-1", 1);

        assertNotNull(emitter);
        awaitClosed(body);
        assertTrue(body.isClosed(), "上游流读取结束后必须关闭");
    }

    @Test
    void rejectedConnectionDoesNotThrowAndEndsTheStream() {
        ExecutorService closedPool = Executors.newSingleThreadExecutor();
        closedPool.shutdown();
        AssistantEventStreamService streamService = new AssistantEventStreamService(
                gatewayReturning(200, new TrackingInputStream("")), MAPPER, closedPool);

        SseEmitter emitter = streamService.open(CONVERSATION_ID, "user-1", 0);

        assertNotNull(emitter, "并发上限/线程池关闭时不得向调用方抛异常");
    }

    @Test
    void shutdownWorkersIsIdempotentAndStopsAcceptingNewStreams() {
        AssistantEventStreamService streamService = new AssistantEventStreamService(
                gatewayReturning(200, new TrackingInputStream("")),
                MAPPER,
                Executors.newSingleThreadExecutor());

        streamService.shutdownWorkers();
        streamService.shutdownWorkers();

        assertNotNull(streamService.open(CONVERSATION_ID, "user-1", 0));
    }

    private static AgentGatewayService gatewayReturning(int status, InputStream body) {
        return new AgentGatewayService("http://127.0.0.1:1", "test-token", MAPPER) {
            @Override
            public HttpResponse<InputStream> openEventStream(String path, String ownerId) {
                return response(status, body);
            }
        };
    }

    private static HttpResponse<InputStream> response(int status, InputStream body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpRequest request() {
                return null;
            }

            @Override
            public Optional<HttpResponse<InputStream>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (name, value) -> true);
            }

            @Override
            public InputStream body() {
                return body;
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("http://127.0.0.1:1/internal/assistant/events/stream");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    private static void awaitClosed(TrackingInputStream body) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (!body.isClosed() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    /** 记录是否被关闭，用于验证上游响应体没有泄漏。 */
    private static final class TrackingInputStream extends ByteArrayInputStream {

        private volatile boolean closed;

        private TrackingInputStream(String content) {
            super(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private boolean isClosed() {
            return closed;
        }
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
