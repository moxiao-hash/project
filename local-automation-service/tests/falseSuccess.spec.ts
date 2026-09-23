import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { describe, it, expect, vi } from 'vitest';
import { PlaywrightBrowserAutomationAdapter } from '../src/browserAdapter.js';
import {
  NativeBridgeIdeaAutomationAdapter,
  PluginBridgeIdeaAutomationAdapter,
} from '../src/ideaAdapter.js';

describe('False-Success Defenses for Adapters', () => {
  describe('Browser Adapter origin and action verification', () => {
    it('rejects FOCUS_AGENT_INPUT when page is not on a registered StudyPilot origin', async () => {
      const adapter = new PlaywrightBrowserAutomationAdapter({
        trustedLoopbackOrigin: 'http://127.0.0.1:8080',
      });

      // Inject a fake page that is on an untrusted origin
      const fakePage = {
        isClosed: () => false,
        url: () => 'https://untrusted-external-site.com/some-page',
        goto: vi.fn().mockImplementation(async () => ({ status: () => 200 })),
        locator: vi.fn(),
        evaluate: vi.fn(),
      };
      (adapter as any).page = fakePage;

      const res = await adapter.focusAgentInput();
      expect(res).toBe(false);
      expect(adapter.getLastBlockerReason()).toContain('registered StudyPilot origin');
      expect(fakePage.locator).not.toHaveBeenCalled();
    });

    it('requires OPEN_RESULT_PANEL to trigger the fixed open action rather than merely observing an already visible panel', async () => {
      const adapter = new PlaywrightBrowserAutomationAdapter({
        trustedLoopbackOrigin: 'http://127.0.0.1:8080',
      });

      let triggerClicked = false;
      const fakeTrigger = {
        click: vi.fn().mockImplementation(async () => {
          triggerClicked = true;
        }),
      };
      const fakePanel = {
        isVisible: vi.fn().mockImplementation(async () => triggerClicked),
        locator: vi.fn().mockReturnValue({ isVisible: vi.fn().mockResolvedValue(false) }),
      };

      const fakePage = {
        isClosed: () => false,
        url: () => 'http://127.0.0.1:8080/workspaces',
        locator: vi.fn().mockImplementation((selector: string) => {
          if (selector === '[data-testid="open-results-panel-trigger"]') {
            return fakeTrigger;
          }
          if (selector === '[data-testid="workspace-results-panel"]') {
            return fakePanel;
          }
          return { isVisible: async () => false, click: async () => {} };
        }),
      };
      (adapter as any).page = fakePage;

      const res = await adapter.openResultPanel();
      expect(res).toBe(true);
      expect(fakeTrigger.click).toHaveBeenCalled();
      expect(fakePanel.isVisible).toHaveBeenCalled();
    });

    it('rejects OPEN_RESULT_PANEL when the panel displays an error state rather than verified results', async () => {
      const adapter = new PlaywrightBrowserAutomationAdapter({
        trustedLoopbackOrigin: 'http://127.0.0.1:8080',
      });

      const fakeTrigger = { click: vi.fn().mockResolvedValue(undefined) };
      const fakePanel = {
        isVisible: vi.fn().mockResolvedValue(true),
        locator: vi.fn().mockImplementation((selector: string) => {
          if (selector === '[data-testid="workspace-results-error"]') {
            return {
              isVisible: vi.fn().mockResolvedValue(true),
              count: vi.fn().mockResolvedValue(1),
            };
          }
          return { isVisible: vi.fn().mockResolvedValue(false), count: vi.fn().mockResolvedValue(0) };
        }),
      };

      const fakePage = {
        isClosed: () => false,
        url: () => 'http://127.0.0.1:8080/workspaces',
        locator: vi.fn().mockImplementation((selector: string) => {
          if (selector === '[data-testid="open-results-panel-trigger"]') {
            return fakeTrigger;
          }
          if (selector === '[data-testid="workspace-results-panel"]') {
            return fakePanel;
          }
          return { isVisible: async () => false, click: async () => {} };
        }),
      };
      (adapter as any).page = fakePage;

      const res = await adapter.openResultPanel();
      expect(res).toBe(false);
      expect(adapter.getLastBlockerReason()).toContain('error state');
    });

    it('rejects an openRoute target whose origin is not the configured trusted loopback origin', async () => {
      const adapter = new PlaywrightBrowserAutomationAdapter({
        trustedLoopbackOrigin: 'http://127.0.0.1:8080',
      });

      // Same allowlisted pathname, attacker-controlled origin: must never establish trust.
      const res = await adapter.openRoute('http://evil.example.com/');
      expect(res).toBe(false);
      expect((adapter as any).trustedOrigin).toBe('http://127.0.0.1:8080');
      expect(adapter.getLastBlockerReason()).toContain('origin');
    });
  });

  describe('IDEA Adapter native bridge requirement', () => {
    it('fails closed when no compliant native bridge is configured on host', async () => {
      const adapter = new NativeBridgeIdeaAutomationAdapter(null); // no bridge provided

      const resFile = await adapter.openRegisteredFile('/some/real/file.java');
      expect(resFile).toBe(false);
      expect(adapter.getLastBlockerReason()).toContain('BLOCKED');

      const resRun = await adapter.focusRunConfiguration('RUN_CONFIG_DEFAULT');
      expect(resRun).toBe(false);
      expect(adapter.getLastBlockerReason()).toContain('BLOCKED');

      const resTest = await adapter.showTestResult('TEST_RESULT_SUMMARY');
      expect(resTest).toBe(false);
      expect(adapter.getLastBlockerReason()).toContain('BLOCKED');
    });

    it('reports success only when the trusted plugin bridge dispatches AND verifies', async () => {
      const verified = { ok: true, verified: true, code: 'OK', detail: '' };
      const plugin = {
        openRegisteredFile: vi.fn().mockResolvedValue(verified),
        focusRunConfiguration: vi.fn().mockResolvedValue(verified),
        showTestResult: vi.fn().mockResolvedValue(verified),
      };
      const adapter = new PluginBridgeIdeaAutomationAdapter(plugin);

      expect(await adapter.openRegisteredFile('FILE_REGISTERED')).toBe(true);
      expect(plugin.openRegisteredFile).toHaveBeenCalledWith('FILE_REGISTERED');
      expect(await adapter.focusRunConfiguration('RUN_REGISTERED')).toBe(true);
      expect(await adapter.showTestResult('RESULT_REGISTERED')).toBe(true);

      // A dispatched-but-unverified plugin outcome must never become success.
      const unverified = new PluginBridgeIdeaAutomationAdapter({
        openRegisteredFile: vi.fn().mockResolvedValue({
          ok: true,
          verified: false,
          code: 'UNVERIFIED_TARGET_STATE',
          detail: 'post state not proven',
        }),
        focusRunConfiguration: vi.fn(),
        showTestResult: vi.fn(),
      });
      expect(await unverified.openRegisteredFile('FILE_REGISTERED')).toBe(false);
      expect(unverified.getLastFailureCode()).toBe('UNVERIFIED_TARGET_STATE');
    });
  });
});
