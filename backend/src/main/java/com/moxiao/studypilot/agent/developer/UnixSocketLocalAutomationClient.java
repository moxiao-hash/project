package com.moxiao.studypilot.agent.developer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Task 33：只通过 Unix Domain Socket 调用本地界面适配器的实现。
 *
 * <p>边界与冻结契约一致：单行 UTF-8 JSON、最大 16 KiB、以换行结束；独立且不少于
 * 32 字节的 HMAC-SHA256 密钥；固定九字段长度前缀签名顺序；60 秒有效窗口；
 * Socket 文件必须仅所有者可访问；连接/读写都有超时。任何超时、Socket 不可用、
 * 权限过宽、响应缺失/超限/非法 UTF-8/重复 JSON/未允许字段/枚举不匹配/关联或摘要
 * 不匹配都失败关闭，不向上层报告成功。</p>
 */
@Component
public class UnixSocketLocalAutomationClient implements LocalAutomationClient {

    /** 冻结契约：请求与响应均为单行 JSON，最大 16 KiB。 */
    static final int MAX_FRAME_BYTES = 16 * 1024;
    static final int REQUEST_VALIDITY_SECONDS = 60;
    /** 终止换行之后只允许极少量空白填充，避免对端用空白流拖住客户端。 */
    static final int MAX_TRAILING_PADDING_BYTES = 64;

    private static final Set<String> REQUIRED_RECEIPT_FIELDS = Set.of(
            "version", "requestId", "adapter", "action", "targetDigest",
            "startedAt", "finishedAt", "status", "errorCode", "message");
    private static final Set<String> ALLOWED_RECEIPT_FIELDS = REQUIRED_RECEIPT_FIELDS;
    private static final String STABLE_CODE = "[A-Z][A-Z0-9_]{0,63}";
    private static final Set<String> FORBIDDEN_MESSAGE_FRAGMENTS = Set.of(
            "http://", "https://", "file://", "javascript:", "data:",
            "\"", "<", ">", "\\", "stack", "traceback", "java.lang.",
            "org.springframework.", "com.moxiao.studypilot.",
            "authorization", "bearer", "token", "secret", "password", "api_key", "apikey");
    private static final String ABSOLUTE_PATH = "(^|\\s)/[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*";

    private static final String MANUAL_RECOVERY =
            "请手动打开目标页面或 IDE 目标后重试，或改用确定性业务 API";

    private final Path socketPath;
    private final String signingSecret;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Supplier<String> nonceSupplier;
    private final SecureRandom secureRandom = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public UnixSocketLocalAutomationClient(
            @Value("${studypilot.local-automation.socket-path:"
                    + "/tmp/studypilot-local-automation/local-automation.sock}") String socketPath,
            @Value("${studypilot.local-automation.signing-secret:"
                    + LocalAutomationSigning.DOCUMENTED_DEFAULT_SECRET + "}") String signingSecret,
            @Value("${studypilot.local-automation.connect-timeout-millis:2000}")
            int connectTimeoutMillis,
            @Value("${studypilot.local-automation.read-timeout-millis:5000}")
            int readTimeoutMillis,
            ObjectMapper objectMapper
    ) {
        this(socketPath, signingSecret, connectTimeoutMillis, readTimeoutMillis, objectMapper,
                Clock.systemUTC(), null);
    }

    UnixSocketLocalAutomationClient(
            String socketPath,
            String signingSecret,
            int connectTimeoutMillis,
            int readTimeoutMillis,
            ObjectMapper objectMapper,
            Clock clock,
            Supplier<String> nonceSupplier
    ) {
        this.socketPath = Path.of(socketPath).toAbsolutePath().normalize();
        this.signingSecret = signingSecret;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.nonceSupplier = nonceSupplier;
    }

    @Override
    public LocalAutomationReceipt invoke(
            InterfaceAutomationChannel channel,
            LocalInterfaceAction action,
            String targetKey,
            String ownerId
    ) {
        requireUsableConfiguration();
        if (channel == null || action == null || channel != action.channel()
                || channel == InterfaceAutomationChannel.BUSINESS_API) {
            throw new IllegalArgumentException("未注册的界面兜底通道与动作组合");
        }
        if (!LocalAutomationSigning.isSymbolicTarget(targetKey)) {
            throw new IllegalArgumentException("未注册的界面兜底目标，失败关闭");
        }
        requireOwner(ownerId);
        requireSecureSocket();

        Instant issuedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(REQUEST_VALIDITY_SECONDS);
        String requestId = UUID.randomUUID().toString();
        String ownerHash = LocalAutomationSigning.ownerHash(ownerId);
        String nonce = nextNonce();
        String canonicalPayload = LocalAutomationSigning.canonicalRequestPayload(
                LocalAutomationRequest.FROZEN_VERSION, requestId, ownerHash, channel.name(),
                action.name(), targetKey, issuedAt, expiresAt, nonce);
        String signature = LocalAutomationSigning.signature(signingSecret, canonicalPayload);

        LocalAutomationRequest request = new LocalAutomationRequest(
                LocalAutomationRequest.FROZEN_VERSION, requestId, ownerHash, channel.name(),
                action.name(), targetKey, issuedAt, expiresAt, nonce, signature);

        byte[] frame = encodeFrame(request);
        byte[] responseBody = exchange(frame);
        return parseReceipt(responseBody, request);
    }

    private void requireUsableConfiguration() {
        try {
            LocalAutomationSigning.requireUsableSecret(signingSecret);
        } catch (IllegalStateException exception) {
            throw new LocalAutomationException(ERROR_NOT_CONFIGURED,
                    "本地界面适配器未正确配置独立签名密钥；" + MANUAL_RECOVERY, exception);
        }
        if (connectTimeoutMillis < 1 || connectTimeoutMillis > 60_000
                || readTimeoutMillis < 1 || readTimeoutMillis > 60_000) {
            throw new LocalAutomationException(ERROR_NOT_CONFIGURED,
                    "本地界面适配器超时配置不安全；" + MANUAL_RECOVERY);
        }
    }

    private static void requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("本地界面动作缺少归属用户");
        }
    }

    private String nextNonce() {
        String nonce = nonceSupplier == null
                ? LocalAutomationSigning.newNonce(secureRandom) : nonceSupplier.get();
        try {
            LocalAutomationSigning.requireValidNonce(nonce);
        } catch (IllegalArgumentException exception) {
            throw new LocalAutomationException(ERROR_NOT_CONFIGURED,
                    "本地界面适配器 nonce 不符合冻结的 base64url 128 位规则；" + MANUAL_RECOVERY,
                    exception);
        }
        return nonce;
    }

    private void requireSecureSocket() {
        if (!Files.exists(socketPath) || Files.isSymbolicLink(socketPath)) {
            throw new LocalAutomationException(ERROR_UNAVAILABLE,
                    "本地界面适配器 Unix Socket 不可用；" + MANUAL_RECOVERY);
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(socketPath);
            boolean exposed = permissions.stream().anyMatch(permission -> switch (permission) {
                case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE,
                     OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE -> true;
                default -> false;
            });
            if (exposed) {
                throw new LocalAutomationException(ERROR_UNAVAILABLE,
                        "本地界面适配器 Unix Socket 权限必须限制为所有者访问；" + MANUAL_RECOVERY);
            }
        } catch (UnsupportedOperationException | IOException exception) {
            throw new LocalAutomationException(ERROR_UNAVAILABLE,
                    "无法验证本地界面适配器 Unix Socket 权限；" + MANUAL_RECOVERY, exception);
        }
    }

    private byte[] encodeFrame(LocalAutomationRequest request) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("version", request.version());
        node.put("requestId", request.requestId());
        node.put("ownerHash", request.ownerHash());
        node.put("channel", request.channel());
        node.put("action", request.action());
        node.put("targetKey", request.targetKey());
        node.put("issuedAt", request.issuedAt().toString());
        node.put("expiresAt", request.expiresAt().toString());
        node.put("nonce", request.nonce());
        node.put("signature", request.signature());
        byte[] body = objectMapper.writeValueAsBytes(node);
        if (body.length > MAX_FRAME_BYTES) {
            throw new LocalAutomationException(ERROR_PROTOCOL,
                    "本地界面请求超过 16 KiB 安全上限；" + MANUAL_RECOVERY);
        }
        byte[] frame = new byte[body.length + 1];
        System.arraycopy(body, 0, frame, 0, body.length);
        frame[body.length] = '\n';
        return frame;
    }

    private byte[] exchange(byte[] frame) {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
             Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            // Unix Domain Socket 的非阻塞连接通常立即完成，此时不会再有 OP_CONNECT 事件。
            if (!channel.connect(UnixDomainSocketAddress.of(socketPath))) {
                channel.register(selector, SelectionKey.OP_CONNECT);
                if (!awaitReady(selector, SelectionKey.OP_CONNECT) || !channel.finishConnect()) {
                    throw timeout("本地界面适配器连接超时");
                }
            }
            writeFully(channel, selector, frame);
            channel.register(selector, SelectionKey.OP_READ);
            return readFrame(channel, selector);
        } catch (LocalAutomationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new LocalAutomationException(ERROR_UNAVAILABLE,
                    "本地界面适配器 Unix Socket 调用失败；" + MANUAL_RECOVERY, exception);
        }
    }

    private void writeFully(SocketChannel channel, Selector selector, byte[] payload)
            throws IOException {
        channel.register(selector, SelectionKey.OP_WRITE);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        long deadline = deadline(readTimeoutMillis);
        while (buffer.hasRemaining()) {
            if (!awaitReady(selector, SelectionKey.OP_WRITE, deadline)) {
                throw timeout("本地界面适配器写入超时");
            }
            channel.write(buffer);
        }
    }

    /**
     * 读取一个且仅一个以换行结束的响应 JSON 文档。
     *
     * <p>冻结契约要求“一次请求只产生一个响应文档”，因此在终止换行之后：
     * 已缓冲的尾随字节只允许 JSON 空白，任何非空白字节（例如第二个换行分隔的 JSON 对象）
     * 都失败关闭。终止后不再阻塞等待新数据，避免对端保持连接时引入额外时延。</p>
     */
    private byte[] readFrame(SocketChannel channel, Selector selector) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteBuffer chunk = ByteBuffer.allocate(1024);
        long deadline = deadline(readTimeoutMillis);
        boolean terminated = false;
        int trailingPadding = 0;
        while (true) {
            if (terminated) {
                if (selector.selectNow() == 0) {
                    return body.toByteArray();
                }
            } else if (!awaitReady(selector, SelectionKey.OP_READ, deadline)) {
                throw timeout("本地界面适配器响应超时");
            }
            chunk.clear();
            int read = channel.read(chunk);
            if (read < 0) {
                if (!terminated) {
                    throw new LocalAutomationException(ERROR_PROTOCOL,
                            "本地界面适配器响应未以换行结束；" + MANUAL_RECOVERY);
                }
                return body.toByteArray();
            }
            if (read == 0) {
                if (terminated) {
                    return body.toByteArray();
                }
                continue;
            }
            byte[] bytes = chunk.array();
            for (int index = 0; index < read; index++) {
                byte current = bytes[index];
                if (terminated) {
                    if (!isJsonWhitespace(current)) {
                        throw new LocalAutomationException(ERROR_PROTOCOL,
                                "本地界面适配器响应在终止换行后仍包含非空白尾部数据；"
                                        + MANUAL_RECOVERY);
                    }
                    if (++trailingPadding > MAX_TRAILING_PADDING_BYTES) {
                        throw new LocalAutomationException(ERROR_PROTOCOL,
                                "本地界面适配器响应尾部空白填充异常；" + MANUAL_RECOVERY);
                    }
                    continue;
                }
                if (current == '\n') {
                    terminated = true;
                    continue;
                }
                body.write(current);
                if (body.size() > MAX_FRAME_BYTES) {
                    throw new LocalAutomationException(ERROR_PROTOCOL,
                            "本地界面适配器响应超过 16 KiB 安全上限；" + MANUAL_RECOVERY);
                }
            }
        }
    }

    private static boolean isJsonWhitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r';
    }

    /**
     * 严格解析回执：先校验字段集与精确 JSON 类型，再校验冻结格式，最后才做关联比较。
     *
     * <p>禁止使用 {@code asInt/asText} 的隐式强转：字符串 {@code version}、数字
     * {@code message/status/时间戳}、非字符串标识符等类型错配一律失败关闭。</p>
     */
    private LocalAutomationReceipt parseReceipt(byte[] body, LocalAutomationRequest request) {
        JsonNode node = parseSingleJsonObject(decodeUtf8(body));
        LinkedHashSet<String> unknown = new LinkedHashSet<>();
        node.propertyNames().forEach(name -> {
            if (!ALLOWED_RECEIPT_FIELDS.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw protocol("本地界面适配器回执包含未允许字段");
        }
        // 冻结回执形状要求每个命名字段都存在；errorCode 允许 JSON null，但不允许缺失。
        for (String required : REQUIRED_RECEIPT_FIELDS) {
            if (!node.has(required)) {
                throw protocol("本地界面适配器回执缺少字段: " + required);
            }
        }

        JsonNode versionNode = node.get("version");
        if (versionNode == null || !versionNode.isIntegralNumber()) {
            throw protocol("本地界面适配器回执 version 必须是整数");
        }
        String requestId = requireText(node, "requestId");
        String adapter = requireText(node, "adapter");
        String action = requireText(node, "action");
        String targetDigest = requireText(node, "targetDigest");
        String startedAtText = requireText(node, "startedAt");
        String finishedAtText = requireText(node, "finishedAt");
        String statusText = requireText(node, "status");
        String rawMessage = requireText(node, "message");
        String errorCode = optionalStableCode(node);

        if (!targetDigest.matches(LocalAutomationSigning.LOWER_HEX_64)) {
            throw protocol("本地界面适配器回执 targetDigest 必须是 64 位小写 hex");
        }

        LocalAutomationStatus status;
        try {
            status = LocalAutomationStatus.valueOf(statusText);
        } catch (IllegalArgumentException exception) {
            throw mismatch("本地界面适配器回执状态不在冻结枚举内");
        }
        if (versionNode.asInt() != LocalAutomationRequest.FROZEN_VERSION) {
            throw mismatch("本地界面适配器回执版本不匹配");
        }
        if (!request.requestId().equals(requestId)) {
            throw mismatch("本地界面适配器回执 requestId 与请求不匹配");
        }
        if (!request.channel().equals(adapter)) {
            throw mismatch("本地界面适配器回执 adapter 与请求不匹配");
        }
        if (!request.action().equals(action)) {
            throw mismatch("本地界面适配器回执 action 与请求不匹配");
        }
        String expectedDigest = LocalAutomationSigning.targetDigest(
                request.channel(), request.action(), request.targetKey());
        if (!expectedDigest.equals(targetDigest)) {
            throw mismatch("本地界面适配器回执目标摘要不匹配");
        }
        if (status == LocalAutomationStatus.SUCCEEDED && errorCode != null) {
            throw mismatch("成功的本地界面适配器回执不得携带错误码");
        }
        Instant startedAt = parseInstant(startedAtText, "startedAt");
        Instant finishedAt = parseInstant(finishedAtText, "finishedAt");
        if (finishedAt.isBefore(startedAt)) {
            throw mismatch("本地界面适配器回执时间戳不合法");
        }
        String message = sanitizeMessage(rawMessage);

        return new LocalAutomationReceipt(LocalAutomationRequest.FROZEN_VERSION,
                request.requestId(), request.channel(), request.action(), expectedDigest,
                startedAt, finishedAt, status, errorCode, message);
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw protocol("本地界面适配器回执字段类型或取值不合法: " + field);
        }
        return value.asText();
    }

    private JsonNode parseSingleJsonObject(String text) {
        try (JsonParser parser = objectMapper.createParser(text)) {
            JsonNode node = objectMapper.readTree(parser);
            if (node == null || !node.isObject()) {
                throw protocol("本地界面适配器回执必须是 JSON 对象");
            }
            if (parser.nextToken() != null) {
                throw protocol("本地界面适配器回执包含重复 JSON");
            }
            return node;
        } catch (JacksonException exception) {
            throw protocol("本地界面适配器回执不是合法 JSON");
        }
    }

    private String decodeUtf8(byte[] body) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw protocol("本地界面适配器回执不是合法 UTF-8");
        }
    }

    private static Instant parseInstant(String text, String field) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException exception) {
            throw mismatch("本地界面适配器回执时间戳不合法: " + field);
        }
    }

    private static String optionalStableCode(JsonNode node) {
        JsonNode value = node.get("errorCode");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw protocol("本地界面适配器回执 errorCode 必须是文本或 JSON null");
        }
        String code = value.asText();
        if (!code.matches(STABLE_CODE)) {
            throw protocol("本地界面适配器回执错误码不合法");
        }
        return code;
    }

    private static String sanitizeMessage(String raw) {
        String message = raw == null ? "" : raw.strip();
        if (message.isEmpty()) {
            throw protocol("本地界面适配器回执缺少可展示消息");
        }
        if (message.length() > LocalAutomationReceipt.MAX_MESSAGE_CHARS) {
            throw protocol("本地界面适配器回执消息超过 200 字符上限");
        }
        for (int index = 0; index < message.length(); index++) {
            if (Character.isISOControl(message.charAt(index))) {
                throw protocol("本地界面适配器回执消息包含控制字符");
            }
        }
        String lowered = message.toLowerCase();
        for (String fragment : FORBIDDEN_MESSAGE_FRAGMENTS) {
            if (lowered.contains(fragment)) {
                throw protocol("本地界面适配器回执消息包含被禁止的内容");
            }
        }
        if (message.matches("(?s).*" + ABSOLUTE_PATH + ".*")) {
            throw protocol("本地界面适配器回执消息包含文件路径");
        }
        return message;
    }

    private static long deadline(int timeoutMillis) {
        return System.nanoTime() + timeoutMillis * 1_000_000L;
    }

    private boolean awaitReady(Selector selector, int operation, long deadlineNanos)
            throws IOException {
        while (true) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            int selected = selector.select(Math.max(1L, remaining / 1_000_000L));
            if (selected > 0) {
                boolean ready = selector.selectedKeys().stream()
                        .anyMatch(key -> key.isValid() && (key.readyOps() & operation) != 0);
                selector.selectedKeys().clear();
                if (ready) {
                    return true;
                }
            }
        }
    }

    private static LocalAutomationException timeout(String message) {
        return new LocalAutomationException(ERROR_TIMEOUT, message + "；" + MANUAL_RECOVERY);
    }

    private static LocalAutomationException protocol(String message) {
        return new LocalAutomationException(ERROR_PROTOCOL, message + "；" + MANUAL_RECOVERY);
    }

    private static LocalAutomationException mismatch(String message) {
        return new LocalAutomationException(ERROR_MISMATCH, message + "；" + MANUAL_RECOVERY);
    }

    /** 连接阶段使用独立的连接超时，因此单独暴露一个重载。 */
    private boolean awaitReady(Selector selector, int operation) throws IOException {
        return awaitReady(selector, operation, deadline(connectTimeoutMillis));
    }
}
