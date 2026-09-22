import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

/**
 * StudyPilot Task 33 — loader and structural guard for the native macOS Accessibility
 * bridge.
 *
 * The automation service talks to IntelliJ IDEA exclusively through this in-process
 * native binding. There is deliberately no network, shell, script-automation, or generic
 * desktop-automation fallback anywhere on this path: if the binding is absent or its
 * export surface is not exactly the four registered functions below, the IDEA channel
 * fails closed.
 */

export interface NativeAxProbe {
  platform: string;
  bridgeVersion: string;
  axApiAvailable: boolean;
  axTrusted: boolean;
  ideaRunning: boolean;
}

export interface NativeAxResult {
  /** The registered accessibility action was dispatched. */
  ok: boolean;
  /** The required accessibility state was actually observed afterwards. */
  verified: boolean;
  /** Stable non-sensitive code describing the outcome. */
  code: string;
  /** Short non-sensitive detail. Never surfaced in protocol receipts. */
  detail: string;
}

export interface NativeAxModule {
  probe(): NativeAxProbe;
  openRegisteredFile(realFilePath: string): NativeAxResult;
  focusRunConfiguration(configHandle: string): NativeAxResult;
  showTestResult(resultHandle: string): NativeAxResult;
}

/**
 * The complete permitted export surface of the native addon. Anything else — especially
 * generic click/type/key/shell capabilities — makes the module inadmissible.
 */
export const NATIVE_AX_ALLOWED_EXPORTS: readonly string[] = [
  'probe',
  'openRegisteredFile',
  'focusRunConfiguration',
  'showTestResult',
];

const ADDON_RELATIVE_PATH = path.join('native', 'build', 'idea_ax_bridge.node');

/**
 * Fixed, in-package addon location. Callers cannot redirect it: the path is derived from
 * this module's own location and never from an environment variable or a request field.
 */
export function resolveNativeAddonPath(): string {
  const moduleDir = path.dirname(fileURLToPath(import.meta.url));
  return path.resolve(moduleDir, '..', ADDON_RELATIVE_PATH);
}

/**
 * Validates the export surface of a candidate native module.
 *
 * Required: every permitted export present and callable.
 * Forbidden: any additional own export.
 */
export function validateNativeAxModuleSurface(candidate: unknown): candidate is NativeAxModule {
  if (typeof candidate !== 'object' || candidate === null) {
    return false;
  }
  const record = candidate as Record<string, unknown>;

  for (const name of NATIVE_AX_ALLOWED_EXPORTS) {
    if (typeof record[name] !== 'function') {
      return false;
    }
  }
  for (const key of Object.keys(record)) {
    if (!NATIVE_AX_ALLOWED_EXPORTS.includes(key)) {
      return false;
    }
  }
  return true;
}

let cachedModule: NativeAxModule | null | undefined;

/**
 * Loads the native accessibility binding in-process.
 *
 * Returns null when the platform is not macOS, the addon has not been built, the addon
 * cannot be loaded, or its export surface does not match the permitted set. The caller is
 * then required to fail closed.
 */
export function loadNativeAxAddon(): NativeAxModule | null {
  if (cachedModule !== undefined) {
    return cachedModule;
  }
  cachedModule = loadUncached();
  return cachedModule;
}

function loadUncached(): NativeAxModule | null {
  if (process.platform !== 'darwin') {
    return null;
  }

  const addonPath = resolveNativeAddonPath();
  if (!fs.existsSync(addonPath)) {
    return null;
  }

  try {
    const require = createRequire(import.meta.url);
    const loaded: unknown = require(addonPath);
    if (!validateNativeAxModuleSurface(loaded)) {
      return null;
    }
    return loaded;
  } catch {
    return null;
  }
}
