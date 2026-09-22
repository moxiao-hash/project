import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { PlaywrightBrowserAutomationAdapter } from '../src/browserAdapter.js';
import { MacAccessibilityIdeaAutomationAdapter } from '../src/ideaAdapter.js';

describe('Real Adapters & Fail-Closed State Verification', () => {
  let tmpDir: string;
  let testFile: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'studypilot-adapter-test-'));
    testFile = path.join(tmpDir, 'Sample.java');
    fs.writeFileSync(testFile, 'public class Sample {}', 'utf8');
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  describe('PlaywrightBrowserAutomationAdapter', () => {
    it('fails closed when CDP endpoint is unreachable or browser unavailable', async () => {
      // Connect to unreachable port
      const adapter = new PlaywrightBrowserAutomationAdapter({
        cdpEndpoint: 'http://127.0.0.1:59999',
      });

      const res = await adapter.openRoute('http://127.0.0.1:8080/');
      expect(res).toBe(false);

      const focusRes = await adapter.focusAgentInput();
      expect(focusRes).toBe(false);

      const panelRes = await adapter.openResultPanel();
      expect(panelRes).toBe(false);
      await adapter.close();
    });

    it('rejects navigation to unallowlisted routes', async () => {
      const adapter = new PlaywrightBrowserAutomationAdapter();
      const res = await adapter.openRoute('http://127.0.0.1:8080/evil-unregistered-route');
      expect(res).toBe(false);
      await adapter.close();
    });
  });

  describe('MacAccessibilityIdeaAutomationAdapter', () => {
    it('fails closed when no compliant native IDEA accessibility bridge is available on host', async () => {
      const adapter = new MacAccessibilityIdeaAutomationAdapter(null);

      // With no admissible native bridge, actions must return false (never fake success)
      const isRunning = adapter.isIdeaRunning();
      expect(isRunning).toBe(false);
      expect(adapter.isBridgeAvailable()).toBe(false);

      const openRes = await adapter.openRegisteredFile(testFile);
      expect(openRes).toBe(false);

      const focusRes = await adapter.focusRunConfiguration('RUN_CONFIG_DEFAULT');
      expect(focusRes).toBe(false);

      const testRes = await adapter.showTestResult('TEST_RESULT_SUMMARY');
      expect(testRes).toBe(false);

      expect(adapter.getLastBlockerReason()).toContain('BLOCKED');
    });

    it('rejects non-existent or invalid files', async () => {
      const adapter = new MacAccessibilityIdeaAutomationAdapter(null);
      const nonExistent = path.join(tmpDir, 'NoSuchFile.java');
      const res = await adapter.openRegisteredFile(nonExistent);
      expect(res).toBe(false);
    });
  });
});
