import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import net from 'node:net';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { parseServiceConfigFromEnv } from '../src/config.js';
import { LocalAutomationServer } from '../src/server.js';
import { calculateCanonicalPayload } from '../src/canonical.js';
import type { AutomationRequest } from '../src/types.js';

const packageRoot = path.resolve(__dirname, '..');

function sendLineOverSocket(sockPath: string, line: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const client = net.createConnection(sockPath, () => client.write(line));
    let data = '';
    client.on('data', (chunk) => {
      data += chunk.toString('utf8');
      if (data.includes('\n')) client.end();
    });
    client.on('end', () => resolve(data));
    client.on('error', reject);
  });
}

describe('Production environment configuration', () => {
  const secret = 'z'.repeat(40);

  function validEnv(): NodeJS.ProcessEnv {
    return {
      STUDYPILOT_AUTOMATION_SOCKET_PATH: '/tmp/studypilot-prod/automation.sock',
      STUDYPILOT_AUTOMATION_HMAC_SECRET: secret,
      STUDYPILOT_AUTOMATION_NONCE_DB: '/tmp/studypilot-prod/nonces.db',
      STUDYPILOT_AUTOMATION_LOOPBACK_BASE_URL: 'http://127.0.0.1:8080',
      STUDYPILOT_AUTOMATION_WORKSPACE_ROOTS: '["/tmp"]',
      STUDYPILOT_AUTOMATION_REGISTERED_FILES: '{"FILE_APP":"/tmp/App.java"}',
      STUDYPILOT_AUTOMATION_REGISTERED_RUN_CONFIGS: '{"RUN_APP":"AppRunner"}',
      STUDYPILOT_AUTOMATION_REGISTERED_TEST_RESULTS: '{"TEST_RESULTS":"surefire-reports"}',
    };
  }

  it('parses a complete production environment into the frozen ServiceConfig shape', () => {
    const config = parseServiceConfigFromEnv(validEnv());
    expect(config.signingSecret).toBe(secret);
    expect(config.socketPath).toBe('/tmp/studypilot-prod/automation.sock');
    expect(config.nonceDbPath).toBe('/tmp/studypilot-prod/nonces.db');
    expect(config.loopbackBaseUrl).toBe('http://127.0.0.1:8080');
    expect(config.workspaceRoots).toEqual(['/tmp']);
    expect(config.registeredFiles).toEqual({ FILE_APP: '/tmp/App.java' });
    expect(config.registeredRunConfigs).toEqual({ RUN_APP: 'AppRunner' });
    expect(config.registeredTestResults).toEqual({ TEST_RESULTS: 'surefire-reports' });
  });

  it('rejects a missing or too-short HMAC signing secret', () => {
    const missing = validEnv();
    delete missing.STUDYPILOT_AUTOMATION_HMAC_SECRET;
    expect(() => parseServiceConfigFromEnv(missing)).toThrow(/HMAC_SECRET/);

    expect(() => parseServiceConfigFromEnv({ ...validEnv(), STUDYPILOT_AUTOMATION_HMAC_SECRET: 'too-short' })).toThrow(
      /32 bytes/
    );
  });

  it('rejects a non-loopback trusted browser base URL', () => {
    expect(() =>
      parseServiceConfigFromEnv({
        ...validEnv(),
        STUDYPILOT_AUTOMATION_LOOPBACK_BASE_URL: 'http://evil.example.com:8080',
      })
    ).toThrow(/loopback/);
  });

  it('rejects relative socket and nonce database paths', () => {
    expect(() =>
      parseServiceConfigFromEnv({ ...validEnv(), STUDYPILOT_AUTOMATION_SOCKET_PATH: 'relative.sock' })
    ).toThrow(/absolute/);
    expect(() =>
      parseServiceConfigFromEnv({ ...validEnv(), STUDYPILOT_AUTOMATION_NONCE_DB: 'relative.db' })
    ).toThrow(/absolute/);
  });

  it('rejects symbolic handle keys that are paths, URLs, or otherwise not opaque', () => {
    for (const badKey of ['/tmp/App.java', 'http://x/y', 'lower case', 'a/b', 'has-dash', '']) {
      expect(() =>
        parseServiceConfigFromEnv({
          ...validEnv(),
          STUDYPILOT_AUTOMATION_REGISTERED_FILES: JSON.stringify({ [badKey]: '/tmp/App.java' }),
        })
      ).toThrow();
    }
  });

  it('rejects registered file values that are not absolute paths', () => {
    expect(() =>
      parseServiceConfigFromEnv({
        ...validEnv(),
        STUDYPILOT_AUTOMATION_REGISTERED_FILES: '{"FILE_APP":"relative/App.java"}',
      })
    ).toThrow(/absolute/);
  });
});

describe('Production UDS entrypoint', () => {
  it('exists and only ever wires the Unix Domain Socket server', () => {
    const mainPath = path.join(packageRoot, 'src/main.ts');
    expect(fs.existsSync(mainPath)).toBe(true);
    const content = fs.readFileSync(mainPath, 'utf8');

    expect(content).toContain('parseServiceConfigFromEnv');
    expect(content).toContain('LocalAutomationServer');
    expect(content).toContain('SIGINT');
    expect(content).toContain('SIGTERM');
    expect(/from\s+['"]node:https?['"]/.test(content)).toBe(false);
    expect(/from\s+['"]node:net['"]/.test(content)).toBe(false);
    expect(/\bnet\.createServer\b/.test(content)).toBe(false);
    expect(/\.listen\(\s*\d/.test(content)).toBe(false);
    expect(/\bfetch\s*\(/.test(content)).toBe(false);
  });

  it('is exposed as a runnable production binary by package.json', () => {
    const pkg = JSON.parse(fs.readFileSync(path.join(packageRoot, 'package.json'), 'utf8')) as {
      scripts?: Record<string, string>;
      bin?: Record<string, string>;
    };
    const start = pkg.scripts?.start ?? '';
    expect(start).toContain('main.js');
    expect(fs.existsSync(path.join(packageRoot, 'src/main.ts'))).toBe(true);
  });
});

describe('Live UDS evidence using the production configuration path', () => {
  let tmpDir: string;
  let socketPath: string;
  let server: LocalAutomationServer;
  const secret = 'live-uds-evidence-secret-32-bytes-min';

  beforeEach(async () => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'studypilot-entrypoint-'));
    socketPath = path.join(tmpDir, 'automation.sock');
    const workspaceRoot = path.join(tmpDir, 'workspace');
    fs.mkdirSync(workspaceRoot, { recursive: true });
    const registeredFile = path.join(workspaceRoot, 'Registered.java');
    fs.writeFileSync(registeredFile, 'public class Registered {}', 'utf8');

    const config = parseServiceConfigFromEnv({
      STUDYPILOT_AUTOMATION_SOCKET_PATH: socketPath,
      STUDYPILOT_AUTOMATION_HMAC_SECRET: secret,
      STUDYPILOT_AUTOMATION_NONCE_DB: path.join(tmpDir, 'nonces.db'),
      STUDYPILOT_AUTOMATION_LOOPBACK_BASE_URL: 'http://127.0.0.1:8080',
      STUDYPILOT_AUTOMATION_WORKSPACE_ROOTS: JSON.stringify([workspaceRoot]),
      STUDYPILOT_AUTOMATION_REGISTERED_FILES: JSON.stringify({ FILE_REGISTERED: registeredFile }),
      STUDYPILOT_AUTOMATION_REGISTERED_RUN_CONFIGS: JSON.stringify({ RUN_REGISTERED: 'AppRunner' }),
      STUDYPILOT_AUTOMATION_REGISTERED_TEST_RESULTS: JSON.stringify({ RESULT_REGISTERED: 'surefire-reports' }),
    });

    server = new LocalAutomationServer(config);
    await server.start();
  });

  afterEach(async () => {
    await server.stop();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  function signed(overrides: Partial<AutomationRequest> = {}): AutomationRequest {
    const base: AutomationRequest = {
      version: 1,
      requestId: crypto.randomUUID(),
      ownerHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      channel: 'IDEA_ACCESSIBILITY',
      action: 'OPEN_REGISTERED_FILE',
      targetKey: 'FILE_REGISTERED',
      issuedAt: new Date(Date.now() - 1000).toISOString(),
      expiresAt: new Date(Date.now() + 50000).toISOString(),
      nonce: crypto.randomBytes(16).toString('base64url'),
      signature: '',
      ...overrides,
    };
    const signature = crypto
      .createHmac('sha256', secret)
      .update(calculateCanonicalPayload(base), 'utf8')
      .digest('hex')
      .toLowerCase();
    return { ...base, signature };
  }

  it('serves signed requests over the real production socket with 0600 permissions', async () => {
    const stat = fs.statSync(socketPath);
    expect(stat.isSocket()).toBe(true);
    if (process.platform !== 'win32') {
      expect(stat.mode & 0o777 & 0o077).toBe(0);
    }

    const request = signed();
    const response = await sendLineOverSocket(socketPath, JSON.stringify(request) + '\n');
    const receipt = JSON.parse(response.trim());
    expect(receipt.version).toBe(1);
    expect(receipt.requestId).toBe(request.requestId);
    expect(receipt.adapter).toBe('IDEA_ACCESSIBILITY');
    expect(receipt.action).toBe('OPEN_REGISTERED_FILE');
    // IntelliJ IDEA is not running in CI/host probe mode: the real AX adapter must fail closed.
    expect(['FAILED', 'REJECTED']).toContain(receipt.status);
    expect(receipt.status).not.toBe('SUCCEEDED');

    const replay = JSON.parse(
      (await sendLineOverSocket(socketPath, JSON.stringify(request) + '\n')).trim()
    );
    expect(replay.status).toBe('REJECTED');
    expect(replay.errorCode).toBe('REPLAY_DETECTED');
  });

  it('rejects a tampered signature over the real socket without consuming the nonce', async () => {
    const request = signed();
    const tampered = { ...request, action: 'FOCUS_RUN_CONFIGURATION', targetKey: 'RUN_REGISTERED' };
    const tamperedReceipt = JSON.parse(
      (await sendLineOverSocket(socketPath, JSON.stringify(tampered) + '\n')).trim()
    );
    expect(tamperedReceipt.status).toBe('REJECTED');
    expect(tamperedReceipt.errorCode).toBe('INVALID_SIGNATURE');

    const honest = JSON.parse(
      (await sendLineOverSocket(socketPath, JSON.stringify(request) + '\n')).trim()
    );
    expect(honest.status).not.toBe('REJECTED');
  });
});

describe('Built entrypoint binary (real process)', () => {
  const distMain = path.join(packageRoot, 'dist/main.js');
  const built = fs.existsSync(distMain);

  it.runIf(built)('starts a real UDS listener and shuts down cleanly on SIGTERM', async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'studypilot-bin-'));
    const socketPath = path.join(tmpDir, 'automation.sock');
    const child = spawn(process.execPath, [distMain], {
      env: {
        ...process.env,
        STUDYPILOT_AUTOMATION_SOCKET_PATH: socketPath,
        STUDYPILOT_AUTOMATION_HMAC_SECRET: 'built-entrypoint-secret-32-bytes-min!',
        STUDYPILOT_AUTOMATION_NONCE_DB: path.join(tmpDir, 'nonces.db'),
        STUDYPILOT_AUTOMATION_LOOPBACK_BASE_URL: 'http://127.0.0.1:8080',
        STUDYPILOT_AUTOMATION_WORKSPACE_ROOTS: '[]',
        STUDYPILOT_AUTOMATION_REGISTERED_FILES: '{}',
        STUDYPILOT_AUTOMATION_REGISTERED_RUN_CONFIGS: '{}',
        STUDYPILOT_AUTOMATION_REGISTERED_TEST_RESULTS: '{}',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    try {
      const deadline = Date.now() + 15000;
      while (!fs.existsSync(socketPath) && Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
      expect(fs.existsSync(socketPath)).toBe(true);
      const stat = fs.statSync(socketPath);
      expect(stat.isSocket()).toBe(true);
      if (process.platform !== 'win32') {
        expect(stat.mode & 0o777 & 0o077).toBe(0);
      }

      const response = await sendLineOverSocket(socketPath, JSON.stringify(signedRaw('built-entrypoint-secret-32-bytes-min!')) + '\n');
      const receipt = JSON.parse(response.trim());
      expect(receipt.version).toBe(1);
      expect(receipt.status).not.toBe('SUCCEEDED');
    } finally {
      child.kill('SIGTERM');
      await new Promise<void>((resolve) => {
        child.on('exit', () => resolve());
        setTimeout(() => {
          child.kill('SIGKILL');
          resolve();
        }, 8000);
      });
      expect(fs.existsSync(socketPath)).toBe(false);
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });
});

function signedRaw(secret: string): AutomationRequest {
  const base: AutomationRequest = {
    version: 1,
    requestId: crypto.randomUUID(),
    ownerHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    channel: 'IDEA_ACCESSIBILITY',
    action: 'SHOW_TEST_RESULT',
    targetKey: 'UNREGISTERED_HANDLE',
    issuedAt: new Date(Date.now() - 1000).toISOString(),
    expiresAt: new Date(Date.now() + 50000).toISOString(),
    nonce: crypto.randomBytes(16).toString('base64url'),
    signature: '',
  };
  const signature = crypto
    .createHmac('sha256', secret)
    .update(calculateCanonicalPayload(base), 'utf8')
    .digest('hex')
    .toLowerCase();
  return { ...base, signature };
}
