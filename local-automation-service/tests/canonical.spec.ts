import crypto from 'node:crypto';
import { describe, it, expect } from 'vitest';
import { calculateCanonicalPayload, calculateTargetDigest } from '../src/canonical.js';
import type { AutomationRequest } from '../src/types.js';

describe('Canonical Payload Calculation', () => {
  const sampleRequest: AutomationRequest = {
    version: 1,
    requestId: 'c28d22db-363d-429a-8c85-618d3632cf4b',
    ownerHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    channel: 'PLAYWRIGHT_DOM',
    action: 'OPEN_STUDYPILOT_ROUTE',
    targetKey: 'ASSISTANT',
    issuedAt: '2026-09-22T08:00:00Z',
    expiresAt: '2026-09-22T08:01:00Z',
    nonce: 'dGVzdC1ub25jZS0xMjgtYml0cw',
    signature: 'placeholder',
  };

  it('calculates exact fixed length-prefixed canonical payload in contract order', () => {
    // 9 fields: version, requestId, ownerHash, channel, action, targetKey, issuedAt, expiresAt, nonce
    // Format: length#value for each field
    const expected =
      '1#1' +
      '36#c28d22db-363d-429a-8c85-618d3632cf4b' +
      '64#e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' +
      '14#PLAYWRIGHT_DOM' +
      '21#OPEN_STUDYPILOT_ROUTE' +
      '9#ASSISTANT' +
      '20#2026-09-22T08:00:00Z' +
      '20#2026-09-22T08:01:00Z' +
      '26#dGVzdC1ub25jZS0xMjgtYml0cw';

    const actual = calculateCanonicalPayload(sampleRequest);
    expect(actual).toBe(expected);
  });

  it('calculates correct targetDigest as lowercase sha256 hex of channel/action/targetKey', () => {
    const digest = calculateTargetDigest('PLAYWRIGHT_DOM', 'OPEN_STUDYPILOT_ROUTE', 'ASSISTANT');
    expect(digest).toMatch(/^[0-9a-f]{64}$/);

    const direct = crypto.createHash('sha256').update('PLAYWRIGHT_DOM/OPEN_STUDYPILOT_ROUTE/ASSISTANT').digest('hex').toLowerCase();
    expect(digest).toBe(direct);
  });
});
