import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { ActionRegistry } from '../src/actionRegistry.js';
import type { ServiceConfig } from '../src/types.js';

describe('ActionRegistry & Security Boundaries', () => {
  let tmpDir: string;
  let workspaceRoot: string;
  let outsideDir: string;
  let testFile: string;
  let symlinkFile: string;
  let outsideFile: string;
  let config: ServiceConfig;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'studypilot-registry-test-'));
    workspaceRoot = path.join(tmpDir, 'workspace');
    outsideDir = path.join(tmpDir, 'outside');

    fs.mkdirSync(workspaceRoot, { recursive: true });
    fs.mkdirSync(outsideDir, { recursive: true });

    testFile = path.join(workspaceRoot, 'Sample.java');
    fs.writeFileSync(testFile, 'public class Sample {}', 'utf8');

    outsideFile = path.join(outsideDir, 'Secret.java');
    fs.writeFileSync(outsideFile, 'secret content', 'utf8');

    symlinkFile = path.join(workspaceRoot, 'Symlink.java');
    fs.symlinkSync(testFile, symlinkFile);

    config = {
      signingSecret: 'a'.repeat(32),
      socketPath: path.join(tmpDir, 'service.sock'),
      nonceDbPath: path.join(tmpDir, 'nonces.db'),
      loopbackBaseUrl: 'http://127.0.0.1:8080',
      workspaceRoots: [workspaceRoot],
      registeredFiles: {
        FILE_SAMPLE: testFile,
        FILE_OUTSIDE: outsideFile,
        FILE_SYMLINK: symlinkFile,
      },
      registeredRunConfigs: {
        RUN_CONFIG_DEFAULT: 'StudyPilotApplication',
      },
      registeredTestResults: {
        TEST_RESULT_SUMMARY: 'target/surefire-reports',
      },
    };
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('validates browser routes with loopback base URL only', () => {
    const registry = new ActionRegistry(config);

    // Allowed combinations:
    const res1 = registry.resolveBrowserAction('OPEN_STUDYPILOT_ROUTE', 'ASSISTANT');
    expect(res1.valid).toBe(true);
    if (res1.valid) {
      expect(res1.targetUrl).toBe('http://127.0.0.1:8080/');
    }

    const res2 = registry.resolveBrowserAction('OPEN_STUDYPILOT_ROUTE', 'ASSISTANT_HEALTH');
    expect(res2.valid).toBe(true);
    if (res2.valid) {
      expect(res2.targetUrl).toBe('http://127.0.0.1:8080/assistant/health');
    }

    const res3 = registry.resolveBrowserAction('OPEN_STUDYPILOT_ROUTE', 'WORKSPACE_ARTIFACTS');
    expect(res3.valid).toBe(true);
    if (res3.valid) {
      expect(res3.targetUrl).toBe('http://127.0.0.1:8080/workspaces');
    }

    // Invalid target for OPEN_STUDYPILOT_ROUTE
    const invalidTarget = registry.resolveBrowserAction('OPEN_STUDYPILOT_ROUTE', 'UNKNOWN_ROUTE');
    expect(invalidTarget.valid).toBe(false);
  });

  it('rejects non-loopback base URLs in config', () => {
    expect(() => {
      new ActionRegistry({
        ...config,
        loopbackBaseUrl: 'http://evil.com:8080',
      });
    }).toThrow('loopback');
  });

  it('validates FOCUS_AGENT_INPUT fixed locator and OPEN_RESULT_PANEL fixed panel', () => {
    const registry = new ActionRegistry(config);

    const focus = registry.resolveBrowserAction('FOCUS_AGENT_INPUT', 'ASSISTANT_INPUT');
    expect(focus.valid).toBe(true);
    if (focus.valid) {
      expect(focus.fixedLocator).toBe('[data-testid="agent-message-input"]');
    }

    const panel = registry.resolveBrowserAction('OPEN_RESULT_PANEL', 'WORKSPACE_RESULTS');
    expect(panel.valid).toBe(true);
    if (panel.valid) {
      expect(panel.fixedPanelId).toBe('workspace-results-panel');
    }

    // Mismatched targets
    expect(registry.resolveBrowserAction('FOCUS_AGENT_INPUT', 'WRONG_INPUT').valid).toBe(false);
    expect(registry.resolveBrowserAction('OPEN_RESULT_PANEL', 'WRONG_PANEL').valid).toBe(false);
  });

  it('validates OPEN_REGISTERED_FILE and strictly enforces regular file within workspace and rejects symlinks', () => {
    const registry = new ActionRegistry(config);

    // Legitimate registered file inside workspace
    const validFile = registry.resolveIdeaAction('OPEN_REGISTERED_FILE', 'FILE_SAMPLE');
    expect(validFile.valid).toBe(true);
    if (validFile.valid) {
      expect(validFile.resolvedPath).toBe(fs.realpathSync(testFile));
    }

    // Symlinks must be rejected
    const symlinkResult = registry.resolveIdeaAction('OPEN_REGISTERED_FILE', 'FILE_SYMLINK');
    expect(symlinkResult.valid).toBe(false);
    if (!symlinkResult.valid) {
      expect(symlinkResult.errorCode).toBe('TARGET_NOT_REGISTERED');
    }

    // Outside workspace root must be rejected
    const outsideResult = registry.resolveIdeaAction('OPEN_REGISTERED_FILE', 'FILE_OUTSIDE');
    expect(outsideResult.valid).toBe(false);

    // Unregistered target handle must be rejected
    const unknownHandle = registry.resolveIdeaAction('OPEN_REGISTERED_FILE', 'UNREGISTERED_HANDLE');
    expect(unknownHandle.valid).toBe(false);
  });

  it('validates FOCUS_RUN_CONFIGURATION and SHOW_TEST_RESULT with opaque handles', () => {
    const registry = new ActionRegistry(config);

    const runConfig = registry.resolveIdeaAction('FOCUS_RUN_CONFIGURATION', 'RUN_CONFIG_DEFAULT');
    expect(runConfig.valid).toBe(true);
    if (runConfig.valid) {
      expect(runConfig.handle).toBe('StudyPilotApplication');
    }

    const testResult = registry.resolveIdeaAction('SHOW_TEST_RESULT', 'TEST_RESULT_SUMMARY');
    expect(testResult.valid).toBe(true);
    if (testResult.valid) {
      expect(testResult.handle).toBe('target/surefire-reports');
    }

    // Unregistered handles
    expect(registry.resolveIdeaAction('FOCUS_RUN_CONFIGURATION', 'RUN_TESTS_COMMAND').valid).toBe(false);
    expect(registry.resolveIdeaAction('SHOW_TEST_RESULT', 'START_NEW_TESTS').valid).toBe(false);
  });

  it('rejects channel-action mismatches', () => {
    const registry = new ActionRegistry(config);

    // Attempting to run IDE action on PLAYWRIGHT_DOM channel
    const mismatch1 = registry.resolveAction('PLAYWRIGHT_DOM', 'OPEN_REGISTERED_FILE', 'FILE_SAMPLE');
    expect(mismatch1.valid).toBe(false);
    if (!mismatch1.valid) {
      expect(mismatch1.errorCode).toBe('CHANNEL_ACTION_MISMATCH');
    }

    // Attempting to run browser action on IDEA_ACCESSIBILITY channel
    const mismatch2 = registry.resolveAction('IDEA_ACCESSIBILITY', 'OPEN_STUDYPILOT_ROUTE', 'ASSISTANT');
    expect(mismatch2.valid).toBe(false);
    if (!mismatch2.valid) {
      expect(mismatch2.errorCode).toBe('CHANNEL_ACTION_MISMATCH');
    }
  });
});
