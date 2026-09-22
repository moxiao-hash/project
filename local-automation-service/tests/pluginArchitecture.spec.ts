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

  it('uses the trustworthful IntelliJ verification APIs', () => {
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
    expect(platform).toContain('VfsUtilCore.isAncestor');
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
