import crypto from 'node:crypto';

/**
 * StudyPilot Task 33 — private protocol between the local automation service and the trusted
 * JetBrains plugin.
 *
 * This is a SEPARATE protocol domain from the Java-facing socket: different socket, different
 * >=32-byte HMAC key, different nonce store, shorter expiry, and a canonical payload that is
 * prefixed with a domain marker. A signature produced for one side is therefore never valid
 * on the other, and vice versa.
 *
 * Requests carry only a fixed version, correlation fields, one of the three frozen IDEA
 * actions and an opaque registered handle. Paths, run-configuration names, free text,
 * selectors, IntelliJ Action ids, process ids and window titles are structurally impossible.
 */

export const PLUGIN_PROTOCOL_DOMAIN = 'studypilot-idea-plugin-v1';
export const PLUGIN_MAX_FRAME_BYTES = 16384;
export const PLUGIN_MAX_LIFETIME_MS = 15_000;
export const PLUGIN_MAX_FUTURE_DRIFT_MS = 5_000;
export const PLUGIN_MIN_SECRET_BYTES = 32;

export const PLUGIN_ACTIONS = [
  'OPEN_REGISTERED_FILE',
  'FOCUS_RUN_CONFIGURATION',
  'SHOW_TEST_RESULT',
] as const;

export type PluginAction = (typeof PLUGIN_ACTIONS)[number];

export const PLUGIN_REQUEST_FIELDS = [
  'version',
  'requestId',
  'action',
  'targetKey',
  'issuedAt',
  'expiresAt',
  'nonce',
  'signature',
] as const;

export const PLUGIN_RESPONSE_FIELDS = [
  'version',
  'requestId',
  'action',
  'status',
  'errorCode',
  'message',
  'finishedAt',
] as const;

export interface PluginRequest {
  version: 1;
  requestId: string;
  action: PluginAction;
  targetKey: string;
  issuedAt: string;
  expiresAt: string;
  nonce: string;
  signature: string;
}

export type PluginStatus = 'SUCCEEDED' | 'FAILED' | 'REJECTED';

export interface PluginResponse {
  version: 1;
  requestId: string;
  action: string;
  status: PluginStatus;
  errorCode: string | null;
  message: string;
  finishedAt: string;
}

function appendField(parts: string[], value: string): void {
  const bytes = Buffer.byteLength(value, 'utf8');
  parts.push(`${bytes}#${value}|`);
}

/** Canonical payload for signing; byte-identical to the plugin's implementation. */
export function calculatePluginCanonicalPayload(request: {
  version: number;
  requestId: string;
  action: string;
  targetKey: string;
  issuedAt: string;
  expiresAt: string;
  nonce: string;
}): string {
  const parts: string[] = [];
  appendField(parts, PLUGIN_PROTOCOL_DOMAIN);
  appendField(parts, String(request.version));
  appendField(parts, request.requestId);
  appendField(parts, request.action);
  appendField(parts, request.targetKey);
  appendField(parts, request.issuedAt);
  appendField(parts, request.expiresAt);
  appendField(parts, request.nonce);
  return parts.join('');
}

export function signPluginRequest(
  request: Omit<PluginRequest, 'signature'>,
  secret: string
): PluginRequest {
  const signature = crypto
    .createHmac('sha256', Buffer.from(secret, 'utf8'))
    .update(calculatePluginCanonicalPayload(request), 'utf8')
    .digest('hex')
    .toLowerCase();
  return { ...request, signature };
}

export interface BuildPluginRequestOptions {
  action: PluginAction;
  targetKey: string;
  secret: string;
  nowMs?: number;
  lifetimeMs?: number;
  nonce?: string;
  requestId?: string;
}

/** Builds one signed plugin request with a short expiry. */
export function buildPluginRequest(options: BuildPluginRequestOptions): PluginRequest {
  const nowMs = options.nowMs ?? Date.now();
  const lifetimeMs = options.lifetimeMs ?? 10_000;
  if (lifetimeMs <= 0 || lifetimeMs > PLUGIN_MAX_LIFETIME_MS) {
    throw new Error('plugin request lifetime must be within the frozen 15 second window');
  }
  if (Buffer.byteLength(options.secret, 'utf8') < PLUGIN_MIN_SECRET_BYTES) {
    throw new Error(`plugin HMAC key must contain at least ${PLUGIN_MIN_SECRET_BYTES} bytes`);
  }
  const request = {
    version: 1 as const,
    requestId: options.requestId ?? crypto.randomUUID(),
    action: options.action,
    targetKey: options.targetKey,
    issuedAt: new Date(nowMs).toISOString(),
    expiresAt: new Date(nowMs + lifetimeMs).toISOString(),
    nonce: options.nonce ?? crypto.randomBytes(16).toString('base64url'),
  };
  return signPluginRequest(request, options.secret);
}

/** Serialises a request as a single-line frame. */
export function formatPluginFrame(request: PluginRequest): string {
  return `${JSON.stringify(request)}\n`;
}

export type PluginParseResult =
  | { ok: true; response: PluginResponse }
  | { ok: false; code: string; message: string };

function hasOnlyFields(value: Record<string, unknown>, allowed: readonly string[]): boolean {
  return Object.keys(value).every((key) => allowed.includes(key));
}

/**
 * Strictly validates one response line from the plugin.
 *
 * Rejects oversize frames, embedded newlines, malformed JSON, any extra or missing field, an
 * unknown status, and a missing correlation. Callers must additionally verify that the
 * correlation matches the request they sent.
 */
export function parsePluginResponse(raw: string, expectedAction: string): PluginParseResult {
  if (typeof raw !== 'string' || raw.length === 0) {
    return { ok: false, code: 'EMPTY_RESPONSE', message: 'plugin returned no frame' };
  }
  const byteLength = Buffer.byteLength(raw, 'utf8');
  if (byteLength > PLUGIN_MAX_FRAME_BYTES) {
    return { ok: false, code: 'OVERSIZE_RESPONSE', message: 'plugin response exceeds 16 KiB' };
  }
  const trimmed = raw.trim();
  if (trimmed.includes('\n') || trimmed.includes('\r')) {
    return { ok: false, code: 'INVALID_RESPONSE', message: 'plugin response must be exactly one line' };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return { ok: false, code: 'INVALID_RESPONSE', message: 'plugin response is not valid JSON' };
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return { ok: false, code: 'INVALID_RESPONSE', message: 'plugin response must be a JSON object' };
  }

  const record = parsed as Record<string, unknown>;
  if (!hasOnlyFields(record, PLUGIN_RESPONSE_FIELDS)) {
    return { ok: false, code: 'UNKNOWN_RESPONSE_FIELDS', message: 'plugin response has unexpected fields' };
  }
  for (const field of PLUGIN_RESPONSE_FIELDS) {
    if (field === 'errorCode') {
      continue; // nullable by contract
    }
    if (record[field] === undefined || record[field] === null) {
      return { ok: false, code: 'MISSING_RESPONSE_FIELD', message: 'plugin response is missing a required field' };
    }
  }
  if (record.version !== 1) {
    return { ok: false, code: 'UNSUPPORTED_VERSION', message: 'plugin response version must be 1' };
  }
  if (typeof record.requestId !== 'string' || typeof record.action !== 'string') {
    return { ok: false, code: 'INVALID_RESPONSE', message: 'plugin response correlation fields must be strings' };
  }
  if (record.action !== expectedAction) {
    return { ok: false, code: 'CORRELATION_MISMATCH', message: 'plugin response action does not match the request' };
  }
  const status = record.status;
  if (status !== 'SUCCEEDED' && status !== 'FAILED' && status !== 'REJECTED') {
    return { ok: false, code: 'INVALID_RESPONSE', message: 'plugin response status is not a frozen enum value' };
  }
  if (record.errorCode !== null && typeof record.errorCode !== 'string') {
    return { ok: false, code: 'INVALID_RESPONSE', message: 'plugin response errorCode must be null or a string' };
  }
  if (typeof record.message !== 'string' || typeof record.finishedAt !== 'string') {
    return { ok: false, code: 'INVALID_RESPONSE', message: 'plugin response message/finishedAt must be strings' };
  }

  return {
    ok: true,
    response: {
      version: 1,
      requestId: record.requestId,
      action: record.action,
      status,
      errorCode: (record.errorCode as string | null) ?? null,
      message: (record.message as string).slice(0, 200),
      finishedAt: record.finishedAt,
    },
  };
}

export interface PluginOutcome {
  /** The plugin dispatched the registered action. */
  ok: boolean;
  /** The plugin observed the required IDE state afterwards. */
  verified: boolean;
  code: string;
  detail: string;
}

/**
 * Maps a validated plugin response onto the outcome shape used by the IDEA adapter.
 *
 * `SUCCEEDED` is the ONLY status that yields `verified`, and it can only come from the plugin.
 */
export function mapPluginResponse(response: PluginResponse, expectedRequestId: string): PluginOutcome {
  if (response.requestId !== expectedRequestId) {
    return {
      ok: false,
      verified: false,
      code: 'CORRELATION_MISMATCH',
      detail: 'plugin response request id does not match the request',
    };
  }
  switch (response.status) {
    case 'SUCCEEDED':
      return { ok: true, verified: true, code: 'OK', detail: response.message };
    case 'FAILED':
      if (response.errorCode === 'UNVERIFIED_TARGET_STATE') {
        return { ok: true, verified: false, code: 'UNVERIFIED_TARGET_STATE', detail: response.message };
      }
      return {
        ok: false,
        verified: false,
        code: response.errorCode ?? 'PLUGIN_FAILURE',
        detail: response.message,
      };
    case 'REJECTED':
      return {
        ok: false,
        verified: false,
        code: response.errorCode ?? 'REJECTED',
        detail: response.message,
      };
    default:
      return { ok: false, verified: false, code: 'INVALID_RESPONSE', detail: 'unknown plugin status' };
  }
}
