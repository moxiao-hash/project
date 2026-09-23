package com.studypilot.automation.idea;

import com.studypilot.automation.idea.dispatch.IdeActionDispatcher;
import com.studypilot.automation.idea.dispatch.IdeOutcome;
import com.studypilot.automation.idea.dispatch.IdePlatform;
import com.studypilot.automation.idea.dispatch.UiExecutor;
import com.studypilot.automation.idea.dispatch.UiScheduler;
import com.studypilot.automation.idea.platform.IdeUiExecutor;
import com.studypilot.automation.idea.platform.PathBinding;
import com.studypilot.automation.idea.platform.PluginConfig;
import com.studypilot.automation.idea.platform.PluginSocketServer;
import com.studypilot.automation.idea.platform.TestResultContentMatcher;
import com.studypilot.automation.idea.protocol.NonceLedger;
import com.studypilot.automation.idea.protocol.PluginProtocol;
import com.studypilot.automation.idea.registry.IdeaTargetRegistry;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Hardening self test for the four review blockers: real configuration loading, exact
 * test-result content identity, single-frame enforcement, and canonical filesystem binding.
 *
 * Dependency free, like {@link PluginSelfTest}: plain Java with a non-zero exit on any failure.
 */
public final class PluginHardeningSelfTest {

  private static int passed = 0;
  private static final List<String> failures = new ArrayList<>();

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

  private static Path tempRoot;

  public static void main(String[] args) throws Exception {
    tempRoot = Files.createTempDirectory("studypilot-hardening-selftest-");
    try {
      configLoadingTests();
      pathBindingTests();
      singleFrameTests();
      responseFramingTests();
      uiSchedulerTests();
      testResultMatcherTests();
    } finally {
      deleteRecursively(tempRoot);
    }

    System.out.println();
    System.out.println(
        "PluginHardeningSelfTest: passed=" + passed + " failed=" + failures.size());
    for (String failure : failures) {
      System.out.println("  FAILED: " + failure);
    }
    if (!failures.isEmpty()) {
      System.exit(1);
    }
  }

  // ------------------------------------------------------------------ config

  private static Path writeConfig(String name, String content) throws IOException {
    Path file = tempRoot.resolve(name + ".tsv");
    Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    NonceLedger.setOwnerOnlyFile(file);
    return file;
  }

  private static String baseScalars(Path socketPath, Path ledgerPath, Path projectRoot, Path fileTarget) {
    return String.join(
            "\t",
            "socketPath",
            socketPath.toAbsolutePath().toString())
        + "\n"
        + "ledgerPath\t"
        + ledgerPath.toAbsolutePath()
        + "\n"
        + "projectRoot\t"
        + projectRoot.toAbsolutePath()
        + "\n"
        + "uiTimeoutMs\t2000\n"
        + "secretFile\t/nonexistent/secret\n"
        + "FILE_REGISTERED\tFILE\t"
        + fileTarget.toAbsolutePath()
        + "\n"
        + "RUN_REGISTERED\tRUN_CONFIGURATION\tStudyPilotApplication\n"
        + "RESULT_REGISTERED\tTEST_RESULT\tRun\tsurefire-reports\n";
  }

  private static Map<String, String> envWithSecret() {
    Map<String, String> env = new HashMap<>();
    env.put(PluginConfig.ENV_SECRET, "studypilot-plugin-secret-32-bytes!!");
    return env;
  }

  private static void configLoadingTests() throws Exception {
    Path dir = Files.createDirectories(tempRoot.resolve("cfg"));
    Path fileTarget = dir.resolve("Registered.java");
    Files.write(fileTarget, "class Registered {}".getBytes(StandardCharsets.UTF_8));
    Path socketPath = dir.resolve("plugin.sock");
    Path ledgerPath = dir.resolve("nonces.ledger");

    // 1. A real configuration containing all three target kinds must load and resolve.
    Path config = writeConfig("valid", baseScalars(socketPath, ledgerPath, dir, fileTarget));
    PluginConfig loaded = PluginConfig.load(config, envWithSecret());
    checkEquals("config: registry size", 3, loaded.registry.size());

    Optional<IdeaTargetRegistry.RegisteredTarget> fileTargetEntry =
        loaded.registry.lookup("FILE_REGISTERED", IdeaTargetRegistry.Kind.FILE);
    check("config: FILE handle resolves", fileTargetEntry.isPresent());
    if (fileTargetEntry.isPresent()) {
      checkEquals(
          "config: FILE value canonicalised",
          fileTarget.toRealPath().toString(),
          fileTargetEntry.get().value);
      checkEquals(
          "config: FILE project root",
          dir.toRealPath().toString(),
          fileTargetEntry.get().projectRoot);
    }

    Optional<IdeaTargetRegistry.RegisteredTarget> runEntry =
        loaded.registry.lookup("RUN_REGISTERED", IdeaTargetRegistry.Kind.RUN_CONFIGURATION);
    check("config: RUN handle resolves", runEntry.isPresent());
    if (runEntry.isPresent()) {
      checkEquals("config: RUN value", "StudyPilotApplication", runEntry.get().value);
    }

    Optional<IdeaTargetRegistry.RegisteredTarget> resultEntry =
        loaded.registry.lookup("RESULT_REGISTERED", IdeaTargetRegistry.Kind.TEST_RESULT);
    check("config: TEST_RESULT handle resolves", resultEntry.isPresent());
    if (resultEntry.isPresent()) {
      checkEquals("config: TEST_RESULT tool window", "Run", resultEntry.get().value);
      checkEquals(
          "config: TEST_RESULT content identity", "surefire-reports", resultEntry.get().contentName);
    }

    checkEquals("config: kind mismatch does not leak", Optional.empty(), loaded.registry.lookup("FILE_REGISTERED", IdeaTargetRegistry.Kind.RUN_CONFIGURATION));

    // 2. Target rows may appear before or after scalars.
    String reordered = "FILE_REGISTERED\tFILE\t" + fileTarget.toAbsolutePath() + "\n"
        + "socketPath\t" + socketPath.toAbsolutePath() + "\n"
        + "RUN_REGISTERED\tRUN_CONFIGURATION\tStudyPilotApplication\n"
        + "ledgerPath\t" + ledgerPath.toAbsolutePath() + "\n"
        + "RESULT_REGISTERED\tTEST_RESULT\tRun\tsurefire-reports\n"
        + "projectRoot\t" + dir.toAbsolutePath() + "\n";
    Path configReordered = writeConfig("reordered", reordered);
    PluginConfig loadedReordered = PluginConfig.load(configReordered, envWithSecret());
    checkEquals("config: order independent registry size", 3, loadedReordered.registry.size());

    // 3. Duplicate scalar key.
    String duplicateScalar =
        baseScalars(socketPath, ledgerPath, dir, fileTarget)
            + "socketPath\t" + socketPath.toAbsolutePath() + "\n";
    checkEquals(
        "config: duplicate scalar key rejected",
        true,
        throwsIOException(() -> PluginConfig.load(writeConfig("dup-scalar", duplicateScalar), envWithSecret())));

    // 4. Unknown scalar key.
    String unknownScalar = baseScalars(socketPath, ledgerPath, dir, fileTarget) + "socketPathX\t/x\n";
    checkEquals(
        "config: unknown scalar key rejected",
        true,
        throwsIOException(() -> PluginConfig.load(writeConfig("unknown-scalar", unknownScalar), envWithSecret())));

    // 5. Duplicate handle.
    String duplicateHandle =
        baseScalars(socketPath, ledgerPath, dir, fileTarget)
            + "FILE_REGISTERED\tFILE\t" + fileTarget.toAbsolutePath() + "\n";
    checkEquals(
        "config: duplicate handle rejected",
        true,
        throwsIOException(() -> PluginConfig.load(writeConfig("dup-handle", duplicateHandle), envWithSecret())));

    // 6. Malformed UTF-8.
    Path badUtf8 = tempRoot.resolve("bad-utf8.tsv");
    byte[] head = ("socketPath\t" + socketPath.toAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8);
    byte[] bad = new byte[] {'p', 'r', 'o', 'j', 'e', 'c', 't', 'R', 'o', 'o', 't', '\t', (byte) 0xC3, (byte) 0x28, '\n'};
    byte[] all = new byte[head.length + bad.length];
    System.arraycopy(head, 0, all, 0, head.length);
    System.arraycopy(bad, 0, all, head.length, bad.length);
    Files.write(badUtf8, all);
    checkEquals(
        "config: malformed UTF-8 rejected",
        true,
        throwsIOException(() -> PluginConfig.load(badUtf8, envWithSecret())));

    // 7. Wrong column count for a target row.
    String badColumns = baseScalars(socketPath, ledgerPath, dir, fileTarget) + "EXTRA\tFILE\t/a\t/b\n";
    checkEquals(
        "config: wrong column count rejected",
        true,
        throwsIOException(() -> PluginConfig.load(writeConfig("bad-cols", badColumns), envWithSecret())));

    // 8. Missing projectRoot.
    Path noRoot = writeConfig(
        "no-root",
        "socketPath\t" + socketPath.toAbsolutePath() + "\nledgerPath\t" + ledgerPath.toAbsolutePath() + "\n"
            + "FILE_REGISTERED\tFILE\t" + fileTarget.toAbsolutePath() + "\n");
    checkEquals(
        "config: missing projectRoot rejected",
        true,
        throwsIOException(() -> PluginConfig.load(noRoot, envWithSecret())));

    // 9. FILE target that is a directory.
    Path noFileTarget = writeConfig(
        "dir-target",
        baseScalars(socketPath, ledgerPath, dir, dir));
    checkEquals(
        "config: directory FILE target rejected",
        true,
        throwsIOException(() -> PluginConfig.load(noFileTarget, envWithSecret())));

    // 10. FILE target reached through a symlinked directory component.
    Path realDir = Files.createDirectories(tempRoot.resolve("real-dir"));
    Path realFile = realDir.resolve("Real.java");
    Files.write(realFile, "class Real {}".getBytes(StandardCharsets.UTF_8));
    Path linkDir = tempRoot.resolve("link-dir");
    Files.createSymbolicLink(linkDir, realDir);
    Path symlinkedTarget = linkDir.resolve("Real.java");
    Path symlinkConfig = writeConfig(
        "symlink-target",
        baseScalars(socketPath, ledgerPath, dir, symlinkedTarget));
    checkEquals(
        "config: FILE target through symlinked component rejected",
        true,
        throwsIOException(() -> PluginConfig.load(symlinkConfig, envWithSecret())));

    // 11. socket parent under a symlinked directory.
    Path linkSocketDir = tempRoot.resolve("link-socket-dir");
    Files.createSymbolicLink(linkSocketDir, realDir);
    Path symlinkSocketConfig = writeConfig(
        "symlink-socket",
        baseScalars(linkSocketDir.resolve("p.sock"), ledgerPath, dir, fileTarget));
    checkEquals(
        "config: socket path through symlinked component rejected",
        true,
        throwsIOException(() -> PluginConfig.load(symlinkSocketConfig, envWithSecret())));

    // 12. Dot segments in projectRoot are canonicalised, not kept raw.
    String dotted = "socketPath\t" + socketPath.toAbsolutePath() + "\n"
        + "ledgerPath\t" + ledgerPath.toAbsolutePath() + "\n"
        + "projectRoot\t" + dir.toAbsolutePath() + "/./sub/..\n"
        + "RUN_REGISTERED\tRUN_CONFIGURATION\tStudyPilotApplication\n";
    Path dottedConfig = writeConfig("dotted-root", dotted);
    PluginConfig dottedLoaded = PluginConfig.load(dottedConfig, envWithSecret());
    Optional<IdeaTargetRegistry.RegisteredTarget> dottedRun =
        dottedLoaded.registry.lookup("RUN_REGISTERED", IdeaTargetRegistry.Kind.RUN_CONFIGURATION);
    check("config: dotted root still loads", dottedRun.isPresent());
    if (dottedRun.isPresent()) {
      checkEquals("config: dotted root canonicalised", dir.toRealPath().toString(), dottedRun.get().projectRoot);
    }

    // 13. A config file that is itself a symlink is rejected.
    Path link = tempRoot.resolve("link-config.tsv");
    Files.createSymbolicLink(link, config);
    checkEquals(
        "config: symlinked config file rejected",
        true,
        throwsIOException(() -> PluginConfig.load(link, envWithSecret())));
  }

  private static boolean throwsIOException(ThrowingRunnable runnable) {
    try {
      runnable.run();
      return false;
    } catch (IOException e) {
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  // ------------------------------------------------------------------ paths

  private static void pathBindingTests() throws Exception {
    Path root = Files.createDirectories(tempRoot.resolve("binding/work/project"));
    Path child = Files.createDirectories(root.resolve("src"));
    check("binding: child contained in root", PathBinding.contains(root, child));
    check("binding: root contains itself", PathBinding.contains(root, root));

    Path sibling = Files.createDirectories(tempRoot.resolve("binding/work/project2"));
    checkEquals("binding: prefix sibling not contained", false, PathBinding.contains(root, sibling));

    Path dotted = tempRoot.resolve("binding/work/./project/src/..");
    checkEquals(
        "binding: dot segments canonicalised",
        root.toRealPath().toString(),
        PathBinding.canonical(dotted, true).toString());

    Path outside = tempRoot.resolve("binding/elsewhere");
    Files.createDirectories(outside);
    checkEquals("binding: unrelated path not contained", false, PathBinding.contains(root, outside));

    // A symlinked component must be rejected anywhere in the path.
    Path linkRoot = tempRoot.resolve("binding/link-root");
    Files.createSymbolicLink(linkRoot, root);
    checkEquals(
        "binding: symlinked component rejected",
        true,
        throwsIOException(() -> PathBinding.canonical(linkRoot.resolve("src"), true)));

    // A missing path with canonicalisation disallowed to exist must fail.
    checkEquals(
        "binding: missing required path rejected",
        true,
        throwsIOException(() -> PathBinding.canonical(tempRoot.resolve("binding/does-not-exist"), true)));
  }

  // ------------------------------------------------------------------ framing

  private static PluginConfig socketConfig(Path socketPath, Path ledgerPath, Path projectRoot) throws IOException {
    return socketConfig(socketPath, ledgerPath, projectRoot, null);
  }

  private static PluginConfig socketConfig(
      Path socketPath, Path ledgerPath, Path projectRoot, Path fileTarget) throws IOException {
    String body =
        "socketPath\t" + socketPath.toAbsolutePath() + "\n"
            + "ledgerPath\t" + ledgerPath.toAbsolutePath() + "\n"
            + "projectRoot\t" + projectRoot.toAbsolutePath() + "\n"
            + "RUN_REGISTERED\tRUN_CONFIGURATION\tStudyPilotApplication\n";
    if (fileTarget != null) {
      body += "FILE_REGISTERED\tFILE\t" + fileTarget.toAbsolutePath() + "\n";
    }
    return PluginConfig.load(writeConfig("socket-" + socketPath.getFileName(), body), envWithSecret());
  }

  private static String readResponse(Path socketPath, byte[] payload) throws IOException {
    return readResponse(socketPath, payload, -1);
  }

  /**
   * Writes the payload, optionally writes a delayed trailing byte after a pause, then
   * half-closes the write side so the plugin can read through EOF. The reply is read until
   * the plugin closes its side. No timing assumption is required for correctness: the plugin
   * accepts a request only when the bytes it read are exactly one newline-terminated frame.
   */
  private static String readResponse(Path socketPath, byte[] payload, long delayedTrailingAfterMs)
      throws IOException {
    try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      client.connect(UnixDomainSocketAddress.of(socketPath));
      client.configureBlocking(true);
      ByteBuffer out = ByteBuffer.wrap(payload);
      while (out.hasRemaining()) {
        client.write(out);
      }
      if (delayedTrailingAfterMs >= 0) {
        try {
          Thread.sleep(delayedTrailingAfterMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        ByteBuffer trailing = ByteBuffer.wrap("{\"late\":true}\n".getBytes(StandardCharsets.UTF_8));
        while (trailing.hasRemaining()) {
          client.write(trailing);
        }
      }
      client.shutdownOutput();
      ByteBuffer in = ByteBuffer.allocate(65536);
      StringBuilder response = new StringBuilder();
      long deadline = System.currentTimeMillis() + 5000;
      while (System.currentTimeMillis() < deadline) {
        in.clear();
        int read = client.read(in);
        if (read > 0) {
          in.flip();
          byte[] bytes = new byte[in.remaining()];
          in.get(bytes);
          response.append(new String(bytes, StandardCharsets.UTF_8));
          if (response.indexOf("\n") >= 0) {
            break;
          }
        } else if (read < 0) {
          break;
        }
      }
      return response.toString();
    }
  }

  private static void singleFrameTests() throws Exception {
    // Unix domain socket paths are limited to ~104 bytes, so sockets live in a short /tmp dir.
    Path socketDir = Files.createTempDirectory(Path.of("/tmp"), "t33h-");
    Path socketPath = socketDir.resolve("single.sock");
    Path ledgerPath = socketDir.resolve("single.ledger");
    Path projectRoot = Files.createDirectories(tempRoot.resolve("sock-project"));

    PluginConfig config = socketConfig(socketPath, ledgerPath, projectRoot);
    NoopPlatform platform = new NoopPlatform();
    PluginSocketServer server = new PluginSocketServer(
        config, new PluginProtocol(config.secret), dispatcherFor(config, platform), message -> {});
    server.start();
    try {
      check("frame: socket file created", Files.exists(socketPath));
      checkEquals(
          "frame: socket is owner-only",
          EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
          Files.getPosixFilePermissions(socketPath));

      // A valid signed frame followed by trailing bytes in the SAME write must be rejected.
      byte[] validFrame = PluginTestFrames.validRunFrame(config);
      byte[] withTrailing = new byte[validFrame.length + 10];
      System.arraycopy(validFrame, 0, withTrailing, 0, validFrame.length);
      System.arraycopy("{\"x\":1}\n".getBytes(StandardCharsets.UTF_8), 0, withTrailing, validFrame.length, 8);
      String trailingResponse = readResponse(socketPath, withTrailing);
      check(
          "frame: valid frame plus trailing json rejected: " + trailingResponse.trim(),
          trailingResponse.contains("INVALID_FRAME"));

      // A valid single frame must still be accepted (proving the check is not blanket).
      int callsBefore = platform.calls;
      String goodResponse = readResponse(socketPath, validFrame);
      check(
          "frame: single valid frame still answered: " + goodResponse.trim(),
          goodResponse.contains("\"status\""));
      check("frame: single valid frame produced an effect", platform.calls == callsBefore + 1);

      // Trailing data that only arrives in a LATER write must still be rejected, with no
      // effect at all: enforcement must not depend on write timing.
      int callsBeforeLate = platform.calls;
      String lateTrailingResponse = readResponse(socketPath, PluginTestFrames.validRunFrame(config), 150L);
      check(
          "frame: delayed trailing bytes rejected: " + lateTrailingResponse.trim(),
          lateTrailingResponse.contains("INVALID_FRAME"));
      check("frame: delayed trailing bytes produced no effect", platform.calls == callsBeforeLate);

      // A second whole frame arriving later is rejected the same way.
      int callsBeforeSecond = platform.calls;
      byte[] twoFrames = new byte[validFrame.length + validFrame.length];
      System.arraycopy(validFrame, 0, twoFrames, 0, validFrame.length);
      System.arraycopy(validFrame, 0, twoFrames, validFrame.length, validFrame.length);
      String secondFrameResponse = readResponse(socketPath, twoFrames);
      check(
          "frame: second frame rejected: " + secondFrameResponse.trim(),
          secondFrameResponse.contains("INVALID_FRAME"));
      check("frame: second frame produced no effect", platform.calls == callsBeforeSecond);

      // Oversize frame keeps its own code.
      byte[] oversize = new byte[PluginProtocol.MAX_FRAME_BYTES + 64];
      java.util.Arrays.fill(oversize, (byte) 'a');
      oversize[oversize.length - 1] = '\n';
      String oversizeResponse = readResponse(socketPath, oversize);
      check(
          "frame: oversize frame rejected: " + oversizeResponse.trim(),
          oversizeResponse.contains("OVERSIZE_FRAME"));
    } finally {
      server.stop();
    }

    // A pre-existing regular file at socketPath must be refused, not deleted.
    Path occupiedDir = Files.createTempDirectory(Path.of("/tmp"), "t33ho-");
    Path occupied = occupiedDir.resolve("occupied.sock");
    Files.write(occupied, "not a socket".getBytes(StandardCharsets.UTF_8));
    PluginConfig occupiedConfig = socketConfig(occupied, occupiedDir.resolve("n.ledger"), projectRoot);
    PluginSocketServer occupiedServer = new PluginSocketServer(
        occupiedConfig, new PluginProtocol(occupiedConfig.secret), dispatcherFor(occupiedConfig), message -> {});
    boolean refused = false;
    try {
      occupiedServer.start();
    } catch (IOException e) {
      refused = true;
    } finally {
      occupiedServer.stop();
    }
    check("frame: pre-existing regular file at socket path refused", refused);
    check("frame: pre-existing object not deleted", Files.exists(occupied));

    // A world-writable parent directory must be refused.
    Path looseDir = Files.createTempDirectory(Path.of("/tmp"), "t33hl-");
    Files.setPosixFilePermissions(
        looseDir,
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.OTHERS_WRITE));
    try {
      PluginConfig looseConfig = socketConfig(looseDir.resolve("loose.sock"), looseDir.resolve("n.ledger"), projectRoot);
      PluginSocketServer looseServer = new PluginSocketServer(
          looseConfig, new PluginProtocol(looseConfig.secret), dispatcherFor(looseConfig), message -> {});
      boolean looseRefused = false;
      try {
        looseServer.start();
      } catch (IOException e) {
        looseRefused = true;
      } finally {
        looseServer.stop();
      }
      check("frame: other-writable parent directory refused", looseRefused);
    } finally {
      Files.setPosixFilePermissions(
          looseDir,
          EnumSet.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE));
    }
  }

  private static IdeActionDispatcher dispatcherFor(PluginConfig config) {
    return dispatcherFor(config, new NoopPlatform());
  }

  private static IdeActionDispatcher dispatcherFor(PluginConfig config, IdePlatform platform) {
    return new IdeActionDispatcher(
        new PluginProtocol(config.secret),
        config.registry,
        new NonceLedger(config.ledgerPath),
        platform,
        new ImmediateUi(),
        System::currentTimeMillis,
        2000L);
  }

  private static final class NoopPlatform implements IdePlatform {
    volatile int calls = 0;
    volatile IdeOutcome outcome = IdeOutcome.verified();

    @Override
    public IdeOutcome openRegisteredFile(String canonicalPath, String projectRoot) {
      calls++;
      return outcome;
    }

    @Override
    public IdeOutcome focusRunConfiguration(String configurationName, String projectRoot) {
      calls++;
      return outcome;
    }

    @Override
    public IdeOutcome showTestResult(String toolWindowId, String contentName, String projectRoot) {
      calls++;
      return outcome;
    }
  }

  private static final class ImmediateUi implements UiExecutor {
    @Override
    public <T> T runOnUiThread(Callable<T> task, long timeoutMs) {
      try {
        return task.call();
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  // ------------------------------------------------------------------ content matcher

  /**
   * Builds an observed-content descriptor. Recognition is type-backed: {@code descriptorLinked}
   * plus {@code testConsole} are the signals, and the wrapper component type is diagnostic-only.
   */
  private static TestResultContentMatcher.ContentView content(
      String displayName, boolean descriptorLinked, boolean testConsole, String wrapperType, boolean valid) {
    return new TestResultContentMatcher.ContentView(
        displayName, valid, descriptorLinked, testConsole, wrapperType);
  }

  /** A genuine test result view: linked descriptor and a real test console type. */
  private static TestResultContentMatcher.ContentView testResult(String displayName) {
    return content(displayName, true, true, "javax.swing.JPanel", true);
  }

  /** A non-test console (plain run output) with the same display name. */
  private static TestResultContentMatcher.ContentView plainConsole(String displayName) {
    return content(displayName, true, false, "com.intellij.execution.impl.ConsoleViewImpl", true);
  }

  /** An editor-like or unrelated content: no run content descriptor at all. */
  private static TestResultContentMatcher.ContentView unrelated(String displayName) {
    return content(displayName, false, false, "com.intellij.openapi.editor.impl.EditorImpl", true);
  }

  private static final String REGISTERED_RESULT = "LogTest.testLog";

  /**
   * The response boundary must be exactly one newline-terminated JSON frame. This is asserted
   * against the REAL Java UDS server, because the production defect was a producer-side
   * missing terminating LF (the TypeScript client correctly rejected it).
   */
  private static void responseFramingTests() throws Exception {
    Path socketDir = Files.createTempDirectory(Path.of("/tmp"), "t33fr-");
    Path socketPath = socketDir.resolve("f.sock");
    Path ledgerPath = socketDir.resolve("f.ledger");
    Path projectRoot = Files.createDirectories(tempRoot.resolve("fr-project"));
    Path target = projectRoot.resolve("Registered.java");
    Files.write(target, "class Registered {}".getBytes(StandardCharsets.UTF_8));

    PluginConfig config = socketConfig(socketPath, ledgerPath, projectRoot, target);
    NoopPlatform platform = new NoopPlatform();
    PluginSocketServer server =
        new PluginSocketServer(
            config, new PluginProtocol(config.secret), dispatcherFor(config, platform), message -> {});
    server.start();
    try {
      String rejected = readResponse(socketPath, "{}\n".getBytes(StandardCharsets.UTF_8));
      assertSingleResponseFrame("framing: rejected response", rejected);
      check("framing: rejected response code", rejected.contains("MISSING_FIELD"));

      String succeeded = readResponse(socketPath, PluginTestFrames.validFileFrame(config));
      assertSingleResponseFrame("framing: succeeded response", succeeded);
      check("framing: succeeded status", succeeded.contains("\"status\":\"SUCCEEDED\""));

      platform.outcome = IdeOutcome.unverified("POST_STATE_NOT_VERIFIED", "not proven");
      String failed = readResponse(socketPath, PluginTestFrames.validFileFrame(config));
      assertSingleResponseFrame("framing: failed response", failed);
      check("framing: failed status", failed.contains("\"status\":\"FAILED\""));

      byte[] oversize = new byte[PluginProtocol.MAX_FRAME_BYTES + 64];
      java.util.Arrays.fill(oversize, (byte) 'a');
      oversize[oversize.length - 1] = '\n';
      String oversizeResponse = readResponse(socketPath, oversize);
      assertSingleResponseFrame("framing: oversize response", oversizeResponse);
      check("framing: oversize code", oversizeResponse.contains("OVERSIZE_FRAME"));
    } finally {
      server.stop();
    }
  }

  private static void assertSingleResponseFrame(String name, String response) {
    check(name + ": response is non-empty", response != null && !response.isEmpty());
    check(name + ": ends with exactly one LF", response.endsWith("\n"));
    int newlines = 0;
    for (int i = 0; i < response.length(); i++) {
      if (response.charAt(i) == '\n') {
        newlines++;
      }
    }
    check(name + ": exactly one newline in total", newlines == 1);
    String body = response.substring(0, response.length() - 1);
    check(name + ": no carriage return", body.indexOf('\r') < 0);
    check(
        name + ": body is exactly one JSON object",
        body.startsWith("{") && body.endsWith("}") && body.indexOf("}{") < 0);
  }

  /**
   * UI scheduling invariants. The measured production defect was a scheduling condition that
   * treated a HEALTHY IDE as expired, so no queued callable ever ran.
   */
  private static void uiSchedulerTests() throws Exception {
    UiScheduler liveScheduler =
        new UiScheduler() {
          @Override
          public void schedule(Runnable runnable) {
            Thread thread = new Thread(runnable, "fake-edt");
            thread.setDaemon(true);
            thread.start();
          }

          @Override
          public boolean isDisposed() {
            return false;
          }
        };
    try {
      checkEquals(
          "ui: live callable executes and returns",
          "ran",
          new IdeUiExecutor(liveScheduler).runOnUiThread(() -> "ran", 2000L));
    } catch (Exception e) {
      check("ui: live callable executes and returns (threw " + e.getClass().getSimpleName() + ")", false);
    }

    UiScheduler blockedScheduler =
        new UiScheduler() {
          @Override
          public void schedule(Runnable runnable) {
            // never runs
          }

          @Override
          public boolean isDisposed() {
            return false;
          }
        };
    boolean timedOut = false;
    try {
      new IdeUiExecutor(blockedScheduler).runOnUiThread(() -> "never", 300L);
    } catch (UiExecutor.UiTimeoutException e) {
      timedOut = true;
    } catch (Exception e) {
      // fall through to the assertion
    }
    check("ui: blocked callable fails closed with a timeout", timedOut);

    final java.util.concurrent.atomic.AtomicInteger lateEffects =
        new java.util.concurrent.atomic.AtomicInteger(0);
    final java.util.List<Runnable> deferred = new java.util.ArrayList<>();
    UiScheduler deferredScheduler =
        new UiScheduler() {
          @Override
          public void schedule(Runnable runnable) {
            synchronized (deferred) {
              deferred.add(runnable);
            }
          }

          @Override
          public boolean isDisposed() {
            return false;
          }
        };
    boolean deferredTimedOut = false;
    try {
      new IdeUiExecutor(deferredScheduler)
          .runOnUiThread(
              () -> {
                lateEffects.incrementAndGet();
                return "late";
              },
              300L);
    } catch (UiExecutor.UiTimeoutException e) {
      deferredTimedOut = true;
    } catch (Exception e) {
      // fall through
    }
    check("ui: deferred callable fails closed with a timeout", deferredTimedOut);
    synchronized (deferred) {
      for (Runnable runnable : deferred) {
        runnable.run();
      }
    }
    checkEquals("ui: timed-out callable produces no late side effect", 0, lateEffects.get());

    final java.util.concurrent.atomic.AtomicInteger scheduled =
        new java.util.concurrent.atomic.AtomicInteger(0);
    UiScheduler disposedScheduler =
        new UiScheduler() {
          @Override
          public void schedule(Runnable runnable) {
            scheduled.incrementAndGet();
          }

          @Override
          public boolean isDisposed() {
            return true;
          }
        };
    boolean disposed = false;
    try {
      new IdeUiExecutor(disposedScheduler).runOnUiThread(() -> "nope", 1000L);
    } catch (UiExecutor.IdeDisposedException e) {
      disposed = true;
    } catch (Exception e) {
      // fall through
    }
    check("ui: disposed IDE fails closed", disposed);
    checkEquals("ui: disposed IDE schedules nothing", 0, scheduled.get());
  }

  private static void testResultMatcherTests() {
    // The MEASURED production case: the Run tool window content is wrapped in a plain container,
    // so the wrapper class is NOT a test-framework type even though the console behind it is a
    // genuine test console. The previous class-name-prefix predicate failed here.
    TestResultContentMatcher.Result wrapped = TestResultContentMatcher.match(
        List.of(testResult(REGISTERED_RESULT), testResult("Other.testLog")), REGISTERED_RESULT);
    checkEquals(
        "matcher: wrapper-typed test result is recognised (measured case)",
        TestResultContentMatcher.Outcome.MATCHED,
        wrapped.outcome);
    checkEquals("matcher: matched index", 0, wrapped.index);

    // No content at all.
    checkEquals(
        "matcher: no content",
        TestResultContentMatcher.Outcome.NOT_FOUND,
        TestResultContentMatcher.match(List.of(), REGISTERED_RESULT).outcome);

    // An unrelated single content must not be accepted (no "the only content" fallback).
    checkEquals(
        "matcher: unrelated single content rejected",
        TestResultContentMatcher.Outcome.NOT_FOUND,
        TestResultContentMatcher.match(List.of(testResult("Unrelated.testLog")), REGISTERED_RESULT).outcome);

    // Same-name duplicates are ambiguous.
    checkEquals(
        "matcher: same-name duplicates ambiguous",
        TestResultContentMatcher.Outcome.AMBIGUOUS,
        TestResultContentMatcher.match(
                List.of(testResult(REGISTERED_RESULT), testResult(REGISTERED_RESULT)), REGISTERED_RESULT)
            .outcome);

    // A plain console with the same name is not a test result.
    checkEquals(
        "matcher: console content with same name rejected",
        TestResultContentMatcher.Outcome.NOT_A_TEST_RESULT,
        TestResultContentMatcher.match(List.of(plainConsole(REGISTERED_RESULT)), REGISTERED_RESULT).outcome);

    // An editor/unrelated content with the same name is not a test result either.
    checkEquals(
        "matcher: editor content with same name rejected",
        TestResultContentMatcher.Outcome.NOT_A_TEST_RESULT,
        TestResultContentMatcher.match(List.of(unrelated(REGISTERED_RESULT)), REGISTERED_RESULT).outcome);

    // A linked descriptor whose console is not a test console fails closed.
    checkEquals(
        "matcher: linked non-test console rejected",
        TestResultContentMatcher.Outcome.NOT_A_TEST_RESULT,
        TestResultContentMatcher.match(
                List.of(content(REGISTERED_RESULT, true, false, "javax.swing.JPanel", true)), REGISTERED_RESULT)
            .outcome);

    // A test console that could not be linked to a descriptor fails closed.
    checkEquals(
        "matcher: unlinked test console rejected",
        TestResultContentMatcher.Outcome.NOT_A_TEST_RESULT,
        TestResultContentMatcher.match(
                List.of(content(REGISTERED_RESULT, false, true, "javax.swing.JPanel", true)), REGISTERED_RESULT)
            .outcome);

    // A disposed/invalid content cannot match.
    checkEquals(
        "matcher: invalid content rejected",
        TestResultContentMatcher.Outcome.NOT_FOUND,
        TestResultContentMatcher.match(
                List.of(content(REGISTERED_RESULT, true, true, "javax.swing.JPanel", false)), REGISTERED_RESULT)
            .outcome);

    // One invalid and one valid duplicate: the invalid one must not create ambiguity.
    checkEquals(
        "matcher: invalid duplicate ignored",
        TestResultContentMatcher.Outcome.MATCHED,
        TestResultContentMatcher.match(
                List.of(
                    content(REGISTERED_RESULT, true, true, "javax.swing.JPanel", false),
                    testResult(REGISTERED_RESULT)),
                REGISTERED_RESULT)
            .outcome);

    // A registered identity must be non-empty and bounded.
    checkEquals(
        "matcher: empty registered identity rejected",
        TestResultContentMatcher.Outcome.INVALID_REGISTRATION,
        TestResultContentMatcher.match(List.of(testResult("")), "").outcome);
    checkEquals(
        "matcher: oversized registered identity rejected",
        TestResultContentMatcher.Outcome.INVALID_REGISTRATION,
        TestResultContentMatcher.match(List.of(testResult("x".repeat(200))), "x".repeat(200)).outcome);

    // Exact match only: a partial title must not be accepted.
    checkEquals(
        "matcher: partial title rejected",
        TestResultContentMatcher.Outcome.NOT_FOUND,
        TestResultContentMatcher.match(List.of(testResult(REGISTERED_RESULT + "-summary")), REGISTERED_RESULT)
            .outcome);

    // Unknown layout: a same-name content that is neither linked nor a test console stays
    // failed closed rather than being accepted heuristically.
    checkEquals(
        "matcher: unknown layout fails closed",
        TestResultContentMatcher.Outcome.NOT_A_TEST_RESULT,
        TestResultContentMatcher.match(
                List.of(content(REGISTERED_RESULT, false, false, "com.intellij.ui.components.JBPanel", true)),
                REGISTERED_RESULT)
            .outcome);
  }

  private static void deleteRecursively(Path root) {
    try {
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
                  // best effort
                }
              });
    } catch (Exception ignored) {
      // best effort
    }
  }
}
