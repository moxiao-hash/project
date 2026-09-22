import type {
  AutomationRequest,
  AutomationReceipt,
  ErrorCode,
  Channel,
  Action,
} from './types.js';

export const MAX_FRAME_SIZE = 16384; // 16 KiB

const REQUIRED_FIELDS: (keyof AutomationRequest)[] = [
  'version',
  'requestId',
  'ownerHash',
  'channel',
  'action',
  'targetKey',
  'issuedAt',
  'expiresAt',
  'nonce',
  'signature',
];

const ALLOWED_FIELDS = new Set<string>(REQUIRED_FIELDS);

const ALLOWED_CHANNELS = new Set<Channel>(['PLAYWRIGHT_DOM', 'IDEA_ACCESSIBILITY']);

const ALLOWED_ACTIONS = new Set<Action>([
  'OPEN_STUDYPILOT_ROUTE',
  'FOCUS_AGENT_INPUT',
  'OPEN_RESULT_PANEL',
  'OPEN_REGISTERED_FILE',
  'FOCUS_RUN_CONFIGURATION',
  'SHOW_TEST_RESULT',
]);

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const OWNER_HASH_REGEX = /^[0-9a-f]{64}$/;
const SIGNATURE_REGEX = /^[0-9a-f]{64}$/;

export type ParseResult =
  | { success: true; request: AutomationRequest }
  | { success: false; errorCode: ErrorCode; message: string };

/**
 * Checks for duplicate keys in a JSON string.
 */
function hasDuplicateKeys(text: string): boolean {
  const stack: Set<string>[] = [];
  let i = 0;
  const n = text.length;

  while (i < n) {
    const ch = text[i];
    if (ch === '{') {
      stack.push(new Set<string>());
      i++;
    } else if (ch === '}') {
      stack.pop();
      i++;
    } else if (ch === '"') {
      // Parse string literal
      i++;
      let key = '';
      while (i < n) {
        if (text[i] === '\\' && i + 1 < n) {
          key += text[i] + text[i + 1];
          i += 2;
        } else if (text[i] === '"') {
          i++;
          break;
        } else {
          key += text[i];
          i++;
        }
      }

      // Check if this string is followed by ':' (meaning it's an object key)
      let j = i;
      while (j < n && (text[j] === ' ' || text[j] === '\t' || text[j] === '\r' || text[j] === '\n')) {
        j++;
      }
      if (j < n && text[j] === ':') {
        const currentSet = stack[stack.length - 1];
        if (currentSet) {
          if (currentSet.has(key)) {
            return true; // Duplicate key detected
          }
          currentSet.add(key);
        }
      }
    } else {
      i++;
    }
  }
  return false;
}

/**
 * Parses and validates raw frame bytes or string.
 */
export function parseAndValidateRequestFrame(rawInput: Buffer | string): ParseResult {
  let buf: Buffer;
  if (typeof rawInput === 'string') {
    buf = Buffer.from(rawInput, 'utf8');
  } else {
    buf = rawInput;
  }

  if (buf.byteLength > MAX_FRAME_SIZE) {
    return {
      success: false,
      errorCode: 'OVERSIZE_FRAME',
      message: 'Frame size exceeds maximum 16 KiB limit',
    };
  }

  // Validate UTF-8 strictly
  let decodedString: string;
  try {
    const decoder = new TextDecoder('utf-8', { fatal: true });
    decodedString = decoder.decode(buf);
  } catch {
    return {
      success: false,
      errorCode: 'INVALID_UTF8',
      message: 'Frame contains invalid UTF-8 byte sequences',
    };
  }

  const trimmed = decodedString.trim();
  if (!trimmed) {
    return {
      success: false,
      errorCode: 'INVALID_FRAME',
      message: 'Empty request frame',
    };
  }

  // Detect duplicate JSON keys
  if (hasDuplicateKeys(trimmed)) {
    return {
      success: false,
      errorCode: 'DUPLICATE_KEYS',
      message: 'Request JSON contains duplicate keys',
    };
  }

  // Parse JSON
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return {
      success: false,
      errorCode: 'INVALID_JSON',
      message: 'Malformed JSON in request frame',
    };
  }

  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return {
      success: false,
      errorCode: 'INVALID_JSON',
      message: 'Request payload must be a JSON object',
    };
  }

  const obj = parsed as Record<string, unknown>;

  // Check unknown fields
  for (const key of Object.keys(obj)) {
    if (!ALLOWED_FIELDS.has(key)) {
      return {
        success: false,
        errorCode: 'UNKNOWN_FIELDS',
        message: `Unknown or forbidden field: ${key}`,
      };
    }
  }

  // Check missing fields
  for (const field of REQUIRED_FIELDS) {
    if (obj[field] === undefined || obj[field] === null) {
      return {
        success: false,
        errorCode: 'MISSING_FIELD',
        message: `Missing required field: ${field}`,
      };
    }
  }

  // Validate version
  if (obj.version !== 1) {
    return {
      success: false,
      errorCode: 'UNSUPPORTED_VERSION',
      message: 'Protocol version must be integer 1',
    };
  }

  // Validate requestId
  if (typeof obj.requestId !== 'string' || !UUID_REGEX.test(obj.requestId)) {
    return {
      success: false,
      errorCode: 'INVALID_UUID',
      message: 'requestId must be a valid UUID',
    };
  }

  // Validate ownerHash
  if (typeof obj.ownerHash !== 'string' || !OWNER_HASH_REGEX.test(obj.ownerHash)) {
    return {
      success: false,
      errorCode: 'INVALID_OWNER_HASH',
      message: 'ownerHash must be a 64-character lowercase hex sha256',
    };
  }

  // Validate channel
  if (typeof obj.channel !== 'string' || !ALLOWED_CHANNELS.has(obj.channel as Channel)) {
    return {
      success: false,
      errorCode: 'INVALID_CHANNEL',
      message: 'channel must be PLAYWRIGHT_DOM or IDEA_ACCESSIBILITY',
    };
  }

  // Validate action
  if (typeof obj.action !== 'string' || !ALLOWED_ACTIONS.has(obj.action as Action)) {
    return {
      success: false,
      errorCode: 'INVALID_ACTION',
      message: 'action must be one of the six frozen actions',
    };
  }

  // Validate targetKey
  if (typeof obj.targetKey !== 'string' || obj.targetKey.trim().length === 0) {
    return {
      success: false,
      errorCode: 'INVALID_TARGET_KEY',
      message: 'targetKey must be a non-empty string',
    };
  }

  // Validate issuedAt
  if (typeof obj.issuedAt !== 'string' || Number.isNaN(Date.parse(obj.issuedAt))) {
    return {
      success: false,
      errorCode: 'INVALID_TIMESTAMP',
      message: 'issuedAt must be a valid ISO-8601 timestamp string',
    };
  }

  // Validate expiresAt
  if (typeof obj.expiresAt !== 'string' || Number.isNaN(Date.parse(obj.expiresAt))) {
    return {
      success: false,
      errorCode: 'INVALID_TIMESTAMP',
      message: 'expiresAt must be a valid ISO-8601 timestamp string',
    };
  }

  // Validate nonce
  if (typeof obj.nonce !== 'string') {
    return {
      success: false,
      errorCode: 'INVALID_NONCE',
      message: 'nonce must be a string',
    };
  }

  // Validate signature
  if (typeof obj.signature !== 'string' || !SIGNATURE_REGEX.test(obj.signature)) {
    return {
      success: false,
      errorCode: 'INVALID_SIGNATURE',
      message: 'signature must be a 64-character lowercase hex HMAC-SHA256',
    };
  }

  return {
    success: true,
    request: obj as unknown as AutomationRequest,
  };
}

/**
 * Sanitizes messages so they never leak file paths, URLs, secrets, or internal stacks.
 * Output is strictly capped at 200 characters.
 */
export function sanitizeMessage(rawMessage: string): string {
  if (!rawMessage) return '';

  let sanitized = rawMessage;

  // Strip URLs
  sanitized = sanitized.replace(/https?:\/\/[^\s]+/gi, '[URL]');

  // Strip file paths (e.g. /Users/..., /etc/..., ./path/...)
  sanitized = sanitized.replace(/(?:\/[a-zA-Z0-9._-]+)+/g, '[PATH]');

  // Strip secrets, keys, tokens
  sanitized = sanitized.replace(/(key|secret|token|password|auth|bearer)=[^\s&]+/gi, '$1=[REDACTED]');

  // Strip stack trace lines
  sanitized = sanitized.replace(/\s+at\s+.*/gi, '');

  sanitized = sanitized.trim();
  if (sanitized.length > 200) {
    sanitized = sanitized.slice(0, 197) + '...';
  }

  return sanitized;
}

/**
 * Formats a receipt as a single-line UTF-8 JSON ending with newline \n, capped at 16 KiB.
 */
export function formatReceiptFrame(receipt: AutomationReceipt): string {
  const sanitizedReceipt: AutomationReceipt = {
    ...receipt,
    message: sanitizeMessage(receipt.message),
  };

  const line = JSON.stringify(sanitizedReceipt) + '\n';
  const buf = Buffer.from(line, 'utf8');
  if (buf.byteLength > MAX_FRAME_SIZE) {
    // Truncate message further if somehow over 16 KiB
    sanitizedReceipt.message = 'Receipt truncated';
    return JSON.stringify(sanitizedReceipt) + '\n';
  }
  return line;
}
