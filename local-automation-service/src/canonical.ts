import crypto from 'node:crypto';
import type { AutomationRequest, Channel, Action } from './types.js';

/**
 * Calculates the exact fixed length-prefixed canonical payload in contract order:
 * version, requestId, ownerHash, channel, action, targetKey, issuedAt, expiresAt, nonce
 *
 * Each field is formatted as: `${utf8ByteLength}#${value}`
 */
export function calculateCanonicalPayload(request: AutomationRequest): string {
  const fields: string[] = [
    String(request.version),
    request.requestId,
    request.ownerHash,
    request.channel,
    request.action,
    request.targetKey,
    request.issuedAt,
    request.expiresAt,
    request.nonce,
  ];

  return fields
    .map((field) => `${Buffer.byteLength(field, 'utf8')}#${field}`)
    .join('');
}

/**
 * Calculates the targetDigest: lowercase sha256 hex of channel/action/targetKey
 */
export function calculateTargetDigest(
  channel: Channel | string,
  action: Action | string,
  targetKey: string
): string {
  const payload = `${channel}/${action}/${targetKey}`;
  return crypto.createHash('sha256').update(payload, 'utf8').digest('hex').toLowerCase();
}
