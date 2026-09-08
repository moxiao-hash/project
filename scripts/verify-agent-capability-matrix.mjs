#!/usr/bin/env node

/**
 * scripts/verify-agent-capability-matrix.mjs
 *
 * Task 27: 自动化校验全系统 Vue 路由、Java 工具与能力矩阵 (docs/agent-capability-matrix-v2.md) 的完备性与一致性。
 * 若文件不存在、缺少任何已注册页面或工具，以退出码 1 退出。
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const rootDir = path.resolve(__dirname, '..');

const routerFile = path.join(rootDir, 'web/src/app/router.ts');
const javaCoverageTestFile = path.join(
  rootDir,
  'backend/src/test/java/com/moxiao/studypilot/agent/tool/AgentToolCoverageTest.java'
);
const matrixFile = path.join(rootDir, 'docs/agent-capability-matrix-v2.md');

const VALID_CAPABILITIES = new Set([
  'AUTO_READ',
  'AUTO_NAVIGATE',
  'PREVIEW_WRITE',
  'USER_ONLY',
  'UNSUPPORTED',
]);

const VALID_RISK_LEVELS = new Set(['NONE', 'LOW', 'HIGH']);

const VALID_AUTHENTICITIES = new Set([
  'AGENT_PERMITTED',
  'USER_ONLY_SUBMISSION',
  'USER_ONLY_REVIEW',
  'USER_ONLY_DECISION',
]);

function fail(message) {
  console.error(`[ERROR] 矩阵校验失败: ${message}`);
  process.exit(1);
}

// 1. 提取 Vue 路由名
if (!fs.existsSync(routerFile)) {
  fail(`找不到 Vue 路由定义文件: ${routerFile}`);
}

const routerContent = fs.readFileSync(routerFile, 'utf8');
const routeNameRegex = /name:\s*['"]([^'"]+)['"]/g;
const vueRouteNames = new Set();
let match;
while ((match = routeNameRegex.exec(routerContent)) !== null) {
  vueRouteNames.add(match[1]);
}

if (vueRouteNames.size === 0) {
  fail('未能从 router.ts 中解析出任何路由名称');
}

// 2. 提取 Java Tool 列表
if (!fs.existsSync(javaCoverageTestFile)) {
  fail(`找不到 Java 工具覆盖测试文件: ${javaCoverageTestFile}`);
}

const javaTestContent = fs.readFileSync(javaCoverageTestFile, 'utf8');
const toolNameRegex = /"([a-z0-9_]+(?:\.[a-z0-9_]+)+)"/g;
const javaToolNames = new Set();
while ((match = toolNameRegex.exec(javaTestContent)) !== null) {
  javaToolNames.add(match[1]);
}

if (javaToolNames.size === 0) {
  fail('未能从 AgentToolCoverageTest.java 中解析出任何已注册工具');
}

// 3. 检查矩阵文件是否存在
if (!fs.existsSync(matrixFile)) {
  fail(`能力矩阵文件不存在: ${matrixFile}`);
}

const matrixContent = fs.readFileSync(matrixFile, 'utf8');

// 4. 校验 Vue 路由覆盖
const missingRoutes = [];
for (const routeName of vueRouteNames) {
  // 匹配 `| ` + routeName + ` |`
  const regex = new RegExp(`\\|\\s*${routeName}\\s*\\|`);
  if (!regex.test(matrixContent)) {
    missingRoutes.push(routeName);
  }
}

if (missingRoutes.length > 0) {
  fail(`能力矩阵缺少以下 Vue 路由映射 (${missingRoutes.length} 个): ${missingRoutes.join(', ')}`);
}

// 5. 校验 Java 工具覆盖
const missingTools = [];
for (const toolName of javaToolNames) {
  const regex = new RegExp(`\`${toolName.replace('.', '\\.')}\``);
  if (!regex.test(matrixContent)) {
    missingTools.push(toolName);
  }
}

if (missingTools.length > 0) {
  fail(`能力矩阵缺少以下 Java 工具映射 (${missingTools.length} 个): ${missingTools.join(', ')}`);
}

// 6. 校验能力枚举合法性与 USER_ONLY 原因
const lines = matrixContent.split('\n');
let tableHeaderFound = false;
let verifiedRows = 0;

for (const line of lines) {
  if (line.includes('| 路由名 (name) |')) {
    tableHeaderFound = true;
    continue;
  }
  if (!tableHeaderFound) continue;
  if (!line.trim().startsWith('|')) {
    // 遇到非表格行，页面表格结束
    if (verifiedRows > 0) break;
    continue;
  }
  if (line.includes('---')) continue;

  const cols = line.split('|').map((c) => c.trim()).filter(Boolean);
  if (cols.length >= 6) {
    const [routeName, routeKey, capability, readTool, writeTool, risk, authenticity, reason] = cols;
    if (!vueRouteNames.has(routeName)) continue;

    if (!VALID_CAPABILITIES.has(capability)) {
      fail(`路由 [${routeName}] 的 capability [${capability}] 不合法`);
    }
    if (risk && !VALID_RISK_LEVELS.has(risk)) {
      fail(`路由 [${routeName}] 的 risk [${risk}] 不合法`);
    }
    if (authenticity && !VALID_AUTHENTICITIES.has(authenticity)) {
      fail(`路由 [${routeName}] 的 authenticity [${authenticity}] 不合法`);
    }
    if ((capability === 'USER_ONLY' || capability === 'UNSUPPORTED') && (!reason || reason === '-' || reason === '无')) {
      fail(`路由 [${routeName}] 标记为 ${capability}，但未提供拒绝代办的明确原因 (reason)`);
    }
    verifiedRows++;
  }
}

if (verifiedRows < vueRouteNames.size) {
  fail(`校验的页面表格行数 (${verifiedRows}) 少于已定义的 Vue 路由总数 (${vueRouteNames.size})`);
}

console.log(`[SUCCESS] 能力矩阵校验通过！覆盖全部 ${vueRouteNames.size} 个页面路由与 ${javaToolNames.size} 个 Java 工具。`);
process.exit(0);
