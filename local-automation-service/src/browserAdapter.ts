import { chromium, type Browser, type Page } from 'playwright-core';
import type { BrowserAutomationAdapter } from './types.js';

export interface BrowserAdapterConfig {
  cdpEndpoint?: string;
  channel?: string;
  headless?: boolean; // Defaults to false for user-visible recovery session
  executablePath?: string;
  trustedLoopbackOrigin?: string; // e.g. http://127.0.0.1:8080 or http://localhost:5173
}

/**
 * Concrete Playwright Browser Adapter.
 * Performs only the three fixed registered browser actions and verifies the resulting state.
 * Each action is completely independent and does not require prior openRoute calls.
 * Fails closed without simulating success when browser or endpoint is unavailable.
 */
export class PlaywrightBrowserAutomationAdapter implements BrowserAutomationAdapter {
  private config?: BrowserAdapterConfig;
  private browser: Browser | null = null;
  private page: Page | null = null;
  private allowedRoutes = new Set(['/', '/assistant/health', '/workspaces']);
  private trustedOrigin: string | null = null;
  private lastBlockerReason = '';

  constructor(config?: BrowserAdapterConfig) {
    this.config = config;
    if (config?.trustedLoopbackOrigin) {
      try {
        this.trustedOrigin = new URL(config.trustedLoopbackOrigin).origin;
      } catch {
        this.trustedOrigin = null;
      }
    }
  }

  public getLastBlockerReason(): string {
    return this.lastBlockerReason;
  }

  private async getOrCreatePage(): Promise<Page | null> {
    if (this.page && !this.page.isClosed()) {
      return this.page;
    }

    try {
      if (this.config?.cdpEndpoint) {
        this.browser = await chromium.connectOverCDP(this.config.cdpEndpoint, {
          timeout: 4000,
        });
        const contexts = this.browser.contexts();
        const context = contexts.length > 0 ? contexts[0] : await this.browser.newContext();
        const pages = context.pages();
        this.page = pages.length > 0 ? pages[0] : await context.newPage();
        return this.page;
      }

      // Launch local browser (defaults to visible headed session for user-visible recovery)
      this.browser = await chromium.launch({
        channel: this.config?.channel || 'chrome',
        headless: this.config?.headless ?? false, // User-visible recovery session
        executablePath: this.config?.executablePath,
        timeout: 4000,
      });

      const context = await this.browser.newContext();
      this.page = await context.newPage();
      return this.page;
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      this.lastBlockerReason = `Browser prerequisite unavailable: ${msg}`;
      return null;
    }
  }

  public async openRoute(routeUrl: string): Promise<boolean> {
    try {
      const url = new URL(routeUrl);
      if (!this.allowedRoutes.has(url.pathname)) {
        this.lastBlockerReason = `Route ${url.pathname} is not in allowlist`;
        return false;
      }

      // The trusted base address may only come from local configuration. A route URL that
      // does not match the configured loopback origin is rejected outright, so this method
      // can never establish trust from a caller-supplied origin.
      if (!this.trustedOrigin) {
        this.lastBlockerReason = 'No trusted StudyPilot loopback origin configured';
        return false;
      }
      if (url.origin !== this.trustedOrigin) {
        this.lastBlockerReason = `Route origin (${url.origin}) does not match the configured StudyPilot origin`;
        return false;
      }

      const page = await this.getOrCreatePage();
      if (!page) {
        return false; // Fail closed
      }

      const response = await page.goto(routeUrl, {
        timeout: 6000,
        waitUntil: 'domcontentloaded',
      });

      // Verify target state
      const currentUrl = page.url();
      const currentPathname = new URL(currentUrl).pathname;
      const statusOk = response ? response.status() < 400 : true;

      const verified = currentPathname === url.pathname && statusOk;
      if (!verified) {
        this.lastBlockerReason = `Failed to verify route state: current=${currentPathname}, expected=${url.pathname}`;
      }
      return verified;
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      this.lastBlockerReason = `Failed to open route: ${msg}`;
      return false; // Fail closed
    }
  }

  public async focusAgentInput(): Promise<boolean> {
    try {
      const page = await this.getOrCreatePage();
      if (!page) {
        return false; // Fail closed
      }

      // Independent action requirement:
      // If page is not on the trusted origin or on an unrelated origin, navigate to the fixed Assistant page first
      const currentUrl = page.url();
      let currentOrigin = '';
      try {
        currentOrigin = new URL(currentUrl).origin;
      } catch {
        currentOrigin = '';
      }

      if (!this.trustedOrigin) {
        this.lastBlockerReason = 'No trusted StudyPilot loopback origin configured';
        return false;
      }

      const assistantPageUrl = `${this.trustedOrigin}/`;
      if (currentOrigin !== this.trustedOrigin || new URL(currentUrl).pathname !== '/') {
        const navRes = await page.goto(assistantPageUrl, {
          timeout: 6000,
          waitUntil: 'domcontentloaded',
        });
        if (navRes && navRes.status() >= 400) {
          this.lastBlockerReason = `Failed to navigate to Assistant page: HTTP ${navRes.status()}`;
          return false;
        }
      }

      // Verify page is on the registered StudyPilot origin
      const verifiedOrigin = new URL(page.url()).origin;
      if (verifiedOrigin !== this.trustedOrigin) {
        this.lastBlockerReason = `Target page origin (${verifiedOrigin}) does not match registered StudyPilot origin (${this.trustedOrigin})`;
        return false;
      }

      // Fixed in-source locator
      const locator = page.locator('[data-testid="agent-message-input"]');
      await locator.waitFor({ state: 'attached', timeout: 3000 });
      await locator.focus({ timeout: 3000 });

      // Verify target state directly in DOM
      const isFocused = await page.evaluate(() => {
        const target = document.querySelector('[data-testid="agent-message-input"]');
        return document.activeElement === target;
      });

      if (!isFocused) {
        this.lastBlockerReason = 'Target state verification failed: agent input is not focused';
      }
      return isFocused;
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      this.lastBlockerReason = `Failed to focus agent input: ${msg}`;
      return false; // Fail closed
    }
  }

  public async openResultPanel(): Promise<boolean> {
    try {
      const page = await this.getOrCreatePage();
      if (!page) {
        return false; // Fail closed
      }

      if (!this.trustedOrigin) {
        this.lastBlockerReason = 'No trusted StudyPilot loopback origin configured';
        return false;
      }

      // Independent action requirement:
      // If page is not on the Workspaces page on the trusted origin, navigate there first
      const currentUrl = page.url();
      let currentOrigin = '';
      try {
        currentOrigin = new URL(currentUrl).origin;
      } catch {
        currentOrigin = '';
      }

      const workspacesPageUrl = `${this.trustedOrigin}/workspaces`;
      if (currentOrigin !== this.trustedOrigin || new URL(currentUrl).pathname !== '/workspaces') {
        const navRes = await page.goto(workspacesPageUrl, {
          timeout: 6000,
          waitUntil: 'domcontentloaded',
        });
        if (navRes && navRes.status() >= 400) {
          this.lastBlockerReason = `Failed to navigate to Workspaces page: HTTP ${navRes.status()}`;
          return false;
        }
      }

      // Verify page is on the registered StudyPilot origin
      const verifiedOrigin = new URL(page.url()).origin;
      if (verifiedOrigin !== this.trustedOrigin) {
        this.lastBlockerReason = `Target page origin (${verifiedOrigin}) does not match registered StudyPilot origin (${this.trustedOrigin})`;
        return false;
      }

      // Perform fixed source-registered open trigger action rather than merely observing
      const trigger = page.locator('[data-testid="open-results-panel-trigger"]');
      await trigger.click({ timeout: 3000 });

      // Verify panel visibility after executing trigger
      const panel = page.locator('[data-testid="workspace-results-panel"]');
      const isVisible = await panel.isVisible({ timeout: 3000 });

      if (!isVisible) {
        this.lastBlockerReason = 'Target state verification failed: workspace results panel is not visible after trigger action';
        return false;
      }

      // Defense against false-success: if the panel displays an error state due to API/auth failure, fail closed!
      const errorLocator = panel.locator('[data-testid="workspace-results-error"]');
      const hasError = await errorLocator.isVisible().catch(() => false);
      if (hasError) {
        this.lastBlockerReason = 'Target state verification failed: workspace results panel displays error state';
        return false;
      }

      return true;
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      this.lastBlockerReason = `Failed to open result panel: ${msg}`;
      return false; // Fail closed
    }
  }

  public async close(): Promise<void> {
    try {
      if (this.page && !this.page.isClosed()) {
        await this.page.close().catch(() => {});
      }
      if (this.browser && this.browser.isConnected()) {
        await this.browser.close().catch(() => {});
      }
    } finally {
      this.page = null;
      this.browser = null;
    }
  }
}

/**
 * Backward compatibility alias for narrow adapter testing.
 */
export const DefaultBrowserAutomationAdapter = PlaywrightBrowserAutomationAdapter;
