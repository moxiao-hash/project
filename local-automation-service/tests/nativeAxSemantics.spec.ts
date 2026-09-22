import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { describe, it, expect } from 'vitest';
import { validateNativeAxModuleSurface } from '../src/nativeAxBridge.js';

/**
 * Adversarial fixture tests for the native Accessibility decision logic, calibrated
 * against a live IntelliJ IDEA 2026.1.1 tree (ide.support.screenreaders.enabled=true).
 *
 * Live shape this file mirrors:
 *   project view : AXOutline -> FLAT AXOutlineRow siblings, nesting carried by
 *                  AXDisclosureLevel, single-child packages compacted into one row
 *                  ("com.itmoxiao" for com/itmoxiao), ".java" omitted from file rows,
 *                  folders carrying a ", <type>" suffix, and the level-0 module row
 *                  embedding its real path after a thin space (U+2009)
 *   editor       : AXTabGroup whose description is exactly the open file name
 *   run widget   : AXGroup desc="帧标题" -> AXButton desc="<config>" with exact
 *                  "运行 '<config>'" / "调试 '<config>'" siblings
 *   test results : no tool-window result view exists in the live tree
 *
 * The production addon exposes only four operations and drives the real desktop, so it
 * cannot be unit tested directly. The build also produces a test-seam artifact compiled
 * from the SAME source with AX_BRIDGE_TEST_SEAM, which runs the same matching, uniqueness,
 * path-alignment and post-action verification functions over fixture trees.
 *
 * Fixture line format, one node per line, depth-first:
 *   depth|role|subrole|identifier|description|title|value|flags|size|level
 * flags: "s" selected, "f" focused, "h" hidden. size: "WxH" or empty. level: integer or
 * empty. depth 0 is the traversal root (the window).
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
    postFixture: string,
    workspaceRoot?: string
  ): SeamResult;
}

const seam: SeamModule | null = testAddonAvailable
  ? (createRequire(import.meta.url)(testAddonPath) as SeamModule)
  : null;

const ROOT = '/Users/moxiao/IdeaProjects/web-ai-project-learning -01';
const CANONICAL = `${ROOT}/telers-web-management/src/main/java/com/itmoxiao/controller/DeptController.java`;
const THIN = '\u2009';

function row(title: string, level: number, flags = ''): string {
  return ['2', 'AXRow', 'AXOutlineRow', '', title, '', '', flags, '', String(level)].join('|');
}

const WINDOW = ['0', 'AXWindow', '', '', 'Project', '', '', '', '', ''].join('|');
const OUTLINE = ['1', 'AXOutline', '', '', '项目结构树', '', '', '', '', ''].join('|');

/** The exact outline shape the live IDE exposes for the canonical file. */
function projectRows(leafFlags = ''): string {
  return [
    row(`web-ai-project-learning -01 ${THIN}~/IdeaProjects/web-ai-project-learning -01, 模块`, 0),
    row('telers-web-management, 模块', 1),
    row('src', 2),
    row('main', 3),
    row('java, 源根', 4),
    row('com.itmoxiao', 5),
    row('controller', 6),
    row('DeptController', 7, leafFlags),
  ].join('\n');
}

function tree(...nodes: string[]): string {
  return nodes.join('\n');
}

function editorTab(fileName: string, flags = ''): string {
  return ['1', 'AXTabGroup', '', '', fileName, '', '', flags, '', ''].join('|');
}

function evaluate(
  operation: string,
  argument: string,
  pre: string,
  post: string,
  workspaceRoot?: string
): SeamResult {
  if (!seam) {
    throw new Error('test-seam addon is not built; run `npm run build:native`');
  }
  return seam.__testEvaluate(operation, argument, pre, post, workspaceRoot);
}

function preForFile(): string {
  return tree(WINDOW, OUTLINE, projectRows());
}

function postForFile(fileName: string): string {
  return tree(WINDOW, OUTLINE, projectRows('s'), editorTab(fileName));
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
    expect(validateNativeAxModuleSurface(testAddon)).toBe(false);
  });
});

describe('OPEN_REGISTERED_FILE binds to the canonical registered path', () => {
  it.runIf(testAddonAvailable)('opens and verifies the live IDE outline shape for the canonical file', () => {
    const result = evaluate('OPEN_REGISTERED_FILE', CANONICAL, preForFile(), postForFile('DeptController.java'));
    expect(result.ok).toBe(true);
    expect(result.verified).toBe(true);
  });

  it.runIf(testAddonAvailable)('aligns a compacted package row ("com.itmoxiao") to two path components', () => {
    // Removing the compaction handling makes this chain fail to align.
    const result = evaluate('OPEN_REGISTERED_FILE', CANONICAL, preForFile(), postForFile('DeptController.java'));
    expect(result.verified).toBe(true);
  });

  it.runIf(testAddonAvailable)('prunes the outline section that does not contain the file', () => {
    const other = [
      row(`web-ai-project-learning -01 ${THIN}~/IdeaProjects/web-ai-project-learning -01, 模块`, 0),
      row('other-service, 模块', 1),
      row('src', 2),
      row('main', 3),
      row('com.itmoxiao', 4),
      row('controller', 5),
      row('DeptController', 6),
    ].join('\n');
    const result = evaluate(
      'OPEN_REGISTERED_FILE',
      CANONICAL,
      tree(WINDOW, OUTLINE, other),
      postForFile('DeptController.java')
    );
    expect(result.ok).toBe(false);
    expect(result.code).toBe('TARGET_NOT_FOUND');
  });

  it.runIf(testAddonAvailable)('fails closed when two rows prove the same path', () => {
    const duplicated = projectRows() + '\n' + row('DeptController', 7);
    const result = evaluate(
      'OPEN_REGISTERED_FILE',
      CANONICAL,
      tree(WINDOW, OUTLINE, duplicated),
      postForFile('DeptController.java')
    );
    expect(result.ok).toBe(false);
    expect(result.code).toBe('TARGET_AMBIGUOUS');
  });

  it.runIf(testAddonAvailable)('never treats a selected basename row as proof of editor state', () => {
    // Post-action: the row is selected but no editor identifies the file.
    const result = evaluate(
      'OPEN_REGISTERED_FILE',
      CANONICAL,
      preForFile(),
      tree(WINDOW, OUTLINE, projectRows('s'))
    );
    expect(result.ok).toBe(true);
    expect(result.verified).toBe(false);
    expect(result.code).toBe('STATE_NOT_VERIFIED');
  });

  it.runIf(testAddonAvailable)('fails closed when the editor shows a different file', () => {
    const result = evaluate(
      'OPEN_REGISTERED_FILE',
      CANONICAL,
      preForFile(),
      postForFile('DeptMapper.java')
    );
    expect(result.verified).toBe(false);
    expect(result.code).toBe('STATE_NOT_VERIFIED');
  });

  it.runIf(testAddonAvailable)('never accepts a partial basename match for the file row', () => {
    const partial = projectRows().replace(row('DeptController', 7), row('DeptControllerExtra', 7));
    const result = evaluate(
      'OPEN_REGISTERED_FILE',
      CANONICAL,
      tree(WINDOW, OUTLINE, partial),
      postForFile('DeptController.java')
    );
    expect(result.ok).toBe(false);
    expect(result.code).toBe('TARGET_NOT_FOUND');
  });

  it.runIf(testAddonAvailable)('only omits recognised SOURCE extensions from the basename', () => {
    const canonicalTxt = `${ROOT}/telers-web-management/src/main.txt`;
    const rows = [
      row('web-ai-project-learning -01', 0),
      row('telers-web-management, 模块', 1),
      row('src', 2),
      row('main', 3),
    ].join('\n');
    // The row says "main" but the canonical basename is "main.txt": .txt is not a source
    // extension, so it must NOT be treated as an omission.
    const result = evaluate(
      'OPEN_REGISTERED_FILE',
      canonicalTxt,
      tree(WINDOW, OUTLINE, rows),
      tree(WINDOW, OUTLINE, rows)
    );
    expect(result.ok).toBe(false);
    expect(result.code).toBe('TARGET_NOT_FOUND');
  });

  it.runIf(testAddonAvailable)('accepts an omitted .java extension for the leaf row', () => {
    const result = evaluate('OPEN_REGISTERED_FILE', CANONICAL, preForFile(), postForFile('DeptController.java'));
    expect(result.verified).toBe(true);
  });

  const embeddedRootRows = (leafFlags = ''): string =>
    [
      row(`MODULE ${THIN}/root/sub, 模块`, 0),
      row('a', 1),
      row('b', 2),
      row('Foo', 3, leafFlags),
    ].join('\n');
  const EMBEDDED_CANONICAL = '/root/sub/a/b/Foo.java';

  it.runIf(testAddonAvailable)('anchors an unnameable module row through its embedded root path', () => {
    const pre = tree(WINDOW, OUTLINE, embeddedRootRows());
    const post = tree(WINDOW, OUTLINE, embeddedRootRows('s'), editorTab('Foo.java'));
    const result = evaluate('OPEN_REGISTERED_FILE', EMBEDDED_CANONICAL, pre, post, '/root');
    expect(result.ok).toBe(true);
    expect(result.verified).toBe(true);
  });

  it.runIf(testAddonAvailable)('rejects the embedded root path when it sits outside the trusted workspace root', () => {
    const pre = tree(WINDOW, OUTLINE, embeddedRootRows());
    const post = tree(WINDOW, OUTLINE, embeddedRootRows('s'), editorTab('Foo.java'));
    const result = evaluate('OPEN_REGISTERED_FILE', EMBEDDED_CANONICAL, pre, post, '/somewhere/else');
    expect(result.ok).toBe(false);
    expect(result.code).toBe('TARGET_NOT_FOUND');
  });

  it.runIf(testAddonAvailable)('rejects a file that is not in the registered project at all', () => {
    const result = evaluate(
      'OPEN_REGISTERED_FILE',
      '/Users/moxiao/IdeaProjects/project-zcode-task-33/package.json',
      preForFile(),
      postForFile('DeptController.java')
    );
    expect(result.ok).toBe(false);
    expect(result.code).toBe('TARGET_NOT_FOUND');
  });
});

describe('FOCUS_RUN_CONFIGURATION is restricted to the real run-configuration control', () => {
  const CONFIG = 'TelersWebManagementApplication';
  const FRAME = ['1', 'AXGroup', '', '', '帧标题', '', '', '', '', ''].join('|');
  const RUN_GROUP = ['2', 'AXGroup', '', '', '', '', '', '', '', ''].join('|');

  function button(description: string, flags = ''): string {
    return ['3', 'AXButton', '', '', description, '', '', flags, '', ''].join('|');
  }

  const liveWidget = (configFlags = ''): string =>
    [
      button(CONFIG, configFlags),
      button(`运行 '${CONFIG}'`),
      button(`调试 '${CONFIG}'`),
      button('更多操作'),
    ].join('\n');

  it.runIf(testAddonAvailable)('rejects a same-title editor tab outside the frame-title container', () => {
    const decoy = tree(
      WINDOW,
      ['1', 'AXGroup', '', '', 'editorTabs', '', '', '', '', ''].join('|'),
      ['2', 'AXRadioButton', '', '', CONFIG, '', '', '', '', ''].join('|')
    );
    const result = evaluate('FOCUS_RUN_CONFIGURATION', CONFIG, decoy, decoy);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('SELECTOR_NOT_IDENTIFIED');
  });

  it.runIf(testAddonAvailable)('rejects a same-title static label inside the frame-title container', () => {
    const decoy = tree(
      WINDOW,
      FRAME,
      RUN_GROUP,
      ['3', 'AXStaticText', '', '', CONFIG, '', '', '', '', ''].join('|')
    );
    const result = evaluate('FOCUS_RUN_CONFIGURATION', CONFIG, decoy, decoy);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('SELECTOR_NOT_IDENTIFIED');
  });

  it.runIf(testAddonAvailable)('rejects a same-title button without the exact Run/Debug siblings', () => {
    const decoy = tree(WINDOW, FRAME, RUN_GROUP, button(CONFIG), button('更多操作'));
    const result = evaluate('FOCUS_RUN_CONFIGURATION', CONFIG, decoy, decoy);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('SELECTOR_NOT_IDENTIFIED');
  });

  it.runIf(testAddonAvailable)('rejects siblings that merely resemble the Run/Debug labels', () => {
    const decoy = tree(
      WINDOW,
      FRAME,
      RUN_GROUP,
      button(CONFIG),
      button(`运行 '${CONFIG}Extra'`),
      button(`调试 '${CONFIG}'`)
    );
    const result = evaluate('FOCUS_RUN_CONFIGURATION', CONFIG, decoy, decoy);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('SELECTOR_NOT_IDENTIFIED');
  });

  it.runIf(testAddonAvailable)('fails closed when the live IDE never reports the control as focused', () => {
    // This is the observed behaviour of IntelliJ 2026.1.1: the focus request is accepted
    // but AXFocused/AXSelected is never set, so the action cannot be verified.
    const pre = tree(WINDOW, FRAME, RUN_GROUP, liveWidget());
    const result = evaluate('FOCUS_RUN_CONFIGURATION', CONFIG, pre, pre);
    expect(result.ok).toBe(true);
    expect(result.verified).toBe(false);
    expect(result.code).toBe('STATE_NOT_VERIFIED');
  });

  it.runIf(testAddonAvailable)('verifies only when the unique control reports focus after the action', () => {
    const pre = tree(WINDOW, FRAME, RUN_GROUP, liveWidget());
    const post = tree(WINDOW, FRAME, RUN_GROUP, liveWidget('f'));
    const result = evaluate('FOCUS_RUN_CONFIGURATION', CONFIG, pre, post);
    expect(result.ok).toBe(true);
    expect(result.verified).toBe(true);
  });

  it.runIf(testAddonAvailable)('fails closed when two admissible controls exist', () => {
    const pre = tree(WINDOW, FRAME, RUN_GROUP, liveWidget(), button(CONFIG));
    const result = evaluate('FOCUS_RUN_CONFIGURATION', CONFIG, pre, pre);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('TARGET_AMBIGUOUS');
  });

  it.runIf(testAddonAvailable)('rejects a different configuration name', () => {
    const pre = tree(WINDOW, FRAME, RUN_GROUP, liveWidget());
    const result = evaluate('FOCUS_RUN_CONFIGURATION', 'OtherConfiguration', pre, pre);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('SELECTOR_NOT_IDENTIFIED');
  });
});

describe('SHOW_TEST_RESULT stays inside the tool-window results hierarchy', () => {
  const RESULT = 'surefire-reports';
  const TAB_GROUP = ['1', 'AXTabGroup', '', '', 'toolWindows', '', '', '', '', ''].join('|');

  function tab(title: string, flags = ''): string {
    return ['2', 'AXTab', '', '', title, '', '', flags, '', ''].join('|');
  }

  it.runIf(testAddonAvailable)('rejects a same-title label outside a tab group', () => {
    const label = tree(
      WINDOW,
      ['1', 'AXToolbar', '', '', 'mainToolbar', '', '', '', '', ''].join('|'),
      ['2', 'AXStaticText', '', '', RESULT, '', '', '', '', ''].join('|')
    );
    const result = evaluate('SHOW_TEST_RESULT', RESULT, label, label);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('RESULT_VIEW_NOT_IDENTIFIED');
  });

  it.runIf(testAddonAvailable)('never accepts the editor tab role, even with the exact title', () => {
    // AXRadioButton is the editor-tab role in the live tree; it must never be admissible.
    const editorTabNode = tree(
      WINDOW,
      TAB_GROUP,
      ['2', 'AXRadioButton', '', '', RESULT, '', '', 's', '', ''].join('|')
    );
    const result = evaluate('SHOW_TEST_RESULT', RESULT, editorTabNode, editorTabNode);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('RESULT_VIEW_NOT_IDENTIFIED');
  });

  it.runIf(testAddonAvailable)('fails closed when a same-title tab was already visible before the action', () => {
    const pre = tree(WINDOW, TAB_GROUP, tab(RESULT));
    const result = evaluate('SHOW_TEST_RESULT', RESULT, pre, pre);
    expect(result.ok).toBe(true);
    expect(result.verified).toBe(false);
    expect(result.code).toBe('STATE_NOT_VERIFIED');
  });

  it.runIf(testAddonAvailable)('fails closed when a selected view disappears after the action', () => {
    const pre = tree(WINDOW, TAB_GROUP, tab(RESULT, 's'));
    const post = tree(WINDOW, TAB_GROUP, tab(RESULT));
    const result = evaluate('SHOW_TEST_RESULT', RESULT, pre, post);
    expect(result.verified).toBe(false);
    expect(result.code).toBe('STATE_NOT_VERIFIED');
  });

  it.runIf(testAddonAvailable)('verifies a post-action selected and visible result view', () => {
    const pre = tree(WINDOW, TAB_GROUP, tab(RESULT));
    const post = tree(WINDOW, TAB_GROUP, tab(RESULT, 's'));
    const result = evaluate('SHOW_TEST_RESULT', RESULT, pre, post);
    expect(result.ok).toBe(true);
    expect(result.verified).toBe(true);
  });

  it.runIf(testAddonAvailable)('never accepts a partial title match', () => {
    const partial = tree(WINDOW, TAB_GROUP, tab(`${RESULT}-summary`, 's'));
    const result = evaluate('SHOW_TEST_RESULT', RESULT, partial, partial);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('RESULT_VIEW_NOT_IDENTIFIED');
  });

  it.runIf(testAddonAvailable)('reports no result view when the live tree has none, without inventing roles', () => {
    // The live IDE exposes only the editor AXTabGroup, which carries no AXTab children.
    const live = tree(WINDOW, editorTab('TelersWebManagementApplicationTests.java'));
    const result = evaluate('SHOW_TEST_RESULT', RESULT, live, live);
    expect(result.ok).toBe(false);
    expect(result.code).toBe('RESULT_VIEW_NOT_IDENTIFIED');
  });
});

describe('Snapshot roots and bounds', () => {
  it.runIf(testAddonAvailable)('rejects an empty snapshot', () => {
    const result = evaluate('SHOW_TEST_RESULT', 'surefire-reports', '', '');
    expect(result.ok).toBe(false);
  });

  it.runIf(testAddonAvailable)('rejects an unknown operation', () => {
    const result = evaluate('EXECUTE_SHELL_COMMAND', 'anything', preForFile(), preForFile());
    expect(result.ok).toBe(false);
    expect(result.code).toBe('INVALID_ACTION');
  });
});
