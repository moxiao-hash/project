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

/** Default transport: a single request/response exchange over the plugin's Unix socket. */
export const unixSocketPluginTransport: PluginTransport = (frame, socketPath, timeoutMs) =>
  new Promise<string>((resolve, reject) => {
    let settled = false;
    let received = '';
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

    socket.on('connect', () => socket.write(frame));
    socket.on('data', (chunk: Buffer) => {
      received += chunk.toString('utf8');
      if (Buffer.byteLength(received, 'utf8') > PLUGIN_MAX_FRAME_BYTES) {
        finish(
          new PluginResponseInvalidError('plugin response exceeded the 16 KiB frame limit')
        );
        return;
      }
      const newline = received.indexOf('\n');
      if (newline >= 0) {
        // The reply must be EXACTLY one newline-terminated frame. Any trailing byte — a
        // second frame or stray data — is a protocol violation, never a valid answer.
        const trailing = received.slice(newline + 1);
        if (trailing.length > 0) {
          finish(
            new PluginResponseInvalidError(
              'plugin response must be exactly one single-line frame'
            )
          );
          return;
        }
        // Guard against a second frame that is only flushed on the next read.
        socket.pause();
        finish(null, received.slice(0, newline));
      }
    });
    socket.on('error', (error: Error) => finish(error));
    socket.on('close', () => {
      if (!settled) {
        if (received.trim().length === 0) {
          finish(new Error('plugin closed the connection without a response'));
          return;
        }
        finish(null, received);
      }
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
