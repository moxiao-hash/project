import crypto from 'node:crypto';
import { calculateCanonicalPayload } from './canonical.js';
import { validateNonceFormat } from './nonceStore.js';
import { isValidUtcIsoInstant } from './protocol.js';
import type { AutomationRequest, ErrorCode } from './types.js';

export const MIN_SECRET_LENGTH = 32;
export const MAX_LIFETIME_MS = 60 * 1000; // 60s max lifetime
export const MAX_FUTURE_DRIFT_MS = 10 * 1000; // 10s max future clock drift

export type VerificationResult =
  | { valid: true }
  | { valid: false; errorCode: ErrorCode; message: string };

/**
 * Verifies request authentication, HMAC-SHA256 signature, and timing constraints.
 */
export function verifyRequestAuthAndTiming(
  request: AutomationRequest,
  secret: string,
  nowMs: number = Date.now()
): VerificationResult {
  // Enforce secret length >= 32 bytes
  const secretBytes = Buffer.from(secret, 'utf8');
  if (secretBytes.byteLength < MIN_SECRET_LENGTH) {
    return {
      valid: false,
      errorCode: 'KEY_TOO_SHORT',
      message: `Signing key must contain at least ${MIN_SECRET_LENGTH} bytes`,
    };
  }

  // Validate nonce format
  if (!validateNonceFormat(request.nonce)) {
    return {
      valid: false,
      errorCode: 'INVALID_NONCE',
      message: 'Nonce must be base64url without padding with at least 128 bits',
    };
  }

  // Validate strict canonical UTC ISO-8601 instant format
  if (!isValidUtcIsoInstant(request.issuedAt) || !isValidUtcIsoInstant(request.expiresAt)) {
    return {
      valid: false,
      errorCode: 'INVALID_TIMESTAMP',
      message: 'Timestamps must be valid canonical UTC ISO-8601 instants ending in Z',
    };
  }

  // Parse and validate timestamps
  const issuedAtMs = Date.parse(request.issuedAt);
  const expiresAtMs = Date.parse(request.expiresAt);

  if (Number.isNaN(issuedAtMs) || Number.isNaN(expiresAtMs)) {
    return {
      valid: false,
      errorCode: 'INVALID_TIMESTAMP',
      message: 'Invalid ISO-8601 timestamp in request',
    };
  }

  // Reject expiresAt earlier than issuedAt
  if (expiresAtMs < issuedAtMs) {
    return {
      valid: false,
      errorCode: 'INVALID_TIMESTAMP',
      message: 'expiresAt cannot be earlier than issuedAt',
    };
  }

  // Enforce expiresAt <= issuedAt + 60s
  if (expiresAtMs > issuedAtMs + MAX_LIFETIME_MS) {
    return {
      valid: false,
      errorCode: 'LIFETIME_EXCEEDED',
      message: 'Request lifetime cannot exceed 60 seconds',
    };
  }

  // Enforce future clock drift <= 10s
  if (issuedAtMs > nowMs + MAX_FUTURE_DRIFT_MS) {
    return {
      valid: false,
      errorCode: 'CLOCK_DRIFT',
      message: 'Request issuedAt exceeds maximum 10-second future drift',
    };
  }

  // Enforce not expired
  if (nowMs >= expiresAtMs) {
    return {
      valid: false,
      errorCode: 'EXPIRED_REQUEST',
      message: 'Request has already expired',
    };
  }

  // Verify HMAC-SHA256 signature
  const canonicalPayload = calculateCanonicalPayload(request);
  const expectedSignatureHex = crypto
    .createHmac('sha256', secretBytes)
    .update(canonicalPayload, 'utf8')
    .digest('hex')
    .toLowerCase();

  const expectedBuf = Buffer.from(expectedSignatureHex, 'hex');
  const actualBuf = Buffer.from(request.signature.toLowerCase(), 'hex');

  if (expectedBuf.length !== actualBuf.length || !crypto.timingSafeEqual(expectedBuf, actualBuf)) {
    return {
      valid: false,
      errorCode: 'INVALID_SIGNATURE',
      message: 'HMAC-SHA256 signature mismatch',
    };
  }

  return { valid: true };
}
