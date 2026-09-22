import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { NonceStore, validateNonceFormat } from '../src/nonceStore.js';

describe('NonceStore & Nonce Validation', () => {
  let tmpDir: string;
  let dbPath: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'studypilot-nonce-test-'));
    dbPath = path.join(tmpDir, 'nonces.db');
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('validates base64url nonce format of at least 128 bits (16 bytes = 22 base64url chars)', () => {
    expect(validateNonceFormat('dGVzdC1ub25jZS0xMjgtYml0cw')).toBe(true); // 26 chars base64url
    expect(validateNonceFormat('1234567890123456789012')).toBe(true); // 22 chars

    // Invalid formats:
    expect(validateNonceFormat('short')).toBe(false); // < 22 chars (< 128 bits)
    expect(validateNonceFormat('dGVzdC1ub25jZS0xMjgtYml0cw==')).toBe(false); // padding forbidden
    expect(validateNonceFormat('dGVzd+ub25jZS/xMjgtYml0cw')).toBe(false); // '+' and '/' not base64url
    expect(validateNonceFormat('')).toBe(false);
  });

  it('atomically consumes a new nonce and rejects replay in same session', () => {
    const store = new NonceStore(dbPath);
    const nonce = 'dGVzdC1ub25jZS0xMjgtYml0cw';
    const expiresAt = '2026-09-22T08:01:00Z';

    expect(store.isConsumed(nonce)).toBe(false);
    expect(store.consume(nonce, expiresAt)).toBe(true);
    expect(store.isConsumed(nonce)).toBe(true);

    // Replay in same session must be rejected
    expect(store.consume(nonce, expiresAt)).toBe(false);
    store.close();
  });

  it('rejects replay after process restart (new NonceStore instance on same database file)', () => {
    const nonce = 'cmVzdGFydC1yZXBsYXktdGVzdC0wMQ';
    const expiresAt = '2026-09-22T08:05:00Z';

    // First process instance consumes nonce
    const store1 = new NonceStore(dbPath);
    expect(store1.consume(nonce, expiresAt)).toBe(true);
    store1.close();

    // Second process instance starts up with same db file
    const store2 = new NonceStore(dbPath);
    expect(store2.isConsumed(nonce)).toBe(true);
    expect(store2.consume(nonce, expiresAt)).toBe(false); // REPLAY REJECTED AFTER RESTART
    store2.close();
  });

  it('guarantees only one caller succeeds under concurrent race condition for same nonce', async () => {
    const store = new NonceStore(dbPath);
    const nonce = 'Y29uY3VycmVudC1yYWNlLXRlc3QtMDA';
    const expiresAt = '2026-09-22T08:10:00Z';

    const attempts = await Promise.all([
      Promise.resolve().then(() => store.consume(nonce, expiresAt)),
      Promise.resolve().then(() => store.consume(nonce, expiresAt)),
      Promise.resolve().then(() => store.consume(nonce, expiresAt)),
    ]);

    const successfulAttempts = attempts.filter((result) => result === true);
    const failedAttempts = attempts.filter((result) => result === false);

    expect(successfulAttempts.length).toBe(1);
    expect(failedAttempts.length).toBe(2);
    store.close();
  });
});
