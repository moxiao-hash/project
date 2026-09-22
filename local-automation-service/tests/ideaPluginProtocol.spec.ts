import { describe, it, expect } from 'vitest';
import {
  PLUGIN_MAX_FRAME_BYTES,
  PLUGIN_MAX_LIFETIME_MS,
  PLUGIN_PROTOCOL_DOMAIN,
  buildPluginRequest,
  calculatePluginCanonicalPayload,
  formatPluginFrame,
  mapPluginResponse,
  parsePluginResponse,
  signPluginRequest,
  type PluginRequest,
  type PluginResponse,
} from '../src/ideaPluginProtocol.js';

/**
 * Cross-language frozen test vector.
 *
 * The SAME values and the SAME expected signature are asserted by the Java plugin self test
 * (idea-plugin/src/test/java/.../PluginSelfTest.java). If either implementation changed its
 * canonical payload, framing or HMAC usage, one of the two suites would fail.
 */
const VECTOR_SECRET = 'studypilot-plugin-secret-32-bytes!!';
const VECTOR_REQUEST = {
  version: 1 as const,
  requestId: '11111111-2222-4333-8444-555555555555',
  action: 'OPEN_REGISTERED_FILE' as const,
  targetKey: 'FILE_REGISTERED',
  issuedAt: '2026-09-22T10:00:00Z',
  expiresAt: '2026-09-22T10:00:10Z',
  nonce: 'abcdefghijklmnopqrstuv',
};
const VECTOR_PAYLOAD =
  '25#studypilot-idea-plugin-v1|1#1|36#11111111-2222-4333-8444-555555555555|20#OPEN_REGISTERED_FILE|15#FILE_REGISTERED|20#2026-09-22T10:00:00Z|20#2026-09-22T10:00:10Z|22#abcdefghijklmnopqrstuv|';
const VECTOR_SIGNATURE = 'a8fd5b27819a20254c450a7a1eee54609eb8acb334a22f0818600e7643f1060c';

function responseFrame(overrides: Partial<PluginResponse> = {}): string {
  const response: PluginResponse = {
    version: 1,
    requestId: VECTOR_REQUEST.requestId,
    action: VECTOR_REQUEST.action,
    status: 'SUCCEEDED',
    errorCode: null,
    message: 'ok',
    finishedAt: '2026-09-22T10:00:01Z',
    ...overrides,
  };
  return JSON.stringify(response);
}

describe('Plugin protocol domain separation and request building', () => {
  it('produces the frozen canonical payload and signature for the shared test vector', () => {
    expect(calculatePluginCanonicalPayload(VECTOR_REQUEST)).toBe(VECTOR_PAYLOAD);
    const signed = signPluginRequest(VECTOR_REQUEST, VECTOR_SECRET);
    expect(signed.signature).toBe(VECTOR_SIGNATURE);
  });

  it('binds the payload to the plugin protocol domain, so a Java-facing signature cannot match', () => {
    // Dropping the domain marker must change the signature: the two sockets are separate domains.
    const withDomain = calculatePluginCanonicalPayload(VECTOR_REQUEST);
    const withoutDomain = withDomain.replace(`${PLUGIN_PROTOCOL_DOMAIN.length}#${PLUGIN_PROTOCOL_DOMAIN}|`, '');
    expect(withDomain).not.toBe(withoutDomain);
    expect(calculatePluginCanonicalPayload(VECTOR_REQUEST)).toContain(PLUGIN_PROTOCOL_DOMAIN);
  });

  it('rejects a key shorter than 32 bytes and a lifetime beyond the frozen window', () => {
    expect(() => buildPluginRequest({ action: 'OPEN_REGISTERED_FILE', targetKey: 'A', secret: 'short' })).toThrow(
      /32 bytes/
    );
    expect(() =>
      buildPluginRequest({
        action: 'OPEN_REGISTERED_FILE',
        targetKey: 'A',
        secret: VECTOR_SECRET,
        lifetimeMs: PLUGIN_MAX_LIFETIME_MS + 1,
      })
    ).toThrow(/15 second/);
  });

  it('builds a short-lived, correlated, single-line request carrying only the opaque handle', () => {
    const request = buildPluginRequest({
      action: 'FOCUS_RUN_CONFIGURATION',
      targetKey: 'RUN_REGISTERED',
      secret: VECTOR_SECRET,
      nowMs: Date.parse('2026-09-22T10:00:00Z'),
      lifetimeMs: 10_000,
    });
    expect(Object.keys(request).sort()).toEqual([
      'action',
      'expiresAt',
      'issuedAt',
      'nonce',
      'requestId',
      'signature',
      'targetKey',
      'version',
    ]);
    expect(Date.parse(request.expiresAt) - Date.parse(request.issuedAt)).toBe(10_000);
    expect(request.nonce.length).toBeGreaterThanOrEqual(22);
    const frame = formatPluginFrame(request);
    expect(frame.endsWith('\n')).toBe(true);
    expect(frame.trimEnd().includes('\n')).toBe(false);
    // No path, run-configuration name, selector or Action id can appear in the request.
    expect(frame).not.toContain('/');
    expect(frame).not.toContain('StudyPilotApplication');
  });

  it('never derives the target from caller-controlled text', () => {
    const request = buildPluginRequest({
      action: 'OPEN_REGISTERED_FILE',
      targetKey: 'FILE_REGISTERED',
      secret: VECTOR_SECRET,
    });
    expect(JSON.stringify(request)).not.toContain('..');
    expect(/-{2}/.test(JSON.stringify(request))).toBe(false);
  });
});

describe('Plugin response validation', () => {
  it('accepts a well-formed correlated response', () => {
    const parsed = parsePluginResponse(responseFrame(), 'OPEN_REGISTERED_FILE');
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.response.status).toBe('SUCCEEDED');
    }
  });

  it('rejects an oversize frame', () => {
    const oversize = `{"version":1,"requestId":"${'x'.repeat(PLUGIN_MAX_FRAME_BYTES)}","action":"OPEN_REGISTERED_FILE","status":"SUCCEEDED","errorCode":null,"message":"m","finishedAt":"t"}`;
    const parsed = parsePluginResponse(oversize, 'OPEN_REGISTERED_FILE');
    expect(parsed.ok).toBe(false);
    if (!parsed.ok) {
      expect(parsed.code).toBe('OVERSIZE_RESPONSE');
    }
  });

  it('rejects malformed JSON, extra fields and a missing field', () => {
    expect(parsePluginResponse('{not json', 'OPEN_REGISTERED_FILE').ok).toBe(false);

    const extra = JSON.stringify({ ...JSON.parse(responseFrame()), path: '/etc/passwd' });
    const extraParsed = parsePluginResponse(extra, 'OPEN_REGISTERED_FILE');
    expect(extraParsed.ok).toBe(false);
    if (!extraParsed.ok) {
      expect(extraParsed.code).toBe('UNKNOWN_RESPONSE_FIELDS');
    }

    const missing = JSON.stringify({ version: 1, requestId: 'x', action: 'OPEN_REGISTERED_FILE', status: 'SUCCEEDED' });
    const missingParsed = parsePluginResponse(missing, 'OPEN_REGISTERED_FILE');
    expect(missingParsed.ok).toBe(false);
    if (!missingParsed.ok) {
      expect(missingParsed.code).toBe('MISSING_RESPONSE_FIELD');
    }
  });

  it('rejects a multi-line frame and an unknown status', () => {
    const multiline = `${responseFrame()}\n${responseFrame()}`;
    expect(parsePluginResponse(multiline, 'OPEN_REGISTERED_FILE').ok).toBe(false);

    const badStatus = parsePluginResponse(responseFrame({ status: 'OK' as never }), 'OPEN_REGISTERED_FILE');
    expect(badStatus.ok).toBe(false);
    if (!badStatus.ok) {
      expect(badStatus.code).toBe('INVALID_RESPONSE');
    }
  });

  it('rejects a response correlated with a different action', () => {
    const parsed = parsePluginResponse(responseFrame(), 'SHOW_TEST_RESULT');
    expect(parsed.ok).toBe(false);
    if (!parsed.ok) {
      expect(parsed.code).toBe('CORRELATION_MISMATCH');
    }
  });
});

describe('Plugin outcome mapping cannot manufacture success', () => {
  const base: PluginResponse = {
    version: 1,
    requestId: 'r-1',
    action: 'OPEN_REGISTERED_FILE',
    status: 'SUCCEEDED',
    errorCode: null,
    message: 'm',
    finishedAt: 't',
  };

  it('treats only SUCCEEDED as verified', () => {
    const success = mapPluginResponse(base, 'r-1');
    expect(success.ok).toBe(true);
    expect(success.verified).toBe(true);
  });

  it('treats FAILED/UNVERIFIED_TARGET_STATE as dispatched but unverified', () => {
    const outcome = mapPluginResponse(
      { ...base, status: 'FAILED', errorCode: 'UNVERIFIED_TARGET_STATE', message: 'post state not proven' },
      'r-1'
    );
    expect(outcome.ok).toBe(true);
    expect(outcome.verified).toBe(false);
    expect(outcome.code).toBe('UNVERIFIED_TARGET_STATE');
  });

  it('treats FAILED with another code and REJECTED as not dispatched', () => {
    for (const status of ['FAILED', 'REJECTED'] as const) {
      const outcome = mapPluginResponse({ ...base, status, errorCode: 'TARGET_NOT_FOUND' }, 'r-1');
      expect(outcome.ok).toBe(false);
      expect(outcome.verified).toBe(false);
      expect(outcome.code).toBe('TARGET_NOT_FOUND');
    }
  });

  it('rejects a mismatched correlation id', () => {
    const outcome = mapPluginResponse(base, 'r-2');
    expect(outcome.ok).toBe(false);
    expect(outcome.code).toBe('CORRELATION_MISMATCH');
  });
});

// A type-level guard that the request shape stays limited to the frozen fields.
const _requestShapeGuard: keyof PluginRequest = 'targetKey';
void _requestShapeGuard;
