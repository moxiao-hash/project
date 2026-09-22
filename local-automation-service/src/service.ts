import { ActionRegistry } from './actionRegistry.js';
import { NonceStore } from './nonceStore.js';
import {
  parseAndValidateRequestFrame,
  formatReceiptFrame,
} from './protocol.js';
import { verifyRequestAuthAndTiming } from './verifier.js';
import { calculateTargetDigest } from './canonical.js';
import { PlaywrightBrowserAutomationAdapter } from './browserAdapter.js';
import { createDefaultIdeaAdapter } from './ideaAdapter.js';
import type {
  ServiceConfig,
  BrowserAutomationAdapter,
  IdeaAutomationAdapter,
  AutomationReceipt,
  Channel,
  Action,
  ErrorCode,
} from './types.js';

export class LocalAutomationService {
  private config: ServiceConfig;
  private actionRegistry: ActionRegistry;
  private nonceStore: NonceStore;
  private browserAdapter: BrowserAutomationAdapter;
  private ideaAdapter: IdeaAutomationAdapter;

  constructor(
    config: ServiceConfig,
    browserAdapter?: BrowserAutomationAdapter,
    ideaAdapter?: IdeaAutomationAdapter
  ) {
    this.config = config;
    this.actionRegistry = new ActionRegistry(config);
    this.nonceStore = new NonceStore(config.nonceDbPath);

    // Default production wiring:
    // - browser adapter wired with trustedLoopbackOrigin from config.loopbackBaseUrl
    // - idea adapter wired with the in-process macOS Accessibility bridge; when that
    //   binding is unavailable the IDEA channel fails closed with an honest BLOCKED reason
    //   (there is no HTTP, TCP, shell, or generic automation fallback).
    this.browserAdapter =
      browserAdapter ||
      new PlaywrightBrowserAutomationAdapter({
        trustedLoopbackOrigin: config.loopbackBaseUrl,
      });

    // Production IDEA execution goes through the trusted JetBrains plugin bridge. The macOS
    // AX binding is retained only as a fail-closed diagnostic probe and can never produce a
    // successful IDEA receipt.
    this.ideaAdapter = ideaAdapter || createDefaultIdeaAdapter(config);
  }

  /**
   * Processes a single request line and returns a single receipt line ending with \n.
   */
  public async handleRequestLine(rawInput: Buffer | string): Promise<string> {
    const startedAt = new Date().toISOString();

    // 1. Framing and JSON schema validation
    const parseResult = parseAndValidateRequestFrame(rawInput);
    if (!parseResult.success) {
      const finishedAt = new Date().toISOString();
      return this.buildRejectedReceipt(
        '00000000-0000-0000-0000-000000000000',
        'PLAYWRIGHT_DOM',
        'OPEN_STUDYPILOT_ROUTE',
        '0'.repeat(64),
        startedAt,
        finishedAt,
        parseResult.errorCode,
        parseResult.message
      );
    }

    const request = parseResult.request;
    const targetDigest = calculateTargetDigest(
      request.channel,
      request.action,
      request.targetKey
    );

    // 2. Authentication, HMAC signature, and timing constraints
    const authResult = verifyRequestAuthAndTiming(request, this.config.signingSecret);
    if (!authResult.valid) {
      const finishedAt = new Date().toISOString();
      return this.buildRejectedReceipt(
        request.requestId,
        request.channel,
        request.action,
        targetDigest,
        startedAt,
        finishedAt,
        authResult.errorCode,
        authResult.message
      );
    }

    // 3. Channel, action, and targetKey resolution in ActionRegistry
    const resolution = this.actionRegistry.resolveAction(
      request.channel,
      request.action,
      request.targetKey
    );
    if (!resolution.valid) {
      const finishedAt = new Date().toISOString();
      return this.buildRejectedReceipt(
        request.requestId,
        request.channel,
        request.action,
        targetDigest,
        startedAt,
        finishedAt,
        resolution.errorCode,
        resolution.message
      );
    }

    // 4. Atomic nonce consumption (guarantees validation failures do not consume nonce)
    const consumed = this.nonceStore.consume(request.nonce, request.expiresAt);
    if (!consumed) {
      const finishedAt = new Date().toISOString();
      return this.buildRejectedReceipt(
        request.requestId,
        request.channel,
        request.action,
        targetDigest,
        startedAt,
        finishedAt,
        'REPLAY_DETECTED',
        'Request nonce has already been consumed or replayed'
      );
    }

    // 5. Execute narrow adapter and verify target state
    // Note: Passes trusted internal configured mapping value (handle) to the bridge, never raw targetKey
    let executionSuccess = false;
    try {
      if (resolution.channel === 'PLAYWRIGHT_DOM') {
        if (resolution.browser.action === 'OPEN_STUDYPILOT_ROUTE') {
          executionSuccess = await this.browserAdapter.openRoute(resolution.browser.targetUrl!);
        } else if (resolution.browser.action === 'FOCUS_AGENT_INPUT') {
          executionSuccess = await this.browserAdapter.focusAgentInput();
        } else if (resolution.browser.action === 'OPEN_RESULT_PANEL') {
          executionSuccess = await this.browserAdapter.openResultPanel();
        }
      } else if (resolution.channel === 'IDEA_ACCESSIBILITY') {
        if (resolution.idea.action === 'OPEN_REGISTERED_FILE') {
          // Only the opaque registered handle is forwarded; the resolved path stays local.
          executionSuccess = await this.ideaAdapter.openRegisteredFile(resolution.idea.targetKey);
        } else if (resolution.idea.action === 'FOCUS_RUN_CONFIGURATION') {
          executionSuccess = await this.ideaAdapter.focusRunConfiguration(
            resolution.idea.handle!
          );
        } else if (resolution.idea.action === 'SHOW_TEST_RESULT') {
          executionSuccess = await this.ideaAdapter.showTestResult(resolution.idea.handle!);
        }
      }
    } catch {
      executionSuccess = false;
    }

    const finishedAt = new Date().toISOString();

    if (executionSuccess) {
      const receipt: AutomationReceipt = {
        version: 1,
        requestId: request.requestId,
        adapter: request.channel,
        action: request.action,
        targetDigest,
        startedAt,
        finishedAt,
        status: 'SUCCEEDED',
        errorCode: null,
        message: 'Action completed and verified successfully',
      };
      return formatReceiptFrame(receipt);
    } else {
      const failureCode: ErrorCode =
        resolution.channel === 'IDEA_ACCESSIBILITY'
          ? this.resolveIdeaFailureCode()
          : 'UNVERIFIED_TARGET_STATE';
      const receipt: AutomationReceipt = {
        version: 1,
        requestId: request.requestId,
        adapter: request.channel,
        action: request.action,
        targetDigest,
        startedAt,
        finishedAt,
        status: 'FAILED',
        errorCode: failureCode,
        message:
          failureCode === 'ADAPTER_FAILURE'
            ? 'Registered interface adapter is unavailable or the action could not be dispatched'
            : 'Action execution or target state verification failed',
      };
      return formatReceiptFrame(receipt);
    }
  }

  /**
   * Distinguishes "the adapter could not run" from "the action ran but the required
   * interface state was not verified". Uses only the frozen receipt error codes and never
   * reports success.
   */
  private resolveIdeaFailureCode(): ErrorCode {
    const adapter = this.ideaAdapter as IdeaAutomationAdapter & {
      getLastFailureCode?: () => string | null;
    };
    if (typeof adapter.getLastFailureCode === 'function') {
      const code = adapter.getLastFailureCode();
      if (code === 'ADAPTER_FAILURE') {
        return 'ADAPTER_FAILURE';
      }
    }
    return 'UNVERIFIED_TARGET_STATE';
  }

  private buildRejectedReceipt(
    requestId: string,
    adapter: Channel,
    action: Action,
    targetDigest: string,
    startedAt: string,
    finishedAt: string,
    errorCode: ErrorCode,
    message: string
  ): string {
    const receipt: AutomationReceipt = {
      version: 1,
      requestId,
      adapter,
      action,
      targetDigest,
      startedAt,
      finishedAt,
      status: 'REJECTED',
      errorCode,
      message,
    };
    return formatReceiptFrame(receipt);
  }

  public close(): void {
    this.nonceStore.close();
  }
}
