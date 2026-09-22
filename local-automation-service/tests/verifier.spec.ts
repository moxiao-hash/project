import crypto from 'node:crypto';
import { describe, it, expect } from 'vitest';
import { verifyRequestAuthAndTiming } from '../src/verifier.js';
import { calculateCanonicalPayload } from '../src/canonical.js';
import type { AutomationRequest } from '../src/types.js';

describe('Verifier: Auth & Timing Verification', () => {
  const secretKey = 'this-is-a-valid-32-byte-secret-key-12345'; // 40 bytes >= 32

  function createSignedRequest(overrides: Partial<AutomationRequest> = {}): {
    request: AutomationRequest;
    secret: string;
  } {
    const baseRequest: AutomationRequest = {
      version: 1,
      requestId: 'c28d22db-363d-429a-8c85-618d3632cf4b',
      ownerHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      channel: 'PLAYWRIGHT_DOM',
      action: 'OPEN_STUDYPILOT_ROUTE',
      targetKey: 'ASSISTANT',
      issuedAt: new Date(Date.now() - 5000).toISOString(),
      expiresAt: new Date(Date.now() + 50000).toISOString(),
      nonce: 'dGVzdC1ub25jZS0xMjgtYml0cw',
      signature: '',
      ...overrides,
    };

    const payload = calculateCanonicalPayload(baseRequest);
    const signature = crypto
      .createHmac('sha256', secretKey)
      .update(payload, 'utf8')
      .digest('hex')
      .toLowerCase();

    return {
      request: { ...baseRequest, signature },
      secret: secretKey,
    };
  }

  it('rejects secret keys shorter than 32 bytes', () => {
    const { request } = createSignedRequest();
    const result = verifyRequestAuthAndTiming(request, 'short-key-under-32-bytes');
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.errorCode).toBe('KEY_TOO_SHORT');
    }
  });

  it('accepts valid request with correct HMAC-SHA256 signature and fresh timestamp', () => {
    const { request, secret } = createSignedRequest();
    const result = verifyRequestAuthAndTiming(request, secret);
    expect(result.valid).toBe(true);
  });

  it('rejects tampered request or wrong signature', () => {
    const { request, secret } = createSignedRequest();
    // Tamper targetKey after signature calculation
    const tampered = { ...request, targetKey: 'WORKSPACE_ARTIFACTS' };
    const result = verifyRequestAuthAndTiming(tampered, secret);
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.errorCode).toBe('INVALID_SIGNATURE');
    }
  });

  it('rejects expiresAt earlier than issuedAt', () => {
    const now = Date.now();
    const { request, secret } = createSignedRequest({
      issuedAt: new Date(now).toISOString(),
      expiresAt: new Date(now - 5000).toISOString(), // expiresAt earlier than issuedAt
    });
    const result = verifyRequestAuthAndTiming(request, secret, now);
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.errorCode).toBe('INVALID_TIMESTAMP');
    }
  });

  it('rejects expired request (now >= expiresAt)', () => {
    const now = Date.now();
    const { request, secret } = createSignedRequest({
      issuedAt: new Date(now - 70000).toISOString(),
      expiresAt: new Date(now - 10000).toISOString(),
    });
    const result = verifyRequestAuthAndTiming(request, secret, now);
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.errorCode).toBe('EXPIRED_REQUEST');
    }
  });

  it('rejects future drift exceeding 10 seconds (issuedAt > now + 10s)', () => {
    const now = Date.now();
    const { request, secret } = createSignedRequest({
      issuedAt: new Date(now + 15000).toISOString(),
      expiresAt: new Date(now + 60000).toISOString(),
    });
    const result = verifyRequestAuthAndTiming(request, secret, now);
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.errorCode).toBe('CLOCK_DRIFT');
    }
  });

  it('rejects lifetime exceeding 60 seconds (expiresAt > issuedAt + 60s)', () => {
    const now = Date.now();
    const { request, secret } = createSignedRequest({
      issuedAt: new Date(now).toISOString(),
      expiresAt: new Date(now + 65000).toISOString(), // 65 seconds
    });
    const result = verifyRequestAuthAndTiming(request, secret, now);
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.errorCode).toBe('LIFETIME_EXCEEDED');
    }
  });

  it('rejects invalid nonce format during auth verification', () => {
    const { request, secret } = createSignedRequest({
      nonce: 'invalid+nonce/not=base64url',
    });
    const result = verifyRequestAuthAndTiming(request, secret);
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.errorCode).toBe('INVALID_NONCE');
    }
  });
});
