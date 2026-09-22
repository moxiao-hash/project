import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseServiceConfigFromEnv } from './config.js';
import { LocalAutomationServer } from './server.js';

/**
 * StudyPilot Task 33 — production entrypoint for the local automation service.
 *
 * Transport is Unix Domain Socket only. This process never opens a TCP or HTTP listener;
 * the socket is created with owner-only (0600) permissions by LocalAutomationServer, and
 * the frozen protocol version, HMAC, timestamp, and nonce rules are unchanged.
 *
 * Configuration comes exclusively from the local host environment (see config.ts).
 * Secrets are never logged.
 */

export interface EntrypointHandle {
  server: LocalAutomationServer;
  stop(): Promise<void>;
}

/**
 * Starts the service on the configured Unix Domain Socket.
 *
 * Throws (without starting anything) when the environment is incomplete or violates the
 * frozen trust boundary, so an operator failure can never silently weaken the boundary.
 */
export async function startServiceFromEnv(
  env: NodeJS.ProcessEnv = process.env
): Promise<EntrypointHandle> {
  const config = parseServiceConfigFromEnv(env);
  const server = new LocalAutomationServer(config);

  let stopping: Promise<void> | null = null;
  const stop = (): Promise<void> => {
    if (!stopping) {
      stopping = server.stop();
    }
    return stopping;
  };

  await server.start();

  return { server, stop };
}

function isDirectInvocation(): boolean {
  const entry = process.argv[1];
  if (!entry) {
    return false;
  }
  try {
    return path.resolve(fileURLToPath(import.meta.url)) === path.resolve(entry);
  } catch {
    return false;
  }
}

async function main(): Promise<void> {
  const handle = await startServiceFromEnv(process.env);

  // Diagnostics only: no secret, key, token, or owner data is ever printed.
  process.stdout.write(
    'local-automation-service listening on unix domain socket (owner-only, protocol version 1)\n'
  );

  const shutdown = (signal: NodeJS.Signals): void => {
    process.stdout.write(`local-automation-service received ${signal}, shutting down\n`);
    handle
      .stop()
      .then(() => process.exit(0))
      .catch(() => process.exit(1));
  };

  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

if (isDirectInvocation()) {
  main().catch((error: unknown) => {
    const message = error instanceof Error ? error.message : 'unknown startup failure';
    process.stderr.write(`local-automation-service failed to start: ${message}\n`);
    process.exit(1);
  });
}
