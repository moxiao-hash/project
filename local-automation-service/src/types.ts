/**
 * Task 33 Frozen Contract Types
 * Reference: docs/verification/task-33-frozen-contract.md
 */

export type Channel = 'PLAYWRIGHT_DOM' | 'IDEA_ACCESSIBILITY';

export type BrowserAction =
  | 'OPEN_STUDYPILOT_ROUTE'
  | 'FOCUS_AGENT_INPUT'
  | 'OPEN_RESULT_PANEL';

export type IdeaAction =
  | 'OPEN_REGISTERED_FILE'
  | 'FOCUS_RUN_CONFIGURATION'
  | 'SHOW_TEST_RESULT';

export type Action = BrowserAction | IdeaAction;

export type ExecutionStatus = 'SUCCEEDED' | 'FAILED' | 'REJECTED';

export type ErrorCode =
  | 'INVALID_FRAME'
  | 'OVERSIZE_FRAME'
  | 'INVALID_UTF8'
  | 'INVALID_JSON'
  | 'UNKNOWN_FIELDS'
  | 'DUPLICATE_KEYS'
  | 'MISSING_FIELD'
  | 'UNSUPPORTED_VERSION'
  | 'INVALID_UUID'
  | 'INVALID_OWNER_HASH'
  | 'INVALID_CHANNEL'
  | 'INVALID_ACTION'
  | 'INVALID_TARGET_KEY'
  | 'INVALID_TIMESTAMP'
  | 'CLOCK_DRIFT'
  | 'EXPIRED_REQUEST'
  | 'LIFETIME_EXCEEDED'
  | 'INVALID_NONCE'
  | 'INVALID_SIGNATURE'
  | 'REPLAY_DETECTED'
  | 'KEY_TOO_SHORT'
  | 'TARGET_NOT_REGISTERED'
  | 'CHANNEL_ACTION_MISMATCH'
  | 'UNVERIFIED_TARGET_STATE'
  | 'ADAPTER_FAILURE'
  | 'INSECURE_SOCKET_PERMISSIONS'
  | 'INTERNAL_ERROR';

/**
 * Request framing: single-line JSON, max 16 KiB.
 */
export interface AutomationRequest {
  version: 1;
  requestId: string;
  ownerHash: string;
  channel: Channel;
  action: Action;
  targetKey: string;
  issuedAt: string;
  expiresAt: string;
  nonce: string;
  signature: string;
}

/**
 * Receipt framing: single-line JSON, max 16 KiB.
 */
export interface AutomationReceipt {
  version: 1;
  requestId: string;
  adapter: Channel;
  action: Action;
  targetDigest: string;
  startedAt: string;
  finishedAt: string;
  status: ExecutionStatus;
  errorCode: string | null;
  message: string;
}

/**
 * Configuration required for local automation service.
 */
export interface ServiceConfig {
  signingSecret: string;
  socketPath: string;
  nonceDbPath: string;
  loopbackBaseUrl: string;
  workspaceRoots: string[];
  registeredFiles: Record<string, string>;
  registeredRunConfigs: Record<string, string>;
  registeredTestResults: Record<string, string>;
}

/**
 * Browser adapter interface.
 * Must not expose arbitrary navigate/click/type/eval.
 */
export interface BrowserAutomationAdapter {
  openRoute(route: string): Promise<boolean>;
  focusAgentInput(): Promise<boolean>;
  openResultPanel(): Promise<boolean>;
}

/**
 * IDE adapter interface.
 * Must not expose arbitrary file opening, shell, or test running.
 */
export interface IdeaAutomationAdapter {
  openRegisteredFile(realFilePath: string, workspaceRoot?: string): Promise<boolean>;
  focusRunConfiguration(handle: string): Promise<boolean>;
  showTestResult(handle: string): Promise<boolean>;
}
