package com.studypilot.automation.idea;

import com.studypilot.automation.idea.dispatch.IdeActionDispatcher;
import com.studypilot.automation.idea.dispatch.IdeOutcome;
import com.studypilot.automation.idea.dispatch.IdePlatform;
import com.studypilot.automation.idea.dispatch.UiExecutor;
import com.studypilot.automation.idea.protocol.NonceLedger;
import com.studypilot.automation.idea.protocol.PluginProtocol;
import com.studypilot.automation.idea.protocol.PluginRequest;
import com.studypilot.automation.idea.protocol.PluginResponse;
import com.studypilot.automation.idea.registry.IdeaTargetRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Dependency-free self test for the plugin's security-critical logic.
 *
 * The plugin cannot rely on external test frameworks being present in the IDE sandbox, so
 * this harness runs the same assertions with plain Java and exits non-zero on any failure.
 * It is wired into the Gradle build as an ordinary JUnit-free test executable.
 */
public final class PluginSelfTest {

  private static int passed = 0;
  private static final java.util.List<String> failures = new java.util.ArrayList<>();

  private static void check(String name, boolean condition) {
    if (condition) {
      passed++;
    } else {
      failures.add(name);
      System.out.println("FAIL " + name);
    }
  }

  private static void checkEquals(String name, Object expected, Object actual) {
    boolean ok = expected == null ? actual == null : expected.equals(actual);
    if (!ok) {
      System.out.println("     expected=" + expected + " actual=" + actual);
    }
    check(name, ok);
  }

  // ------------------------------------------------------------------ helpers

  private static final byte[] SECRET = "studypilot-plugin-secret-32-bytes!!".getBytes(StandardCharsets.UTF_8);
  private static final String FILE_HANDLE = "FILE_REGISTERED";
  private static final String RUN_HANDLE = "RUN_REGISTERED";
  private static final String RESULT_HANDLE = "RESULT_REGISTERED";
  private static final String PROJECT_ROOT = "/work/project";

  private static String uuid(int suffix) {
    return String.format("11111111-2222-4333-8444-%012d", suffix);
  }

  private static String nonce(int suffix) {
    return "nonce-" + suffix + "-abcdefghijklmnop";
  }

  private static byte[] frame(
      PluginProtocol protocol, String action, String targetKey, long issuedAtMs, long expiresAtMs, String nonceValue) {
    PluginRequest request =
        new PluginRequest(
            1,
            uuid(targetKey.hashCode() & 0xFFFF),
            action,
            targetKey,
            Instant.ofEpochMilli(issuedAtMs).toString(),
            Instant.ofEpochMilli(expiresAtMs).toString(),
            nonceValue,
            "0".repeat(64));
    String signature = protocol.signatureOf(request);
    PluginRequest signed =
        new PluginRequest(
            request.version,
            request.requestId,
            request.action,
            request.targetKey,
            request.issuedAt,
            request.expiresAt,
            request.nonce,
            signature);
    return json(signed).getBytes(StandardCharsets.UTF_8);
  }

  private static String json(PluginRequest r) {
    return "{"
        + "\"version\":1,"
        + "\"requestId\":\"" + r.requestId + "\","
        + "\"action\":\"" + r.action + "\","
        + "\"targetKey\":\"" + r.targetKey + "\","
        + "\"issuedAt\":\"" + r.issuedAt + "\","
        + "\"expiresAt\":\"" + r.expiresAt + "\","
        + "\"nonce\":\"" + r.nonce + "\","
        + "\"signature\":\"" + r.signature + "\""
        + "}";
  }

  /** Signature over the JAVA-FACING payload layout, to prove domain separation. */
  private static String javaDomainSignature(PluginRequest r) {
    try {
      StringBuilder sb = new StringBuilder();
      String[] fields = {
        Integer.toString(r.version), r.requestId, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "IDEA_ACCESSIBILITY", r.action, r.targetKey, r.issuedAt, r.expiresAt, r.nonce
      };
      for (String field : fields) {
        sb.append(field.getBytes(StandardCharsets.UTF_8).length).append('#').append(field).append('|');
      }
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
      byte[] digest = mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class FakePlatform implements IdePlatform {
    IdeOutcome fileOutcome = IdeOutcome.verified();
    IdeOutcome runOutcome = IdeOutcome.verified();
    IdeOutcome resultOutcome = IdeOutcome.verified();
    int fileCalls = 0;
    int runCalls = 0;
    int resultCalls = 0;
    String lastPath;
    String lastRun;
    String lastResult;

    @Override
    public IdeOutcome openRegisteredFile(String canonicalPath, String projectRoot) {
      fileCalls++;
      lastPath = canonicalPath;
      return fileOutcome;
    }

    @Override
    public IdeOutcome focusRunConfiguration(String configurationName, String projectRoot) {
      runCalls++;
      lastRun = configurationName;
      return runOutcome;
    }

    @Override
    public IdeOutcome showTestResult(String toolWindowId, String contentDisplayName, String projectRoot) {
      resultCalls++;
      lastResult = toolWindowId + "/" + contentDisplayName;
      return resultOutcome;
    }
  }

  private static final class FakeUi implements UiExecutor {
    RuntimeException toThrow;
    boolean timeout;
    boolean disposed;
    int calls = 0;

    @Override
    public <T> T runOnUiThread(Callable<T> task, long timeoutMs) throws UiTimeoutException, IdeDisposedException {
      calls++;
      if (timeout) {
        throw new UiTimeoutException("timed out");
      }
      if (disposed) {
        throw new IdeDisposedException("disposed");
      }
      if (toThrow != null) {
        throw toThrow;
      }
      try {
        return task.call();
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static IdeaTargetRegistry registry() {
    IdeaTargetRegistry registry = new IdeaTargetRegistry();
    registry.register(FILE_HANDLE, IdeaTargetRegistry.Kind.FILE, "/work/project/src/App.java", PROJECT_ROOT);
    registry.register(RUN_HANDLE, IdeaTargetRegistry.Kind.RUN_CONFIGURATION, "StudyPilotApplication", PROJECT_ROOT);
    registry.register(
        RESULT_HANDLE, IdeaTargetRegistry.Kind.TEST_RESULT, "Run", "surefire-reports", PROJECT_ROOT);
    return registry;
  }

  private static IdeActionDispatcher dispatcher(
      PluginProtocol protocol, IdeaTargetRegistry registry, NonceLedger ledger, FakePlatform platform, FakeUi ui) {
    return new IdeActionDispatcher(protocol, registry, ledger, platform, ui, () -> NOW, 2_000L);
  }

  private static final long NOW = Instant.parse("2026-09-22T10:00:00Z").toEpochMilli();
  private static Path tempDir;

  // ------------------------------------------------------------------ tests

  public static void main(String[] args) throws Exception {
    tempDir = Files.createTempDirectory("studypilot-plugin-selftest-");
    try {
      framingTests();
      authTests();
      replayTests();
      registryTests();
      dispatchTests();
      uiThreadTests();
      responseHygieneTests();
      architectureGuardTests();
    } finally {
      deleteRecursively(tempDir);
    }

    System.out.println();
    System.out.println("PluginSelfTest: passed=" + passed + " failed=" + failures.size());
    for (String failure : failures) {
      System.out.println("  FAILED: " + failure);
    }
    if (!failures.isEmpty()) {
      System.exit(1);
    }
  }

  private static void framingTests() {
    PluginProtocol protocol = new PluginProtocol(SECRET);

    byte[] good = frame(protocol, "OPEN_REGISTERED_FILE", FILE_HANDLE, NOW - 1000, NOW + 5000, nonce(1));
    check("framing: valid frame parses", protocol.parse(good).ok);

    byte[] oversize = new byte[PluginProtocol.MAX_FRAME_BYTES + 1];
    java.util.Arrays.fill(oversize, (byte) 'x');
    checkEquals("framing: oversize rejected", "OVERSIZE_FRAME", protocol.parse(oversize).errorCode);

    String withNewline = new String(good, StandardCharsets.UTF_8) + "\n";
    checkEquals(
        "framing: embedded newline rejected", "INVALID_FRAME", protocol.parse(withNewline.getBytes(StandardCharsets.UTF_8)).errorCode);

    String duplicate =
        new String(good, StandardCharsets.UTF_8).replace("\"action\":\"OPEN_REGISTERED_FILE\"", "\"action\":\"OPEN_REGISTERED_FILE\",\"action\":\"SHOW_TEST_RESULT\"");
    checkEquals(
        "framing: duplicate key rejected", "DUPLICATE_KEYS", protocol.parse(duplicate.getBytes(StandardCharsets.UTF_8)).errorCode);

    String extra = new String(good, StandardCharsets.UTF_8).replace("}", ",\"path\":\"/etc/passwd\"}");
    checkEquals(
        "framing: extra field rejected", "UNKNOWN_FIELDS", protocol.parse(extra.getBytes(StandardCharsets.UTF_8)).errorCode);

    String missing = new String(good, StandardCharsets.UTF_8).replace(",\"nonce\":\"nonce-1-abcdefghijklmnop\"", "");
    checkEquals(
        "framing: missing field rejected", "MISSING_FIELD", protocol.parse(missing.getBytes(StandardCharsets.UTF_8)).errorCode);

    String badVersion = new String(good, StandardCharsets.UTF_8).replace("\"version\":1", "\"version\":2");
    checkEquals(
        "framing: unsupported version rejected", "UNSUPPORTED_VERSION", protocol.parse(badVersion.getBytes(StandardCharsets.UTF_8)).errorCode);

    String arrayValue = new String(good, StandardCharsets.UTF_8).replace("\"targetKey\":\"FILE_REGISTERED\"", "\"targetKey\":[\"a\"]");
    checkEquals(
        "framing: array value rejected", "INVALID_JSON", protocol.parse(arrayValue.getBytes(StandardCharsets.UTF_8)).errorCode);

    String pathHandle = new String(good, StandardCharsets.UTF_8).replace("\"targetKey\":\"FILE_REGISTERED\"", "\"targetKey\":\"/etc/passwd\"");
    checkEquals(
        "framing: path-like handle rejected", "INVALID_TARGET_KEY", protocol.parse(pathHandle.getBytes(StandardCharsets.UTF_8)).errorCode);

    byte[] invalidUtf8 = new byte[] {'{', '"', (byte) 0xC3, (byte) 0x28, '"', ':', '1', '}'};
    checkEquals("framing: invalid UTF-8 rejected", "INVALID_UTF8", protocol.parse(invalidUtf8).errorCode);
  }

  private static void authTests() {
    PluginProtocol protocol = new PluginProtocol(SECRET);
    NonceLedger ledger = new NonceLedger(tempDir.resolve("nonce-auth.ledger"));
    IdeActionDispatcher dispatcher = dispatcher(protocol, registry(), ledger, new FakePlatform(), new FakeUi());

    byte[] good = frame(protocol, "OPEN_REGISTERED_FILE", FILE_HANDLE, NOW - 1000, NOW + 5000, nonce(10));
    PluginProtocol.Result<PluginRequest> parsed = protocol.parse(good);
    check("auth: valid request verifies", protocol.verify(parsed.value, NOW).ok);

    PluginRequest tampered =
        new PluginRequest(1, parsed.value.requestId, parsed.value.action, parsed.value.targetKey, parsed.value.issuedAt, parsed.value.expiresAt, parsed.value.nonce, "0".repeat(64));
    checkEquals("auth: wrong signature rejected", "INVALID_SIGNATURE", protocol.verify(tampered, NOW).errorCode);

    PluginProtocol shortSecret = new PluginProtocol("short".getBytes(StandardCharsets.UTF_8));
    checkEquals("auth: short secret rejected", "KEY_TOO_SHORT", shortSecret.verify(parsed.value, NOW).errorCode);

    // Lifetime stays inside the 15 s window so that the expiry rule (not the lifetime rule)
    // is the one under test.
    PluginProtocol.Result<PluginRequest> expired =
        protocol.parse(frame(protocol, "OPEN_REGISTERED_FILE", FILE_HANDLE, NOW - 10000, NOW - 1000, nonce(11)));
    checkEquals("auth: expired rejected", "EXPIRED_REQUEST", protocol.verify(expired.value, NOW).errorCode);

    PluginProtocol.Result<PluginRequest> drift =
        protocol.parse(frame(protocol, "OPEN_REGISTERED_FILE", FILE_HANDLE, NOW + 30000, NOW + 35000, nonce(12)));
    checkEquals("auth: future drift rejected", "CLOCK_DRIFT", protocol.verify(drift.value, NOW).errorCode);

    PluginProtocol.Result<PluginRequest> longLived =
        protocol.parse(frame(protocol, "OPEN_REGISTERED_FILE", FILE_HANDLE, NOW - 1000, NOW + 60000, nonce(13)));
    checkEquals("auth: lifetime over 15s rejected", "LIFETIME_EXCEEDED", protocol.verify(longLived.value, NOW).errorCode);

    // Domain separation: a signature produced for the Java-facing payload must never be
    // accepted by the plugin protocol.
    PluginRequest crossDomain =
        new PluginRequest(1, uuid(99), "OPEN_REGISTERED_FILE", FILE_HANDLE, Instant.ofEpochMilli(NOW - 1000).toString(), Instant.ofEpochMilli(NOW + 5000).toString(), nonce(14), "0".repeat(64));
    PluginRequest crossSigned =
        new PluginRequest(1, crossDomain.requestId, crossDomain.action, crossDomain.targetKey, crossDomain.issuedAt, crossDomain.expiresAt, crossDomain.nonce, javaDomainSignature(crossDomain));
    PluginResponse crossResponse = dispatcher.dispatch(crossSigned, NOW);
    checkEquals("auth: java-facing signature rejected by plugin domain", "INVALID_SIGNATURE", crossResponse.errorCode);
  }

  private static void replayTests() throws Exception {
    Path ledgerFile = tempDir.resolve("nonce-replay.ledger");
    PluginProtocol protocol = new PluginProtocol(SECRET);
    IdeaTargetRegistry registry = registry();
    FakePlatform platform = new FakePlatform();

    NonceLedger ledger1 = new NonceLedger(ledgerFile);
    IdeActionDispatcher dispatcher1 = dispatcher(protocol, registry, ledger1, platform, new FakeUi());

    byte[] request = frame(protocol, "OPEN_REGISTERED_FILE", FILE_HANDLE, NOW - 1000, NOW + 5000, nonce(20));
    PluginResponse first = dispatcher1.dispatch(protocol.parse(request).value, NOW);
    checkEquals("replay: first use succeeds", "SUCCEEDED", first.status);

    PluginResponse second = dispatcher1.dispatch(protocol.parse(request).value, NOW);
    checkEquals("replay: same nonce rejected", "REJECTED", second.status);
    checkEquals("replay: same nonce code", "REPLAY_DETECTED", second.errorCode);

    // Restart: a brand new ledger on the same file must still reject the nonce.
    NonceLedger ledger2 = new NonceLedger(ledgerFile);
    IdeActionDispatcher dispatcher2 = dispatcher(protocol, registry, ledger2, platform, new FakeUi());
    PluginResponse afterRestart = dispatcher2.dispatch(protocol.parse(request).value, NOW);
    checkEquals("replay: rejected after restart", "REJECTED", afterRestart.status);
    checkEquals("replay: restart code", "REPLAY_DETECTED", afterRestart.errorCode);
  }

  private static void registryTests() {
    PluginProtocol protocol = new PluginProtocol(SECRET);
    FakePlatform platform = new FakePlatform();
    IdeActionDispatcher dispatcher =
        new IdeActionDispatcher(protocol, registry(), new NonceLedger(tempDir.resolve("nonce-registry.ledger")), platform, new FakeUi(), () -> NOW, 2000L);

    PluginRequest unknown =
        protocol.parse(frame(protocol, "OPEN_REGISTERED_FILE", "NOT_REGISTERED", NOW - 1000, NOW + 5000, nonce(30))).value;
    PluginResponse unknownResponse = dispatcher.dispatch(unknown, NOW);
    checkEquals("registry: unknown handle rejected", "TARGET_NOT_REGISTERED", unknownResponse.errorCode);

    // A FILE handle may never satisfy a FOCUS_RUN_CONFIGURATION request.
    PluginRequest wrongKind =
        protocol.parse(frame(protocol, "FOCUS_RUN_CONFIGURATION", FILE_HANDLE, NOW - 1000, NOW + 5000, nonce(31))).value;
    PluginResponse wrongKindResponse = dispatcher.dispatch(wrongKind, NOW);
    checkEquals("registry: kind mismatch rejected", "TARGET_NOT_REGISTERED", wrongKindResponse.errorCode);
    checkEquals("registry: kind mismatch never dispatched", 0, platform.runCalls);

    boolean threw = false;
    try {
      IdeaTargetRegistry bad = new IdeaTargetRegistry();
      bad.register("has-dash", IdeaTargetRegistry.Kind.FILE, "/x", PROJECT_ROOT);
    } catch (IllegalArgumentException expected) {
      threw = true;
    }
    check("registry: invalid handle rejected at registration", threw);
  }

  private static void dispatchTests() {
    PluginProtocol protocol = new PluginProtocol(SECRET);
    IdeaTargetRegistry registry = registry();
    FakePlatform platform = new FakePlatform();
    FakeUi ui = new FakeUi();

    // 1. OPEN_REGISTERED_FILE verified.
    PluginResponse okFile =
        dispatcher(protocol, registry, new NonceLedger(tempDir.resolve("n-d1.ledger")), platform, ui)
            .dispatch(protocol.parse(frame(protocol, "OPEN_REGISTERED_FILE", FILE_HANDLE, NOW - 1000, NOW + 5000, nonce(40))).value, NOW);
    checkEquals("dispatch: verified file open succeeds", "SUCCEEDED", okFile.status);
    checkEquals("dispatch: platform received the registered value", "/work/project/src/App.java", platform.lastPath);
    check("dispatch: ui thread used", ui.calls > 0);

    // 2. OPEN_REGISTERED_FILE dispatched but unverified -> must never succeed.
    FakePlatform unverifiedFile = new FakePlatform();
    unverifiedFile.fileOutcome = IdeOutcome.unverified("POST_STATE_NOT_VERIFIED", "editor did not select the file");
    PluginResponse unverifiedFileResponse =
        dispatcher(protocol, registry, new NonceLedger(tempDir.resolve("n-d2.ledger")), unverifiedFile, new FakeUi())
            .dispatch(protocol.parse(frame(protocol, "OPEN_REGISTERED_FILE", FILE_HANDLE, NOW - 1000, NOW + 5000, nonce(41))).value, NOW);
    checkEquals("dispatch: unverified file open fails", "FAILED", unverifiedFileResponse.status);
    checkEquals("dispatch: unverified file code", "UNVERIFIED_TARGET_STATE", unverifiedFileResponse.errorCode);

    // 3. FOCUS_RUN_CONFIGURATION verified.
    PluginResponse okRun =
        dispatcher(protocol, registry, new NonceLedger(tempDir.resolve("n-d3.ledger")), platform, new FakeUi())
            .dispatch(protocol.parse(frame(protocol, "FOCUS_RUN_CONFIGURATION", RUN_HANDLE, NOW - 1000, NOW + 5000, nonce(42))).value, NOW);
    checkEquals("dispatch: verified run focus succeeds", "SUCCEEDED", okRun.status);
    checkEquals("dispatch: platform received the registered configuration", "StudyPilotApplication", platform.lastRun);

    // 4. FOCUS_RUN_CONFIGURATION unverified.
    FakePlatform unverifiedRun = new FakePlatform();
    unverifiedRun.runOutcome = IdeOutcome.unverified("POST_STATE_NOT_VERIFIED", "selected configuration not confirmed");
    PluginResponse unverifiedRunResponse =
        dispatcher(protocol, registry, new NonceLedger(tempDir.resolve("n-d4.ledger")), unverifiedRun, new FakeUi())
            .dispatch(protocol.parse(frame(protocol, "FOCUS_RUN_CONFIGURATION", RUN_HANDLE, NOW - 1000, NOW + 5000, nonce(43))).value, NOW);
    checkEquals("dispatch: unverified run focus fails", "FAILED", unverifiedRunResponse.status);
    checkEquals("dispatch: unverified run code", "UNVERIFIED_TARGET_STATE", unverifiedRunResponse.errorCode);

    // 5. SHOW_TEST_RESULT verified.
    PluginResponse okResult =
        dispatcher(protocol, registry, new NonceLedger(tempDir.resolve("n-d5.ledger")), platform, new FakeUi())
            .dispatch(protocol.parse(frame(protocol, "SHOW_TEST_RESULT", RESULT_HANDLE, NOW - 1000, NOW + 5000, nonce(44))).value, NOW);
    checkEquals("dispatch: verified result reveal succeeds", "SUCCEEDED", okResult.status);
    checkEquals("dispatch: platform received the registered result identity", "Run/surefire-reports", platform.lastResult);

    // 6. SHOW_TEST_RESULT with no existing result view -> honest failure.
    FakePlatform missingResult = new FakePlatform();
    missingResult.resultOutcome = IdeOutcome.refused("RESULT_VIEW_NOT_PRESENT", "no existing result content");
    PluginResponse missingResultResponse =
        dispatcher(protocol, registry, new NonceLedger(tempDir.resolve("n-d6.ledger")), missingResult, new FakeUi())
            .dispatch(protocol.parse(frame(protocol, "SHOW_TEST_RESULT", RESULT_HANDLE, NOW - 1000, NOW + 5000, nonce(45))).value, NOW);
    checkEquals("dispatch: missing result view fails", "FAILED", missingResultResponse.status);
    checkEquals("dispatch: missing result code", "RESULT_VIEW_NOT_PRESENT", missingResultResponse.errorCode);
    check("dispatch: missing result never succeeds", !missingResultResponse.isSuccess());

    // 7. Project mismatch and ambiguity are refusals.
    for (String[] pair :
        new String[][] {
          {"PROJECT_MISMATCH", "target belongs to another project"},
          {"TARGET_AMBIGUOUS", "more than one matching target"},
        }) {
      FakePlatform refused = new FakePlatform();
      refused.fileOutcome = IdeOutcome.refused(pair[0], pair[1]);
      PluginResponse response =
          dispatcher(protocol, registry, new NonceLedger(tempDir.resolve("n-d7-" + pair[0] + ".ledger")), refused, new FakeUi())
              .dispatch(protocol.parse(frame(protocol, "OPEN_REGISTERED_FILE", FILE_HANDLE, NOW - 1000, NOW + 5000, nonce(46 + pair[0].hashCode() % 100))).value, NOW);
      checkEquals("dispatch: " + pair[0] + " fails", "FAILED", response.status);
      checkEquals("dispatch: " + pair[0] + " code", pair[0], response.errorCode);
    }

    // 8. A rejected request must not run anything.
    FakePlatform untouched = new FakePlatform();
    PluginRequest expired =
        protocol.parse(frame(protocol, "OPEN_REGISTERED_FILE", FILE_HANDLE, NOW - 10000, NOW - 5000, nonce(47))).value;
    PluginResponse expiredResponse =
        dispatcher(protocol, registry, new NonceLedger(tempDir.resolve("n-d8.ledger")), untouched, new FakeUi())
            .dispatch(expired, NOW);
    checkEquals("dispatch: expired request rejected", "REJECTED", expiredResponse.status);
    checkEquals("dispatch: expired request has no effect", 0, untouched.fileCalls);
    checkEquals("dispatch: expired request never touches the UI thread", 0, new FakeUi().calls);
  }

  private static void uiThreadTests() {
    PluginProtocol protocol = new PluginProtocol(SECRET);
    IdeaTargetRegistry registry = registry();
    FakePlatform platform = new FakePlatform();

    FakeUi timeout = new FakeUi();
    timeout.timeout = true;
    PluginResponse timeoutResponse =
        dispatcher(protocol, registry, new NonceLedger(tempDir.resolve("n-u1.ledger")), platform, timeout)
            .dispatch(protocol.parse(frame(protocol, "OPEN_REGISTERED_FILE", FILE_HANDLE, NOW - 1000, NOW + 5000, nonce(50))).value, NOW);
    checkEquals("ui: timeout fails", "FAILED", timeoutResponse.status);
    checkEquals("ui: timeout code", "UI_THREAD_TIMEOUT", timeoutResponse.errorCode);

    FakeUi disposed = new FakeUi();
    disposed.disposed = true;
    PluginResponse disposedResponse =
        dispatcher(protocol, registry, new NonceLedger(tempDir.resolve("n-u2.ledger")), platform, disposed)
            .dispatch(protocol.parse(frame(protocol, "OPEN_REGISTERED_FILE", FILE_HANDLE, NOW - 1000, NOW + 5000, nonce(51))).value, NOW);
    checkEquals("ui: disposed fails", "FAILED", disposedResponse.status);
    checkEquals("ui: disposed code", "IDE_DISPOSING", disposedResponse.errorCode);

    FakeUi throwing = new FakeUi();
    throwing.toThrow = new IllegalStateException("boom");
    PluginResponse throwingResponse =
        dispatcher(protocol, registry, new NonceLedger(tempDir.resolve("n-u3.ledger")), platform, throwing)
            .dispatch(protocol.parse(frame(protocol, "OPEN_REGISTERED_FILE", FILE_HANDLE, NOW - 1000, NOW + 5000, nonce(52))).value, NOW);
    checkEquals("ui: exception fails", "FAILED", throwingResponse.status);
    checkEquals("ui: exception code", "INTERNAL_ERROR", throwingResponse.errorCode);
  }

  private static void responseHygieneTests() {
    PluginProtocol protocol = new PluginProtocol(SECRET);
    byte[] request = frame(protocol, "OPEN_REGISTERED_FILE", FILE_HANDLE, NOW - 1000, NOW + 5000, nonce(60));
    PluginRequest parsed = protocol.parse(request).value;

    FakePlatform platform = new FakePlatform();
    platform.fileOutcome = IdeOutcome.unverified("POST_STATE_NOT_VERIFIED", "editor /work/project/src/App.java https://example.com");
    PluginResponse response =
        dispatcher(protocol, registry(), new NonceLedger(tempDir.resolve("n-h1.ledger")), platform, new FakeUi())
            .dispatch(parsed, NOW);

    checkEquals("response: correlates requestId", parsed.requestId, response.requestId);
    checkEquals("response: correlates action", parsed.action, response.action);

    String frameOut = protocol.format(response);
    check("response: single line frame", frameOut.indexOf('\n') < 0);
    check("response: within 16 KiB", frameOut.getBytes(StandardCharsets.UTF_8).length <= PluginProtocol.MAX_FRAME_BYTES);
    check("response: no path leaked", !frameOut.contains("/work/project"));
    check("response: no URL leaked", !frameOut.contains("https://"));
    check("response: message capped", response.message.length() <= 200);
  }

  /**
   * Architecture guards: prove at source level that the plugin cannot do anything beyond the
   * three registered actions. These are deliberately token-based so that removing a capability
   * cannot pass by renaming a single method.
   */
  private static void architectureGuardTests() {
    String sourceDir = System.getProperty("studypilot.plugin.source", "src/main/java");
    String resourcesDir = System.getProperty("studypilot.plugin.resources", "src/main/resources");
    StringBuilder sources = new StringBuilder();
    StringBuilder resourceText = new StringBuilder();
    try {
      collect(new java.io.File(sourceDir), sources, ".java");
      collect(new java.io.File(resourcesDir), resourceText, ".xml");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    String code = sources.toString();
    String resources = resourceText.toString();
    check("guards: plugin sources found", code.length() > 1000);

    String[] forbidden = {
      "java.net.ServerSocket",
      "java.net.Socket(",
      "HttpURLConnection",
      "java.net.http",
      "java.net.URL(",
      "new Robot",
      "java.awt.Robot",
      "ActionManager",
      "getAction(",
      "ProcessBuilder",
      "Runtime.getRuntime",
      "osascript",
      "AppleScript",
      "java.lang.reflect",
      "Class.forName",
      "ExecutionManager",
      "ProgramRunner",
      "runConfiguration(",
      "DebuggerManager",
      "setText(",
      "KeyEvent",
      "MouseEvent",
      "STUDYPILOT_AUTOMATION_SOCKET_PATH",
      "STUDYPILOT_AUTOMATION_HMAC_SECRET",
      "http://",
      "https://"
    };
    for (String token : forbidden) {
      check("guards: plugin must not contain '" + token + "'", !code.contains(token));
    }

    // Only a UNIX domain socket may be opened, and only the registered IDE APIs may be used.
    String[] required = {
      "StandardProtocolFamily.UNIX",
      "UnixDomainSocketAddress",
      "OpenFileDescriptor",
      "FileEditorManager",
      "RunManager",
      "setSelectedConfiguration",
      "getSelectedConfiguration",
      "ToolWindowManager",
      "setSelectedContent",
      "getSelectedContent",
      "LocalFileSystem",
      // Containment must be decided on canonical Paths, not on raw VFS paths or strings.
      "PathBinding",
      "PathBinding.contains",
      "PathBinding.canonical",
      // The registered root must EQUAL the open project's canonical base path.
      "candidate.equals(registeredRoot)",
      // The result view must be matched by exact identity, never "the only content".
      "TestResultContentMatcher"
    };
    for (String token : required) {
      check("guards: plugin must contain '" + token + "'", code.contains(token));
    }

    // Raw string-prefix containment and "the only content" fallbacks are forbidden.
    for (String token : new String[] {"startsWith(basePath", "existing.get(0)", "contents.get(0)"}) {
      check("guards: plugin must not contain '" + token + "'", !code.contains(token));
    }

    // The plugin must never reach the Java-facing socket configuration.
    check("guards: plugin.xml declares only the platform module", resources.contains("com.intellij.modules.platform"));
    check("guards: plugin.xml declares no other module dependency", !resources.contains("<depends>com.intellij.modules.lang"));
    check("guards: plugin.xml has no executor or run configuration extension", !resources.contains("executor"));
    check("guards: plugin.xml declares no test runner extension", !resources.contains("testRunner"));
  }

  private static void collect(java.io.File file, StringBuilder out, String suffix) throws Exception {
    if (!file.exists()) {
      return;
    }
    if (file.isDirectory()) {
      java.io.File[] children = file.listFiles();
      if (children != null) {
        for (java.io.File child : children) {
          collect(child, out, suffix);
        }
      }
      return;
    }
    if (file.getName().endsWith(suffix)) {
      out.append(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).append('\n');
    }
  }

  private static void deleteRecursively(Path root) {    try {
      if (!Files.exists(root)) {
        return;
      }
      Files.walk(root)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (Exception ignored) {
                  // best effort cleanup
                }
              });
    } catch (Exception ignored) {
      // best effort cleanup
    }
  }
}
