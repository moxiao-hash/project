/**
 * Task 33 真实握手服务端启动器（Java 侧测试支撑，不修改对端任何文件）。
 *
 * 作用：加载 ZCode 已交付的 `local-automation-service`（编译产物），注入**测试适配器**，
 * 在真实 Unix Domain Socket 上运行真实服务，供 Java 端 `UnixSocketLocalAutomationClient`
 * 做真实跨语言握手。
 *
 * 注意：**这不是真实浏览器/IDE 界面验收**。适配器是注入的测试替身，只验证传输、签名、
 * 白名单、nonce、回执关联与封帧的一致性，不产生也不观察任何 OS 界面状态。
 *
 * 用法（环境变量）：
 *   LOCAL_AUTOMATION_SERVICE_DIST   对端编译产物目录（dist，必填）
 *   HANDSHAKE_SECRET                至少 32 字节的签名密钥（必填）
 *   HANDSHAKE_SOCKET                Unix Domain Socket 路径（必填，需短路径）
 *   HANDSHAKE_NONCE_DB              nonce 持久化文件（必填）
 *   HANDSHAKE_BASE_URL              受信回环基础地址（默认 http://127.0.0.1:8099）
 *   HANDSHAKE_FAIL_TARGETS          逗号分隔的目标键，其适配器返回 false 以走 FAILED 分支
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const distDir = process.env.LOCAL_AUTOMATION_SERVICE_DIST;
const secret = process.env.HANDSHAKE_SECRET;
const socketPath = process.env.HANDSHAKE_SOCKET;
const nonceDbPath = process.env.HANDSHAKE_NONCE_DB;
const baseUrl = process.env.HANDSHAKE_BASE_URL || 'http://127.0.0.1:8099';
const failTargets = new Set(
  (process.env.HANDSHAKE_FAIL_TARGETS || '').split(',').map((v) => v.trim()).filter(Boolean),
);

for (const [name, value] of Object.entries({
  LOCAL_AUTOMATION_SERVICE_DIST: distDir,
  HANDSHAKE_SECRET: secret,
  HANDSHAKE_SOCKET: socketPath,
  HANDSHAKE_NONCE_DB: nonceDbPath,
})) {
  if (!value) {
    console.error(`缺少必需环境变量: ${name}`);
    process.exit(2);
  }
}

const { LocalAutomationServer } = await import(`${distDir}/server.js`);

// 注入测试适配器：只记录调用并返回可验证的布尔结果，不触碰任何真实界面。
const calls = [];
const browserAdapter = {
  async openRoute(route) {
    calls.push({ adapter: 'browser', method: 'openRoute', route });
    return !failTargets.has('ASSISTANT_ROUTE');
  },
  async focusAgentInput() {
    calls.push({ adapter: 'browser', method: 'focusAgentInput' });
    return !failTargets.has('ASSISTANT_INPUT');
  },
  async openResultPanel() {
    calls.push({ adapter: 'browser', method: 'openResultPanel' });
    return !failTargets.has('WORKSPACE_RESULTS');
  },
};
const ideaAdapter = {
  async openRegisteredFile(filePath) {
    calls.push({ adapter: 'idea', method: 'openRegisteredFile', filePath });
    return !failTargets.has('SOURCE_PRIMARY');
  },
  async focusRunConfiguration(handle) {
    calls.push({ adapter: 'idea', method: 'focusRunConfiguration', handle });
    return !failTargets.has('RUN_DEFAULT');
  },
  async showTestResult(handle) {
    calls.push({ adapter: 'idea', method: 'showTestResult', handle });
    return !failTargets.has('TEST_LATEST');
  },
};

const workspaceRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'spl33-ws-'));
const sourceFile = path.join(workspaceRoot, 'Prima.java');
fs.writeFileSync(sourceFile, 'public class Prima {}\n', 'utf8');

const config = {
  signingSecret: secret,
  socketPath,
  nonceDbPath,
  loopbackBaseUrl: baseUrl,
  workspaceRoots: [workspaceRoot],
  registeredFiles: { SOURCE_PRIMARY: sourceFile },
  registeredRunConfigs: { RUN_DEFAULT: 'RUN_DEFAULT' },
  registeredTestResults: { TEST_LATEST: 'TEST_LATEST' },
};

const server = new LocalAutomationServer(config, browserAdapter, ideaAdapter);
await server.start();

console.log(`HANDSHAKE_READY ${socketPath}`);
console.log(`HANDSHAKE_WORKSPACE ${workspaceRoot}`);

const shutdown = () => {
  console.log(`HANDSHAKE_ADAPTER_CALLS ${JSON.stringify(calls)}`);
  try {
    server.stop();
  } finally {
    process.exit(0);
  }
};
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
setInterval(() => {}, 1000);
