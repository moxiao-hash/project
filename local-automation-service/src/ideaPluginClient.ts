import net from 'node:net';
import {
  PLUGIN_MAX_FRAME_BYTES,
  buildPluginRequest,
  formatPluginFrame,
  mapPluginResponse,
  parsePluginResponse,
  type PluginAction,
  type PluginOutcome,
} from './ideaPluginProtocol.js';

/**
 * StudyPilot Task 33 — client for the trusted JetBrains plugin's PRIVATE Unix Domain Socket.
 *
 * The service is the only client. Failures of any kind (missing socket, timeout, oversize or
 * malformed frame, correlation mismatch) surface as `ok === false`; this client never invents
 * success and never falls back to another channel.
 */

export interface PluginTransport {
  (frame: string, socketPath: string, timeoutMs: number): Promise<string>;
}

/**
 * Raised when the plugin's reply is not exactly one well-formed single-line frame within
 * 16 KiB. Distinct from a transport failure so the caller reports a precise stable code.
 */
export class PluginResponseInvalidError extends Error {}

export interface IdeaPluginClientConfig {
  socketPath: string;
  secret: string;
  timeoutMs?: number;
  transport?: PluginTransport;
  nowMs?: () => number;
}

const DEFAULT_TIMEOUT_MS = 3000;

/**
 * Default transport: one request/response exchange over the plugin's Unix socket.
 *
 * The exchange is deterministic and timing independent:
 *   * the service HALF-CLOSES its write side right after the request, so the plugin can read
 *     through EOF and can never act on a partially delivered request;
 *   * the reply is buffered until the plugin closes its side, and only then is the whole
 *     payload validated as EXACTLY one newline-terminated single-line frame. A second frame or
 *     any trailing byte is rejected no matter which chunk it arrives in;
 *   * UTF-8 is decoded strictly across chunk boundaries.
 */
export const unixSocketPluginTransport: PluginTransport = (frame, socketPath, timeoutMs) =>
  new Promise<string>((resolve, reject) => {
    const chunks: Buffer[] = [];
    let receivedBytes = 0;
    let settled = false;

    const finish = (error: Error | null, value?: string): void => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      socket.destroy();
      if (error) {
        reject(error);
      } else {
        resolve(value ?? '');
      }
    };

    const socket = net.createConnection(socketPath);
    const timer = setTimeout(() => finish(new Error('plugin socket response timed out')), timeoutMs);

    socket.on('connect', () => {
      // Half-close the write side: the plugin reads to EOF, then answers and closes.
      socket.end(Buffer.from(frame, 'utf8'));
    });

    socket.on('data', (chunk: Buffer) => {
      chunks.push(chunk);
      receivedBytes += chunk.length;
      if (receivedBytes > PLUGIN_MAX_FRAME_BYTES) {
        finish(
          new PluginResponseInvalidError('plugin response exceeded the 16 KiB frame limit')
        );
      }
    });

    socket.on('error', (error: Error) => finish(error));

    socket.on('close', () => {
      if (settled) {
        return;
      }
      let text: string;
      try {
        text = new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks));
      } catch {
        finish(new PluginResponseInvalidError('plugin response is not valid UTF-8'));
        return;
      }
      if (!text.endsWith('\n')) {
        finish(
          new PluginResponseInvalidError(
            'plugin response must be exactly one newline-terminated frame'
          )
        );
        return;
      }
      const body = text.slice(0, -1);
      if (body.length === 0) {
        finish(new PluginResponseInvalidError('plugin returned an empty frame'));
        return;
      }
      if (body.includes('\n') || body.includes('\r')) {
        finish(
          new PluginResponseInvalidError('plugin response must be exactly one single-line frame')
        );
        return;
      }
      finish(null, body);
    });
  });

export class IdeaPluginClient {
  private readonly config: IdeaPluginClientConfig;
  private readonly transport: PluginTransport;

  constructor(config: IdeaPluginClientConfig) {
    this.config = config;
    this.transport = config.transport ?? unixSocketPluginTransport;
  }

  public isConfigured(): boolean {
    return typeof this.config.socketPath === 'string' && this.config.socketPath.length > 0;
  }

  public openRegisteredFile(handle: string): Promise<PluginOutcome> {
    return this.invoke('OPEN_REGISTERED_FILE', handle);
  }

  public focusRunConfiguration(handle: string): Promise<PluginOutcome> {
    return this.invoke('FOCUS_RUN_CONFIGURATION', handle);
  }

  public showTestResult(handle: string): Promise<PluginOutcome> {
    return this.invoke('SHOW_TEST_RESULT', handle);
  }

  /**
   * Sends one signed request and maps the reply. Every failure path returns `ok: false` with a
   * stable code so the caller can only ever produce a FAILED receipt.
   */
  private async invoke(action: PluginAction, handle: string): Promise<PluginOutcome> {
    if (!this.isConfigured()) {
      return {
        ok: false,
        verified: false,
        code: 'PLUGIN_NOT_CONFIGURED',
        detail: 'no trusted IDEA plugin socket is configured',
      };
    }
    // Defense in depth: only an opaque symbolic handle may ever cross the socket, so a path,
    // a run-configuration name, a selector or free text cannot be forwarded by mistake.
    if (typeof handle !== 'string' || !/^[A-Z0-9_]{1,64}$/.test(handle)) {
      return {
        ok: false,
        verified: false,
        code: 'INVALID_TARGET_HANDLE',
        detail: 'the plugin bridge only accepts an opaque symbolic handle',
      };
    }

    let request;
    try {
      request = buildPluginRequest({
        action,
        targetKey: handle,
        secret: this.config.secret,
        nowMs: this.config.nowMs ? this.config.nowMs() : Date.now(),
      });
    } catch (error) {
      return {
        ok: false,
        verified: false,
        code: 'PLUGIN_REQUEST_INVALID',
        detail: error instanceof Error ? error.message : 'plugin request could not be built',
      };
    }

    let raw: string;
    try {
      raw = await this.transport(
        formatPluginFrame(request),
        this.config.socketPath,
        this.config.timeoutMs ?? DEFAULT_TIMEOUT_MS
      );
    } catch (error) {
      if (error instanceof PluginResponseInvalidError) {
        return {
          ok: false,
          verified: false,
          code: 'PLUGIN_RESPONSE_INVALID',
          detail: error.message,
        };
      }
      const detail = error instanceof Error ? error.message : 'plugin socket failure';
      return {
        ok: false,
        verified: false,
        code: /timed out/.test(detail) ? 'PLUGIN_TIMEOUT' : 'PLUGIN_UNAVAILABLE',
        detail,
      };
    }

    const parsed = parsePluginResponse(raw, action);
    if (!parsed.ok) {
      return { ok: false, verified: false, code: parsed.code, detail: parsed.message };
    }
    return mapPluginResponse(parsed.response, request.requestId);
  }
}
