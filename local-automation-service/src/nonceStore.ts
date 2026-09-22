import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';

const BASE64URL_NONCE_REGEX = /^[A-Za-z0-9_-]{22,}$/;

/**
 * Validates that the nonce is base64url without padding, representing at least 128 bits (>= 22 chars).
 */
export function validateNonceFormat(nonce: unknown): boolean {
  if (typeof nonce !== 'string') {
    return false;
  }
  return BASE64URL_NONCE_REGEX.test(nonce);
}

/**
 * SQLite-backed atomic nonce persistence.
 * Guarantees that nonces are consumed exactly once and replay remains rejected across restarts.
 */
export class NonceStore {
  private db: DatabaseSync;

  constructor(dbPath: string) {
    const parentDir = path.dirname(dbPath);
    if (!fs.existsSync(parentDir)) {
      fs.mkdirSync(parentDir, { recursive: true, mode: 0o700 });
    }

    this.db = new DatabaseSync(dbPath);
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS consumed_nonces (
        nonce TEXT PRIMARY KEY,
        expires_at TEXT NOT NULL,
        consumed_at TEXT NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_consumed_nonces_expires_at ON consumed_nonces(expires_at);
    `);
  }

  /**
   * Atomically attempts to consume a nonce.
   * Returns true if successfully consumed, false if already consumed or invalid.
   */
  public consume(nonce: string, expiresAt: string): boolean {
    if (!validateNonceFormat(nonce)) {
      return false;
    }

    const nowIso = new Date().toISOString();
    try {
      // Immediate insert guarded by PRIMARY KEY unique constraint inside SQLite
      const stmt = this.db.prepare(
        'INSERT INTO consumed_nonces (nonce, expires_at, consumed_at) VALUES (?, ?, ?)'
      );
      stmt.run(nonce, expiresAt, nowIso);
      return true;
    } catch (err: unknown) {
      if (err instanceof Error && err.message.includes('UNIQUE constraint failed')) {
        return false;
      }
      throw err;
    }
  }

  /**
   * Checks whether a nonce has already been consumed.
   */
  public isConsumed(nonce: string): boolean {
    if (!validateNonceFormat(nonce)) {
      return false;
    }
    const stmt = this.db.prepare('SELECT 1 FROM consumed_nonces WHERE nonce = ? LIMIT 1');
    const row = stmt.get(nonce);
    return row !== undefined;
  }

  /**
   * Closes the database connection.
   */
  public close(): void {
    this.db.close();
  }
}
