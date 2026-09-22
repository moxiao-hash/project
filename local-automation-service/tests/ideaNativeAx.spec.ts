import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { describe, it, expect, vi } from 'vitest';
import {
  NativeBridgeIdeaAutomationAdapter,
  MacAxIdeaBridge,
  createDefaultIdeaBridge,
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

describe('Exact AX state verification before reporting success', () => {
  const notVerified: NativeAxResult = {
    ok: true,
    verified: false,
    code: 'STATE_NOT_VERIFIED',
    detail: 'action dispatched but required accessibility state was not observed',
  };
  const dispatchFailed: NativeAxResult = {
    ok: false,
    verified: false,
    code: 'IDEA_NOT_RUNNING',
    detail: 'trusted IntelliJ IDEA application is not running',
  };
  const verified: NativeAxResult = { ok: true, verified: true, code: 'OK', detail: '' };

  it('never reports success for a dispatched action whose AX state was not verified', async () => {
    const adapter = new NativeBridgeIdeaAutomationAdapter(
      makeFakeBridge({ openFile: notVerified, focusConfiguration: notVerified, showResult: notVerified })
    );

    expect(await adapter.openRegisteredFile('/tmp/Registered.java')).toBe(false);
    expect(adapter.getLastBlockerReason()).toContain('BLOCKED');
    expect(adapter.getLastFailureCode()).toBe('UNVERIFIED_TARGET_STATE');

    expect(await adapter.focusRunConfiguration('StudyPilotApplication')).toBe(false);
    expect(adapter.getLastFailureCode()).toBe('UNVERIFIED_TARGET_STATE');

    expect(await adapter.showTestResult('surefire-reports')).toBe(false);
    expect(adapter.getLastFailureCode()).toBe('UNVERIFIED_TARGET_STATE');
  });

  it('never reports success when the bridge claimed verification but the action did not run', async () => {
    const adapter = new NativeBridgeIdeaAutomationAdapter(
      makeFakeBridge({
        openFile: { ok: false, verified: true, code: 'PRESS_FAILED', detail: 'accessibility press failed' },
        focusConfiguration: dispatchFailed,
        showResult: dispatchFailed,
      })
    );

    expect(await adapter.openRegisteredFile('/tmp/Registered.java')).toBe(false);
    expect(adapter.getLastFailureCode()).toBe('ADAPTER_FAILURE');
  });

  it('reports success only when both the action ran and the AX state was verified', async () => {
    const adapter = new NativeBridgeIdeaAutomationAdapter(
      makeFakeBridge({ openFile: verified, focusConfiguration: verified, showResult: verified })
    );

    expect(await adapter.openRegisteredFile('/tmp/Registered.java')).toBe(true);
    expect(adapter.getLastFailureCode()).toBeNull();
    expect(await adapter.focusRunConfiguration('StudyPilotApplication')).toBe(true);
    expect(await adapter.showTestResult('surefire-reports')).toBe(true);
  });

  it('passes the trusted registered handle/value through to the bridge, never the raw request targetKey', async () => {
    const bridge = makeFakeBridge({ openFile: verified, focusConfiguration: verified, showResult: verified });
    const adapter = new NativeBridgeIdeaAutomationAdapter(bridge);

    await adapter.focusRunConfiguration('StudyPilotApplication');
    expect(bridge.focusConfiguration).toHaveBeenCalledWith('StudyPilotApplication');

    await adapter.showTestResult('surefire-reports');
    expect(bridge.showResult).toHaveBeenCalledWith('surefire-reports');
  });

  it('fails closed when the bridge throws instead of surfacing a fake success', async () => {
    const bridge: IdeaAccessibilityBridge = {
      probe: () => ({
        platform: 'darwin',
        bridgeVersion: 'test',
        axApiAvailable: true,
        axTrusted: true,
        ideaRunning: true,
        ideaWindowExposed: true,
      }),
      openFile: vi.fn().mockRejectedValue(new Error('native failure')),
      focusConfiguration: vi.fn().mockRejectedValue(new Error('native failure')),
      showResult: vi.fn().mockRejectedValue(new Error('native failure')),
    };
    const adapter = new NativeBridgeIdeaAutomationAdapter(bridge);

    expect(await adapter.openRegisteredFile('/tmp/Registered.java')).toBe(false);
    expect(await adapter.focusRunConfiguration('StudyPilotApplication')).toBe(false);
    expect(await adapter.showTestResult('surefire-reports')).toBe(false);
    expect(adapter.getLastBlockerReason()).toContain('BLOCKED');
  });

  it('hands the canonical real path to the native layer, never a symlink or display name', async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'task33-canonical-'));
    const realFile = path.join(tmpDir, 'Registered.java');
    fs.writeFileSync(realFile, 'public class Registered {}', 'utf8');
    const symlink = path.join(tmpDir, 'Link.java');
    fs.symlinkSync(realFile, symlink);

    const nativeModule = {
      probe: vi.fn().mockReturnValue({
        platform: 'darwin',
        bridgeVersion: 'test',
        axApiAvailable: true,
        axTrusted: true,
        ideaRunning: true,
        ideaWindowExposed: true,
      }),
      openRegisteredFile: vi.fn().mockReturnValue({
        ok: false,
        verified: false,
        code: 'IDEA_NOT_RUNNING',
        detail: 'stub',
      }),
      focusRunConfiguration: vi.fn(),
      showTestResult: vi.fn(),
    };

    try {
      const bridge = new MacAxIdeaBridge(nativeModule);
      await bridge.openFile(symlink);
      // fs.realpathSync on macOS resolves /var -> /private/var, so compare canonically.
      expect(nativeModule.openRegisteredFile).toHaveBeenCalledWith(fs.realpathSync(realFile));
      expect(nativeModule.openRegisteredFile).not.toHaveBeenCalledWith(symlink);
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  it('fails closed deterministically when no native bridge is available', async () => {
    const adapter = new NativeBridgeIdeaAutomationAdapter(null);

    expect(adapter.isBridgeAvailable()).toBe(false);
    expect(adapter.isIdeaRunning()).toBe(false);
    expect(await adapter.openRegisteredFile('/tmp/Registered.java')).toBe(false);
    expect(await adapter.focusRunConfiguration('StudyPilotApplication')).toBe(false);
    expect(await adapter.showTestResult('surefire-reports')).toBe(false);
    expect(adapter.getLastBlockerReason()).toContain('BLOCKED');
    expect(adapter.getLastFailureCode()).toBe('ADAPTER_FAILURE');
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
  it('wires the real macOS AX bridge, or fails closed when the addon is not built', () => {
    const bridge = createDefaultIdeaBridge();
    const nativeModule = loadNativeAxAddon();

    if (nativeModule) {
      expect(bridge).not.toBeNull();
      expect(bridge).toBeInstanceOf(MacAxIdeaBridge);
      expect(bridge!.probe().platform).toBe('darwin');
    } else {
      expect(bridge).toBeNull();
    }
  });

  it('LocalAutomationService default adapter is the real AX adapter, never the removed HTTP bridge', () => {
    const service = new LocalAutomationService(baseConfig);
    const ideaAdapter = (service as unknown as { ideaAdapter: NativeBridgeIdeaAutomationAdapter })
      .ideaAdapter;

    expect(ideaAdapter).toBeInstanceOf(NativeBridgeIdeaAutomationAdapter);
    if (createDefaultIdeaBridge()) {
      expect(ideaAdapter.isBridgeAvailable()).toBe(true);
    } else {
      expect(ideaAdapter.isBridgeAvailable()).toBe(false);
      expect(ideaAdapter.getLastBlockerReason()).toContain('BLOCKED');
    }
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
