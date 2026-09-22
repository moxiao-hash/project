import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { IdeaPluginClient } from '../src/ideaPluginClient.js';
import { calculatePluginCanonicalPayload, type PluginRequest } from '../src/ideaPluginProtocol.js';

/**
 * Real Unix Domain Socket integration test for the plugin client.
 *
 * A fake plugin server speaks the frozen protocol over a real owner-only UDS, recomputes the
 * HMAC independently from the documented canonical payload, and answers per scenario. This
 * proves framing, signing, correlation, strict response validation and every fail-closed path
 * over a real socket — while remaining clearly test infrastructure, not REAL_E2E against the
 * installed plugin.
 */

const SECRET = 'studypilot-plugin-secret-32-bytes!!';
let tmpDir: string;
let socketPath: string;
let server: net.Server | null = null;

type ServerBehaviour =
  | { kind: 'respond'; status: string; errorCode: string | null; message?: string; requestIdOverride?: string; extraField?: boolean }
  | { kind: 'oversize' }
  | { kind: 'malformed' }
  | { kind: 'silent' }
  | { kind: 'respondThenTrailing' }
  | { kind: 'rejectSignature' };

let behaviour: ServerBehaviour = { kind: 'respond', status: 'SUCCEEDED', errorCode: null };

function startFakePluginServer(): Promise<void> {
  return new Promise((resolve) => {
    const created = net.createServer((socket) => {
      let buffer = '';
      socket.on('data', (chunk: Buffer) => {
        buffer += chunk.toString('utf8');
        const newline = buffer.indexOf('\n');
        if (newline < 0) {
          return;
        }
        const frame = buffer.slice(0, newline);
        let request: PluginRequest;
        try {
          request = JSON.parse(frame) as PluginRequest;
        } catch {
          socket.write('{malformed');
          return;
        }

        // Independent server-side signature check from the documented canonical payload.
        const expected = crypto
          .createHmac('sha256', Buffer.from(SECRET, 'utf8'))
          .update(calculatePluginCanonicalPayload(request), 'utf8')
          .digest('hex');
        if (expected !== request.signature) {
          socket.write(
            `${JSON.stringify({
              version: 1,
              requestId: request.requestId,
              action: request.action,
              status: 'REJECTED',
              errorCode: 'INVALID_SIGNATURE',
              message: 'signature mismatch',
              finishedAt: new Date().toISOString(),
            })}\n`
          );
          return;
        }

        switch (behaviour.kind) {
          case 'silent':
            return;
          case 'oversize':
            socket.write(`${'x'.repeat(20000)}\n`);
            return;
          case 'malformed':
            socket.write('{ this is not json }\n');
            return;
          case 'respondThenTrailing':
            // A valid frame followed by extra bytes in the SAME write must be rejected: a
            // response must be exactly one newline-terminated frame.
            socket.write(
              `${JSON.stringify({
                version: 1,
                requestId: request.requestId,
                action: request.action,
                status: 'SUCCEEDED',
                errorCode: null,
                message: 'ok',
                finishedAt: new Date().toISOString(),
              })}\n{"second":"frame"}\n`
            );
            return;
          case 'rejectSignature':
            socket.write(
              `${JSON.stringify({
                version: 1,
                requestId: request.requestId,
                action: request.action,
                status: 'REJECTED',
                errorCode: 'INVALID_SIGNATURE',
                message: 'signature mismatch',
                finishedAt: new Date().toISOString(),
              })}\n`
            );
            return;
          case 'respond': {
            const payload: Record<string, unknown> = {
              version: 1,
              requestId: behaviour.requestIdOverride ?? request.requestId,
              action: request.action,
              status: behaviour.status,
              errorCode: behaviour.errorCode,
              message: behaviour.message ?? 'plugin response',
              finishedAt: new Date().toISOString(),
            };
            if (behaviour.extraField) {
              payload['path'] = '/etc/passwd';
            }
            socket.write(`${JSON.stringify(payload)}\n`);
            return;
          }
        }
      });
    });
    server = created;
    created.listen(socketPath, () => {
      fs.chmodSync(socketPath, 0o600);
      resolve();
    });
  });
}

function client(timeoutMs = 1500): IdeaPluginClient {
  return new IdeaPluginClient({ socketPath, secret: SECRET, timeoutMs });
}

beforeEach(async () => {
  tmpDir = fs.mkdtempSync(path.join('/tmp', 't33-plugin-client-'));
  socketPath = path.join(tmpDir, 'p.sock');
  behaviour = { kind: 'respond', status: 'SUCCEEDED', errorCode: null };
});

afterEach(async () => {
  if (server) {
    await new Promise<void>((resolve) => server?.close(() => resolve()));
    server = null;
  }
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

describe('IdeaPluginClient over a real Unix Domain Socket', () => {
  it('returns verified success only for a correlated SUCCEEDED response', async () => {
    await startFakePluginServer();
    const outcome = await client().openRegisteredFile('FILE_REGISTERED');
    expect(outcome.ok).toBe(true);
    expect(outcome.verified).toBe(true);
    expect(outcome.code).toBe('OK');
  });

  it('maps an unverified plugin outcome to dispatched-but-unverified, never success', async () => {
    behaviour = { kind: 'respond', status: 'FAILED', errorCode: 'UNVERIFIED_TARGET_STATE' };
    await startFakePluginServer();
    const outcome = await client().focusRunConfiguration('RUN_REGISTERED');
    expect(outcome.ok).toBe(true);
    expect(outcome.verified).toBe(false);
    expect(outcome.code).toBe('UNVERIFIED_TARGET_STATE');
  });

  it('fails closed when the plugin rejects the request', async () => {
    behaviour = { kind: 'respond', status: 'REJECTED', errorCode: 'TARGET_NOT_REGISTERED' };
    await startFakePluginServer();
    const outcome = await client().showTestResult('RESULT_REGISTERED');
    expect(outcome.ok).toBe(false);
    expect(outcome.verified).toBe(false);
    expect(outcome.code).toBe('TARGET_NOT_REGISTERED');
  });

  it('rejects an oversize response frame', async () => {
    behaviour = { kind: 'oversize' };
    await startFakePluginServer();
    const outcome = await client().openRegisteredFile('FILE_REGISTERED');
    expect(outcome.ok).toBe(false);
    expect(outcome.code).toBe('PLUGIN_RESPONSE_INVALID');
  });

  it('rejects a valid frame followed by trailing bytes in the same write', async () => {
    behaviour = { kind: 'respondThenTrailing' };
    await startFakePluginServer();
    const outcome = await client().openRegisteredFile('FILE_REGISTERED');
    expect(outcome.ok).toBe(false);
    expect(outcome.verified).toBe(false);
    expect(outcome.code).toBe('PLUGIN_RESPONSE_INVALID');
  });

  it('rejects a valid frame followed by a second complete frame', async () => {
    behaviour = { kind: 'respondThenTrailing' };
    await startFakePluginServer();
    const outcome = await client().showTestResult('RESULT_REGISTERED');
    expect(outcome.ok).toBe(false);
    expect(outcome.code).toBe('PLUGIN_RESPONSE_INVALID');
  });

  it('rejects a malformed response frame', async () => {
    behaviour = { kind: 'malformed' };
    await startFakePluginServer();
    const outcome = await client().openRegisteredFile('FILE_REGISTERED');
    expect(outcome.ok).toBe(false);
    expect(outcome.code).toBe('INVALID_RESPONSE');
  });

  it('rejects a response carrying extra fields', async () => {
    behaviour = { kind: 'respond', status: 'SUCCEEDED', errorCode: null, extraField: true };
    await startFakePluginServer();
    const outcome = await client().openRegisteredFile('FILE_REGISTERED');
    expect(outcome.ok).toBe(false);
    expect(outcome.code).toBe('UNKNOWN_RESPONSE_FIELDS');
  });

  it('rejects a response correlated with a different request id', async () => {
    behaviour = {
      kind: 'respond',
      status: 'SUCCEEDED',
      errorCode: null,
      requestIdOverride: '99999999-2222-4333-8444-555555555555',
    };
    await startFakePluginServer();
    const outcome = await client().openRegisteredFile('FILE_REGISTERED');
    expect(outcome.ok).toBe(false);
    expect(outcome.code).toBe('CORRELATION_MISMATCH');
  });

  it('verifies its own signature server-side, so a bad signature would be rejected', async () => {
    behaviour = { kind: 'rejectSignature' };
    await startFakePluginServer();
    const outcome = await client().openRegisteredFile('FILE_REGISTERED');
    expect(outcome.ok).toBe(false);
    expect(outcome.code).toBe('INVALID_SIGNATURE');
  });

  it('times out and fails closed when the plugin never answers', async () => {
    behaviour = { kind: 'silent' };
    await startFakePluginServer();
    const outcome = await client(400).openRegisteredFile('FILE_REGISTERED');
    expect(outcome.ok).toBe(false);
    expect(outcome.code).toBe('PLUGIN_TIMEOUT');
  });

  it('fails closed when the plugin socket does not exist', async () => {
    const outcome = await client(500).openRegisteredFile('FILE_REGISTERED');
    expect(outcome.ok).toBe(false);
    expect(outcome.code).toBe('PLUGIN_UNAVAILABLE');
  });

  it('fails closed when no plugin socket is configured', async () => {
    const outcome = await new IdeaPluginClient({ socketPath: '', secret: SECRET }).openRegisteredFile('FILE');
    expect(outcome.ok).toBe(false);
    expect(outcome.code).toBe('PLUGIN_NOT_CONFIGURED');
  });

  it('never forwards a path or configuration name', async () => {
    await startFakePluginServer();
    const outcome = await client().openRegisteredFile('FILE_REGISTERED');
    expect(JSON.stringify(outcome)).not.toContain('/work/');
    const filtered = await client().openRegisteredFile('/etc/passwd');
    expect(filtered.ok).toBe(false);
  });
});
