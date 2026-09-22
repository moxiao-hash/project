import type { IdeaAutomationAdapter } from './types.js';

export interface IdeaBridgeResult {
  success: boolean;
  verified: boolean;
  error?: string;
}

/**
 * Narrow, typed Accessibility bridge interface for IntelliJ IDEA.
 * Must perform and verify exactly the three registered actions without generic desktop automation.
 */
export interface IdeaAccessibilityBridge {
  openFile(realFilePath: string): Promise<IdeaBridgeResult>;
  focusConfiguration(configName: string): Promise<IdeaBridgeResult>;
  showResult(resultHandle: string): Promise<IdeaBridgeResult>;
}

/**
 * Concrete IDEA Automation Adapter.
 * Uses a strictly typed native Accessibility bridge.
 * If no compliant native bridge is available on this host, defaults to FAILED and records BLOCKED.
 */
export class NativeBridgeIdeaAutomationAdapter implements IdeaAutomationAdapter {
  private bridge: IdeaAccessibilityBridge | null;
  private lastBlockerReason = '';

  constructor(bridge?: IdeaAccessibilityBridge) {
    this.bridge = bridge || null;
    if (!this.bridge) {
      this.lastBlockerReason =
        'BLOCKED: No compliant native IDEA accessibility bridge available on host';
    }
  }

  public getLastBlockerReason(): string {
    return this.lastBlockerReason;
  }

  public isBridgeAvailable(): boolean {
    return this.bridge !== null;
  }

  public isIdeaRunning(): boolean {
    return this.isBridgeAvailable();
  }

  public async openRegisteredFile(realFilePath: string): Promise<boolean> {
    if (!this.bridge) {
      this.lastBlockerReason =
        'BLOCKED: No compliant native IDEA accessibility bridge available on host';
      return false; // Fail closed
    }

    try {
      const result = await this.bridge.openFile(realFilePath);
      if (!result.success || !result.verified) {
        this.lastBlockerReason = result.error || 'Failed to verify file open state in IDEA';
        return false;
      }
      return true;
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      this.lastBlockerReason = `Native bridge failure: ${msg}`;
      return false;
    }
  }

  public async focusRunConfiguration(handle: string): Promise<boolean> {
    if (!this.bridge) {
      this.lastBlockerReason =
        'BLOCKED: No compliant native IDEA accessibility bridge available on host';
      return false; // Fail closed
    }

    try {
      const result = await this.bridge.focusConfiguration(handle);
      if (!result.success || !result.verified) {
        this.lastBlockerReason = result.error || 'Failed to verify run configuration focus state in IDEA';
        return false;
      }
      return true;
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      this.lastBlockerReason = `Native bridge failure: ${msg}`;
      return false;
    }
  }

  public async showTestResult(handle: string): Promise<boolean> {
    if (!this.bridge) {
      this.lastBlockerReason =
        'BLOCKED: No compliant native IDEA accessibility bridge available on host';
      return false; // Fail closed
    }

    try {
      const result = await this.bridge.showResult(handle);
      if (!result.success || !result.verified) {
        this.lastBlockerReason = result.error || 'Failed to verify test result display state in IDEA';
        return false;
      }
      return true;
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      this.lastBlockerReason = `Native bridge failure: ${msg}`;
      return false;
    }
  }
}

/**
 * Aliases for compatibility.
 */
export const MacAccessibilityIdeaAutomationAdapter = NativeBridgeIdeaAutomationAdapter;
export const DefaultIdeaAutomationAdapter = NativeBridgeIdeaAutomationAdapter;
