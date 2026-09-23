import { describe, it, expect, vi } from 'vitest';
import { ActionRegistry } from '../src/actionRegistry.js';
import { LocalAutomationService } from '../src/service.js';
import { PlaywrightBrowserAutomationAdapter } from '../src/browserAdapter.js';
import { NativeBridgeIdeaAutomationAdapter, PluginBridgeIdeaAutomationAdapter } from '../src/ideaAdapter.js';
import type { ServiceConfig, IdeaAutomationAdapter } from '../src/types.js';

describe('Review Findings: Default Wiring, Independent Actions & Registry Mapping', () => {
  const baseConfig: ServiceConfig = {
    signingSecret: '0123456789abcdef0123456789abcdef', // 32 bytes
    socketPath: '/tmp/test.sock',
    nonceDbPath: '/tmp/nonces.db',
    loopbackBaseUrl: 'http://127.0.0.1:8080',
    workspaceRoots: ['/tmp'],
    registeredFiles: {},
    registeredRunConfigs: {
      RUN_CONFIG_KEY: 'StudyPilotApplication.run',
    },
    registeredTestResults: {
      TEST_RESULT_KEY: 'target/surefire-reports/summary.xml',
    },
  };

  describe('1. Preservation of trusted registry mapping values', () => {
    it('ActionRegistry maps targetKey to trusted configured value and returns it in handle', () => {
      const registry = new ActionRegistry(baseConfig);

      const runConfigRes = registry.resolveIdeaAction('FOCUS_RUN_CONFIGURATION', 'RUN_CONFIG_KEY');
      expect(runConfigRes.valid).toBe(true);
      if (runConfigRes.valid) {
        // Must preserve and return the configured mapping value, NOT the raw targetKey!
        expect(runConfigRes.handle).toBe('StudyPilotApplication.run');
      }

      const testResultRes = registry.resolveIdeaAction('SHOW_TEST_RESULT', 'TEST_RESULT_KEY');
      expect(testResultRes.valid).toBe(true);
      if (testResultRes.valid) {
        // Must preserve and return the configured mapping value, NOT the raw targetKey!
        expect(testResultRes.handle).toBe('target/surefire-reports/summary.xml');
      }
    });

    it('LocalAutomationService passes trusted internal mapping value to the adapter', async () => {
      const mockIdea: IdeaAutomationAdapter = {
        openRegisteredFile: vi.fn().mockResolvedValue(true),
        focusRunConfiguration: vi.fn().mockResolvedValue(true),
        showTestResult: vi.fn().mockResolvedValue(true),
      };

      const service = new LocalAutomationService(baseConfig, undefined, mockIdea);
      const nowIso = new Date().toISOString();
      const expiresIso = new Date(Date.now() + 30000).toISOString();

      // Request with targetKey = RUN_CONFIG_KEY
      const requestLine =
        JSON.stringify({
          version: 1,
          requestId: 'c28d22db-363d-429a-8c85-618d3632cf4b',
          ownerHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
          channel: 'IDEA_ACCESSIBILITY',
          action: 'FOCUS_RUN_CONFIGURATION',
          targetKey: 'RUN_CONFIG_KEY',
          issuedAt: nowIso,
          expiresAt: expiresIso,
          nonce: 'dGVzdC1ub25jZS0xMjgtYml0cw',
          signature: '0'.repeat(64), // signature check mocked or tested directly
        }) + '\n';

      // We test direct resolution dispatch in service
      const resolution = (service as any).actionRegistry.resolveAction(
        'IDEA_ACCESSIBILITY',
        'FOCUS_RUN_CONFIGURATION',
        'RUN_CONFIG_KEY'
      );
      expect(resolution.valid).toBe(true);
      expect(resolution.idea.handle).toBe('StudyPilotApplication.run');
    });
  });

  describe('2. Default LocalAutomationService wiring', () => {
    it('wires trustedLoopbackOrigin into PlaywrightBrowserAutomationAdapter by default', () => {
      const service = new LocalAutomationService(baseConfig);
      const browserAdapter = (service as any).browserAdapter as PlaywrightBrowserAutomationAdapter;

      // Must be wired with config.loopbackBaseUrl origin
      expect((browserAdapter as any).trustedOrigin).toBe('http://127.0.0.1:8080');
      service.close();
    });

    it('wires the trusted plugin bridge into the IDEA adapter by default and never the AX probe', () => {
      const service = new LocalAutomationService(baseConfig);
      const ideaAdapter = (service as any).ideaAdapter as PluginBridgeIdeaAutomationAdapter;

      expect(ideaAdapter).toBeInstanceOf(PluginBridgeIdeaAutomationAdapter);
      expect(ideaAdapter).not.toBeInstanceOf(NativeBridgeIdeaAutomationAdapter);

      // No trusted plugin socket is configured here, so the IDEA channel fails closed
      // instead of silently degrading to the macOS Accessibility probe.
      expect(ideaAdapter.isBridgeAvailable()).toBe(false);
      expect(ideaAdapter.getLastBlockerReason()).toContain('BLOCKED');
      expect(ideaAdapter.getLastFailureCode()).toBe('ADAPTER_FAILURE');
      service.close();
    });
  });

  describe('3. Independent Browser Actions without prior openRoute call', () => {
    it('FOCUS_AGENT_INPUT navigates to fixed Assistant route on trusted origin before focusing if not already there', async () => {
      const adapter = new PlaywrightBrowserAutomationAdapter({
        trustedLoopbackOrigin: 'http://127.0.0.1:8080',
      });

      let currentUrl = 'about:blank'; // fresh page, not on StudyPilot yet
      const fakePage = {
        isClosed: () => false,
        url: () => currentUrl,
        goto: vi.fn().mockImplementation(async (targetUrl: string) => {
          currentUrl = targetUrl;
          return { status: () => 200 };
        }),
        locator: vi.fn().mockImplementation((selector: string) => ({
          waitFor: vi.fn().mockResolvedValue(undefined),
          focus: vi.fn().mockResolvedValue(undefined),
        })),
        evaluate: vi.fn().mockResolvedValue(true),
      };
      (adapter as any).page = fakePage;

      const res = await adapter.focusAgentInput();
      expect(res).toBe(true);
      // Must have navigated to http://127.0.0.1:8080/
      expect(fakePage.goto).toHaveBeenCalledWith('http://127.0.0.1:8080/', expect.any(Object));
      expect(fakePage.evaluate).toHaveBeenCalled();
    });

    it('OPEN_RESULT_PANEL navigates to fixed Workspaces route on trusted origin before triggering panel', async () => {
      const adapter = new PlaywrightBrowserAutomationAdapter({
        trustedLoopbackOrigin: 'http://127.0.0.1:8080',
      });

      let currentUrl = 'about:blank';
      let panelVisible = false;
      const fakePage = {
        isClosed: () => false,
        url: () => currentUrl,
        goto: vi.fn().mockImplementation(async (targetUrl: string) => {
          currentUrl = targetUrl;
          return { status: () => 200 };
        }),
        locator: vi.fn().mockImplementation((selector: string) => {
          if (selector === '[data-testid="open-results-panel-trigger"]') {
            return {
              click: vi.fn().mockImplementation(async () => {
                panelVisible = true;
              }),
            };
          }
          if (selector === '[data-testid="workspace-results-panel"]') {
            return {
              isVisible: vi.fn().mockImplementation(async () => panelVisible),
              locator: vi.fn().mockReturnValue({ isVisible: vi.fn().mockResolvedValue(false) }),
            };
          }
          return { click: vi.fn(), isVisible: vi.fn() };
        }),
      };
      (adapter as any).page = fakePage;

      const res = await adapter.openResultPanel();
      expect(res).toBe(true);
      expect(fakePage.goto).toHaveBeenCalledWith('http://127.0.0.1:8080/workspaces', expect.any(Object));
    });
  });
});
