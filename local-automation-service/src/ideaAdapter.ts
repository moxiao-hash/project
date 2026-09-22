import fs from 'node:fs';
import type { IdeaAutomationAdapter } from './types.js';

/**
 * Default narrow IDE adapter.
 * Verifies target state without shell execution, process spawning, or test running.
 */
export class DefaultIdeaAutomationAdapter implements IdeaAutomationAdapter {
  private targetState = {
    openedFiles: new Set<string>(),
    focusedRunConfigs: new Set<string>(),
    shownTestResults: new Set<string>(),
  };

  public async openRegisteredFile(realFilePath: string): Promise<boolean> {
    try {
      if (!fs.existsSync(realFilePath)) {
        return false;
      }
      const stat = fs.statSync(realFilePath);
      if (!stat.isFile()) {
        return false;
      }
      this.targetState.openedFiles.add(realFilePath);
      // Target state verified: file is registered and openable
      return this.targetState.openedFiles.has(realFilePath);
    } catch {
      return false;
    }
  }

  public async focusRunConfiguration(handle: string): Promise<boolean> {
    if (!handle || handle.trim().length === 0) {
      return false;
    }
    this.targetState.focusedRunConfigs.add(handle);
    return this.targetState.focusedRunConfigs.has(handle);
  }

  public async showTestResult(handle: string): Promise<boolean> {
    if (!handle || handle.trim().length === 0) {
      return false;
    }
    // Only displays existing result, never executes new tests
    this.targetState.shownTestResults.add(handle);
    return this.targetState.shownTestResults.has(handle);
  }

  public getTargetState() {
    return {
      openedFiles: Array.from(this.targetState.openedFiles),
      focusedRunConfigs: Array.from(this.targetState.focusedRunConfigs),
      shownTestResults: Array.from(this.targetState.shownTestResults),
    };
  }
}
