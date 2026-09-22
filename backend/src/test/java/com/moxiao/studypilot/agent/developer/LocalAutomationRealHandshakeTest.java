package com.moxiao.studypilot.agent.developer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 33：真实 Java↔TypeScript Unix Domain Socket 握手。
 *
 * <p>对端是 ZCode 已交付的**真实** `local-automation-service`（编译产物），通过
 * `backend/src/test/resources/local-automation/handshake-server.mjs` 启动；Java 侧使用
 * 真实 {@link UnixSocketLocalAutomationClient}，**不使用** {@code LocalAutomationStubServer}。</p>
 *
 * <p>**证据口径**：对端的界面适配器是注入的测试替身，因此本测试只证明
 * “真实跨语言 UDS 传输、签名、白名单、nonce、封帧与回执关联一致”，
 * **不**证明真实浏览器/IDE 界面状态，**不得**标记为 OS 界面 `REAL_E2E`。</p>
 *
 * <p>未配置系统属性时整体跳过，因此常规 `./mvnw test` 不会尝试连接本地服务。</p>
 */
class LocalAutomationRealHandshakeTest {

    private static final String OWNER_ID = "owner-real-handshake";
    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private String socketPath;
    private String signingSecret;

    @BeforeEach
    void requireExplicitOptIn() {
        Assumptions.assumeTrue(Boolean.parseBoolean(
                        System.getProperty("spl.handshake.enabled", "false")),
                "未启用真实握手（需要 -Dspl.handshake.enabled=true）");
        socketPath = System.getProperty("spl.handshake.socket", "");
        signingSecret = System.getProperty("spl.handshake.secret", "");
        Assumptions.assumeTrue(!socketPath.isBlank() && !signingSecret.isBlank(),
                "真实握手需要 -Dspl.handshake.socket 与 -Dspl.handshake.secret");
    }

    @Test
    void opensTheFrozenAssistantRouteThroughTheRealService() {
        LocalAutomationReceipt receipt = invoke(InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT");

        assertEquals(LocalAutomationStatus.SUCCEEDED, receipt.status());
        assertEquals("PLAYWRIGHT_DOM", receipt.adapter());
        assertEquals("OPEN_STUDYPILOT_ROUTE", receipt.action());
        assertCorrelatedWithTheRequest(receipt, InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT");
        assertTrue(receipt.startedAt().isBefore(receipt.finishedAt())
                || receipt.startedAt().equals(receipt.finishedAt()));
    }

    @Test
    void focusesTheAgentInputAndOpensTheResultPanelThroughTheRealService() {
        LocalAutomationReceipt focus = invoke(InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                LocalInterfaceAction.FOCUS_AGENT_INPUT, "ASSISTANT_INPUT");
        assertEquals(LocalAutomationStatus.SUCCEEDED, focus.status());
        assertCorrelatedWithTheRequest(focus, InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                LocalInterfaceAction.FOCUS_AGENT_INPUT, "ASSISTANT_INPUT");

        LocalAutomationReceipt panel = invoke(InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                LocalInterfaceAction.OPEN_RESULT_PANEL, "WORKSPACE_RESULTS");
        assertEquals(LocalAutomationStatus.SUCCEEDED, panel.status());
        assertCorrelatedWithTheRequest(panel, InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                LocalInterfaceAction.OPEN_RESULT_PANEL, "WORKSPACE_RESULTS");
    }

    @Test
    void showsTheFrozenIdeTargetsThroughTheRealService() {
        LocalAutomationReceipt file = invoke(InterfaceAutomationChannel.IDEA_ACCESSIBILITY,
                LocalInterfaceAction.OPEN_REGISTERED_FILE, "SOURCE_PRIMARY");
        assertEquals(LocalAutomationStatus.SUCCEEDED, file.status());
        assertCorrelatedWithTheRequest(file, InterfaceAutomationChannel.IDEA_ACCESSIBILITY,
                LocalInterfaceAction.OPEN_REGISTERED_FILE, "SOURCE_PRIMARY");

        LocalAutomationReceipt runConfig = invoke(InterfaceAutomationChannel.IDEA_ACCESSIBILITY,
                LocalInterfaceAction.FOCUS_RUN_CONFIGURATION, "RUN_DEFAULT");
        assertEquals(LocalAutomationStatus.SUCCEEDED, runConfig.status());

        LocalAutomationReceipt testResult = invoke(InterfaceAutomationChannel.IDEA_ACCESSIBILITY,
                LocalInterfaceAction.SHOW_TEST_RESULT, "TEST_LATEST");
        assertEquals(LocalAutomationStatus.SUCCEEDED, testResult.status());
    }

    @Test
    void realServiceRejectsUnregisteredTargetsAndCombinations() {
        LocalAutomationReceipt unknownTarget = invoke(InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT_HEALTH_UNKNOWN");
        assertEquals(LocalAutomationStatus.REJECTED, unknownTarget.status());
        assertNotNull(unknownTarget.errorCode());
        assertCorrelatedWithTheRequest(unknownTarget, InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT_HEALTH_UNKNOWN");

        LocalAutomationReceipt wrongTargetForAction = invoke(
                InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                LocalInterfaceAction.OPEN_RESULT_PANEL, "ASSISTANT");
        assertEquals(LocalAutomationStatus.REJECTED, wrongTargetForAction.status());
        assertNotEquals("", wrongTargetForAction.errorCode());

        LocalAutomationReceipt unregisteredIdeHandle = invoke(
                InterfaceAutomationChannel.IDEA_ACCESSIBILITY,
                LocalInterfaceAction.OPEN_REGISTERED_FILE, "NOT_REGISTERED_HANDLE");
        assertEquals(LocalAutomationStatus.REJECTED, unregisteredIdeHandle.status());
        assertCorrelatedWithTheRequest(unregisteredIdeHandle,
                InterfaceAutomationChannel.IDEA_ACCESSIBILITY,
                LocalInterfaceAction.OPEN_REGISTERED_FILE, "NOT_REGISTERED_HANDLE");
    }

    @Test
    void realServiceRejectsReplayedNonceAndExpiredRequests() {
        // 每次运行使用全新 nonce，保证本用例可重复执行；同一 nonce 第二次必须被拒。
        String fixedNonce = LocalAutomationSigning.newNonce(new java.security.SecureRandom());
        LocalAutomationReceipt first = newClient(Clock.systemUTC(), () -> fixedNonce)
                .invoke(InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                        LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT", OWNER_ID);
        assertEquals(LocalAutomationStatus.SUCCEEDED, first.status());

        LocalAutomationReceipt replay = newClient(Clock.systemUTC(), () -> fixedNonce)
                .invoke(InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                        LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT", OWNER_ID);
        assertEquals(LocalAutomationStatus.REJECTED, replay.status());
        assertNotNull(replay.errorCode());

        Clock tenMinutesAgo = Clock.fixed(
                Instant.now().minusSeconds(600), ZoneOffset.UTC);
        LocalAutomationReceipt expired = newClient(tenMinutesAgo, null)
                .invoke(InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                        LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT", OWNER_ID);
        assertEquals(LocalAutomationStatus.REJECTED, expired.status());
        assertNotNull(expired.errorCode());
    }

    @Test
    void realServiceRejectsASignatureFromADifferentSecret() {
        UnixSocketLocalAutomationClient wrongSecret = new UnixSocketLocalAutomationClient(
                socketPath, "wrong-secret-that-is-32-bytes-long!", CONNECT_TIMEOUT_MILLIS,
                READ_TIMEOUT_MILLIS, mapper, Clock.systemUTC(), null);

        LocalAutomationReceipt receipt = wrongSecret.invoke(
                InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT", OWNER_ID);

        assertEquals(LocalAutomationStatus.REJECTED, receipt.status());
        assertNotNull(receipt.errorCode());
    }

    @Test
    void clientRefusesIllegalChannelsAndTargetsBeforeTouchingTheRealService() {
        UnixSocketLocalAutomationClient client = newClient(Clock.systemUTC(), null);

        assertThrows(IllegalArgumentException.class, () -> client.invoke(
                InterfaceAutomationChannel.IDEA_ACCESSIBILITY,
                LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "ASSISTANT", OWNER_ID));
        assertThrows(IllegalArgumentException.class, () -> client.invoke(
                InterfaceAutomationChannel.PLAYWRIGHT_DOM,
                LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE, "../../etc/passwd", OWNER_ID));
    }

    /** 回执必须与本次请求对齐：requestId 为本次 UUID，摘要为本次三元组的 sha256。 */
    private void assertCorrelatedWithTheRequest(
            LocalAutomationReceipt receipt,
            InterfaceAutomationChannel channel,
            LocalInterfaceAction action,
            String targetKey
    ) {
        assertTrue(receipt.requestId().matches(LocalAutomationSigning.LOWER_UUID),
                "回执 requestId 必须是本次请求的小写 UUID");
        assertEquals(channel.name(), receipt.adapter());
        assertEquals(action.name(), receipt.action());
        assertEquals(LocalAutomationSigning.targetDigest(
                        channel.name(), action.name(), targetKey),
                receipt.targetDigest(), "回执目标摘要必须与本次请求三元组一致");
    }

    private LocalAutomationReceipt invoke(
            InterfaceAutomationChannel channel, LocalInterfaceAction action, String targetKey
    ) {
        return newClient(Clock.systemUTC(), null).invoke(channel, action, targetKey, OWNER_ID);
    }

    private UnixSocketLocalAutomationClient newClient(
            Clock clock, java.util.function.Supplier<String> nonceSupplier
    ) {
        return new UnixSocketLocalAutomationClient(socketPath, signingSecret,
                CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS, mapper, clock, nonceSupplier);
    }
}
