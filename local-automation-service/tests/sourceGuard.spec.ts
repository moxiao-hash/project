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
});
