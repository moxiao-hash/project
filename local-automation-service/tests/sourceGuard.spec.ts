import fs from 'node:fs';
import path from 'node:path';
import { describe, it, expect } from 'vitest';

const packageRoot = path.resolve(__dirname, '..');
const srcDir = path.join(packageRoot, 'src');
const nativeSource = path.join(packageRoot, 'native/idea_ax_bridge.mm');

function getProductionTsFiles(dir: string): string[] {
  const results: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...getProductionTsFiles(fullPath));
    } else if (entry.isFile() && entry.name.endsWith('.ts')) {
      results.push(fullPath);
    }
  }
  return results;
}

const productionFiles = getProductionTsFiles(srcDir);
const native = fs.readFileSync(nativeSource, 'utf8');

/** Body of a named top-level C function, from its signature to the closing brace. */
function functionBody(source: string, signature: string, nextSignature: string): string {
  const start = source.indexOf(signature);
  const end = source.indexOf(nextSignature);
  expect(start).toBeGreaterThanOrEqual(0);
  expect(end).toBeGreaterThan(start);
  return source.slice(start, end);
}

describe('Production Source Guards (Contract Forbidden Fallbacks)', () => {
  it('strictly forbids child_process, spawn, exec, and shell in production src/**', () => {
    const forbiddenPatterns = [
      /child_process/,
      /\bexecSync\b/,
      /\bexecFile\b/,
      /\bexecFileSync\b/,
      /\bspawn\b/,
      /\bspawnSync\b/,
      /\bosascript\b/,
      /\bAppleScript\b/,
      /\bJXA\b/,
      /\bopen\s+-a\b/,
    ];

    const violations: { file: string; line: number; matched: string }[] = [];
    for (const file of productionFiles) {
      const content = fs.readFileSync(file, 'utf8');
      content.split('\n').forEach((lineText, idx) => {
        for (const pattern of forbiddenPatterns) {
          if (pattern.test(lineText)) {
            violations.push({
              file: path.relative(packageRoot, file),
              line: idx + 1,
              matched: lineText.trim(),
            });
          }
        }
      });
    }
    expect(violations).toEqual([]);
  });

  it('strictly forbids generic desktop control or arbitrary click/type/keyboard/mouse capabilities', () => {
    const forbiddenCapabilityPatterns = [
      /\bgenericClick\b/,
      /\bgenericType\b/,
      /\bpressKey\b/,
      /\bmouseMove\b/,
      /\barbitraryScript\b/,
      /\bPerformFocus\b/,
    ];

    const violations: string[] = [];
    for (const file of productionFiles) {
      const content = fs.readFileSync(file, 'utf8');
      for (const pattern of forbiddenCapabilityPatterns) {
        if (pattern.test(content)) {
          violations.push(`${file}: contains forbidden generic capability ${pattern}`);
        }
      }
    }
    expect(violations).toEqual([]);
  });

  it('native addon never shells out, links a network transport, or drives the desktop generically', () => {
    const forbidden = [
      /\bosascript\b/,
      /\bAppleScript\b/,
      /\bJXA\b/,
      /child_process/,
      /\bspawn\w*\b/,
      /\bexec[lv]p?e?\b/,
      /\bsystem\s*\(/,
      /\bpopen\s*\(/,
      /\bfork\s*\(/,
      /\bNSTask\b/,
      /\bopenURL\b/,
      /\bopen\s+-a\b/,
      /\bCGEventCreate\w*/,
      /\bAXUIElementCreateSystemWide\b/,
      /\bAXObserverCreate\w*/,
      /https?:\/\//,
      /\b127\.0\.0\.1\b/,
      /\blocalhost:\d+/,
      /\b63342\b/,
      /\bport\b/i,
      /\bNSURLSession\b/,
      /\bNSURLConnection\b/,
      /\bCFSocketCreate\w*/,
      /\bNWConnection\b/,
      /\bsocket\s*\(/,
    ];

    const violations = forbidden
      .filter((pattern) => pattern.test(native))
      .map((pattern) => `native/idea_ax_bridge.mm: ${pattern}`);
    expect(violations).toEqual([]);

    // The bundle identifier is the only application selector, and it is trusted/in-source.
    expect(native).toContain('com.jetbrains.intellij');
    expect(native).toContain('kAXPressAction');
  });

  it('native identity binding stays structural: exact names, unique targets, outline path proof', () => {
    // Substring matching, application-wide search, and unbounded AX calls are forbidden:
    // identity must be an exact, structurally proven match.
    const forbidden = [
      /containsString/,
      /kAXFocusedUIElementAttribute/,
      /AXUIElementCopyParameterized/,
    ];
    const violations = forbidden
      .filter((pattern) => pattern.test(native))
      .map((pattern) => `native/idea_ax_bridge.mm: ${pattern}`);
    expect(violations).toEqual([]);

    // The strict machinery must actually be present, so the guards above cannot pass by a
    // feature simply having been deleted.
    const requiredMarkers = [
      'RowNameMatchesBasename', // exact basename, only source extensions may be omitted
      'kOmittedSourceExtensions', // the recognised source extension allowlist
      'CountAlignments', // component alignment incl. compacted packages
      'ProveOutlinePath', // REJECTS ambiguity (returns false when >1 alignment)
      'StripSpaceLike', // thin-space / non-ASCII whitespace aware
      'AXDisclosureLevel', // outline nesting level
      'kSubroleOutlineRow', // project-view row subrole
      'kFrameTitleGroupLabel', // run-configuration container
      'CopyVerifiedElement', // live identity re-check immediately before dispatch
      'TARGET_AMBIGUOUS', // uniqueness is mandatory
      'AX_SNAPSHOT_TRUNCATED', // bounded traversal reports truncation
      'kRoleWindow', // traversal root must be a real window
      'AXUIElementSetMessagingTimeout', // bounded AX calls
    ];
    for (const marker of requiredMarkers) {
      expect(native).toContain(marker);
    }
  });

  it('FOCUS_RUN_CONFIGURATION only moves focus and never presses the control', () => {
    const body = functionBody(
      native,
      'static napi_value FocusRunConfiguration',
      'static napi_value ShowTestResult'
    );
    // Pressing a run-configuration control opens a menu and can change the selected
    // configuration; the registered action is focus only.
    expect(body).not.toContain('PerformPress');
    expect(body).toContain('kAXFocusedAttribute');
    expect(body).toContain('runLabel');
    expect(body).toContain('debugLabel');
  });

  it('every operation verifies only on the post-action snapshot and never invents roles', () => {
    // OPEN_REGISTERED_FILE re-proves the row and requires the active editor identity
    // AFTER the action; a selected tree row alone is never accepted.
    expect(native).toContain('rowStillUnique');
    expect(native).toContain('rowSelected');
    expect(native).toContain('editorIdentified');

    const resultBody = functionBody(
      native,
      'static napi_value ShowTestResult',
      '// ---------------------------------------------------------------------------\n// Diagnostic probe'
    );
    expect(resultBody).toContain('data->selected');
    expect(resultBody).toContain('IsVisible');
    expect(resultBody).not.toContain('PerformFocus');

    // The editor tab role (AXRadioButton) must never be an admissible result view.
    expect(native).not.toContain('AXRadioButton');
  });
});
