import { describe, it, expect } from 'vitest';
import {
  parseAndValidateRequestFrame,
  formatReceiptFrame,
  sanitizeMessage,
  MAX_FRAME_SIZE,
} from '../src/protocol.js';
import type { AutomationRequest } from '../src/types.js';

describe('Protocol Framing & Validation', () => {
  const validRequest: AutomationRequest = {
    version: 1,
    requestId: 'c28d22db-363d-429a-8c85-618d3632cf4b',
    ownerHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    channel: 'PLAYWRIGHT_DOM',
    action: 'OPEN_STUDYPILOT_ROUTE',
    targetKey: 'ASSISTANT',
    issuedAt: '2026-09-22T08:00:00Z',
    expiresAt: '2026-09-22T08:01:00Z',
    nonce: 'dGVzdC1ub25jZS0xMjgtYml0cw',
    signature: 'a'.repeat(64),
  };

  it('parses valid single-line UTF-8 JSON request frame', () => {
    const rawLine = JSON.stringify(validRequest);
    const parsed = parseAndValidateRequestFrame(rawLine);
    expect(parsed.success).toBe(true);
    if (parsed.success) {
      expect(parsed.request.requestId).toBe(validRequest.requestId);
      expect(parsed.request.action).toBe(validRequest.action);
    }
  });

  it('rejects oversize frame exceeding 16 KiB (16384 bytes)', () => {
    const hugePadding = 'x'.repeat(MAX_FRAME_SIZE);
    const rawLine = JSON.stringify({ ...validRequest, padding: hugePadding });
    const parsed = parseAndValidateRequestFrame(rawLine);
    expect(parsed.success).toBe(false);
    if (!parsed.success) {
      expect(parsed.errorCode).toBe('OVERSIZE_FRAME');
    }
  });

  it('rejects invalid UTF-8 bytes in frame', () => {
    // Buffer with invalid UTF-8 continuation byte
    const invalidBuffer = Buffer.from([0xff, 0xfe, 0xfd]);
    const parsed = parseAndValidateRequestFrame(invalidBuffer);
    expect(parsed.success).toBe(false);
    if (!parsed.success) {
      expect(parsed.errorCode).toBe('INVALID_UTF8');
    }
  });

  it('rejects multiple JSON objects on a single line', () => {
    const rawLine = JSON.stringify(validRequest) + ' ' + JSON.stringify(validRequest);
    const parsed = parseAndValidateRequestFrame(rawLine);
    expect(parsed.success).toBe(false);
    if (!parsed.success) {
      expect(parsed.errorCode).toBe('INVALID_JSON');
    }
  });

  it('rejects duplicate keys in JSON request', () => {
    const duplicateJson = `{"version":1,"version":1,"requestId":"${validRequest.requestId}","ownerHash":"${validRequest.ownerHash}","channel":"PLAYWRIGHT_DOM","action":"OPEN_STUDYPILOT_ROUTE","targetKey":"ASSISTANT","issuedAt":"2026-09-22T08:00:00Z","expiresAt":"2026-09-22T08:01:00Z","nonce":"${validRequest.nonce}","signature":"${validRequest.signature}"}`;
    const parsed = parseAndValidateRequestFrame(duplicateJson);
    expect(parsed.success).toBe(false);
    if (!parsed.success) {
      expect(parsed.errorCode).toBe('DUPLICATE_KEYS');
    }
  });

  it('rejects unknown or forbidden fields (URL, selector, script, path, etc.)', () => {
    const forbiddenFields = [
      { url: 'http://evil.com' },
      { selector: '#agent-input' },
      { script: 'alert(1)' },
      { path: '/etc/passwd' },
      { windowTitle: 'IDE' },
      { pid: 1234 },
      { extraField: 'not-allowed' },
    ];

    for (const field of forbiddenFields) {
      const payload = { ...validRequest, ...field };
      const parsed = parseAndValidateRequestFrame(JSON.stringify(payload));
      expect(parsed.success).toBe(false);
      if (!parsed.success) {
        expect(parsed.errorCode).toBe('UNKNOWN_FIELDS');
      }
    }
  });

  it('rejects non-UTC timestamps, timezone offsets, and informal date formats', () => {
    const invalidTimestamps = [
      '2026-09-22T08:00:00+08:00', // offset +08:00 forbidden (must be Z)
      '2026-09-22T08:00:00-05:00', // offset -05:00 forbidden
      '2026-09-22T08:00:00', // missing Z suffix
      '2026/09/22 08:00:00', // informal slash format
      'Tue, 22 Sep 2026 08:00:00 GMT', // RFC 2822 format
      '2026-13-45T99:99:99Z', // invalid date numbers
      'now',
    ];

    for (const ts of invalidTimestamps) {
      // test on issuedAt
      const resIssued = parseAndValidateRequestFrame(JSON.stringify({ ...validRequest, issuedAt: ts }));
      expect(resIssued.success).toBe(false);
      if (!resIssued.success) {
        expect(resIssued.errorCode).toBe('INVALID_TIMESTAMP');
      }

      // test on expiresAt
      const resExpires = parseAndValidateRequestFrame(JSON.stringify({ ...validRequest, expiresAt: ts }));
      expect(resExpires.success).toBe(false);
      if (!resExpires.success) {
        expect(resExpires.errorCode).toBe('INVALID_TIMESTAMP');
      }
    }
  });

  it('rejects missing required fields', () => {
    const requiredKeys = Object.keys(validRequest) as (keyof AutomationRequest)[];
    for (const key of requiredKeys) {
      const copy = { ...validRequest };
      delete (copy as Record<string, unknown>)[key];
      const parsed = parseAndValidateRequestFrame(JSON.stringify(copy));
      expect(parsed.success).toBe(false);
      if (!parsed.success) {
        expect(parsed.errorCode).toBe('MISSING_FIELD');
      }
    }
  });

  it('sanitizes message and enforces max 200 characters without leaking secrets or paths', () => {
    const sensitiveMessage =
      'Error in /Users/moxiao/secret/key.pem: failed to connect to http://127.0.0.1:8080/api with secret=supersecretkey and stack: Error at line 42';
    const sanitized = sanitizeMessage(sensitiveMessage);
    expect(sanitized.length).toBeLessThanOrEqual(200);
    expect(sanitized).not.toContain('/Users/');
    expect(sanitized).not.toContain('supersecretkey');
    expect(sanitized).not.toContain('http://');
  });

  it('formats receipt frame ending with newline and capped at 16 KiB', () => {
    const formatted = formatReceiptFrame({
      version: 1,
      requestId: validRequest.requestId,
      adapter: 'PLAYWRIGHT_DOM',
      action: 'OPEN_STUDYPILOT_ROUTE',
      targetDigest: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      startedAt: '2026-09-22T08:00:00Z',
      finishedAt: '2026-09-22T08:00:01Z',
      status: 'SUCCEEDED',
      errorCode: null,
      message: 'Route opened successfully',
    });

    expect(formatted.endsWith('\n')).toBe(true);
    expect(Buffer.byteLength(formatted, 'utf8')).toBeLessThanOrEqual(MAX_FRAME_SIZE);
  });
});
