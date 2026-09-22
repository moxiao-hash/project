package com.moxiao.studypilot.agent.developer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 33：Unix Domain Socket 客户端的传输、签名、超时、权限与响应关联边界。
 *
 * <p>对端为 {@link LocalAutomationStubServer} 测试替身：ZCode 的真实本地服务尚未就绪，
 * 但契约允许先用桩验证 Java 侧行为，随后再做最小真实联调。</p>
 */
class UnixSocketLocalAutomationClientTest {

    private static final String SECRET = "task-33-local-automation-test-secret-key";
    private static final String OWNER_ID = "owner-8f3c9d21";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC);
    private static final String FIXED_NONCE = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final Invocation DEFAULT_INVOCATION = new Invocation(
            InterfaceAutomationChannel.PLAYWRIGHT_DOM, LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE,
            "ASSISTANT");

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final java.util.concurrent.atomic.AtomicInteger socketCounter =
            new java.util.concurrent.atomic.AtomicInteger();

    @TempDir
    Path tempDir;

    private Path socketDir;

    @org.junit.jupiter.api.BeforeEach
    void createShortSocketDirectory() throws java.io.IOException {
        socketDir = Files.createTempDirectory("s33");
    }

    @org.junit.jupiter.api.AfterEach
    void removeShortSocketDirectory() throws java.io.IOException {
        if (socketDir == null || !Files.exists(socketDir)) {
            return;
        }
        try (var entries = Files.list(socketDir)) {
            for (Path entry : entries.toList()) {
                Files.deleteIfExists(entry);
            }
        }
        Files.deleteIfExists(socketDir);
    }

    private record Invocation(InterfaceAutomationChannel channel, LocalInterfaceAction action,
                              String targetKey) { }

    @Test
    void sendsExactlyTheFrozenSignedRequestAndReturnsTheValidatedReceipt() {
        AtomicBoolean signatureValid = new AtomicBoolean();
        AtomicBoolean windowValid = new AtomicBoolean();
        AtomicBoolean ownerHashOnly = new AtomicBoolean();
        Path socket = socketPath();
        try (LocalAutomationStubServer server = LocalAutomationStubServer.ownerOnly(socket, request -> {
            JsonNode received = mapper.readTree(request);
            signatureValid.set(signatureMatches(request, SECRET));
            windowValid.set(windowIsValid(received));
            ownerHashOnly.set(received.path("version").asInt() == 1
                    && !received.has("ownerId")
                    && received.path("ownerHash").asText()
                    .equals(LocalAutomationSigning.ownerHash(OWNER_ID)));
            return validReceipt(received).toString().getBytes(StandardCharsets.UTF_8);
        })) {
            LocalAutomationReceipt receipt = client(socket.toString(), SECRET)
                    .invoke(InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                            LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT", OWNER_ID);

            assertEquals(LocalAutomationStatus.SUCCEEDED, receipt.status());
            assertEquals(1, receipt.version());
            assertEquals("PLAYWRIGHT_DOM", receipt.adapter());
            assertEquals("OPEN_STUDYPILOT_ROUTE", receipt.action());
            assertEquals(LocalAutomationSigning.targetDigest(
                            "PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE", "ASSISTANT"),
                    receipt.targetDigest());
            assertNotNull(receipt.startedAt());
            assertNotNull(receipt.finishedAt());

            assertTrue(signatureValid.get(), "客户端签名必须可被对端独立验签");
            assertTrue(windowValid.get(), "请求时间窗必须在 60 秒内且未过期");
            assertTrue(ownerHashOnly.get(), "ownerId 只允许以 sha256 形式发送");
        }
    }

    @Test
    void requestFrameIsOneUtf8JsonLineWithExactlyTheFrozenFields() throws Exception {
        Path socket = socketPath();
        try (LocalAutomationStubServer server = LocalAutomationStubServer.ownerOnly(
                socket, request -> validReceipt(mapper.readTree(request))
                        .toString().getBytes(StandardCharsets.UTF_8))) {
            client(socket.toString(), SECRET).invoke(InterfaceAutomationChannel.IDEA_ACCESSIBILITY,
                    LocalInterfaceAction.OPEN_REGISTERED_FILE, "SOURCE_PRIMARY", OWNER_ID);
            assertTrue(server.awaitConnections(2_000), "对端必须收到一帧请求");

            byte[] raw = server.receivedRaw().get(0);
            assertTrue(server.wasNewlineTerminated(0), "请求必须以换行结束");
            assertTrue(raw.length <= 16 * 1024, "请求不得超过 16 KiB");
            assertEquals(-1, new String(raw, StandardCharsets.UTF_8).indexOf('\n'),
                    "请求帧内不得出现第二个换行");

            JsonNode request = mapper.readTree(server.received().get(0));
            Set<String> fields = new LinkedHashSet<>();
            request.propertyNames().forEach(fields::add);
            assertEquals(Set.of("version", "requestId", "ownerHash", "channel", "action",
                    "targetKey", "issuedAt", "expiresAt", "nonce", "signature"), fields);
            assertEquals(1, request.path("version").asInt());
            assertEquals("IDEA_ACCESSIBILITY", request.path("channel").asText());
            assertEquals("OPEN_REGISTERED_FILE", request.path("action").asText());
            assertEquals("SOURCE_PRIMARY", request.path("targetKey").asText());
            assertEquals(FIXED_NONCE, request.path("nonce").asText());
            assertEquals("2026-09-22T00:00:00Z", request.path("issuedAt").asText());
            assertEquals("2026-09-22T00:01:00Z", request.path("expiresAt").asText());
            assertTrue(request.path("signature").asText().matches("[0-9a-f]{64}"));
        }
    }

    @Test
    void neverForwardsArbitraryUrlSelectorScriptPathOrShellFields() throws Exception {
        Path socket = socketPath();
        try (LocalAutomationStubServer server = LocalAutomationStubServer.ownerOnly(
                socket, request -> validReceipt(mapper.readTree(request))
                        .toString().getBytes(StandardCharsets.UTF_8))) {
            client(socket.toString(), SECRET).invoke(InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                    LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT", OWNER_ID);
            assertTrue(server.awaitConnections(2_000));
            String frame = server.received().get(0);
            for (String forbidden : new String[]{
                    "\"url\"", "\"host\"", "\"port\"", "\"selector\"", "\"xpath\"",
                    "\"script\"", "\"html\"", "\"shell\"", "\"keyCode\"", "\"text\"",
                    "\"path\"", "\"windowTitle\"", "\"pid\"", "\"command\"", "\"ownerId\""}) {
                assertFalse(frame.contains(forbidden), "禁止出现任意控制字段: " + forbidden);
            }
        }
    }

    @Test
    void frozenEnumsCannotBeWidenedIntoGenericComputerControl() {
        assertEquals(Set.of("PLAYWRIGHT_DOM", "IDEA_ACCESSIBILITY", "BUSINESS_API"),
                Arrays.stream(InterfaceAutomationChannel.values())
                        .map(Enum::name).collect(Collectors.toSet()));
        assertEquals(Set.of("OPEN_STUDYPILOT_ROUTE", "FOCUS_AGENT_INPUT", "OPEN_RESULT_PANEL",
                        "OPEN_REGISTERED_FILE", "FOCUS_RUN_CONFIGURATION", "SHOW_TEST_RESULT"),
                Arrays.stream(LocalInterfaceAction.values())
                        .map(Enum::name).collect(Collectors.toSet()));
    }

    @Test
    void refusesToSignWithShortOrPlaceholderSecretsBeforeConnecting() throws Exception {
        Path socket = socketPath();
        try (LocalAutomationStubServer server = LocalAutomationStubServer.ownerOnly(
                socket, request -> new byte[0])) {
            for (String secret : new String[]{"too-short", "c".repeat(31),
                    LocalAutomationSigning.DOCUMENTED_DEFAULT_SECRET}) {
                LocalAutomationException exception = assertThrows(LocalAutomationException.class,
                        () -> client(socket.toString(), secret).invoke(
                                InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                                LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT", OWNER_ID));
                assertEquals(LocalAutomationClient.ERROR_NOT_CONFIGURED, exception.errorCode());
            }
            assertFalse(server.awaitConnections(400), "不安全密钥下不得发起连接");
        }
    }

    @Test
    void refusesWorldAccessibleSocketsBeforeConnecting() throws Exception {
        Path socket = socketPath();
        try (LocalAutomationStubServer server = LocalAutomationStubServer.worldAccessible(socket)) {
            LocalAutomationException exception = assertThrows(LocalAutomationException.class,
                    () -> client(socket.toString(), SECRET).invoke(
                            InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                            LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT", OWNER_ID));
            assertEquals(LocalAutomationClient.ERROR_UNAVAILABLE, exception.errorCode());
            assertTrue(exception.getMessage().contains("手动"), "必须给出人工恢复提示");
            assertFalse(server.awaitConnections(400), "权限过宽的 Socket 不得被连接");
        }
    }

    @Test
    void failsClosedWithManualRecoveryWhenTheSocketIsMissing() {
        Path missing = tempDir.resolve("absent.sock");

        LocalAutomationException exception = assertThrows(LocalAutomationException.class,
                () -> client(missing.toString(), SECRET).invoke(
                        InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                        LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT", OWNER_ID));

        assertEquals(LocalAutomationClient.ERROR_UNAVAILABLE, exception.errorCode());
        assertTrue(exception.getMessage().contains("手动"));
        assertFalse(exception.getMessage().contains(OWNER_ID), "错误信息不得回显用户标识");
    }

    @Test
    void failsClosedOnReadTimeoutWithoutClaimingSuccess() throws Exception {
        Path socket = socketPath();
        try (LocalAutomationStubServer server = LocalAutomationStubServer.silent(socket)) {
            LocalAutomationException exception = assertThrows(LocalAutomationException.class,
                    () -> client(socket.toString(), SECRET).invoke(
                            InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                            LocalInterfaceAction.FOCUS_AGENT_INPUT, "ASSISTANT_INPUT", OWNER_ID));

            assertEquals(LocalAutomationClient.ERROR_TIMEOUT, exception.errorCode());
            assertTrue(exception.getMessage().contains("手动"));
            assertTrue(server.awaitConnections(2_000), "超时用例必须先建立连接");
        }
    }

    @Test
    void rejectsOverlongResponseFramesBeyond16KiB() {
        LocalAutomationException exception = exchangeExpectingFailure(
                request -> ("{\"version\":1,\"message\":\"" + "a".repeat(20_000) + "\"}")
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals(LocalAutomationClient.ERROR_PROTOCOL, exception.errorCode());
    }

    @Test
    void rejectsInvalidUtf8Responses() {
        byte[] invalid = new byte[]{'{', '"', 'a', '"', ':', '"', (byte) 0xFF, (byte) 0xFE,
                '"', '}'};
        LocalAutomationException exception = exchangeExpectingFailure(request -> invalid);

        assertEquals(LocalAutomationClient.ERROR_PROTOCOL, exception.errorCode());
    }

    @Test
    void rejectsDuplicateJsonDocumentsInOneFrame() {
        LocalAutomationException exception = exchangeExpectingFailure(request -> {
            String receipt = validReceipt(mapper.readTree(request)).toString();
            return (receipt + receipt).getBytes(StandardCharsets.UTF_8);
        });

        assertEquals(LocalAutomationClient.ERROR_PROTOCOL, exception.errorCode());
    }

    @Test
    void rejectsResponsesCarryingUnexpectedOrPrivacyBearingFields() {
        for (String extra : new String[]{"screenshot", "dom", "windowTitle", "filePath", "url",
                "userInput", "stack", "secret", "pageContent"}) {
            LocalAutomationException exception = exchangeExpectingFailure(request -> {
                ObjectNode receipt = validReceipt(mapper.readTree(request));
                receipt.put(extra, "leaked");
                return receipt.toString().getBytes(StandardCharsets.UTF_8);
            });
            assertEquals(LocalAutomationClient.ERROR_PROTOCOL, exception.errorCode(),
                    "回执必须拒绝未允许字段: " + extra);
        }
    }

    @Test
    void rejectsResponsesThatDoNotCorrelateWithTheRequest() {
        assertMismatch(request -> {
            ObjectNode receipt = validReceipt(mapper.readTree(request));
            receipt.put("requestId", UUID.randomUUID().toString());
            return receipt;
        });
        assertMismatch(request -> {
            ObjectNode receipt = validReceipt(mapper.readTree(request));
            receipt.put("adapter", "IDEA_ACCESSIBILITY");
            return receipt;
        });
        assertMismatch(request -> {
            ObjectNode receipt = validReceipt(mapper.readTree(request));
            receipt.put("action", "OPEN_RESULT_PANEL");
            return receipt;
        });
        assertMismatch(request -> {
            ObjectNode receipt = validReceipt(mapper.readTree(request));
            receipt.put("targetDigest", "0".repeat(64));
            return receipt;
        });
        assertMismatch(request -> {
            ObjectNode receipt = validReceipt(mapper.readTree(request));
            receipt.put("status", "OK");
            return receipt;
        });
        assertMismatch(request -> {
            ObjectNode receipt = validReceipt(mapper.readTree(request));
            receipt.put("version", 2);
            return receipt;
        });
    }

    @Test
    void rejectsUnsanitizedOrOverlongReceiptMessages() {
        for (String message : new String[]{
                "a".repeat(201),
                "打开失败\n堆栈: java.lang.IllegalStateException",
                "失败 https://internal.example.com/debug",
                "失败 C:\\Users\\moxiao\\.ssh\\id_rsa"}) {
            LocalAutomationException exception = exchangeExpectingFailure(request -> {
                ObjectNode receipt = validReceipt(mapper.readTree(request));
                receipt.put("message", message);
                return receipt.toString().getBytes(StandardCharsets.UTF_8);
            });
            assertEquals(LocalAutomationClient.ERROR_PROTOCOL, exception.errorCode(),
                    "回执 message 必须被清洗与限长");
        }
    }

    @Test
    void surfacesRejectedAndFailedReceiptsWithoutClaimingSuccess() {
        LocalAutomationReceipt rejected = exchange(request -> {
            ObjectNode receipt = validReceipt(mapper.readTree(request));
            receipt.put("status", "REJECTED");
            receipt.put("errorCode", "REQUEST_REJECTED");
            receipt.put("message", "请求在产生界面副作用前被拒绝");
            return receipt.toString().getBytes(StandardCharsets.UTF_8);
        });
        assertEquals(LocalAutomationStatus.REJECTED, rejected.status());
        assertEquals("REQUEST_REJECTED", rejected.errorCode());
        assertNotNull(rejected.startedAt());
        assertNotNull(rejected.finishedAt());

        LocalAutomationReceipt failed = exchange(request -> {
            ObjectNode receipt = validReceipt(mapper.readTree(request));
            receipt.put("status", "FAILED");
            receipt.put("errorCode", "ADAPTER_FAILED");
            receipt.put("message", "目标窗口未就绪");
            return receipt.toString().getBytes(StandardCharsets.UTF_8);
        });
        assertEquals(LocalAutomationStatus.FAILED, failed.status());
        assertEquals("ADAPTER_FAILED", failed.errorCode());
    }

    @Test
    void surfacesExpiredAndFutureDatedRejectionsFromTheService() {
        for (String code : new String[]{"REQUEST_EXPIRED", "REQUEST_NOT_YET_VALID"}) {
            LocalAutomationReceipt receipt = exchange(request -> {
                ObjectNode response = validReceipt(mapper.readTree(request));
                response.put("status", "REJECTED");
                response.put("errorCode", code);
                response.put("message", "请求时间窗不合法");
                return response.toString().getBytes(StandardCharsets.UTF_8);
            });
            assertEquals(LocalAutomationStatus.REJECTED, receipt.status());
            assertEquals(code, receipt.errorCode());
        }
    }

    @Test
    void requestWindowIsExactlySixtySecondsAndNeverLonger() throws Exception {
        Path socket = socketPath();
        try (LocalAutomationStubServer server = LocalAutomationStubServer.ownerOnly(
                socket, request -> validReceipt(mapper.readTree(request))
                        .toString().getBytes(StandardCharsets.UTF_8))) {
            LocalAutomationReceipt receipt = client(socket.toString(), SECRET).invoke(
                    InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                    LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT", OWNER_ID);
            assertEquals(LocalAutomationStatus.SUCCEEDED, receipt.status());
            assertTrue(server.awaitConnections(2_000));

            JsonNode request = mapper.readTree(server.received().get(0));
            Instant issuedAt = Instant.parse(request.path("issuedAt").asText());
            Instant expiresAt = Instant.parse(request.path("expiresAt").asText());
            assertEquals(Instant.parse("2026-09-22T00:00:00Z"), issuedAt);
            assertEquals(60, expiresAt.getEpochSecond() - issuedAt.getEpochSecond());
            assertFalse(expiresAt.isAfter(issuedAt.plusSeconds(60)));
            assertTrue(windowIsValid(request));
        }
    }

    @Test
    void noncesAreFreshPerRequestAndReplayStaysRejectedAcrossServiceRestart() throws Exception {
        Path store = tempDir.resolve("consumed-nonces.txt");
        Path socket = socketPath();

        try (LocalAutomationStubServer server = LocalAutomationStubServer.ownerOnly(
                socket, noncePersistingResponder(store))) {
            LocalAutomationReceipt first = client(socket.toString(), SECRET).invoke(
                    InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                    LocalInterfaceAction.FOCUS_AGENT_INPUT, "ASSISTANT_INPUT", OWNER_ID);
            assertEquals(LocalAutomationStatus.SUCCEEDED, first.status());
            assertTrue(Files.readAllLines(store).contains(FIXED_NONCE), "nonce 必须被持久化消费");
        }

        // 模拟本地服务重启：nonce 已持久化，重放必须仍然被拒绝。
        Path restartedSocket = socketPath();
        try (LocalAutomationStubServer restarted = LocalAutomationStubServer.ownerOnly(
                restartedSocket, noncePersistingResponder(store))) {
            LocalAutomationReceipt replay = client(restartedSocket.toString(), SECRET).invoke(
                    InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                    LocalInterfaceAction.FOCUS_AGENT_INPUT, "ASSISTANT_INPUT", OWNER_ID);
            assertEquals(LocalAutomationStatus.REJECTED, replay.status());
            assertEquals("NONCE_REPLAY", replay.errorCode());
        }
    }

    @Test
    void productionNonceSupplierNeverReusesANonceAcrossRequests() throws Exception {
        Path socket = socketPath();
        SecureRandom random = new SecureRandom();
        try (LocalAutomationStubServer server = LocalAutomationStubServer.ownerOnly(
                socket, request -> validReceipt(mapper.readTree(request))
                        .toString().getBytes(StandardCharsets.UTF_8), 3)) {
            UnixSocketLocalAutomationClient freshNonceClient =
                    new UnixSocketLocalAutomationClient(socket.toString(), SECRET, 1_000, 1_000,
                            mapper, CLOCK, () -> LocalAutomationSigning.newNonce(random));
            for (int index = 0; index < 3; index++) {
                assertEquals(LocalAutomationStatus.SUCCEEDED, freshNonceClient.invoke(
                        InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                        LocalInterfaceAction.OPEN_RESULT_PANEL, "WORKSPACE_RESULTS", OWNER_ID).status());
            }
            assertTrue(server.awaitConnections(2_000));
            List<String> nonces = new ArrayList<>();
            server.received().forEach(frame -> nonces.add(
                    mapper.readTree(frame).path("nonce").asText()));
            assertEquals(3, nonces.size());
            assertEquals(3, Set.copyOf(nonces).size(), "nonce 必须每次新生成");
        }
    }

    @Test
    void rejectsReceiptsThatOmitTheErrorCodeFieldEntirely() {
        // 冻结回执形状要求每个命名字段都存在；缺失 errorCode 与值为 null 是两回事。
        LocalAutomationException exception = exchangeExpectingFailure(request -> {
            ObjectNode receipt = validReceipt(mapper.readTree(request));
            receipt.remove("errorCode");
            return receipt.toString().getBytes(StandardCharsets.UTF_8);
        });

        assertEquals(LocalAutomationClient.ERROR_PROTOCOL, exception.errorCode());
    }

    @Test
    void rejectsReceiptFieldsWithWrongJsonTypesInsteadOfCoercingThem() {
        record Mistype(String field, java.util.function.Consumer<ObjectNode> tamper) { }
        List<Mistype> mistypes = List.of(
                new Mistype("version(string)", receipt -> receipt.put("version", "1")),
                new Mistype("version(fraction)", receipt -> receipt.put("version", 1.5)),
                new Mistype("requestId(number)", receipt -> receipt.put("requestId", 123)),
                new Mistype("adapter(number)", receipt -> receipt.put("adapter", 1)),
                new Mistype("action(boolean)", receipt -> receipt.put("action", true)),
                new Mistype("targetDigest(number)", receipt -> receipt.put("targetDigest", 12345)),
                new Mistype("startedAt(number)", receipt -> receipt.put("startedAt", 20260922)),
                new Mistype("finishedAt(null)", receipt -> receipt.putNull("finishedAt")),
                new Mistype("status(number)", receipt -> receipt.put("status", 1)),
                new Mistype("message(number)", receipt -> receipt.put("message", 42)),
                new Mistype("errorCode(number)", receipt -> receipt.put("errorCode", 5)));

        for (Mistype mistype : mistypes) {
            LocalAutomationException exception = exchangeExpectingFailure(request -> {
                ObjectNode receipt = validReceipt(mapper.readTree(request));
                mistype.tamper().accept(receipt);
                return receipt.toString().getBytes(StandardCharsets.UTF_8);
            });
            assertEquals(LocalAutomationClient.ERROR_PROTOCOL, exception.errorCode(),
                    "回执字段类型不合法必须失败关闭而不是强转: " + mistype.field());
        }
    }

    @Test
    void rejectsMalformedTargetDigestFormatBeforeComparison() {
        for (String digest : new String[]{
                LocalAutomationSigning.targetDigest("PLAYWRIGHT_DOM", "OPEN_STUDYPILOT_ROUTE",
                        "ASSISTANT").toUpperCase(),
                "d".repeat(63),
                "d".repeat(65),
                "z".repeat(64),
                "0x" + "d".repeat(62)}) {
            LocalAutomationException exception = exchangeExpectingFailure(request -> {
                ObjectNode receipt = validReceipt(mapper.readTree(request));
                receipt.put("targetDigest", digest);
                return receipt.toString().getBytes(StandardCharsets.UTF_8);
            });
            assertEquals(LocalAutomationClient.ERROR_PROTOCOL, exception.errorCode(),
                    "targetDigest 必须是 64 位小写 hex: " + digest);
        }
    }

    @Test
    void rejectsNonWhitespaceTrailingDataAfterTheReceiptDocument() {
        List<Function<String, String>> trailers = List.of(
                receipt -> receipt + "\n" + receipt,
                receipt -> receipt + "\n" + "{}",
                receipt -> receipt + "\n" + "JUNK",
                receipt -> receipt + "  {\"version\":1}");

        for (Function<String, String> trailer : trailers) {
            LocalAutomationException exception = exchangeExpectingFailure(request -> trailer
                    .apply(validReceipt(mapper.readTree(request)).toString())
                    .getBytes(StandardCharsets.UTF_8));
            assertEquals(LocalAutomationClient.ERROR_PROTOCOL, exception.errorCode(),
                    "一次请求只能产生一个响应 JSON 文档，尾部非空白字节必须失败关闭");
        }
    }

    @Test
    void toleratesWhitespaceOnlyFramingPaddingAfterTheReceiptDocument() {
        LocalAutomationReceipt receipt = exchange(request -> (validReceipt(mapper.readTree(request))
                + "\n  \t\n").getBytes(StandardCharsets.UTF_8));

        assertEquals(LocalAutomationStatus.SUCCEEDED, receipt.status());
    }

    @Test
    void rejectsAnUnboundedWhitespaceFloodAfterTheReceiptDocument() {
        LocalAutomationException exception = exchangeExpectingFailure(request ->
                (validReceipt(mapper.readTree(request)) + "\n" + " ".repeat(4_000))
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals(LocalAutomationClient.ERROR_PROTOCOL, exception.errorCode());
    }

    @Test
    void rejectsInjectedNoncesThatBreakTheFrozenBase64Url128BitRule() {
        for (String nonce : new String[]{
                "AAAAAAAAAAAAAAAAAAAAAA==",
                "AAAA+AAAAA/AAAAAAAAAAAA",
                "AAAA",
                "not base64url!!",
                "###",
                ""}) {
            Path socket = socketPath();
            try (LocalAutomationStubServer server = LocalAutomationStubServer.ownerOnly(
                    socket, request -> validReceipt(mapper.readTree(request))
                            .toString().getBytes(StandardCharsets.UTF_8))) {
                LocalAutomationException exception = assertThrows(LocalAutomationException.class,
                        () -> new UnixSocketLocalAutomationClient(socket.toString(), SECRET,
                                1_000, 1_000, mapper, CLOCK, () -> nonce).invoke(
                                DEFAULT_INVOCATION.channel(), DEFAULT_INVOCATION.action(),
                                DEFAULT_INVOCATION.targetKey(), OWNER_ID));
                assertEquals(LocalAutomationClient.ERROR_NOT_CONFIGURED, exception.errorCode(),
                        "非冻结格式的 nonce 必须失败关闭: " + nonce);
            }
        }
    }

    @Test
    void correlatesTheResponseToTheActualRequestId() throws Exception {
        Path socket = socketPath();
        try (LocalAutomationStubServer server = LocalAutomationStubServer.ownerOnly(
                socket, request -> validReceipt(mapper.readTree(request))
                        .toString().getBytes(StandardCharsets.UTF_8))) {
            LocalAutomationReceipt receipt = client(socket.toString(), SECRET).invoke(
                    InterfaceAutomationChannel.IDEA_ACCESSIBILITY,
                    LocalInterfaceAction.SHOW_TEST_RESULT, "TEST_LATEST", OWNER_ID);
            assertTrue(server.awaitConnections(2_000));
            JsonNode request = mapper.readTree(server.received().get(0));
            assertEquals(request.path("requestId").asText(), receipt.requestId());
            assertNotEquals("", receipt.requestId());
        }
    }

    // ---------- helpers ----------

    private Path socketPath() {
        // Unix Domain Socket 的 sun_path 上限约 104 字节，因此使用短目录与短文件名。
        return socketDir.resolve("s" + socketCounter.incrementAndGet() + ".sock");
    }

    private UnixSocketLocalAutomationClient client(String socketPath, String secret) {
        return new UnixSocketLocalAutomationClient(
                socketPath, secret, 1_000, 1_000, mapper, CLOCK, () -> FIXED_NONCE);
    }

    private LocalAutomationReceipt exchange(Function<String, byte[]> responder) {
        Path socket = socketPath();
        try (LocalAutomationStubServer server = LocalAutomationStubServer.ownerOnly(socket, responder)) {
            return client(socket.toString(), SECRET).invoke(DEFAULT_INVOCATION.channel(),
                    DEFAULT_INVOCATION.action(), DEFAULT_INVOCATION.targetKey(), OWNER_ID);
        }
    }

    private LocalAutomationException exchangeExpectingFailure(Function<String, byte[]> responder) {
        Path socket = socketPath();
        try (LocalAutomationStubServer server = LocalAutomationStubServer.ownerOnly(socket, responder)) {
            return assertThrows(LocalAutomationException.class, () ->
                    client(socket.toString(), SECRET).invoke(DEFAULT_INVOCATION.channel(),
                            DEFAULT_INVOCATION.action(), DEFAULT_INVOCATION.targetKey(), OWNER_ID));
        }
    }

    private void assertMismatch(Function<String, ObjectNode> tamper) {
        LocalAutomationException exception = exchangeExpectingFailure(
                request -> tamper.apply(request).toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(LocalAutomationClient.ERROR_MISMATCH, exception.errorCode());
    }

    private ObjectNode validReceipt(JsonNode request) {
        ObjectNode receipt = mapper.createObjectNode();
        receipt.put("version", 1);
        receipt.put("requestId", request.path("requestId").asText());
        receipt.put("adapter", request.path("channel").asText());
        receipt.put("action", request.path("action").asText());
        receipt.put("targetDigest", LocalAutomationSigning.targetDigest(
                request.path("channel").asText(), request.path("action").asText(),
                request.path("targetKey").asText()));
        receipt.put("startedAt", "2026-09-22T00:00:00Z");
        receipt.put("finishedAt", "2026-09-22T00:00:01Z");
        receipt.put("status", "SUCCEEDED");
        // 冻结回执形状要求所有命名字段都存在，errorCode 以 JSON null 表示“无错误码”。
        receipt.putNull("errorCode");
        receipt.put("message", "已打开已注册的本地目标");
        return receipt;
    }

    private Function<String, byte[]> noncePersistingResponder(Path store) {
        return request -> {
            JsonNode received = mapper.readTree(request);
            String nonce = received.path("nonce").asText();
            try {
                List<String> consumed = Files.exists(store)
                        ? Files.readAllLines(store) : new ArrayList<>();
                if (consumed.contains(nonce)) {
                    ObjectNode rejected = validReceipt(received);
                    rejected.put("status", "REJECTED");
                    rejected.put("errorCode", "NONCE_REPLAY");
                    rejected.put("message", "检测到重放请求");
                    return rejected.toString().getBytes(StandardCharsets.UTF_8);
                }
                Files.write(store, List.of(nonce));
                return validReceipt(received).toString().getBytes(StandardCharsets.UTF_8);
            } catch (java.io.IOException exception) {
                throw new UncheckedIOException(exception);
            }
        };
    }

    private boolean windowIsValid(JsonNode request) {
        Instant issuedAt = Instant.parse(request.path("issuedAt").asText());
        Instant expiresAt = Instant.parse(request.path("expiresAt").asText());
        Instant now = CLOCK.instant();
        return !expiresAt.isAfter(issuedAt.plusSeconds(60))
                && expiresAt.isAfter(now)
                && !issuedAt.isAfter(now.plusSeconds(10));
    }

    /** 与 Java 生产实现独立的契约侧验签：固定字段顺序 + len#value 长度前缀。 */
    private boolean signatureMatches(String request, String secret) {
        JsonNode node = mapper.readTree(request);
        String[] values = {
                node.path("version").asText(), node.path("requestId").asText(),
                node.path("ownerHash").asText(), node.path("channel").asText(),
                node.path("action").asText(), node.path("targetKey").asText(),
                node.path("issuedAt").asText(), node.path("expiresAt").asText(),
                node.path("nonce").asText()};
        StringBuilder payload = new StringBuilder();
        for (String value : values) {
            payload.append(value.length()).append('#').append(value);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(
                    mac.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8)));
            return expected.equals(node.path("signature").asText());
        } catch (Exception exception) {
            return false;
        }
    }
}
