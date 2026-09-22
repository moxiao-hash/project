import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import { LocalAutomationService } from './service.js';
import { MAX_FRAME_SIZE, formatReceiptFrame } from './protocol.js';
import type {
  ServiceConfig,
  BrowserAutomationAdapter,
  IdeaAutomationAdapter,
} from './types.js';

export class LocalAutomationServer {
  private config: ServiceConfig;
  private service: LocalAutomationService;
  private server: net.Server | null = null;

  constructor(
    config: ServiceConfig,
    browserAdapter?: BrowserAutomationAdapter,
    ideaAdapter?: IdeaAutomationAdapter
  ) {
    this.config = config;
    this.service = new LocalAutomationService(config, browserAdapter, ideaAdapter);
  }

  /**
   * Validates that socket file mode has owner-only permissions (group and other have no access).
   */
  public validateSocketMode(mode: number): boolean {
    if (process.platform === 'win32') {
      return true;
    }
    // Mask off file type bits, keep permission bits
    const permissions = mode & 0o777;
    // Group and others must have 0 access (no read, write, or execute)
    return (permissions & 0o077) === 0;
  }

  /**
   * Starts listening exclusively on Unix Domain Socket.
   * Never opens TCP or HTTP.
   */
  public async start(): Promise<void> {
    const socketPath = this.config.socketPath;
    const socketDir = path.dirname(socketPath);

    // Ensure parent directory exists with owner-only permissions (0o700)
    if (!fs.existsSync(socketDir)) {
      fs.mkdirSync(socketDir, { recursive: true, mode: 0o700 });
    } else if (process.platform !== 'win32') {
      try {
        fs.chmodSync(socketDir, 0o700);
      } catch {
        // Ignore if unable to re-chmod existing dir
      }
    }

    // Clean up stale socket file if present
    if (fs.existsSync(socketPath)) {
      try {
        fs.unlinkSync(socketPath);
      } catch {
        // Ignore unlink error
      }
    }

    this.server = net.createServer((socket) => {
      let buffer = Buffer.alloc(0);

      socket.on('data', async (chunk: Buffer) => {
        buffer = Buffer.concat([buffer, chunk]);

        // Check if buffer exceeds MAX_FRAME_SIZE without a newline
        let newlineIndex = buffer.indexOf(0x0a); // '\n'
        if (newlineIndex === -1 && buffer.byteLength > MAX_FRAME_SIZE) {
          const rejectedReceipt = formatReceiptFrame({
            version: 1,
            requestId: '00000000-0000-0000-0000-000000000000',
            adapter: 'PLAYWRIGHT_DOM',
            action: 'OPEN_STUDYPILOT_ROUTE',
            targetDigest: '0'.repeat(64),
            startedAt: new Date().toISOString(),
            finishedAt: new Date().toISOString(),
            status: 'REJECTED',
            errorCode: 'OVERSIZE_FRAME',
            message: 'Frame size exceeds maximum 16 KiB limit',
          });
          socket.write(rejectedReceipt);
          socket.destroy();
          return;
        }

        // Process all complete lines in buffer
        while ((newlineIndex = buffer.indexOf(0x0a)) !== -1) {
          const lineBuf = buffer.subarray(0, newlineIndex);
          buffer = buffer.subarray(newlineIndex + 1);

          if (lineBuf.byteLength > MAX_FRAME_SIZE) {
            const rejectedReceipt = formatReceiptFrame({
              version: 1,
              requestId: '00000000-0000-0000-0000-000000000000',
              adapter: 'PLAYWRIGHT_DOM',
              action: 'OPEN_STUDYPILOT_ROUTE',
              targetDigest: '0'.repeat(64),
              startedAt: new Date().toISOString(),
              finishedAt: new Date().toISOString(),
              status: 'REJECTED',
              errorCode: 'OVERSIZE_FRAME',
              message: 'Frame size exceeds maximum 16 KiB limit',
            });
            socket.write(rejectedReceipt);
            continue;
          }

          try {
            const response = await this.service.handleRequestLine(lineBuf);
            socket.write(response);
          } catch {
            const internalErrorReceipt = formatReceiptFrame({
              version: 1,
              requestId: '00000000-0000-0000-0000-000000000000',
              adapter: 'PLAYWRIGHT_DOM',
              action: 'OPEN_STUDYPILOT_ROUTE',
              targetDigest: '0'.repeat(64),
              startedAt: new Date().toISOString(),
              finishedAt: new Date().toISOString(),
              status: 'REJECTED',
              errorCode: 'INTERNAL_ERROR',
              message: 'Internal server processing error',
            });
            socket.write(internalErrorReceipt);
          }
        }
      });

      socket.on('error', () => {
        // Suppress client disconnect errors
      });
    });

    await new Promise<void>((resolve, reject) => {
      if (!this.server) return reject(new Error('Server not initialized'));

      this.server.listen(socketPath, () => {
        // Enforce owner-only (0o600) on the created socket file
        if (process.platform !== 'win32') {
          try {
            fs.chmodSync(socketPath, 0o600);
            const stat = fs.statSync(socketPath);
            if (!this.validateSocketMode(stat.mode)) {
              this.server?.close();
              return reject(new Error('Insecure broad socket permissions detected'));
            }
          } catch (err) {
            this.server?.close();
            return reject(err);
          }
        }
        resolve();
      });

      this.server.on('error', (err) => {
        reject(err);
      });
    });
  }

  /**
   * Stops the server, cleans up the socket file, and closes resources.
   */
  public async stop(): Promise<void> {
    if (this.server) {
      await new Promise<void>((resolve) => {
        this.server?.close(() => resolve());
      });
      this.server = null;
    }

    if (fs.existsSync(this.config.socketPath)) {
      try {
        fs.unlinkSync(this.config.socketPath);
      } catch {
        // Ignore unlink error
      }
    }

    this.service.close();
  }
}
