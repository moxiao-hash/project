package com.moxiao.studypilot.agent.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Task 29：把 Python 的内部 SSE 事件流持续代理给浏览器。
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li>只做协议转发、白名单过滤与字段脱敏，不产生业务副作用；</li>
 *   <li>客户端断开只取消本连接的上游订阅，<b>不</b>取消已经进入治理层的业务动作；</li>
 *   <li>上游事件必须已经持久化（Python 侧保证），本类按序号单调递增过滤，重复帧直接丢弃。</li>
 * </ul>
 */
@Service
public class AssistantEventStreamService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AssistantEventStreamService.class);

    /** 契约冻结的事件类型白名单；未登记的类型一律不下发。 */
    private static final Set<String> EVENT_TYPES = Set.of(
            "HEARTBEAT",
            "TURN_STARTED",
            "CONTEXT_LOADED",
            "PLAN_GENERATED",
            "TOOL_STARTED",
            "TOOL_SUCCEEDED",
            "TOOL_FAILED",
            "ACTION_PREVIEW",
            "ASSISTANT_DELTA",
            "UI_ACTION",
            "TURN_COMPLETED",
            "TURN_FAILED",
            "TURN_CANCELLED"
    );

    /** 0 表示不设服务端超时；连接由客户端断开或上游关闭决定。 */
    private static final long STREAM_TIMEOUT_MILLIS = 0L;

    private final AgentGatewayService gateway;
    private final ObjectMapper objectMapper;
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "assistant-event-stream");
        thread.setDaemon(true);
        return thread;
    });

    public AssistantEventStreamService(
            AgentGatewayService gateway,
            ObjectMapper objectMapper
    ) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    /**
     * 打开一条浏览器 SSE 连接。
     *
     * @param conversationId 会话 ID，事件必须属于该会话
     * @param ownerId        由 Bearer 会话解析出的用户，浏览器无法伪造
     * @param afterSequence  客户端已消费的最大序号，来自 Last-Event-ID
     */
    public SseEmitter open(UUID conversationId, String ownerId, long afterSequence) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        StreamSession session = new StreamSession(emitter, conversationId, afterSequence);
        emitter.onCompletion(session::cancel);
        emitter.onTimeout(session::cancel);
        emitter.onError(error -> session.cancel());
        workers.execute(() -> pump(ownerId, afterSequence, session));
        return emitter;
    }

    private void pump(String ownerId, long afterSequence, StreamSession session) {
        try {
            String path = "/internal/assistant/conversations/" + session.conversationId()
                    + "/events/stream?afterSequence=" + afterSequence;
            HttpResponse<InputStream> response = gateway.openEventStream(path, ownerId);
            if (response.statusCode() >= 400) {
                session.completeWithError(new IllegalStateException(
                        "AI 事件流返回状态 " + response.statusCode()));
                return;
            }
            session.attach(response.body());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    response.body(), StandardCharsets.UTF_8))) {
                List<String> frameLines = new ArrayList<>();
                String line;
                while (!session.cancelled() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        dispatch(frameLines, session);
                        frameLines.clear();
                    } else {
                        frameLines.add(line);
                    }
                }
                // 上游若未以空行收尾，最后一帧同样必须处理，不能丢事件。
                dispatch(frameLines, session);
            }
            session.complete();
        } catch (Exception exception) {
            if (!session.cancelled()) {
                LOGGER.debug("统一助手事件流结束", exception);
            }
            session.completeWithError(exception);
        }
    }

    private void dispatch(List<String> frameLines, StreamSession session) {
        if (frameLines.isEmpty()) {
            return;
        }
        ParsedFrame frame = FrameParser.decode(frameLines);
        if (frame == null) {
            return;
        }
        if (frame.comment()) {
            session.sendHeartbeat();
            return;
        }
        StreamEvent event = validate(frame, session.conversationId(), session.lastSent());
        if (event == null) {
            return;
        }
        session.send(event);
    }

    /**
     * 校验并脱敏单个事件；不合法返回 {@code null}。
     *
     * <p>过滤规则：类型白名单、序号严格递增、会话归属一致、事件名与 payload.type 一致、
     * payload 必须是合法 JSON 对象，并移除任何 {@code ownerId} 字段。</p>
     */
    StreamEvent validate(ParsedFrame frame, UUID conversationId, long lastSent) {
        if (!EVENT_TYPES.contains(frame.event())) {
            return null;
        }
        long sequence;
        try {
            sequence = Long.parseLong(frame.id());
        } catch (NumberFormatException exception) {
            return null;
        }
        if (sequence <= lastSent) {
            return null;
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(frame.data());
        } catch (RuntimeException exception) {
            return null;
        }
        if (payload == null || !payload.isObject()) {
            return null;
        }
        if (!conversationId.toString().equals(payload.path("conversationId").asText(""))) {
            return null;
        }
        if (!frame.event().equals(payload.path("type").asText(""))) {
            return null;
        }
        if (payload.path("sequence").asLong(-1) != sequence) {
            return null;
        }
        ObjectNode sanitized = ((ObjectNode) payload).deepCopy();
        sanitized.remove("ownerId");
        return new StreamEvent(sequence, frame.event(), sanitized);
    }

    /** 已校验、可安全下发给浏览器的事件。 */
    record StreamEvent(long sequence, String type, JsonNode payload) {
    }

    /** 一条 SSE 帧的原始字段。 */
    record ParsedFrame(String id, String event, String data, boolean comment) {
    }

    /** 按 SSE 规范拆解 id/event/data 行；未知字段忽略。 */
    static final class FrameParser {

        private FrameParser() {
        }

        static ParsedFrame decode(List<String> lines) {
            String id = null;
            String event = null;
            StringBuilder data = new StringBuilder();
            boolean comment = false;
            for (String line : lines) {
                if (line.startsWith(":")) {
                    comment = true;
                    continue;
                }
                int separator = line.indexOf(':');
                String field = separator < 0 ? line : line.substring(0, separator);
                String value = separator < 0 ? "" : line.substring(separator + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                switch (field) {
                    case "id" -> id = value;
                    case "event" -> event = value;
                    case "data" -> {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(value);
                    }
                    default -> {
                        // retry 等字段对本次代理无意义。
                    }
                }
            }
            if (comment && id == null && event == null && data.isEmpty()) {
                return new ParsedFrame(null, null, null, true);
            }
            if (id == null || event == null) {
                return null;
            }
            return new ParsedFrame(id, event, data.toString(), false);
        }
    }

    /** 单条连接的会话状态；只有工作线程写，断开回调只读 cancelled/upstream。 */
    private static final class StreamSession {

        private final SseEmitter emitter;
        private final UUID conversationId;
        private final long afterSequence;
        private long lastSent;
        private volatile boolean cancelled;
        private volatile InputStream upstream;

        private StreamSession(SseEmitter emitter, UUID conversationId, long afterSequence) {
            this.emitter = emitter;
            this.conversationId = conversationId;
            this.afterSequence = afterSequence;
            this.lastSent = afterSequence;
        }

        private UUID conversationId() {
            return conversationId;
        }

        private long lastSent() {
            return lastSent;
        }

        private boolean cancelled() {
            return cancelled;
        }

        private void attach(InputStream body) {
            this.upstream = body;
            if (cancelled) {
                closeQuietly(body);
            }
        }

        /** 客户端断开：只取消上游订阅，不影响已经提交的业务动作。 */
        private void cancel() {
            cancelled = true;
            closeQuietly(upstream);
        }

        private void send(StreamEvent event) {
            if (cancelled) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(event.sequence()))
                        .name(event.type())
                        .data(event.payload(), MediaType.APPLICATION_JSON));
                lastSent = event.sequence();
            } catch (IOException exception) {
                cancel();
            }
        }

        private void sendHeartbeat() {
            if (cancelled) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException exception) {
                cancel();
            }
        }

        private void complete() {
            if (cancelled) {
                return;
            }
            try {
                emitter.complete();
            } catch (RuntimeException exception) {
                LOGGER.debug("完成事件流时连接已关闭", exception);
            }
        }

        private void completeWithError(Throwable error) {
            if (cancelled) {
                return;
            }
            try {
                emitter.completeWithError(error);
            } catch (RuntimeException exception) {
                LOGGER.debug("结束事件流时连接已关闭", exception);
            }
        }

        private static void closeQuietly(InputStream stream) {
            if (stream == null) {
                return;
            }
            try {
                stream.close();
            } catch (IOException exception) {
                LOGGER.debug("关闭上游事件流失败", exception);
            }
        }
    }
}
