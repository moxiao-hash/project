import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { LocalAutomationService } from '../src/service.js';
import { calculateCanonicalPayload, calculateTargetDigest } from '../src/canonical.js';
import type {
  ServiceConfig,
  AutomationRequest,
  BrowserAutomationAdapter,
  IdeaAutomationAdapter,
} from '../src/types.js';

describe('LocalAutomationService Pipeline', () => {
  let tmpDir: string;
  let workspaceRoot: string;
  let testFile: string;
  let config: ServiceConfig;
  const secretKey = 'valid-32-byte-secret-for-service-testing'; // >= 32 bytes

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'studypilot-service-test-'));
    workspaceRoot = path.join(tmpDir, 'workspace');
    fs.mkdirSync(workspaceRoot, { recursive: true });

    testFile = path.join(workspaceRoot, 'App.java');
    fs.writeFileSync(testFile, 'public class App {}', 'utf8');

    config = {
      signingSecret: secretKey,
      socketPath: path.join(tmpDir, 'service.sock'),
      nonceDbPath: path.join(tmpDir, 'nonces.db'),
      loopbackBaseUrl: 'http://127.0.0.1:8080',
      workspaceRoots: [workspaceRoot],
      registeredFiles: {
        FILE_APP: testFile,
      },
      registeredRunConfigs: {
        RUN_APP: 'AppRunner',
      },
      registeredTestResults: {
        TEST_RESULTS: 'target/surefire-reports',
      },
    };
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  function createSignedRequest(overrides: Partial<AutomationRequest> = {}): AutomationRequest {
    const baseRequest: AutomationRequest = {
      version: 1,
      requestId: crypto.randomUUID(),
      ownerHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      channel: 'PLAYWRIGHT_DOM',
      action: 'OPEN_STUDYPILOT_ROUTE',
      targetKey: 'ASSISTANT',
      issuedAt: new Date(Date.now() - 1000).toISOString(),
      expiresAt: new Date(Date.now() + 50000).toISOString(),
      nonce: crypto.randomBytes(16).toString('base64url'),
      signature: '',
      ...overrides,
    };

    const payload = calculateCanonicalPayload(baseRequest);
    const signature = crypto
      .createHmac('sha256', secretKey)
      .update(payload, 'utf8')
      .digest('hex')
      .toLowerCase();

    return { ...baseRequest, signature };
  }

  it('executes valid browser route request, consumes nonce, and returns SUCCEEDED receipt', async () => {
    const mockBrowser: BrowserAutomationAdapter = {
      openRoute: vi.fn().mockResolvedValue(true),
      focusAgentInput: vi.fn().mockResolvedValue(true),
      openResultPanel: vi.fn().mockResolvedValue(true),
    };

    const service = new LocalAutomationService(config, mockBrowser);
    const request = createSignedRequest();
    const rawLine = JSON.stringify(request) + '\n';

    const responseLine = await service.handleRequestLine(rawLine);
    expect(responseLine.endsWith('\n')).toBe(true);

    const receipt = JSON.parse(responseLine.trim());
    expect(receipt.version).toBe(1);
    expect(receipt.requestId).toBe(request.requestId);
    expect(receipt.adapter).toBe('PLAYWRIGHT_DOM');
    expect(receipt.action).toBe('OPEN_STUDYPILOT_ROUTE');
    expect(receipt.targetDigest).toBe(
      calculateTargetDigest('PLAYWRIGHT_DOM', 'OPEN_STUDYPILOT_ROUTE', 'ASSISTANT')
    );
    expect(receipt.status).toBe('SUCCEEDED');
    expect(receipt.errorCode).toBeNull();
    expect(receipt.message).toBe('Action completed and verified successfully');

    expect(mockBrowser.openRoute).toHaveBeenCalledWith('http://127.0.0.1:8080/');

    // Nonce must now be consumed; replay must be rejected
    const replayLine = await service.handleRequestLine(rawLine);
    const replayReceipt = JSON.parse(replayLine.trim());
    expect(replayReceipt.status).toBe('REJECTED');
    expect(replayReceipt.errorCode).toBe('REPLAY_DETECTED');

    service.close();
  });

  it('does NOT consume nonce when verification or signature fails', async () => {
    const service = new LocalAutomationService(config);
    const request = createSignedRequest();
    // Tamper signature
    const tamperedRequest = { ...request, signature: '0'.repeat(64) };
    const rawLine = JSON.stringify(tamperedRequest) + '\n';

    const responseLine = await service.handleRequestLine(rawLine);
    const receipt = JSON.parse(responseLine.trim());
    expect(receipt.status).toBe('REJECTED');
    expect(receipt.errorCode).toBe('INVALID_SIGNATURE');

    // Because validation failed, the legitimate request with valid signature should still be allowed
    const validLine = JSON.stringify(request) + '\n';
    const validResponseLine = await service.handleRequestLine(validLine);
    const validReceipt = JSON.parse(validResponseLine.trim());
    expect(validReceipt.status).toBe('SUCCEEDED');

    service.close();
  });

  it('handles adapter failure by returning FAILED status with sanitized message', async () => {
    const mockIdea: IdeaAutomationAdapter = {
      openRegisteredFile: vi.fn().mockResolvedValue(false), // Failed verification
      focusRunConfiguration: vi.fn().mockResolvedValue(true),
      showTestResult: vi.fn().mockResolvedValue(true),
    };

    const service = new LocalAutomationService(config, undefined, mockIdea);
    const request = createSignedRequest({
      channel: 'IDEA_ACCESSIBILITY',
      action: 'OPEN_REGISTERED_FILE',
      targetKey: 'FILE_APP',
    });

    const rawLine = JSON.stringify(request) + '\n';
    const responseLine = await service.handleRequestLine(rawLine);
    const receipt = JSON.parse(responseLine.trim());

    expect(receipt.status).toBe('FAILED');
    expect(receipt.errorCode).toBe('UNVERIFIED_TARGET_STATE');
    expect(receipt.message).toBe('Action execution or target state verification failed');

    service.close();
  });

  it('rejects malformed requests before side effects', async () => {
    const mockBrowser: BrowserAutomationAdapter = {
      openRoute: vi.fn(),
      focusAgentInput: vi.fn(),
      openResultPanel: vi.fn(),
    };

    const service = new LocalAutomationService(config, mockBrowser);

    // Oversize line
    const oversize = 'x'.repeat(20000) + '\n';
    const res = await service.handleRequestLine(oversize);
    const receipt = JSON.parse(res.trim());
    expect(receipt.status).toBe('REJECTED');
    expect(receipt.errorCode).toBe('OVERSIZE_FRAME');

    expect(mockBrowser.openRoute).not.toHaveBeenCalled();
    service.close();
  });
});
