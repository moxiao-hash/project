import fs from 'node:fs';
import type { IdeaAutomationAdapter } from './types.js';
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
  openFile(realFilePath: string): Promise<NativeAxResult>;
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
      };
    }
  }

  public async openFile(realFilePath: string): Promise<NativeAxResult> {
    if (typeof realFilePath !== 'string' || !realFilePath.startsWith('/')) {
      return {
        ok: false,
        verified: false,
        code: 'INVALID_REGISTERED_PATH',
        detail: 'registered path must be an absolute path',
      };
    }
    if (!isExistingRegularFile(realFilePath)) {
      return {
        ok: false,
        verified: false,
        code: 'INVALID_REGISTERED_PATH',
        detail: 'registered path is not an existing regular file',
      };
    }
    return this.native.openRegisteredFile(realFilePath);
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
 * Creates the default production bridge.
 *
 * Returns null when the in-process native AX binding is unavailable (non-macOS host,
 * addon not built, load failure, or an inadmissible export surface). In that case the
 * IDEA channel must fail closed with an honest BLOCKED reason.
 */
export function createDefaultIdeaBridge(): IdeaAccessibilityBridge | null {
  const nativeModule = loadNativeAxAddon();
  if (!nativeModule) {
    return null;
  }
  return new MacAxIdeaBridge(nativeModule);
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

  constructor(bridge: IdeaAccessibilityBridge | null = createDefaultIdeaBridge()) {
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

  public async openRegisteredFile(realFilePath: string): Promise<boolean> {
    return this.run('OPEN_REGISTERED_FILE', () =>
      this.bridge ? this.bridge.openFile(realFilePath) : Promise.resolve(null)
    );
  }

  public async focusRunConfiguration(handle: string): Promise<boolean> {
    return this.run('FOCUS_RUN_CONFIGURATION', () =>
      this.bridge ? this.bridge.focusConfiguration(handle) : Promise.resolve(null)
    );
  }

  public async showTestResult(handle: string): Promise<boolean> {
    return this.run('SHOW_TEST_RESULT', () =>
      this.bridge ? this.bridge.showResult(handle) : Promise.resolve(null)
    );
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
