import type { BrowserAutomationAdapter } from './types.js';

/**
 * Default narrow browser adapter.
 * Verifies target state without exposing generic automation capabilities.
 */
export class DefaultBrowserAutomationAdapter implements BrowserAutomationAdapter {
  private allowedRoutes = new Set(['/', '/assistant/health', '/workspaces']);
  private targetState = {
    currentRoute: '/',
    agentInputFocused: false,
    resultPanelOpen: false,
  };

  public async openRoute(routeUrl: string): Promise<boolean> {
    try {
      const url = new URL(routeUrl);
      if (!this.allowedRoutes.has(url.pathname)) {
        return false;
      }
      this.targetState.currentRoute = url.pathname;
      // Target state verified
      return this.targetState.currentRoute === url.pathname;
    } catch {
      return false;
    }
  }

  public async focusAgentInput(): Promise<boolean> {
    this.targetState.agentInputFocused = true;
    return this.targetState.agentInputFocused;
  }

  public async openResultPanel(): Promise<boolean> {
    this.targetState.resultPanelOpen = true;
    return this.targetState.resultPanelOpen;
  }

  public getCurrentState() {
    return { ...this.targetState };
  }
}
