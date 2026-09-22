import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import net from 'node:net';
import crypto from 'node:crypto';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { LocalAutomationServer } from '../src/server.js';
import { calculateCanonicalPayload } from '../src/canonical.js';
import type { ServiceConfig, AutomationRequest } from '../src/types.js';

describe('LocalAutomationServer (Unix Domain Socket)', () => {
  let tmpDir: string;
  let socketPath: string;
  let dbPath: string;
  let config: ServiceConfig;
  const secretKey = 'test-secret-key-32-bytes-minimum-length!'; // 39 bytes

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'studypilot-server-test-'));
    socketPath = path.join(tmpDir, 'service.sock');
    dbPath = path.join(tmpDir, 'nonces.db');

    config = {
      signingSecret: secretKey,
      socketPath,
      nonceDbPath: dbPath,
      loopbackBaseUrl: 'http://127.0.0.1:8080',
      workspaceRoots: [tmpDir],
      registeredFiles: {},
      registeredRunConfigs: {},
      registeredTestResults: {},
    };
  });

  afterEach(async () => {
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

  function sendLineOverSocket(sockPath: string, line: string): Promise<string> {
    return new Promise((resolve, reject) => {
      const client = net.createConnection(sockPath, () => {
        client.write(line);
      });

      let responseData = '';
      client.on('data', (chunk) => {
        responseData += chunk.toString('utf8');
        if (responseData.includes('\n')) {
          client.end();
        }
      });

      client.on('end', () => {
        resolve(responseData);
      });

      client.on('error', (err) => {
        reject(err);
      });
    });
  }

  it('creates Unix Domain Socket with owner-only permissions and serves requests', async () => {
    const mockBrowser = {
      openRoute: async () => true,
      focusAgentInput: async () => true,
      openResultPanel: async () => true,
    };
    const server = new LocalAutomationServer(config, mockBrowser);
    await server.start();

    expect(fs.existsSync(socketPath)).toBe(true);

    // Verify socket file permissions: owner-only (0600 on unix)
    const stat = fs.statSync(socketPath);
    expect(stat.isSocket()).toBe(true);
    if (process.platform !== 'win32') {
      const mode = stat.mode & 0o777;
      // Socket must not have group or other permissions
      expect(mode & 0o077).toBe(0);
    }

    // Client communication
    const request = createSignedRequest();
    const response = await sendLineOverSocket(socketPath, JSON.stringify(request) + '\n');
    expect(response.endsWith('\n')).toBe(true);

    const receipt = JSON.parse(response.trim());
    expect(receipt.version).toBe(1);
    expect(receipt.requestId).toBe(request.requestId);
    expect(receipt.status).toBe('SUCCEEDED');

    await server.stop();
    expect(fs.existsSync(socketPath)).toBe(false);
  });

  it('rejects oversize frames exceeding 16 KiB over the socket connection', async () => {
    const server = new LocalAutomationServer(config);
    await server.start();

    const hugeLine = 'a'.repeat(20000) + '\n';
    const response = await sendLineOverSocket(socketPath, hugeLine);
    const receipt = JSON.parse(response.trim());
    expect(receipt.status).toBe('REJECTED');
    expect(receipt.errorCode).toBe('OVERSIZE_FRAME');

    await server.stop();
  });

  it('rejects replayed requests across the socket', async () => {
    const mockBrowser = {
      openRoute: async () => true,
      focusAgentInput: async () => true,
      openResultPanel: async () => true,
    };
    const server = new LocalAutomationServer(config, mockBrowser);
    await server.start();

    const request = createSignedRequest();
    const reqStr = JSON.stringify(request) + '\n';

    const firstRes = await sendLineOverSocket(socketPath, reqStr);
    const firstReceipt = JSON.parse(firstRes.trim());
    expect(firstReceipt.status).toBe('SUCCEEDED');

    const secondRes = await sendLineOverSocket(socketPath, reqStr);
    const secondReceipt = JSON.parse(secondRes.trim());
    expect(secondReceipt.status).toBe('REJECTED');
    expect(secondReceipt.errorCode).toBe('REPLAY_DETECTED');

    await server.stop();
  });

  it('detects and rejects insecure broad socket permissions', () => {
    const server = new LocalAutomationServer(config);
    // Directly test socket permission validation function
    if (process.platform !== 'win32') {
      const insecureMode = 0o666; // group and world readable/writable
      expect(server.validateSocketMode(insecureMode)).toBe(false);
      expect(server.validateSocketMode(0o600)).toBe(true);
      expect(server.validateSocketMode(0o700)).toBe(true);
    }
  });
});
