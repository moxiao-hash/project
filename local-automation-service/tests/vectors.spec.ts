import crypto from 'node:crypto';
import { describe, it, expect } from 'vitest';
import { calculateCanonicalPayload, calculateTargetDigest } from '../src/canonical.js';
import type { AutomationRequest } from '../src/types.js';

/**
 * Deterministic Test Vectors for Cross-Language Verification (TypeScript <-> Java)
 * These vectors allow MiniMax Code's Java client implementation to verify canonicalization,
 * hashing, and HMAC-SHA256 calculation against byte-exact expectations.
 */
describe('Deterministic Test Vectors for Cross-Language Handshake', () => {
  const deterministicSecret = '0123456789abcdef0123456789abcdef'; // Exactly 32 bytes

  const vectorRequest: AutomationRequest = {
    version: 1,
    requestId: 'c28d22db-363d-429a-8c85-618d3632cf4b',
    ownerHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    channel: 'PLAYWRIGHT_DOM',
    action: 'OPEN_STUDYPILOT_ROUTE',
    targetKey: 'ASSISTANT',
    issuedAt: '2026-09-22T08:00:00Z',
    expiresAt: '2026-09-22T08:01:00Z',
    nonce: 'dGVzdC1ub25jZS0xMjgtYml0cw',
    signature: '',
  };

  const expectedCanonicalPayload =
    '1#1' +
    '36#c28d22db-363d-429a-8c85-618d3632cf4b' +
    '64#e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' +
    '14#PLAYWRIGHT_DOM' +
    '21#OPEN_STUDYPILOT_ROUTE' +
    '9#ASSISTANT' +
    '20#2026-09-22T08:00:00Z' +
    '20#2026-09-22T08:01:00Z' +
    '26#dGVzdC1ub25jZS0xMjgtYml0cw';

  it('matches canonical payload string verbatim', () => {
    const canonical = calculateCanonicalPayload(vectorRequest);
    expect(canonical).toBe(expectedCanonicalPayload);
  });

  it('computes deterministic HMAC-SHA256 signature', () => {
    const expectedHmac = crypto
      .createHmac('sha256', Buffer.from(deterministicSecret, 'utf8'))
      .update(expectedCanonicalPayload, 'utf8')
      .digest('hex')
      .toLowerCase();

    // Verify expected HMAC hex string matches deterministic precomputed value
    expect(expectedHmac).toBe('e471c52c3d1ee78f52a0178f1ec2e0e09eac84a1354ce45141569336c7fdc943');
  });

  it('computes deterministic targetDigest for browser and IDE actions', () => {
    const browserDigest = calculateTargetDigest('PLAYWRIGHT_DOM', 'OPEN_STUDYPILOT_ROUTE', 'ASSISTANT');
    expect(browserDigest).toBe('d30b1c64274f91e571851b4d15914d7c8f7cb917c0689090be571279bbd9f0eb');

    const ideDigest = calculateTargetDigest('IDEA_ACCESSIBILITY', 'OPEN_REGISTERED_FILE', 'FILE_SAMPLE');
    const expectedIde = crypto
      .createHash('sha256')
      .update('IDEA_ACCESSIBILITY/OPEN_REGISTERED_FILE/FILE_SAMPLE')
      .digest('hex')
      .toLowerCase();
    expect(ideDigest).toBe(expectedIde);
  });
});
