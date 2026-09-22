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
      /\blocalhost\b/,
      /\b63342\b/,
      /\bport\b/i,
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
});
