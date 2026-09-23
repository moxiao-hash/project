import fs from 'node:fs';
import path from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * Architecture guards for the revised IDEA production architecture.
 *
 * These prove at source level that:
 *   * the IDEA channel is executed by the trusted JetBrains plugin, not by the macOS AX probe;
 *   * the plugin cannot open a TCP/HTTP listener, execute a generic IntelliJ Action, run or
 *     debug tests, accept paths or free text, or reach the Java-facing socket;
 *   * the build inputs for the installable plugin artifact exist.
 */

const packageRoot = path.resolve(__dirname, '..');
const pluginRoot = path.join(packageRoot, 'idea-plugin');

function collectFiles(root: string, suffix: string): { rel: string; content: string }[] {
  const out: { rel: string; content: string }[] = [];
  if (!fs.existsSync(root)) {
    return out;
  }
  const walk = (dir: string): void => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (entry.name === 'build' || entry.name === 'node_modules') {
        continue;
      }
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (entry.isFile() && entry.name.endsWith(suffix)) {
        out.push({ rel: path.relative(packageRoot, full), content: fs.readFileSync(full, 'utf8') });
      }
    }
  };
  walk(root);
  return out;
}

const pluginJava = collectFiles(path.join(pluginRoot, 'src/main/java'), '.java');
const pluginResources = collectFiles(path.join(pluginRoot, 'src/main/resources'), '.xml');
const pluginAll = [...pluginJava, ...pluginResources];

describe('IDEA production path is the trusted plugin, never the AX probe', () => {
  const adapter = fs.readFileSync(path.join(packageRoot, 'src/ideaAdapter.ts'), 'utf8');
  const service = fs.readFileSync(path.join(packageRoot, 'src/service.ts'), 'utf8');

  it('wires the IDEA channel to the plugin adapter', () => {
    expect(adapter).toContain('PluginBridgeIdeaAutomationAdapter');
    expect(adapter).toContain('createDefaultIdeaAdapter');
    expect(service).toContain('createDefaultIdeaAdapter');
    expect(service).not.toContain('NativeBridgeIdeaAutomationAdapter');
  });

  it('marks the AX binding diagnostic-only and refuses every AX outcome', () => {
    expect(adapter).toContain('AX_DIAGNOSTIC_ONLY');
    expect(adapter).toContain('createDiagnosticAxBridge');
    const axAdapterStart = adapter.indexOf('export class NativeBridgeIdeaAutomationAdapter');
    const axAdapterEnd = adapter.indexOf('export class PluginBridgeIdeaAutomationAdapter');
    expect(axAdapterStart).toBeGreaterThan(0);
    expect(axAdapterEnd).toBeGreaterThan(axAdapterStart);
    const axAdapter = adapter.slice(axAdapterStart, axAdapterEnd);
    // The diagnostics adapter must not invoke the AX bridge for an outcome.
    expect(axAdapter).not.toContain('this.bridge.openFile');
    expect(axAdapter).not.toContain('this.bridge.focusConfiguration');
    expect(axAdapter).not.toContain('this.bridge.showResult');

    const pluginAdapter = adapter.slice(axAdapterEnd);
    // The plugin adapter never consults the AX bridge.
    expect(pluginAdapter).not.toContain('axBridge');
    expect(pluginAdapter).not.toContain('MacAxIdeaBridge');
  });

  it('forwards only the opaque handle to the execution backend', () => {
    expect(service).toContain('openRegisteredFile(resolution.idea.targetKey)');
    expect(service).not.toContain('openRegisteredFile(\n            resolution.idea.resolvedPath');
  });

  it('keeps the external adapter identifier and six-action protocol unchanged', () => {
    const types = fs.readFileSync(path.join(packageRoot, 'src/types.ts'), 'utf8');
    expect(types).toContain("'PLAYWRIGHT_DOM' | 'IDEA_ACCESSIBILITY'");
    for (const action of [
      'OPEN_STUDYPILOT_ROUTE',
      'FOCUS_AGENT_INPUT',
      'OPEN_RESULT_PANEL',
      'OPEN_REGISTERED_FILE',
      'FOCUS_RUN_CONFIGURATION',
      'SHOW_TEST_RESULT',
    ]) {
      expect(types).toContain(action);
    }
  });

  it('requires a separate socket and a separate key for the plugin link', () => {
    const config = fs.readFileSync(path.join(packageRoot, 'src/config.ts'), 'utf8');
    expect(config).toContain('STUDYPILOT_AUTOMATION_IDEA_PLUGIN_SOCKET_PATH');
    expect(config).toContain('STUDYPILOT_AUTOMATION_IDEA_PLUGIN_HMAC_SECRET');
    expect(config).toContain('must differ from the Java-facing socket path');
    expect(config).toContain('must differ from the Java-facing signing key');
  });
});

describe('Plugin source guards', () => {
  it('ships plugin sources and the installable build inputs', () => {
    expect(pluginJava.length).toBeGreaterThan(8);
    expect(fs.existsSync(path.join(pluginRoot, 'src/main/resources/META-INF/plugin.xml'))).toBe(true);
    expect(fs.existsSync(path.join(pluginRoot, 'build.gradle.kts'))).toBe(true);
    expect(fs.existsSync(path.join(pluginRoot, 'settings.gradle.kts'))).toBe(true);
    expect(fs.existsSync(path.join(pluginRoot, 'gradle.properties'))).toBe(true);
    expect(fs.existsSync(path.join(pluginRoot, 'build-local.sh'))).toBe(true);
  });

  it('never opens a TCP or HTTP listener or transport', () => {
    const forbidden = [
      'java.net.ServerSocket',
      'java.net.Socket(',
      'HttpURLConnection',
      'java.net.http',
      'java.net.URL(',
      'SocketChannel.open()',
    ];
    const violations = pluginAll.filter((file) => forbidden.some((token) => file.content.includes(token)));
    expect(violations.map((file) => file.rel)).toEqual([]);
  });

  it('uses only the UNIX protocol family for its own socket', () => {
    const server = fs.readFileSync(
      path.join(pluginRoot, 'src/main/java/com/studypilot/automation/idea/platform/PluginSocketServer.java'),
      'utf8'
    );
    expect(server).toContain('StandardProtocolFamily.UNIX');
    expect(server).toContain('UnixDomainSocketAddress');
    expect(server).toContain('PosixFilePermission.OWNER_READ');
    expect(server).toContain('must not be writable by other users');
  });

  it('never executes a generic IntelliJ Action, run/debug, or tests', () => {
    const forbidden = [
      'ActionManager',
      'getAction(',
      'ExecutionManager',
      'ProgramRunner',
      'DebuggerManager',
      'runConfiguration(',
      'TestRunnerService',
      'SMTestRunner',
      'RunConfigurationExecutor',
    ];
    const violations = pluginJava.filter((file) => forbidden.some((token) => file.content.includes(token)));
    expect(violations.map((file) => file.rel)).toEqual([]);
  });

  it('never uses robot input, magic text entry, reflection or shell escape hatches', () => {
    const forbidden = [
      'new Robot',
      'java.awt.Robot',
      'KeyEvent',
      'MouseEvent',
      'setText(',
      'java.lang.reflect',
      'Class.forName',
      'ProcessBuilder',
      'Runtime.getRuntime',
      'osascript',
      'AppleScript',
    ];
    const violations = pluginJava.filter((file) => forbidden.some((token) => file.content.includes(token)));
    expect(violations.map((file) => file.rel)).toEqual([]);
  });

  it('never reaches the Java-facing socket configuration', () => {
    const forbidden = ['STUDYPILOT_AUTOMATION_SOCKET_PATH', 'STUDYPILOT_AUTOMATION_HMAC_SECRET', 'nonceDbPath'];
    const violations = pluginAll.filter((file) => forbidden.some((token) => file.content.includes(token)));
    expect(violations.map((file) => file.rel)).toEqual([]);
  });

  it('uses the supported IntelliJ verification APIs and canonical path containment', () => {
    const platform = fs.readFileSync(
      path.join(pluginRoot, 'src/main/java/com/studypilot/automation/idea/platform/IdeaPlatformOperations.java'),
      'utf8'
    );
    expect(platform).toContain('OpenFileDescriptor');
    expect(platform).toContain('FileEditorManager');
    expect(platform).toContain('RunManager');
    expect(platform).toContain('setSelectedConfiguration');
    expect(platform).toContain('getSelectedConfiguration');
    expect(platform).toContain('ToolWindowManager');
    expect(platform).toContain('setSelectedContent');
    expect(platform).toContain('getSelectedContent');
    // Containment is decided on canonical Paths, never on raw string prefixes or VFS paths.
    expect(platform).toContain('PathBinding.contains');
    expect(platform).toContain('PathBinding.canonical');
    expect(platform).not.toContain('VfsUtilCore.isAncestor');
    expect(platform).not.toContain('startsWith(basePath');
    // The registered root must EQUAL the open project's canonical base path.
    expect(platform).toContain('candidate.equals(registeredRoot)');
  });

  it('binds a test-result handle to one exact existing content identity', () => {
    const platform = fs.readFileSync(
      path.join(pluginRoot, 'src/main/java/com/studypilot/automation/idea/platform/IdeaPlatformOperations.java'),
      'utf8'
    );
    const registry = fs.readFileSync(
      path.join(pluginRoot, 'src/main/java/com/studypilot/automation/idea/registry/IdeaTargetRegistry.java'),
      'utf8'
    );
    const matcher = fs.readFileSync(
      path.join(pluginRoot, 'src/main/java/com/studypilot/automation/idea/platform/TestResultContentMatcher.java'),
      'utf8'
    );

    expect(registry).toContain('contentName');
    expect(registry).toContain('TEST_RESULT must bind an exact existing content display name');
    expect(platform).toContain('TestResultContentMatcher.match');
    expect(matcher).toContain('com.intellij.execution.testframework');
    expect(matcher).toContain('AMBIGUOUS');
    // No "the only content" fallback anywhere.
    expect(platform).not.toContain('contents.get(0)');
    expect(platform).not.toContain('existing.get(0)');
  });

  it('enforces exactly one frame in both directions', () => {
    const server = fs.readFileSync(
      path.join(pluginRoot, 'src/main/java/com/studypilot/automation/idea/platform/PluginSocketServer.java'),
      'utf8'
    );
    const client = fs.readFileSync(path.join(packageRoot, 'src/ideaPluginClient.ts'), 'utf8');

    expect(server).toContain('exactly one newline-terminated single-line frame');
    expect(server).toContain('refusing to replace a non-socket object');
    expect(client).toContain('PluginResponseInvalidError');
    expect(client).toContain('PLUGIN_RESPONSE_INVALID');
    expect(client).toContain('exactly one single-line frame');
  });

  it('declares every tool its npm scripts invoke', () => {
    const pkg = JSON.parse(fs.readFileSync(path.join(packageRoot, 'package.json'), 'utf8')) as {
      scripts?: Record<string, string>;
      dependencies?: Record<string, string>;
      devDependencies?: Record<string, string>;
    };
    const declared = new Set([
      ...Object.keys(pkg.dependencies ?? {}),
      ...Object.keys(pkg.devDependencies ?? {}),
    ]);
    const scripts = Object.values(pkg.scripts ?? {});
    const toolToPackage: Record<string, string> = {
      tsx: 'tsx',
      vitest: 'vitest',
      tsc: 'typescript',
      node: 'none',
    };
    const violations: string[] = [];
    for (const [tool, packageName] of Object.entries(toolToPackage)) {
      if (packageName === 'none') {
        continue;
      }
      const invoked = scripts.some((script) =>
        new RegExp(`(^|[\\s&|;(])${tool}([\\s&|;)]|$)`).test(script)
      );
      if (invoked && !declared.has(packageName)) {
        violations.push(`${tool} is invoked by a script but ${packageName} is not declared`);
      }
    }
    expect(violations).toEqual([]);
    // The probe runner must be pinned so verification is reproducible without downloading.
    expect(pkg.devDependencies?.tsx).toMatch(/^\d+\.\d+\.\d+$/);
  });

  it('emits exactly one newline-terminated response frame from the plugin', () => {
    const protocol = fs.readFileSync(
      path.join(pluginRoot, 'src/main/java/com/studypilot/automation/idea/protocol/PluginProtocol.java'),
      'utf8'
    );
    // The terminating LF is part of the frozen frame contract; a bare JSON object made every
    // real action unreadable to the service client.
    expect(protocol).toContain('return line + "\\n"');
    expect(protocol).toContain('EXACTLY one newline-terminated single-line JSON frame');
    expect(protocol).not.toContain('// TEMPORARY');
  });

  it('schedules UI work without an expiration condition and cancels timed-out tasks', () => {
    const executor = fs.readFileSync(
      path.join(pluginRoot, 'src/main/java/com/studypilot/automation/idea/platform/IdeUiExecutor.java'),
      'utf8'
    );
    const scheduler = fs.readFileSync(
      path.join(pluginRoot, 'src/main/java/com/studypilot/automation/idea/platform/ApplicationUiScheduler.java'),
      'utf8'
    );

    // The measured defect was an inverted invokeLater expiration predicate, so no expiration
    // condition may be passed at all.
    expect(scheduler).toContain('invokeLater(runnable, ModalityState.any())');
    expect(scheduler).not.toContain('invokeLater(runnable, condition');
    expect(executor).not.toContain('invokeLater');
    // A timed-out task must be cancelled so it cannot produce a late side effect.
    expect(executor).toContain('future.cancel(false)');
    // Disposal must fail closed explicitly.
    expect(executor).toContain('IdeDisposedException');
  });

  it('enforces exactly one frame deterministically, independent of write timing', () => {
    const server = fs.readFileSync(
      path.join(pluginRoot, 'src/main/java/com/studypilot/automation/idea/platform/PluginSocketServer.java'),
      'utf8'
    );
    const client = fs.readFileSync(path.join(packageRoot, 'src/ideaPluginClient.ts'), 'utf8');

    // The plugin reads the request through EOF before dispatching (the service half-closes).
    expect(server).toContain('did not close before the frame deadline');
    expect(server).toContain('exactly one newline-terminated single-line frame');
    // No one-shot drain heuristic may be used to satisfy the single-frame rule.
    expect(server).not.toContain('ByteBuffer.allocate(1)');

    // The service half-closes and validates the whole reply after close, decoding strictly.
    expect(client).toContain('socket.end(Buffer.from(frame');
    expect(client).toContain("new TextDecoder('utf-8', { fatal: true })");
    expect(client).toContain('newline-terminated frame');
  });

  it('binds every trusted path canonically and rejects symlinked components', () => {
    const pathBinding = fs.readFileSync(
      path.join(pluginRoot, 'src/main/java/com/studypilot/automation/idea/platform/PathBinding.java'),
      'utf8'
    );
    const config = fs.readFileSync(
      path.join(pluginRoot, 'src/main/java/com/studypilot/automation/idea/platform/PluginConfig.java'),
      'utf8'
    );
    expect(pathBinding).toContain('rejectSymlinkComponents');
    expect(pathBinding).toContain('PLATFORM_SYSTEM_LINKS');
    expect(pathBinding).toContain('requireRegularFile');
    expect(config).toContain('PathBinding.canonical');
    expect(config).toContain('duplicate configuration key rejected');
    expect(config).toContain('unknown configuration key rejected');
    expect(config).toContain('configuration file must be valid UTF-8');
  });

  it('declares only the platform module and no executor or test-runner extension', () => {
    const descriptor = fs.readFileSync(path.join(pluginRoot, 'src/main/resources/META-INF/plugin.xml'), 'utf8');
    expect(descriptor).toContain('com.intellij.modules.platform');
    expect(descriptor).not.toContain('executor');
    expect(descriptor).not.toContain('testRunner');
    expect(descriptor).not.toContain('<depends>com.intellij.modules.lang');
  });

  it('keeps the plugin protocol in its own domain', () => {
    const protocol = fs.readFileSync(path.join(packageRoot, 'src/ideaPluginProtocol.ts'), 'utf8');
    expect(protocol).toContain("PLUGIN_PROTOCOL_DOMAIN = 'studypilot-idea-plugin-v1'");
    expect(protocol).toContain('PLUGIN_MAX_LIFETIME_MS = 15_000');
    expect(protocol).toContain('PLUGIN_MIN_SECRET_BYTES = 32');
  });
});
