import path from 'node:path';
import { PLUGIN_MIN_SECRET_BYTES } from './ideaPluginProtocol.js';
import type { ServiceConfig } from './types.js';

/**
 * StudyPilot Task 33 — production configuration for the local automation service.
 *
 * Everything the service trusts is injected from the local host environment and resolved
 * once at startup. No request field can influence the socket location, the signing key,
 * the trusted browser base URL, the registered workspace roots, or any registry entry.
 */

export const MIN_SIGNING_SECRET_BYTES = 32;

export const ENV_KEYS = {
  socketPath: 'STUDYPILOT_AUTOMATION_SOCKET_PATH',
  signingSecret: 'STUDYPILOT_AUTOMATION_HMAC_SECRET',
  nonceDbPath: 'STUDYPILOT_AUTOMATION_NONCE_DB',
  loopbackBaseUrl: 'STUDYPILOT_AUTOMATION_LOOPBACK_BASE_URL',
  workspaceRoots: 'STUDYPILOT_AUTOMATION_WORKSPACE_ROOTS',
  registeredFiles: 'STUDYPILOT_AUTOMATION_REGISTERED_FILES',
  registeredRunConfigs: 'STUDYPILOT_AUTOMATION_REGISTERED_RUN_CONFIGS',
  registeredTestResults: 'STUDYPILOT_AUTOMATION_REGISTERED_TEST_RESULTS',
  ideaPluginSocketPath: 'STUDYPILOT_AUTOMATION_IDEA_PLUGIN_SOCKET_PATH',
  ideaPluginSigningSecret: 'STUDYPILOT_AUTOMATION_IDEA_PLUGIN_HMAC_SECRET',
  ideaPluginTimeoutMs: 'STUDYPILOT_AUTOMATION_IDEA_PLUGIN_TIMEOUT_MS',
} as const;

/** Registry handles must be opaque symbolic keys, never paths, URLs, or free text. */
const SYMBOLIC_HANDLE_REGEX = /^[A-Z0-9_]{1,64}$/;

function requireEnv(env: NodeJS.ProcessEnv, key: string): string {
  const value = env[key];
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new Error(`Missing required environment variable: ${key}`);
  }
  return value;
}

function requireAbsolutePath(value: string, key: string): string {
  if (!path.isAbsolute(value)) {
    throw new Error(`${key} must be an absolute path`);
  }
  return value;
}

function parseJsonValue(raw: string, key: string): unknown {
  try {
    return JSON.parse(raw);
  } catch {
    throw new Error(`${key} must be valid JSON`);
  }
}

function parseWorkspaceRoots(env: NodeJS.ProcessEnv): string[] {
  const raw = env[ENV_KEYS.workspaceRoots];
  if (typeof raw !== 'string' || raw.trim().length === 0) {
    return [];
  }
  const parsed = parseJsonValue(raw, ENV_KEYS.workspaceRoots);
  if (!Array.isArray(parsed)) {
    throw new Error(`${ENV_KEYS.workspaceRoots} must be a JSON array of absolute paths`);
  }
  return parsed.map((entry, index) => {
    if (typeof entry !== 'string' || entry.length === 0) {
      throw new Error(`${ENV_KEYS.workspaceRoots}[${index}] must be a non-empty string`);
    }
    return requireAbsolutePath(entry, `${ENV_KEYS.workspaceRoots}[${index}]`);
  });
}

function parseRegistry(
  env: NodeJS.ProcessEnv,
  key: string,
  validateValue: (value: string, handle: string) => string
): Record<string, string> {
  const raw = env[key];
  if (typeof raw !== 'string' || raw.trim().length === 0) {
    return {};
  }
  const parsed = parseJsonValue(raw, key);
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error(`${key} must be a JSON object of symbolic handle to trusted value`);
  }
  const registry: Record<string, string> = {};
  for (const [handle, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (!SYMBOLIC_HANDLE_REGEX.test(handle)) {
      throw new Error(`${key} handle must match ${SYMBOLIC_HANDLE_REGEX.source}: rejected handle`);
    }
    if (typeof value !== 'string' || value.trim().length === 0) {
      throw new Error(`${key} value for handle ${handle} must be a non-empty string`);
    }
    registry[handle] = validateValue(value, handle);
  }
  return registry;
}

function assertLoopbackBaseUrl(raw: string): string {
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    throw new Error(`${ENV_KEYS.loopbackBaseUrl} must be a valid URL`);
  }
  const host = parsed.hostname.toLowerCase();
  const isLoopback =
    host === 'localhost' || host === '127.0.0.1' || host === '::1' || host === '[::1]';
  if (!isLoopback) {
    throw new Error(`${ENV_KEYS.loopbackBaseUrl} must be a loopback address`);
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error(`${ENV_KEYS.loopbackBaseUrl} must use http or https`);
  }
  return parsed.origin;
}

const MIN_PLUGIN_TIMEOUT_MS = 250;
const MAX_PLUGIN_TIMEOUT_MS = 30_000;

/**
 * Resolves the trusted JetBrains plugin bridge settings.
 *
 * The plugin link must be genuinely separate from the Java-facing socket: its own socket
 * path, its own >=32-byte key and its own risk surface. Any attempt to reuse the
 * Java-facing socket path or signing key is rejected outright.
 */
function resolveIdeaPluginConfig(
  env: NodeJS.ProcessEnv,
  javaFacingSocketPath: string,
  javaFacingSecret: string
): Pick<ServiceConfig, 'ideaPluginSocketPath' | 'ideaPluginSigningSecret' | 'ideaPluginTimeoutMs'> {
  const rawSocket = env[ENV_KEYS.ideaPluginSocketPath];
  const rawSecret = env[ENV_KEYS.ideaPluginSigningSecret];

  if ((rawSocket === undefined || rawSocket.trim() === '') && (rawSecret === undefined || rawSecret.trim() === '')) {
    return {};
  }
  if (rawSocket === undefined || rawSocket.trim() === '') {
    throw new Error(`${ENV_KEYS.ideaPluginSocketPath} is required when a plugin key is configured`);
  }
  if (rawSecret === undefined || rawSecret.trim() === '') {
    throw new Error(`${ENV_KEYS.ideaPluginSigningSecret} is required when a plugin socket is configured`);
  }

  const socketPath = requireAbsolutePath(rawSocket, ENV_KEYS.ideaPluginSocketPath);
  if (socketPath === javaFacingSocketPath) {
    throw new Error(`${ENV_KEYS.ideaPluginSocketPath} must differ from the Java-facing socket path`);
  }
  if (Buffer.byteLength(rawSecret, 'utf8') < PLUGIN_MIN_SECRET_BYTES) {
    throw new Error(
      `${ENV_KEYS.ideaPluginSigningSecret} must contain at least ${PLUGIN_MIN_SECRET_BYTES} bytes`
    );
  }
  if (rawSecret === javaFacingSecret) {
    throw new Error(`${ENV_KEYS.ideaPluginSigningSecret} must differ from the Java-facing signing key`);
  }

  let timeoutMs = 3000;
  const rawTimeout = env[ENV_KEYS.ideaPluginTimeoutMs];
  if (rawTimeout !== undefined && rawTimeout.trim() !== '') {
    const parsed = Number(rawTimeout);
    if (
      !Number.isInteger(parsed) ||
      parsed < MIN_PLUGIN_TIMEOUT_MS ||
      parsed > MAX_PLUGIN_TIMEOUT_MS
    ) {
      throw new Error(
        `${ENV_KEYS.ideaPluginTimeoutMs} must be an integer between ${MIN_PLUGIN_TIMEOUT_MS} and ${MAX_PLUGIN_TIMEOUT_MS}`
      );
    }
    timeoutMs = parsed;
  }

  return { ideaPluginSocketPath: socketPath, ideaPluginSigningSecret: rawSecret, ideaPluginTimeoutMs: timeoutMs };
}

/**
 * Builds the frozen ServiceConfig from the local host environment.
 *
 * Throws on any invalid or missing value so the service can never start with a weaker
 * trust boundary than the frozen contract requires.
 */
export function parseServiceConfigFromEnv(env: NodeJS.ProcessEnv = process.env): ServiceConfig {
  const signingSecret = requireEnv(env, ENV_KEYS.signingSecret);
  const secretBytes = Buffer.from(signingSecret, 'utf8').byteLength;
  if (secretBytes < MIN_SIGNING_SECRET_BYTES) {
    throw new Error(
      `${ENV_KEYS.signingSecret} must contain at least ${MIN_SIGNING_SECRET_BYTES} bytes`
    );
  }

  const socketPath = requireAbsolutePath(
    requireEnv(env, ENV_KEYS.socketPath),
    ENV_KEYS.socketPath
  );
  const nonceDbPath = requireAbsolutePath(
    requireEnv(env, ENV_KEYS.nonceDbPath),
    ENV_KEYS.nonceDbPath
  );
  const loopbackBaseUrl = assertLoopbackBaseUrl(requireEnv(env, ENV_KEYS.loopbackBaseUrl));

  return {
    signingSecret,
    socketPath,
    nonceDbPath,
    loopbackBaseUrl,
    ...resolveIdeaPluginConfig(env, socketPath, signingSecret),
    workspaceRoots: parseWorkspaceRoots(env),
    registeredFiles: parseRegistry(env, ENV_KEYS.registeredFiles, (value, handle) =>
      requireAbsolutePath(value, `${ENV_KEYS.registeredFiles}[${handle}]`)
    ),
    registeredRunConfigs: parseRegistry(env, ENV_KEYS.registeredRunConfigs, (value) => value),
    registeredTestResults: parseRegistry(env, ENV_KEYS.registeredTestResults, (value) => value),
  };
}
