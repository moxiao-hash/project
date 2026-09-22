import fs from 'node:fs';
import path from 'node:path';
import { describe, it, expect } from 'vitest';

describe('Production Source Guards (Contract Forbidden Fallbacks)', () => {
  const srcDir = path.resolve(__dirname, '../src');

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
      const lines = content.split('\n');
      lines.forEach((lineText, idx) => {
        for (const pattern of forbiddenPatterns) {
          if (pattern.test(lineText)) {
            violations.push({
              file: path.relative(path.resolve(__dirname, '..'), file),
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

  it('native Accessibility addon never shells out, opens a port, or drives the desktop generically', () => {
    const nativeSource = path.resolve(__dirname, '../native/idea_ax_bridge.mm');
    expect(fs.existsSync(nativeSource)).toBe(true);
    const content = fs.readFileSync(nativeSource, 'utf8');

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
      /https?:\/\//,
      /\b127\.0\.0\.1\b/,
      /\blocalhost:\d+/,
      /\b63342\b/,
      /\bport\b/i,
      // No network transport of any kind may be linked into the bridge. (`file://localhost/`
      // appears only when normalising a file URL authority to a filesystem path.)
      /\bNSURLSession\b/,
      /\bNSURLConnection\b/,
      /\bCFSocketCreate\w*/,
      /\bNWConnection\b/,
      /\bsocket\s*\(/,
    ];

    const violations: string[] = [];
    for (const pattern of forbidden) {
      if (pattern.test(content)) {
        violations.push(`native/idea_ax_bridge.mm: ${pattern}`);
      }
    }
    expect(violations).toEqual([]);

    // The bundle identifier is the only application selector, and it is trusted/in-source.
    expect(content).toContain('com.jetbrains.intellij');
    expect(content).toContain('kAXPressAction');
  });

  it('native identity binding stays structural: no substring identity, no app-wide search', () => {
    const nativeSource = path.resolve(__dirname, '../native/idea_ax_bridge.mm');
    const content = fs.readFileSync(nativeSource, 'utf8');

    // Substring matching, whole-application or whole-system traversal, and unbounded AX
    // calls are all forbidden: identity must be an exact, structurally proven match.
    const forbidden = [
      /containsString/,
      /AXUIElementCreateSystemWide/,
      /kAXFocusedUIElementAttribute/,
      /AXUIElementCopyParameterized/,
      /AXObserverCreate/,
    ];
    const violations = forbidden
      .filter((pattern) => pattern.test(content))
      .map((pattern) => `native/idea_ax_bridge.mm: ${pattern}`);
    expect(violations).toEqual([]);

    // The strict identity machinery must actually be present, so the guards above cannot
    // pass by the feature simply being deleted.
    const requiredMarkers = [
      'CanonicalDirname', // canonical path proof for actuation
      'CanonicalBasename',
      'kAXDocumentAttribute', // exact document/URL proof for the active editor
      'ReverifyLiveIdentity', // re-check identity immediately before dispatch
      'TARGET_AMBIGUOUS', // uniqueness is mandatory
      'kAXWindowRole', // traversal root must be a real window
      'AXToolbar', // run-configuration selector container
      'AXTabGroup', // test-result tool-window container
      'AXUIElementSetMessagingTimeout', // bounded AX calls
      'LocateUnique',
    ];
    for (const marker of requiredMarkers) {
      expect(content).toContain(marker);
    }
  });

  it('verification predicates never read pre-action state and never accept generic focus as proof', () => {
    const nativeSource = path.resolve(__dirname, '../native/idea_ax_bridge.mm');
    const content = fs.readFileSync(nativeSource, 'utf8');

    // The results view must be proven selected on the POST-action snapshot.
    const resultVerifier = content.slice(content.indexOf('static bool VerifyResultViewSelected'));
    const body = resultVerifier.slice(0, resultVerifier.indexOf('\n}\n'));
    expect(body).toContain('data->selected');
    expect(body).toContain('IsVisible');
    expect(body).not.toContain('PerformFocus');
    expect(body).not.toContain('kAXFocusedAttribute');

    // There is no generic focus capability anywhere in the bridge.
    expect(content).not.toContain('PerformFocus');
  });

  it('FOCUS_RUN_CONFIGURATION only moves focus and never presses the selector', () => {
    const nativeSource = path.resolve(__dirname, '../native/idea_ax_bridge.mm');
    const content = fs.readFileSync(nativeSource, 'utf8');

    const operation = content.slice(content.indexOf('static napi_value FocusRunConfiguration'));
    const body = operation.slice(0, operation.indexOf('\nstatic napi_value ShowTestResult'));
    expect(body.length).toBeGreaterThan(0);
    // Pressing a run-configuration selector opens a menu and can change configuration
    // selection; the registered action is focus only, so no press is permitted here.
    expect(body).not.toContain('PerformPress');
    expect(body).toContain('kAXFocusedAttribute');
  });
});
