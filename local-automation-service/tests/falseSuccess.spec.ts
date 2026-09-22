import { describe, it, expect, vi } from 'vitest';
import { PlaywrightBrowserAutomationAdapter } from '../src/browserAdapter.js';
import { NativeBridgeIdeaAutomationAdapter } from '../src/ideaAdapter.js';

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

    it('executes and verifies actions when a compliant native bridge is supplied', async () => {
      const verifiedResult = { ok: true, verified: true, code: 'OK', detail: '' };
      const mockNativeBridge = {
        probe: vi.fn().mockReturnValue({
          platform: 'darwin',
          bridgeVersion: 'test',
          axApiAvailable: true,
          axTrusted: true,
          ideaRunning: true,
        }),
        openFile: vi.fn().mockResolvedValue(verifiedResult),
        focusConfiguration: vi.fn().mockResolvedValue(verifiedResult),
        showResult: vi.fn().mockResolvedValue(verifiedResult),
      };

      const adapter = new NativeBridgeIdeaAutomationAdapter(mockNativeBridge);

      const resFile = await adapter.openRegisteredFile('/valid/path.java');
      expect(resFile).toBe(true);
      expect(mockNativeBridge.openFile).toHaveBeenCalledWith('/valid/path.java');

      const resRun = await adapter.focusRunConfiguration('RUN_APP');
      expect(resRun).toBe(true);
      expect(mockNativeBridge.focusConfiguration).toHaveBeenCalledWith('RUN_APP');

      const resTest = await adapter.showTestResult('TEST_RESULTS');
      expect(resTest).toBe(true);
      expect(mockNativeBridge.showResult).toHaveBeenCalledWith('TEST_RESULTS');
    });
  });
});
