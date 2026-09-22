import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { describe, it, expect } from 'vitest';
import { validateNativeAxModuleSurface } from '../src/nativeAxBridge.js';

/**
 * Adversarial fixture tests for the native Accessibility decision logic.
 *
 * The production addon exposes only four operations and drives the real desktop, so it
 * cannot be unit tested directly. The build therefore also produces a test-seam artifact
 * compiled from the SAME source with AX_BRIDGE_TEST_SEAM defined, which runs the same
 * matching, uniqueness and verification functions over fixture trees. The production
 * artifact provably lacks the seam (its export surface is checked below).
 *
 * Fixture line format, one node per line, depth-first:
 *   depth|role|subrole|identifier|title|document|url|value|flags|size
 * flags: "s" selected, "f" focused, "h" hidden. size: "WxH" or empty.
 */

const testAddonPath = path.resolve(__dirname, '../native/build/idea_ax_bridge.test.node');
const productionAddonPath = path.resolve(__dirname, '../native/build/idea_ax_bridge.node');

const testAddonAvailable = fs.existsSync(testAddonPath);

interface SeamResult {
  ok: boolean;
  verified: boolean;
  code: string;
  detail: string;
}

interface SeamModule {
  __testEvaluate(
    operation: string,
    argument: string,
    preFixture: string,
    postFixture: string
  ): SeamResult;
}

const seam: SeamModule | null = testAddonAvailable
  ? (createRequire(import.meta.url)(testAddonPath) as SeamModule)
  : null;

const CANONICAL_PATH = '/Users/moxiao/work/proj/local-automation-service/package.json';
const RUN_HANDLE = 'StudyPilotApplication';
const RESULT_HANDLE = 'surefire-reports';

function node(
  depth: number,
  role: string,
  title: string,
  options: { document?: string; url?: string; value?: string; flags?: string; size?: string } = {}
): string {
  return [
    String(depth),
    role,
    '',
    '',
    title,
    options.document ?? '',
    options.url ?? '',
    options.value ?? '',
    options.flags ?? '',
    options.size ?? '200x30',
  ].join('|');
}

const WINDOW = node(0, 'AXWindow', 'Project');
const PROJECT_TREE = node(1, 'AXOutline', 'proj');
const CORRECT_DIR = node(2, 'AXCell', 'local-automation-service');
const CORRECT_FILE = node(3, 'AXCell', 'package.json');
const DECOY_DIR = node(2, 'AXCell', 'other-module');
const DECOY_FILE = node(3, 'AXCell', 'package.json');

function tree(...lines: string[]): string {
  return lines.join('\n');
}

/** A post-action editor tree that proves the canonical path through an exact document URL. */
const EDITOR_POST_CORRECT = tree(
  WINDOW,
  node(1, 'AXGroup', 'editor'),
  node(2, 'AXTextArea', 'package.json', { document: `file://${CANONICAL_PATH}` })
);

function evaluate(
  operation: string,
  argument: string,
  pre: string,
  post: string
): SeamResult {
  if (!seam) {
    throw new Error('test-seam addon is not built; run `npm run build:native`');
  }
  return seam.__testEvaluate(operation, argument, pre, post);
}

describe('Test-seam artifact is test-only and cannot be adopted in production', () => {
  it.runIf(testAddonAvailable)('production artifact exposes exactly the four registered operations', () => {
    const production = createRequire(import.meta.url)(productionAddonPath) as Record<string, unknown>;
    expect(Object.keys(production).sort()).toEqual([
      'focusRunConfiguration',
      'openRegisteredFile',
      'probe',
      'showTestResult',
    ]);
    expect(validateNativeAxModuleSurface(production)).toBe(true);
  });

  it.runIf(testAddonAvailable)('a module carrying the test seam is rejected by the export-surface guard', () => {
    const testAddon = createRequire(import.meta.url)(testAddonPath) as Record<string, unknown>;
    expect(Object.keys(testAddon)).toContain('__testEvaluate');
    // The production guard rejects ANY extra export, so the seam can never be loaded by
    // the service even if the wrong artifact were placed on disk.
    expect(validateNativeAxModuleSurface(testAddon)).toBe(false);
  });
});

describe('OPEN_REGISTERED_FILE binds to the canonical registered path', () => {
  it.runIf(testAddonAvailable)('rejects a same-basename file in a different directory', () => {
    // The previous implementation reduced the trusted path to its last component and
    // pressed the first node with that name: it returned SUCCEEDED for the wrong file.
    const pre = tree(WINDOW, PROJECT_TREE, DECOY_DIR, DECOY_FILE);
    const result = evaluate('OPEN_REGISTERED_FILE', CANONICAL_PATH, pre, EDITOR_POST_CORRECT);
    expect(result.ok).toBe(false);
    expect(result.verified).toBe(false);
    expect(result.code).toBe('TARGET_NOT_FOUND');
  });

  it.runIf(testAddonAvailable)('actuates only when the full ancestor chain proves the canonical path', () => {
    const pre = tree(WINDOW, PROJECT_TREE, CORRECT_DIR, CORRECT_FILE);
    const result = evaluate('OPEN_REGISTERED_FILE', CANONICAL_PATH, pre, EDITOR_POST_CORRECT);
    expect(result.ok).toBe(true);
    expect(result.verified).toBe(true);
  });

  it.runIf(testAddonAvailable)('fails closed when two nodes prove the same path', () => {
    const pre = tree(WINDOW, PROJECT_TREE, CORRECT_DIR, CORRECT_FILE, CORRECT_FILE);
    const result = evaluate('OPEN_REGISTERED_FILE', CANONICAL_PATH, pre, EDITOR_POST_CORRECT);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('TARGET_AMBIGUOUS');
  });

  it.runIf(testAddonAvailable)('never treats a selected project-tree basename as proof of editor state', () => {
    const pre = tree(WINDOW, PROJECT_TREE, CORRECT_DIR, CORRECT_FILE);
    const postSelectedOnly = tree(
      WINDOW,
      PROJECT_TREE,
      CORRECT_DIR,
      node(3, 'AXCell', 'package.json', { flags: 's' })
    );
    const result = evaluate('OPEN_REGISTERED_FILE', CANONICAL_PATH, pre, postSelectedOnly);
    expect(result.ok).toBe(true);
    expect(result.verified).toBe(false);
    expect(result.code).toBe('STATE_NOT_VERIFIED');
  });

  it.runIf(testAddonAvailable)('requires an exact canonical document match, not a similar path', () => {
    const pre = tree(WINDOW, PROJECT_TREE, CORRECT_DIR, CORRECT_FILE);
    const postWrongDocument = tree(
      WINDOW,
      node(1, 'AXGroup', 'editor'),
      node(2, 'AXTextArea', 'other.json', {
        document: 'file:///Users/moxiao/work/proj/local-automation-service/other.json',
      })
    );
    const result = evaluate('OPEN_REGISTERED_FILE', CANONICAL_PATH, pre, postWrongDocument);
    expect(result.verified).toBe(false);
    expect(result.code).toBe('STATE_NOT_VERIFIED');
  });

  it.runIf(testAddonAvailable)('accepts a plain absolute path in the document attribute', () => {
    const pre = tree(WINDOW, PROJECT_TREE, CORRECT_DIR, CORRECT_FILE);
    const post = tree(
      WINDOW,
      node(1, 'AXGroup', 'editor'),
      node(2, 'AXTextArea', 'package.json', { document: CANONICAL_PATH })
    );
    expect(evaluate('OPEN_REGISTERED_FILE', CANONICAL_PATH, pre, post).verified).toBe(true);
  });

  it.runIf(testAddonAvailable)('ignores hidden editors when proving the canonical path', () => {
    const pre = tree(WINDOW, PROJECT_TREE, CORRECT_DIR, CORRECT_FILE);
    const post = tree(
      WINDOW,
      node(1, 'AXGroup', 'editor'),
      node(2, 'AXTextArea', 'package.json', { document: `file://${CANONICAL_PATH}`, flags: 'h' })
    );
    const result = evaluate('OPEN_REGISTERED_FILE', CANONICAL_PATH, pre, post);
    expect(result.verified).toBe(false);
  });

  it.runIf(testAddonAvailable)('rejects a document that is merely a parent directory of the registered path', () => {
    const pre = tree(WINDOW, PROJECT_TREE, CORRECT_DIR, CORRECT_FILE);
    const post = tree(
      WINDOW,
      node(1, 'AXGroup', 'editor'),
      node(2, 'AXTextArea', 'local-automation-service', {
        document: 'file:///Users/moxiao/work/proj/local-automation-service',
      })
    );
    const result = evaluate('OPEN_REGISTERED_FILE', CANONICAL_PATH, pre, post);
    expect(result.verified).toBe(false);
  });
});

describe('FOCUS_RUN_CONFIGURATION is restricted to the real run-configuration selector', () => {
  it.runIf(testAddonAvailable)('rejects a same-title control outside the window toolbar', () => {
    // The previous implementation searched the whole application and returned SUCCEEDED
    // for an editor tab that merely shared the registered name.
    const tab = tree(WINDOW, node(1, 'AXGroup', 'editorTabs'), node(2, 'AXRadioButton', RUN_HANDLE));
    const result = evaluate('FOCUS_RUN_CONFIGURATION', RUN_HANDLE, tab, tab);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('TARGET_NOT_FOUND');
  });

  it.runIf(testAddonAvailable)('rejects a same-title static label in the toolbar', () => {
    const label = tree(WINDOW, node(1, 'AXToolbar', 'mainToolbar'), node(2, 'AXStaticText', RUN_HANDLE));
    const result = evaluate('FOCUS_RUN_CONFIGURATION', RUN_HANDLE, label, label);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('TARGET_NOT_FOUND');
  });

  it.runIf(testAddonAvailable)('requires post-action focus on the selector control', () => {
    const pre = tree(WINDOW, node(1, 'AXToolbar', 'mainToolbar'), node(2, 'AXPopUpButton', RUN_HANDLE));
    const result = evaluate('FOCUS_RUN_CONFIGURATION', RUN_HANDLE, pre, pre);
    expect(result.ok).toBe(true);
    expect(result.verified).toBe(false);
    expect(result.code).toBe('STATE_NOT_VERIFIED');
  });

  it.runIf(testAddonAvailable)('verifies the selected configuration value on the control after the action', () => {
    const pre = tree(WINDOW, node(1, 'AXToolbar', 'mainToolbar'), node(2, 'AXPopUpButton', RUN_HANDLE));
    const post = tree(
      WINDOW,
      node(1, 'AXToolbar', 'mainToolbar'),
      node(2, 'AXPopUpButton', RUN_HANDLE, { value: RUN_HANDLE, flags: 'sf' })
    );
    const result = evaluate('FOCUS_RUN_CONFIGURATION', RUN_HANDLE, pre, post);
    expect(result.ok).toBe(true);
    expect(result.verified).toBe(true);
  });

  it.runIf(testAddonAvailable)('fails closed when the selector reports a different configuration', () => {
    const pre = tree(WINDOW, node(1, 'AXToolbar', 'mainToolbar'), node(2, 'AXPopUpButton', RUN_HANDLE));
    const post = tree(
      WINDOW,
      node(1, 'AXToolbar', 'mainToolbar'),
      node(2, 'AXPopUpButton', 'OtherConfiguration', { value: 'OtherConfiguration', flags: 'f' })
    );
    const result = evaluate('FOCUS_RUN_CONFIGURATION', RUN_HANDLE, pre, post);
    expect(result.verified).toBe(false);
    expect(result.code).toBe('STATE_NOT_VERIFIED');
  });

  it.runIf(testAddonAvailable)('fails closed when two selectors match', () => {
    const pre = tree(
      WINDOW,
      node(1, 'AXToolbar', 'mainToolbar'),
      node(2, 'AXPopUpButton', RUN_HANDLE),
      node(2, 'AXComboBox', RUN_HANDLE)
    );
    const result = evaluate('FOCUS_RUN_CONFIGURATION', RUN_HANDLE, pre, pre);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('TARGET_AMBIGUOUS');
  });
});

describe('SHOW_TEST_RESULT is restricted to the tool-window results hierarchy', () => {
  it.runIf(testAddonAvailable)('rejects a same-title label anywhere outside the tool-window tab group', () => {
    const label = tree(WINDOW, node(1, 'AXToolbar', 'mainToolbar'), node(2, 'AXStaticText', RESULT_HANDLE));
    const result = evaluate('SHOW_TEST_RESULT', RESULT_HANDLE, label, label);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('TARGET_NOT_FOUND');
  });

  it.runIf(testAddonAvailable)('rejects a visible same-title label that was already showing before the action', () => {
    // Pre-action visibility must never be reported as the effect of the action.
    const pre = tree(WINDOW, node(1, 'AXTabGroup', 'toolWindows'), node(2, 'AXTab', RESULT_HANDLE));
    const result = evaluate('SHOW_TEST_RESULT', RESULT_HANDLE, pre, pre);
    expect(result.ok).toBe(true);
    expect(result.verified).toBe(false);
    expect(result.code).toBe('STATE_NOT_VERIFIED');
  });

  it.runIf(testAddonAvailable)('fails closed when a selected view disappears after the action', () => {
    const pre = tree(
      WINDOW,
      node(1, 'AXTabGroup', 'toolWindows'),
      node(2, 'AXTab', RESULT_HANDLE, { flags: 's' })
    );
    const post = tree(WINDOW, node(1, 'AXTabGroup', 'toolWindows'), node(2, 'AXTab', RESULT_HANDLE));
    const result = evaluate('SHOW_TEST_RESULT', RESULT_HANDLE, pre, post);
    expect(result.verified).toBe(false);
    expect(result.code).toBe('STATE_NOT_VERIFIED');
  });

  it.runIf(testAddonAvailable)('verifies a post-action selected and visible result view', () => {
    // The results view exists before the action but is NOT the selected tool-window view.
    const pre = tree(
      WINDOW,
      node(1, 'AXTabGroup', 'toolWindows'),
      node(2, 'AXTab', RESULT_HANDLE)
    );
    const post = tree(
      WINDOW,
      node(1, 'AXTabGroup', 'toolWindows'),
      node(2, 'AXTab', RESULT_HANDLE, { flags: 's' })
    );
    const result = evaluate('SHOW_TEST_RESULT', RESULT_HANDLE, pre, post);
    expect(result.ok).toBe(true);
    expect(result.verified).toBe(true);
  });

  it.runIf(testAddonAvailable)('never accepts a partial title match', () => {
    const partial = tree(
      WINDOW,
      node(1, 'AXTabGroup', 'toolWindows'),
      node(2, 'AXTab', `${RESULT_HANDLE}-summary`, { flags: 's' })
    );
    const result = evaluate('SHOW_TEST_RESULT', RESULT_HANDLE, partial, partial);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('TARGET_NOT_FOUND');
  });
});

describe('Snapshot roots and bounds', () => {
  it.runIf(testAddonAvailable)('rejects an empty window snapshot', () => {
    const result = evaluate('SHOW_TEST_RESULT', RESULT_HANDLE, '', '');
    expect(result.ok).toBe(false);
  });

  it.runIf(testAddonAvailable)('rejects an unknown operation', () => {
    const pre = tree(WINDOW, PROJECT_TREE, CORRECT_DIR, CORRECT_FILE);
    const result = evaluate('EXECUTE_SHELL_COMMAND', 'anything', pre, pre);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('INVALID_ACTION');
  });
});
