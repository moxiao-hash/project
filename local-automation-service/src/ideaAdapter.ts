import fs from 'node:fs';
import type { IdeaAutomationAdapter } from './types.js';
import { IdeaPluginClient } from './ideaPluginClient.js';
import {
  loadNativeAxAddon,
  type NativeAxModule,
  type NativeAxProbe,
  type NativeAxResult,
} from './nativeAxBridge.js';

/**
 * Narrow, typed Accessibility bridge for IntelliJ IDEA.
 *
 * `probe` is a side-effect-free capability report. The three operations each dispatch one
 * registered accessibility action and report whether the required accessibility state was
 * actually observed, so callers can never mistake dispatch for success.
 */
export interface IdeaAccessibilityBridge {
  probe(): NativeAxProbe;
  openFile(realFilePath: string, workspaceRoot?: string): Promise<NativeAxResult>;
  focusConfiguration(configHandle: string): Promise<NativeAxResult>;
  showResult(resultHandle: string): Promise<NativeAxResult>;
}

export type IdeaFailureCode = 'ADAPTER_FAILURE' | 'UNVERIFIED_TARGET_STATE';

const UNAVAILABLE_BLOCKER =
  'BLOCKED: no compliant in-process macOS Accessibility bridge is available on this host';

function unavailableReason(code: string, detail: string): string {
  return `BLOCKED: ${code}${detail ? ` — ${detail}` : ''}`;
}

function isExistingRegularFile(candidate: string): boolean {
  try {
    return fs.statSync(candidate).isFile();
  } catch {
    return false;
  }
}

/**
 * Real macOS Accessibility bridge.
 *
 * Wraps the in-process native AX binding directly. Every operation is mediated by the
 * trusted registry values that the action registry resolved; the bridge accepts only a
 * bounded, absolute path or a trusted opaque handle and passes it verbatim to the native
 * layer. There is no network, shell, script-automation, or generic desktop automation path.
 */
export class MacAxIdeaBridge implements IdeaAccessibilityBridge {
  private native: NativeAxModule;

  constructor(nativeModule: NativeAxModule) {
    this.native = nativeModule;
  }

  public probe(): NativeAxProbe {
    try {
      return this.native.probe();
    } catch {
      return {
        platform: process.platform,
        bridgeVersion: 'unknown',
        axApiAvailable: false,
        axTrusted: false,
        ideaRunning: false,
        ideaWindowExposed: false,
      };
    }
  }

  public async openFile(realFilePath: string, workspaceRoot?: string): Promise<NativeAxResult> {
    if (typeof realFilePath !== 'string' || !realFilePath.startsWith('/')) {
      return {
        ok: false,
        verified: false,
        code: 'INVALID_REGISTERED_PATH',
        detail: 'registered path must be an absolute path',
      };
    }

    // The native layer proves the registered file through its canonical path, so the path
    // handed over is always the fully resolved real path (never a display name).
    let canonicalPath: string;
    try {
      canonicalPath = fs.realpathSync(realFilePath);
    } catch {
      return {
        ok: false,
        verified: false,
        code: 'INVALID_REGISTERED_PATH',
        detail: 'registered path could not be resolved to a canonical real path',
      };
    }
    if (!isExistingRegularFile(canonicalPath)) {
      return {
        ok: false,
        verified: false,
        code: 'INVALID_REGISTERED_PATH',
        detail: 'registered path is not an existing regular file',
      };
    }
    // The trusted registered workspace root (when available) additionally anchors the
    // outline root; it is never taken from a request field.
    if (typeof workspaceRoot === 'string' && workspaceRoot.length > 0) {
      try {
        return this.native.openRegisteredFile(canonicalPath, fs.realpathSync(workspaceRoot));
      } catch {
        return {
          ok: false,
          verified: false,
          code: 'INVALID_REGISTERED_PATH',
          detail: 'registered workspace root could not be resolved to a canonical real path',
        };
      }
    }
    return this.native.openRegisteredFile(canonicalPath);
  }

  public async focusConfiguration(configHandle: string): Promise<NativeAxResult> {
    if (typeof configHandle !== 'string' || configHandle.trim().length === 0) {
      return {
        ok: false,
        verified: false,
        code: 'INVALID_ARGUMENT',
        detail: 'run configuration handle must be a non-empty trusted value',
      };
    }
    return this.native.focusRunConfiguration(configHandle);
  }

  public async showResult(resultHandle: string): Promise<NativeAxResult> {
    if (typeof resultHandle !== 'string' || resultHandle.trim().length === 0) {
      return {
        ok: false,
        verified: false,
        code: 'INVALID_ARGUMENT',
        detail: 'test result handle must be a non-empty trusted value',
      };
    }
    return this.native.showTestResult(resultHandle);
  }
}

/**
 * Creates the macOS Accessibility bridge for DIAGNOSTICS ONLY.
 *
 * Returns null when the in-process native AX binding is unavailable (non-macOS host, addon
 * not built, load failure, or an inadmissible export surface). The result is used by probe()
 * and by the acceptance script; it can never supply a successful IDEA receipt.
 */
export function createDiagnosticAxBridge(): IdeaAccessibilityBridge | null {
  const nativeModule = loadNativeAxAddon();
  if (!nativeModule) {
    return null;
  }
  return new MacAxIdeaBridge(nativeModule);
}

/** Narrow capability surface of the trusted JetBrains plugin bridge. */
export interface IdeaPluginBridge {
  openRegisteredFile(handle: string): Promise<NativeAxResult>;
  focusRunConfiguration(handle: string): Promise<NativeAxResult>;
  showTestResult(handle: string): Promise<NativeAxResult>;
}

/**
 * IntelliJ IDEA automation adapter.
 *
 * Success is reported only when the bridge both dispatched the registered action and
 * observed the required accessibility state. Every other outcome fails closed.
 */
export class NativeBridgeIdeaAutomationAdapter implements IdeaAutomationAdapter {
  private bridge: IdeaAccessibilityBridge | null;
  private lastBlockerReason: string;
  private lastFailureCode: IdeaFailureCode | null = null;

  constructor(bridge: IdeaAccessibilityBridge | null = createDiagnosticAxBridge()) {
    this.bridge = bridge;
    this.lastBlockerReason = bridge ? '' : UNAVAILABLE_BLOCKER;
    this.lastFailureCode = bridge ? null : 'ADAPTER_FAILURE';
  }

  public getLastBlockerReason(): string {
    return this.lastBlockerReason;
  }

  /** Frozen receipt error code describing the most recent failure, if any. */
  public getLastFailureCode(): IdeaFailureCode | null {
    return this.lastFailureCode;
  }

  public isBridgeAvailable(): boolean {
    return this.bridge !== null;
  }

  public isIdeaRunning(): boolean {
    if (!this.bridge) {
      return false;
    }
    try {
      return this.bridge.probe().ideaRunning === true;
    } catch {
      return false;
    }
  }

  /**
   * The macOS AX binding is DIAGNOSTIC ONLY.
   *
   * Live acceptance proved that IntelliJ IDEA 2026.1.1 cannot open a file or expose focus
   * through the AX surface in a verifiable way, so AX must never supply a successful IDEA
   * receipt. All three operations therefore fail closed here, and production execution goes
   * through the trusted JetBrains plugin bridge.
   */
  public async openRegisteredFile(_handle: string): Promise<boolean> {
    return this.refuseDiagnosticsOnly('OPEN_REGISTERED_FILE');
  }

  public async focusRunConfiguration(_handle: string): Promise<boolean> {
    return this.refuseDiagnosticsOnly('FOCUS_RUN_CONFIGURATION');
  }

  public async showTestResult(_handle: string): Promise<boolean> {
    return this.refuseDiagnosticsOnly('SHOW_TEST_RESULT');
  }

  private refuseDiagnosticsOnly(operation: string): false {
    this.lastFailureCode = 'ADAPTER_FAILURE';
    this.lastBlockerReason = unavailableReason(
      'AX_DIAGNOSTIC_ONLY',
      `the macOS Accessibility probe is diagnostic only and cannot execute ${operation}`
    );
    return false;
  }

  /**
   * Single success gate for the IDEA channel.
   *
   * `SUCCEEDED` requires `ok === true && verified === true`; a dispatched-but-unverified
   * action is `UNVERIFIED_TARGET_STATE`, and an unavailable/undispatchable action is
   * `ADAPTER_FAILURE`. Neither ever reports success.
   */
  private async run(
    operation: string,
    invoke: () => Promise<NativeAxResult | null>
  ): Promise<boolean> {
    if (!this.bridge) {
      this.lastFailureCode = 'ADAPTER_FAILURE';
      this.lastBlockerReason = UNAVAILABLE_BLOCKER;
      return false;
    }

    let result: NativeAxResult | null;
    try {
      result = await invoke();
    } catch {
      this.lastFailureCode = 'ADAPTER_FAILURE';
      this.lastBlockerReason = unavailableReason(
        'NATIVE_BRIDGE_FAILURE',
        `the native accessibility bridge failed during ${operation}`
      );
      return false;
    }

    if (!result) {
      this.lastFailureCode = 'ADAPTER_FAILURE';
      this.lastBlockerReason = unavailableReason(
        'NATIVE_BRIDGE_UNAVAILABLE',
        `no accessibility result for ${operation}`
      );
      return false;
    }

    const dispatched = result.ok === true;
    const verified = result.verified === true;

    if (dispatched && verified) {
      this.lastFailureCode = null;
      this.lastBlockerReason = '';
      return true;
    }

    this.lastFailureCode = dispatched ? 'UNVERIFIED_TARGET_STATE' : 'ADAPTER_FAILURE';
    this.lastBlockerReason = unavailableReason(result.code, result.detail);
    return false;
  }
}

/** Backward-compatible aliases used across tests and the acceptance script. */
export const MacAccessibilityIdeaAutomationAdapter = NativeBridgeIdeaAutomationAdapter;
export const DefaultIdeaAutomationAdapter = NativeBridgeIdeaAutomationAdapter;

/**
 * Production IDEA automation adapter.
 *
 * Every operation is executed by the trusted JetBrains plugin over its own private Unix
 * Domain Socket. Success requires the plugin to report that it both dispatched the registered
 * action and observed the required IDE state (`ok && verified`). There is no fallback: when
 * the plugin bridge is absent or unreachable the IDEA channel fails closed, and the macOS AX
 * probe is never consulted for an outcome.
 */
export class PluginBridgeIdeaAutomationAdapter implements IdeaAutomationAdapter {
  private plugin: IdeaPluginBridge | null;
  private lastBlockerReason: string;
  private lastFailureCode: IdeaFailureCode | null = null;

  constructor(plugin: IdeaPluginBridge | null) {
    this.plugin = plugin;
    this.lastBlockerReason = plugin
      ? ''
      : 'BLOCKED: no trusted IDEA plugin bridge is configured for this host';
    this.lastFailureCode = plugin ? null : 'ADAPTER_FAILURE';
  }

  public getLastBlockerReason(): string {
    return this.lastBlockerReason;
  }

  public getLastFailureCode(): IdeaFailureCode | null {
    return this.lastFailureCode;
  }

  public isBridgeAvailable(): boolean {
    return this.plugin !== null;
  }

  public async openRegisteredFile(handle: string): Promise<boolean> {
    return this.run('OPEN_REGISTERED_FILE', handle, () =>
      this.plugin ? this.plugin.openRegisteredFile(handle) : Promise.resolve(null)
    );
  }

  public async focusRunConfiguration(handle: string): Promise<boolean> {
    return this.run('FOCUS_RUN_CONFIGURATION', handle, () =>
      this.plugin ? this.plugin.focusRunConfiguration(handle) : Promise.resolve(null)
    );
  }

  public async showTestResult(handle: string): Promise<boolean> {
    return this.run('SHOW_TEST_RESULT', handle, () =>
      this.plugin ? this.plugin.showTestResult(handle) : Promise.resolve(null)
    );
  }

  private async run(
    operation: string,
    handle: string,
    invoke: () => Promise<NativeAxResult | null>
  ): Promise<boolean> {
    if (!this.plugin) {
      this.lastFailureCode = 'ADAPTER_FAILURE';
      this.lastBlockerReason = 'BLOCKED: no trusted IDEA plugin bridge is configured for this host';
      return false;
    }
    if (typeof handle !== 'string' || !/^[A-Z0-9_]{1,64}$/.test(handle)) {
      this.lastFailureCode = 'ADAPTER_FAILURE';
      this.lastBlockerReason = unavailableReason(
        'INVALID_TARGET_HANDLE',
        `no opaque registered handle was available for ${operation}`
      );
      return false;
    }

    let result: NativeAxResult | null;
    try {
      result = await invoke();
    } catch {
      this.lastFailureCode = 'ADAPTER_FAILURE';
      this.lastBlockerReason = unavailableReason(
        'PLUGIN_BRIDGE_FAILURE',
        `the IDEA plugin bridge failed during ${operation}`
      );
      return false;
    }
    if (!result) {
      this.lastFailureCode = 'ADAPTER_FAILURE';
      this.lastBlockerReason = unavailableReason('PLUGIN_BRIDGE_UNAVAILABLE', `no plugin result for ${operation}`);
      return false;
    }

    if (result.ok === true && result.verified === true) {
      this.lastFailureCode = null;
      this.lastBlockerReason = '';
      return true;
    }
    this.lastFailureCode = result.ok === true ? 'UNVERIFIED_TARGET_STATE' : 'ADAPTER_FAILURE';
    this.lastBlockerReason = unavailableReason(result.code, result.detail);
    return false;
  }
}

/**
 * Production wiring.
 *
 * The IDEA channel is only usable when the trusted plugin socket and its separate >=32-byte
 * key are configured. Otherwise it fails closed with an honest BLOCKED reason; the AX probe
 * is never substituted.
 */
export function createDefaultIdeaAdapter(config: {
  ideaPluginSocketPath?: string;
  ideaPluginSigningSecret?: string;
  ideaPluginTimeoutMs?: number;
}): IdeaAutomationAdapter {
  if (!config.ideaPluginSocketPath || !config.ideaPluginSigningSecret) {
    return new PluginBridgeIdeaAutomationAdapter(null);
  }
  return new PluginBridgeIdeaAutomationAdapter(
    new IdeaPluginClient({
      socketPath: config.ideaPluginSocketPath,
      secret: config.ideaPluginSigningSecret,
      timeoutMs: config.ideaPluginTimeoutMs,
    })
  );
}
