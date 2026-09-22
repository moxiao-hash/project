import fs from 'node:fs';
import path from 'node:path';
import type {
  Channel,
  Action,
  BrowserAction,
  IdeaAction,
  ErrorCode,
  ServiceConfig,
} from './types.js';

export type BrowserResolutionResult =
  | {
      valid: true;
      action: BrowserAction;
      targetKey: string;
      targetUrl?: string;
      fixedLocator?: string;
      fixedPanelId?: string;
    }
  | { valid: false; errorCode: ErrorCode; message: string };

export type IdeaResolutionResult =
  | {
      valid: true;
      action: IdeaAction;
      targetKey: string;
      resolvedPath?: string;
      handle?: string;
    }
  | { valid: false; errorCode: ErrorCode; message: string };

export type ResolutionResult =
  | ({ valid: true; channel: Channel } & (
      | { channel: 'PLAYWRIGHT_DOM'; browser: BrowserResolutionResult & { valid: true } }
      | { channel: 'IDEA_ACCESSIBILITY'; idea: IdeaResolutionResult & { valid: true } }
    ))
  | { valid: false; errorCode: ErrorCode; message: string };

export class ActionRegistry {
  private config: ServiceConfig;
  private normalizedBaseUrl: string;
  private workspaceRealRoots: string[];

  constructor(config: ServiceConfig) {
    this.config = config;

    // Validate loopback URL
    let parsedUrl: URL;
    try {
      parsedUrl = new URL(config.loopbackBaseUrl);
    } catch {
      throw new Error(`Invalid base URL: ${config.loopbackBaseUrl}`);
    }

    const host = parsedUrl.hostname.toLowerCase();
    const isLoopback =
      host === 'localhost' ||
      host === '127.0.0.1' ||
      host === '::1' ||
      host === '[::1]';

    if (!isLoopback) {
      throw new Error(`Trusted base URL must be loopback only: ${config.loopbackBaseUrl}`);
    }

    if (parsedUrl.protocol !== 'http:' && parsedUrl.protocol !== 'https:') {
      throw new Error(`Trusted base URL must use http or https protocol: ${config.loopbackBaseUrl}`);
    }

    this.normalizedBaseUrl = parsedUrl.origin;

    // Resolve real workspace roots
    this.workspaceRealRoots = (config.workspaceRoots || []).map((rootPath) => {
      try {
        return fs.realpathSync(rootPath);
      } catch {
        return path.resolve(rootPath);
      }
    });
  }

  public resolveBrowserAction(
    action: BrowserAction | Action,
    targetKey: string
  ): BrowserResolutionResult {
    switch (action) {
      case 'OPEN_STUDYPILOT_ROUTE': {
        let route: string;
        if (targetKey === 'ASSISTANT') {
          route = '/';
        } else if (targetKey === 'ASSISTANT_HEALTH') {
          route = '/assistant/health';
        } else if (targetKey === 'WORKSPACE_ARTIFACTS') {
          route = '/workspaces';
        } else {
          return {
            valid: false,
            errorCode: 'TARGET_NOT_REGISTERED',
            message: `Unknown route targetKey: ${targetKey}`,
          };
        }
        return {
          valid: true,
          action,
          targetKey,
          targetUrl: `${this.normalizedBaseUrl}${route}`,
        };
      }

      case 'FOCUS_AGENT_INPUT': {
        if (targetKey !== 'ASSISTANT_INPUT') {
          return {
            valid: false,
            errorCode: 'TARGET_NOT_REGISTERED',
            message: `FOCUS_AGENT_INPUT only accepts ASSISTANT_INPUT, got: ${targetKey}`,
          };
        }
        return {
          valid: true,
          action,
          targetKey,
          fixedLocator: '[data-testid="agent-message-input"]',
        };
      }

      case 'OPEN_RESULT_PANEL': {
        if (targetKey !== 'WORKSPACE_RESULTS') {
          return {
            valid: false,
            errorCode: 'TARGET_NOT_REGISTERED',
            message: `OPEN_RESULT_PANEL only accepts WORKSPACE_RESULTS, got: ${targetKey}`,
          };
        }
        return {
          valid: true,
          action,
          targetKey,
          fixedPanelId: 'workspace-results-panel',
        };
      }

      default:
        return {
          valid: false,
          errorCode: 'INVALID_ACTION',
          message: `Action ${action} is not a valid browser action`,
        };
    }
  }

  public resolveIdeaAction(
    action: IdeaAction | Action,
    targetKey: string
  ): IdeaResolutionResult {
    switch (action) {
      case 'OPEN_REGISTERED_FILE': {
        const configuredPath = this.config.registeredFiles[targetKey];
        if (!configuredPath) {
          return {
            valid: false,
            errorCode: 'TARGET_NOT_REGISTERED',
            message: `Unregistered file target handle: ${targetKey}`,
          };
        }

        // Must reject symlinks explicitly
        try {
          const lstat = fs.lstatSync(configuredPath);
          if (lstat.isSymbolicLink()) {
            return {
              valid: false,
              errorCode: 'TARGET_NOT_REGISTERED',
              message: 'Symlinks are strictly forbidden for registered files',
            };
          }
        } catch {
          return {
            valid: false,
            errorCode: 'TARGET_NOT_REGISTERED',
            message: 'Registered file path not found',
          };
        }

        // Must resolve to real path and be a regular file
        let realPath: string;
        try {
          realPath = fs.realpathSync(configuredPath);
          const stat = fs.statSync(realPath);
          if (!stat.isFile()) {
            return {
              valid: false,
              errorCode: 'TARGET_NOT_REGISTERED',
              message: 'Target must be a regular file',
            };
          }
        } catch {
          return {
            valid: false,
            errorCode: 'TARGET_NOT_REGISTERED',
            message: 'Failed to resolve real path for registered file',
          };
        }

        // Must be located within registered workspace roots
        const isInsideWorkspace = this.workspaceRealRoots.some(
          (root) => realPath === root || realPath.startsWith(root + path.sep)
        );

        if (!isInsideWorkspace) {
          return {
            valid: false,
            errorCode: 'TARGET_NOT_REGISTERED',
            message: 'Target file is located outside of registered workspace roots',
          };
        }

        return {
          valid: true,
          action,
          targetKey,
          resolvedPath: realPath,
        };
      }

      case 'FOCUS_RUN_CONFIGURATION': {
        const configuredConfig = this.config.registeredRunConfigs[targetKey];
        if (!configuredConfig) {
          return {
            valid: false,
            errorCode: 'TARGET_NOT_REGISTERED',
            message: `Unregistered run configuration handle: ${targetKey}`,
          };
        }
        return {
          valid: true,
          action,
          targetKey,
          handle: targetKey,
        };
      }

      case 'SHOW_TEST_RESULT': {
        const configuredResult = this.config.registeredTestResults[targetKey];
        if (!configuredResult) {
          return {
            valid: false,
            errorCode: 'TARGET_NOT_REGISTERED',
            message: `Unregistered test result handle: ${targetKey}`,
          };
        }
        return {
          valid: true,
          action,
          targetKey,
          handle: targetKey,
        };
      }

      default:
        return {
          valid: false,
          errorCode: 'INVALID_ACTION',
          message: `Action ${action} is not a valid IDE action`,
        };
    }
  }

  public resolveAction(
    channel: Channel,
    action: Action,
    targetKey: string
  ): ResolutionResult {
    if (channel === 'PLAYWRIGHT_DOM') {
      if (
        action !== 'OPEN_STUDYPILOT_ROUTE' &&
        action !== 'FOCUS_AGENT_INPUT' &&
        action !== 'OPEN_RESULT_PANEL'
      ) {
        return {
          valid: false,
          errorCode: 'CHANNEL_ACTION_MISMATCH',
          message: `Action ${action} is not supported on PLAYWRIGHT_DOM channel`,
        };
      }
      const browserRes = this.resolveBrowserAction(action, targetKey);
      if (!browserRes.valid) {
        return browserRes;
      }
      return {
        valid: true,
        channel: 'PLAYWRIGHT_DOM',
        browser: browserRes,
      };
    }

    if (channel === 'IDEA_ACCESSIBILITY') {
      if (
        action !== 'OPEN_REGISTERED_FILE' &&
        action !== 'FOCUS_RUN_CONFIGURATION' &&
        action !== 'SHOW_TEST_RESULT'
      ) {
        return {
          valid: false,
          errorCode: 'CHANNEL_ACTION_MISMATCH',
          message: `Action ${action} is not supported on IDEA_ACCESSIBILITY channel`,
        };
      }
      const ideaRes = this.resolveIdeaAction(action, targetKey);
      if (!ideaRes.valid) {
        return ideaRes;
      }
      return {
        valid: true,
        channel: 'IDEA_ACCESSIBILITY',
        idea: ideaRes,
      };
    }

    return {
      valid: false,
      errorCode: 'INVALID_CHANNEL',
      message: `Unknown channel: ${channel}`,
    };
  }
}
