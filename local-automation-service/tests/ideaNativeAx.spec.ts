import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { describe, it, expect, vi } from 'vitest';
import {
  NativeBridgeIdeaAutomationAdapter,
  PluginBridgeIdeaAutomationAdapter,
  MacAxIdeaBridge,
  createDiagnosticAxBridge,
} from '../src/ideaAdapter.js';
import { loadNativeAxAddon, validateNativeAxModuleSurface } from '../src/nativeAxBridge.js';
import { LocalAutomationService } from '../src/service.js';
import type { ServiceConfig } from '../src/types.js';
import type { IdeaAccessibilityBridge, NativeAxResult } from '../src/ideaAdapter.js';

const packageRoot = path.resolve(__dirname, '..');
const srcDir = path.join(packageRoot, 'src');

function readProductionSources(): { file: string; content: string }[] {
  const results: { file: string; content: string }[] = [];
  const walk = (dir: string): void => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.isFile() && entry.name.endsWith('.ts')) {
        results.push({ file: path.relative(packageRoot, full), content: fs.readFileSync(full, 'utf8') });
      }
    }
  };
  walk(srcDir);
  return results;
}

const baseConfig: ServiceConfig = {
  signingSecret: '0123456789abcdef0123456789abcdef',
  socketPath: '/tmp/task33-idea-test.sock',
  nonceDbPath: '/tmp/task33-idea-test.db',
  loopbackBaseUrl: 'http://127.0.0.1:8080',
  workspaceRoots: ['/tmp'],
  registeredFiles: {},
  registeredRunConfigs: { RUN_CONFIG_KEY: 'StudyPilotApplication' },
  registeredTestResults: { TEST_RESULT_KEY: 'surefire-reports' },
};

function makeFakeBridge(results: {
  openFile: NativeAxResult;
  focusConfiguration: NativeAxResult;
  showResult: NativeAxResult;
}): IdeaAccessibilityBridge {
  return {
    probe: () => ({
      platform: 'darwin',
      bridgeVersion: 'test',
      axApiAvailable: true,
      axTrusted: true,
      ideaRunning: true,
      ideaWindowExposed: true,
    }),
    openFile: vi.fn().mockResolvedValue(results.openFile),
    focusConfiguration: vi.fn().mockResolvedValue(results.focusConfiguration),
    showResult: vi.fn().mockResolvedValue(results.showResult),
  };
}

describe('P0 remediation: no invented HTTP control path for IntelliJ IDEA', () => {
  const productionFiles = readProductionSources();

  it('production src/** contains no 127.0.0.1:63342 invented endpoint or ideaLocalApiBaseUrl config', () => {
    const violations: string[] = [];
    for (const { file, content } of productionFiles) {
      if (content.includes('63342')) violations.push(`${file}: hard-coded 63342`);
      if (content.includes('ideaLocalApiBaseUrl')) violations.push(`${file}: ideaLocalApiBaseUrl`);
      if (content.includes('DirectLocalIdeaBridge')) violations.push(`${file}: DirectLocalIdeaBridge`);
    }
    expect(violations).toEqual([]);
  });

  it('production src/** contains no HTTP client path (node:http, node:https, fetch, XMLHttpRequest)', () => {
    const violations: string[] = [];
    for (const { file, content } of productionFiles) {
      if (/from\s+['"]node:https?['"]/.test(content)) violations.push(`${file}: http module import`);
      if (/require\(\s*['"]https?['"]\s*\)/.test(content)) violations.push(`${file}: http require`);
      if (/\bfetch\s*\(/.test(content)) violations.push(`${file}: fetch(`);
      if (/XMLHttpRequest/.test(content)) violations.push(`${file}: XMLHttpRequest`);
    }
    expect(violations).toEqual([]);
  });

  it('ideaAdapter does not expose any removed direct HTTP bridge class', async () => {
    const mod = await import('../src/ideaAdapter.js');
    expect(Object.keys(mod)).not.toContain('DirectLocalIdeaBridge');
    expect(Object.keys(mod)).not.toContain('DirectIdeaBridgeConfig');
  });

  it('idea bridge surfaces contain no HTTP response or 2xx-status verification behavior', () => {
    const targets = ['src/ideaAdapter.ts', 'src/nativeAxBridge.ts'].map((rel) =>
      path.join(packageRoot, rel)
    );
    const violations: string[] = [];
    for (const target of targets) {
      expect(fs.existsSync(target)).toBe(true);
      const content = fs.readFileSync(target, 'utf8');
      const patterns: RegExp[] = [
        /\bres\.ok\b/,
        /\bresponse\.ok\b/,
        /\bstatus\(\)/,
        /\bstatusCode\b/,
        /https?:\/\//,
        /\b127\.0\.0\.1\b/,
        /\blocalhost\b/,
      ];
      for (const pattern of patterns) {
        if (pattern.test(content)) violations.push(`${path.basename(target)}: ${pattern}`);
      }
    }
    expect(violations).toEqual([]);
  });
});

describe('The macOS AX probe can never supply a successful IDEA receipt', () => {
  const bridgeClaimingSuccess = {
    probe: () => ({
      platform: 'darwin',
      bridgeVersion: 'test',
      axApiAvailable: true,
      axTrusted: true,
      ideaRunning: true,
      ideaWindowExposed: true,
    }),
    openFile: vi.fn().mockResolvedValue({ ok: true, verified: true, code: 'OK', detail: '' }),
    focusConfiguration: vi.fn().mockResolvedValue({ ok: true, verified: true, code: 'OK', detail: '' }),
    showResult: vi.fn().mockResolvedValue({ ok: true, verified: true, code: 'OK', detail: '' }),
  };

  it('fails closed for all three actions even when the AX bridge claims verified success', async () => {
    const adapter = new NativeBridgeIdeaAutomationAdapter(bridgeClaimingSuccess);

    expect(await adapter.openRegisteredFile('FILE_REGISTERED')).toBe(false);
    expect(await adapter.focusRunConfiguration('RUN_REGISTERED')).toBe(false);
    expect(await adapter.showTestResult('RESULT_REGISTERED')).toBe(false);

    expect(adapter.getLastBlockerReason()).toContain('BLOCKED');
    expect(adapter.getLastBlockerReason()).toContain('AX_DIAGNOSTIC_ONLY');
    expect(adapter.getLastFailureCode()).toBe('ADAPTER_FAILURE');
  });

  it('never calls into the AX bridge for an outcome', async () => {
    const adapter = new NativeBridgeIdeaAutomationAdapter(bridgeClaimingSuccess);
    await adapter.openRegisteredFile('FILE_REGISTERED');
    expect(bridgeClaimingSuccess.openFile).not.toHaveBeenCalled();
  });
});

describe('Plugin bridge success rule (the only IDEA success path)', () => {
  function pluginStub(results: { ok: boolean; verified: boolean; code: string; detail: string }) {
    return {
      openRegisteredFile: vi.fn().mockResolvedValue(results),
      focusRunConfiguration: vi.fn().mockResolvedValue(results),
      showTestResult: vi.fn().mockResolvedValue(results),
    };
  }

  it('never reports success for a dispatched action whose IDE state was not verified', async () => {
    const adapter = new PluginBridgeIdeaAutomationAdapter(
      pluginStub({ ok: true, verified: false, code: 'UNVERIFIED_TARGET_STATE', detail: 'post state not proven' })
    );

    expect(await adapter.openRegisteredFile('FILE_REGISTERED')).toBe(false);
    expect(adapter.getLastFailureCode()).toBe('UNVERIFIED_TARGET_STATE');
    expect(adapter.getLastBlockerReason()).toContain('BLOCKED');

    expect(await adapter.focusRunConfiguration('RUN_REGISTERED')).toBe(false);
    expect(await adapter.showTestResult('RESULT_REGISTERED')).toBe(false);
  });

  it('never reports success when the plugin claimed verification but nothing ran', async () => {
    const adapter = new PluginBridgeIdeaAutomationAdapter(
      pluginStub({ ok: false, verified: true, code: 'PRESS_FAILED', detail: 'nothing dispatched' })
    );
    expect(await adapter.openRegisteredFile('FILE_REGISTERED')).toBe(false);
    expect(adapter.getLastFailureCode()).toBe('ADAPTER_FAILURE');
  });

  it('reports success only when the plugin both ran the action and verified it', async () => {
    const adapter = new PluginBridgeIdeaAutomationAdapter(
      pluginStub({ ok: true, verified: true, code: 'OK', detail: '' })
    );
    expect(await adapter.openRegisteredFile('FILE_REGISTERED')).toBe(true);
    expect(adapter.getLastFailureCode()).toBeNull();
    expect(await adapter.focusRunConfiguration('RUN_REGISTERED')).toBe(true);
    expect(await adapter.showTestResult('RESULT_REGISTERED')).toBe(true);
  });

  it('forwards only the opaque registered handle to the plugin', async () => {
    const plugin = pluginStub({ ok: true, verified: true, code: 'OK', detail: '' });
    const adapter = new PluginBridgeIdeaAutomationAdapter(plugin);

    await adapter.openRegisteredFile('FILE_REGISTERED');
    expect(plugin.openRegisteredFile).toHaveBeenCalledWith('FILE_REGISTERED');
    await adapter.focusRunConfiguration('RUN_REGISTERED');
    expect(plugin.focusRunConfiguration).toHaveBeenCalledWith('RUN_REGISTERED');
    await adapter.showTestResult('RESULT_REGISTERED');
    expect(plugin.showTestResult).toHaveBeenCalledWith('RESULT_REGISTERED');
  });

  it('refuses a handle that is not an opaque symbolic key', async () => {
    const plugin = pluginStub({ ok: true, verified: true, code: 'OK', detail: '' });
    const adapter = new PluginBridgeIdeaAutomationAdapter(plugin);

    expect(await adapter.openRegisteredFile('/etc/passwd')).toBe(false);
    expect(await adapter.openRegisteredFile('study pilot')).toBe(false);
    expect(plugin.openRegisteredFile).not.toHaveBeenCalled();
  });

  it('fails closed when the plugin bridge throws instead of surfacing a fake success', async () => {
    const adapter = new PluginBridgeIdeaAutomationAdapter({
      openRegisteredFile: vi.fn().mockRejectedValue(new Error('plugin failure')),
      focusRunConfiguration: vi.fn().mockRejectedValue(new Error('plugin failure')),
      showTestResult: vi.fn().mockRejectedValue(new Error('plugin failure')),
    });

    expect(await adapter.openRegisteredFile('FILE_REGISTERED')).toBe(false);
    expect(await adapter.focusRunConfiguration('RUN_REGISTERED')).toBe(false);
    expect(await adapter.showTestResult('RESULT_REGISTERED')).toBe(false);
    expect(adapter.getLastBlockerReason()).toContain('BLOCKED');
  });

  it('fails closed deterministically when no plugin bridge is configured', async () => {
    const adapter = new PluginBridgeIdeaAutomationAdapter(null);

    expect(adapter.isBridgeAvailable()).toBe(false);
    expect(await adapter.openRegisteredFile('FILE_REGISTERED')).toBe(false);
    expect(await adapter.focusRunConfiguration('RUN_REGISTERED')).toBe(false);
    expect(await adapter.showTestResult('RESULT_REGISTERED')).toBe(false);
    expect(adapter.getLastFailureCode()).toBe('ADAPTER_FAILURE');
    expect(adapter.getLastBlockerReason()).toContain('BLOCKED');
  });
});

describe('Native addon export surface is narrowly typed', () => {
  const full = {
    probe: () => ({}),
    openRegisteredFile: () => ({}),
    focusRunConfiguration: () => ({}),
    showTestResult: () => ({}),
  };

  it('accepts exactly the four allowed native exports', () => {
    expect(validateNativeAxModuleSurface(full)).toBe(true);
  });

  it('rejects generic automation capabilities smuggled into the native module', () => {
    for (const forbidden of ['clickAt', 'typeText', 'pressKey', 'runShell', 'openPath', 'osascript']) {
      expect(validateNativeAxModuleSurface({ ...full, [forbidden]: () => ({}) })).toBe(false);
    }
  });

  it('rejects modules with missing or non-function exports', () => {
    expect(validateNativeAxModuleSurface({ ...full, probe: 'not-a-function' })).toBe(false);
    expect(validateNativeAxModuleSurface({ ...full, showTestResult: undefined })).toBe(false);
    const { focusRunConfiguration: _drop, ...partial } = full;
    expect(validateNativeAxModuleSurface(partial)).toBe(false);
    expect(validateNativeAxModuleSurface(null)).toBe(false);
    expect(validateNativeAxModuleSurface('module')).toBe(false);
  });
});

describe('Default production wiring for the IDEA channel', () => {
  it('creates the diagnostic AX bridge only for probe purposes', () => {
    const bridge = createDiagnosticAxBridge();
    if (bridge) {
      expect(bridge).toBeInstanceOf(MacAxIdeaBridge);
      expect(bridge.probe().platform).toBe('darwin');
    } else {
      expect(bridge).toBeNull();
    }
  });

  it('wires the IDEA channel to the plugin bridge, never to the AX probe', () => {
    const service = new LocalAutomationService(baseConfig);
    const ideaAdapter = (service as unknown as { ideaAdapter: unknown }).ideaAdapter;

    expect(ideaAdapter).toBeInstanceOf(PluginBridgeIdeaAutomationAdapter);
    // No trusted plugin socket is configured in this test, so the channel fails closed.
    expect(
      (ideaAdapter as PluginBridgeIdeaAutomationAdapter).isBridgeAvailable()
    ).toBe(false);
    expect((ideaAdapter as PluginBridgeIdeaAutomationAdapter).getLastBlockerReason()).toContain('BLOCKED');
    service.close();
  });

  it('is not the AX adapter even when the macOS addon is available', () => {
    const service = new LocalAutomationService(baseConfig);
    const ideaAdapter = (service as unknown as { ideaAdapter: unknown }).ideaAdapter;
    expect(ideaAdapter).not.toBeInstanceOf(NativeBridgeIdeaAutomationAdapter);
    service.close();
  });
});

describe('Real native macOS Accessibility bridge (environment aware)', () => {
  const nativeModule = loadNativeAxAddon();

  it.runIf(!nativeModule)(
    'fails closed with an honest BLOCKED reason when the native addon has not been built',
    async () => {
      const adapter = new NativeBridgeIdeaAutomationAdapter(null);
      expect(await adapter.openRegisteredFile('/tmp/Registered.java')).toBe(false);
      expect(adapter.getLastBlockerReason()).toContain('BLOCKED');
    }
  );

  it.runIf(!!nativeModule)('reports real AX availability and rejects non-absolute or missing paths', async () => {
    const probe = nativeModule!.probe();
    expect(probe.platform).toBe('darwin');
    expect(typeof probe.axTrusted).toBe('boolean');
    expect(typeof probe.axApiAvailable).toBe('boolean');
    expect(typeof probe.ideaRunning).toBe('boolean');
    expect(typeof probe.ideaWindowExposed).toBe('boolean');
    expect(typeof probe.bridgeVersion).toBe('string');

    const bridge = new MacAxIdeaBridge(nativeModule!);
    const relative = await bridge.openFile('relative/path.java');
    expect(relative.ok).toBe(false);
    expect(relative.verified).toBe(false);

    const missing = await bridge.openFile('/definitely/not/a/real/registered/file.java');
    expect(missing.ok).toBe(false);
    expect(missing.verified).toBe(false);
  });

  it.runIf(!!nativeModule)(
    'only ever returns verified=true together with a real AX observation',
    async () => {
      const bridge = new MacAxIdeaBridge(nativeModule!);
      const probe = bridge.probe();
      if (!probe.ideaRunning) {
        // IntelliJ IDEA is not running: every registered action must fail closed.
        const registered = path.join(os.tmpdir(), `task33-registered-${process.pid}.java`);
        fs.writeFileSync(registered, 'public class Registered {}', 'utf8');
        try {
          const fileResult = await bridge.openFile(registered);
          expect(fileResult.ok).toBe(false);
          expect(fileResult.verified).toBe(false);
          expect(fileResult.code).toBe('IDEA_NOT_RUNNING');

          const focusResult = await bridge.focusConfiguration('StudyPilotApplication');
          expect(focusResult.ok).toBe(false);
          expect(focusResult.verified).toBe(false);
          expect(focusResult.code).toBe('IDEA_NOT_RUNNING');

          const resultView = await bridge.showResult('surefire-reports');
          expect(resultView.ok).toBe(false);
          expect(resultView.verified).toBe(false);
          expect(resultView.code).toBe('IDEA_NOT_RUNNING');
        } finally {
          fs.rmSync(registered, { force: true });
        }
      }
    }
  );
});
