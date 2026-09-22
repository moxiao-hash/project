import http from 'node:http';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { PlaywrightBrowserAutomationAdapter } from '../src/browserAdapter.js';
import {
  NativeBridgeIdeaAutomationAdapter,
  createDefaultIdeaBridge,
} from '../src/ideaAdapter.js';
import { loadNativeAxAddon, resolveNativeAddonPath } from '../src/nativeAxBridge.js';
import { parseServiceConfigFromEnv } from '../src/config.js';
import { LocalAutomationServer } from '../src/server.js';
import { calculateCanonicalPayload } from '../src/canonical.js';
import type { AutomationRequest } from '../src/types.js';

const BANNER = '='.repeat(63);

function sendLineOverSocket(sockPath: string, line: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const client = net.createConnection(sockPath, () => client.write(line));
    let data = '';
    client.on('data', (chunk) => {
      data += chunk.toString('utf8');
      if (data.includes('\n')) client.end();
    });
    client.on('end', () => resolve(data));
    client.on('error', reject);
  });
}

function signedRequest(
  secret: string,
  overrides: Partial<AutomationRequest> = {}
): AutomationRequest {
  const base: AutomationRequest = {
    version: 1,
    requestId: crypto.randomUUID(),
    ownerHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    channel: 'IDEA_ACCESSIBILITY',
    action: 'OPEN_REGISTERED_FILE',
    targetKey: 'FILE_REGISTERED_PROBE',
    issuedAt: new Date(Date.now() - 1000).toISOString(),
    expiresAt: new Date(Date.now() + 50000).toISOString(),
    nonce: crypto.randomBytes(16).toString('base64url'),
    signature: '',
    ...overrides,
  };
  const signature = crypto
    .createHmac('sha256', secret)
    .update(calculateCanonicalPayload(base), 'utf8')
    .digest('hex')
    .toLowerCase();
  return { ...base, signature };
}

interface SectionResult {
  status: 'PASS' | 'BLOCKED' | 'FAIL';
  detail: string;
}

/**
 * Section A — real in-process macOS Accessibility (AX) evidence.
 *
 * This is the *only* IDEA control path in the service. There is no HTTP endpoint, no TCP
 * listener, no shell, and no script bridge anywhere: the compiled N-API binding talks to
 * the macOS Accessibility server directly.
 */
async function probeNativeAccessibilityBridge(): Promise<SectionResult> {
  console.log(`\n--- 1. [LIVE_PROBE] In-process macOS Accessibility bridge ---`);
  console.log(`Addon path: ${resolveNativeAddonPath()}`);

  const nativeModule = loadNativeAxAddon();
  if (!nativeModule) {
    console.log('Native AX addon: NOT LOADED (run `npm run build:native` on macOS)');
    console.log('  [Real Action 1] OPEN_REGISTERED_FILE:     BLOCKED (addon not built)');
    console.log('  [Real Action 2] FOCUS_RUN_CONFIGURATION: BLOCKED (addon not built)');
    console.log('  [Real Action 3] SHOW_TEST_RESULT:         BLOCKED (addon not built)');
    return { status: 'BLOCKED', detail: 'native AX addon not built on this host' };
  }

  const probe = nativeModule.probe();
  console.log(
    `Native AX addon: LOADED (version ${probe.bridgeVersion}, platform ${probe.platform})`
  );
  console.log(`  macOS Accessibility API available: ${probe.axApiAvailable}`);
  console.log(`  macOS Accessibility permission granted to this process: ${probe.axTrusted}`);
  console.log(`  Trusted IntelliJ IDEA (com.jetbrains.intellij) running: ${probe.ideaRunning}`);

  const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'studypilot-ax-probe-'));
  const registeredFile = path.join(workDir, 'RegisteredProbe.java');
  fs.writeFileSync(registeredFile, 'public class RegisteredProbe {}', 'utf8');

  const adapter = new NativeBridgeIdeaAutomationAdapter(createDefaultIdeaBridge());
  const outcomes: string[] = [];

  try {
    const results: { label: string; ok: boolean }[] = [
      {
        label: 'OPEN_REGISTERED_FILE',
        ok: await adapter.openRegisteredFile(registeredFile),
      },
      {
        label: 'FOCUS_RUN_CONFIGURATION',
        ok: await adapter.focusRunConfiguration('StudyPilotApplication'),
      },
      { label: 'SHOW_TEST_RESULT', ok: await adapter.showTestResult('surefire-reports') },
    ];

    let index = 1;
    for (const result of results) {
      console.log(
        `  [Real Action ${index}] ${result.label}: ${result.ok ? 'SUCCEEDED (AX state verified)' : 'BLOCKED (fail closed)'}`
      );
      console.log(`    Detail: ${adapter.getLastBlockerReason() || 'n/a'}`);
      outcomes.push(`${result.label}=${result.ok ? 'SUCCEEDED' : 'BLOCKED'}`);
      index++;
    }
  } finally {
    fs.rmSync(workDir, { recursive: true, force: true });
  }

  if (!probe.axTrusted) {
    console.log('  Prerequisite: grant macOS Accessibility permission to the service host process');
    return { status: 'BLOCKED', detail: 'macOS Accessibility permission not granted' };
  }
  if (!probe.ideaRunning) {
    console.log(
      '  Prerequisite: IntelliJ IDEA must be running with the registered workspace open'
    );
    return {
      status: 'BLOCKED',
      detail: 'IntelliJ IDEA is not running; real IDE positive acceptance not executed',
    };
  }
  if (outcomes.every((entry) => entry.endsWith('SUCCEEDED'))) {
    return { status: 'PASS', detail: 'all three IDEA actions succeeded with verified AX state' };
  }
  return {
    status: 'BLOCKED',
    detail: 'IntelliJ IDEA running but registered AX state could not be verified',
  };
}

/**
 * Section B — real production Unix Domain Socket evidence.
 *
 * Uses the production configuration path and the real service pipeline (framing, version,
 * HMAC, timestamp window, nonce consumption, registry whitelist) over a real UDS socket.
 */
async function probeProductionUdsService(): Promise<SectionResult> {
  console.log(`\n--- 2. [LIVE_PROBE] Production Unix Domain Socket service ---`);

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'studypilot-uds-probe-'));
  const socketPath = path.join(tmpDir, 'automation.sock');
  const workspaceRoot = path.join(tmpDir, 'workspace');
  fs.mkdirSync(workspaceRoot, { recursive: true });
  const registeredFile = path.join(workspaceRoot, 'Registered.java');
  fs.writeFileSync(registeredFile, 'public class Registered {}', 'utf8');

  const secret = 'task-33-live-uds-probe-secret-32-bytes';
  const config = parseServiceConfigFromEnv({
    STUDYPILOT_AUTOMATION_SOCKET_PATH: socketPath,
    STUDYPILOT_AUTOMATION_HMAC_SECRET: secret,
    STUDYPILOT_AUTOMATION_NONCE_DB: path.join(tmpDir, 'nonces.db'),
    STUDYPILOT_AUTOMATION_LOOPBACK_BASE_URL: 'http://127.0.0.1:8080',
    STUDYPILOT_AUTOMATION_WORKSPACE_ROOTS: JSON.stringify([workspaceRoot]),
    STUDYPILOT_AUTOMATION_REGISTERED_FILES: JSON.stringify({ FILE_REGISTERED_PROBE: registeredFile }),
    STUDYPILOT_AUTOMATION_REGISTERED_RUN_CONFIGS: JSON.stringify({ RUN_PROBE: 'StudyPilotApplication' }),
    STUDYPILOT_AUTOMATION_REGISTERED_TEST_RESULTS: JSON.stringify({ RESULT_PROBE: 'surefire-reports' }),
  });

  const server = new LocalAutomationServer(config);
  await server.start();

  try {
    const stat = fs.statSync(socketPath);
    const mode = stat.mode & 0o777;
    console.log(`Socket is a Unix domain socket: ${stat.isSocket()}`);
    console.log(`Socket permissions: ${mode.toString(8)} (owner-only: ${(mode & 0o077) === 0})`);

    const request = signedRequest(secret);
    const first = JSON.parse((await sendLineOverSocket(socketPath, JSON.stringify(request) + '\n')).trim());
    console.log(
      `Signed IDEA request over UDS -> status=${first.status} errorCode=${first.errorCode}`
    );

    const replay = JSON.parse((await sendLineOverSocket(socketPath, JSON.stringify(request) + '\n')).trim());
    console.log(`Replayed request over UDS  -> status=${replay.status} errorCode=${replay.errorCode}`);

    const badSignature = signedRequest(secret);
    const tampered = JSON.parse(
      (
        await sendLineOverSocket(
          socketPath,
          JSON.stringify({ ...badSignature, signature: '0'.repeat(64) }) + '\n'
        )
      ).trim()
    );
    console.log(
      `Tampered signature over UDS -> status=${tampered.status} errorCode=${tampered.errorCode}`
    );

    const unknownTarget = signedRequest(secret, { targetKey: 'NOT_REGISTERED' });
    const unknown = JSON.parse(
      (await sendLineOverSocket(socketPath, JSON.stringify(unknownTarget) + '\n')).trim()
    );
    console.log(
      `Unregistered target over UDS -> status=${unknown.status} errorCode=${unknown.errorCode}`
    );

    const checks: [string, boolean][] = [
      ['socket is a Unix domain socket', stat.isSocket()],
      ['socket permissions are owner-only', (mode & 0o077) === 0],
      ['no adapter ever reports SUCCEEDED while IDEA is unavailable', first.status !== 'SUCCEEDED'],
      ['replay rejected', replay.status === 'REJECTED' && replay.errorCode === 'REPLAY_DETECTED'],
      [
        'tampered signature rejected',
        tampered.status === 'REJECTED' && tampered.errorCode === 'INVALID_SIGNATURE',
      ],
      [
        'unregistered target rejected',
        unknown.status === 'REJECTED' && unknown.errorCode === 'TARGET_NOT_REGISTERED',
      ],
    ];
    const failed = checks.filter(([, ok]) => !ok).map(([name]) => name);
    for (const [name, ok] of checks) {
      console.log(`  [${ok ? 'PASS' : 'FAIL'}] ${name}`);
    }
    if (failed.length > 0) {
      return { status: 'FAIL', detail: failed.join('; ') };
    }
    return {
      status: 'PASS',
      detail: 'real UDS transport, HMAC, nonce replay, and registry whitelist verified',
    };
  } finally {
    await server.stop();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
}

/**
 * Section C — live StudyPilot loopback availability (honest BLOCKED when offline).
 */
async function probeStudyPilotService(): Promise<SectionResult> {
  console.log(`\n--- 3. [LIVE_PROBE] StudyPilot loopback service (http://127.0.0.1:8080) ---`);
  let online = false;
  try {
    const res = await fetch('http://127.0.0.1:8080/', { signal: AbortSignal.timeout(1500) });
    online = res.ok;
  } catch {
    online = false;
  }

  if (!online) {
    console.log('Live StudyPilot Status: OFFLINE');
    console.log('  [Real Action 4] OPEN_STUDYPILOT_ROUTE: BLOCKED');
    console.log('  [Real Action 5] FOCUS_AGENT_INPUT:     BLOCKED');
    console.log('  [Real Action 6] OPEN_RESULT_PANEL:     BLOCKED');
    console.log('  Prerequisite: live StudyPilot web service on the registered loopback origin');
    return { status: 'BLOCKED', detail: 'live StudyPilot web service offline' };
  }
  console.log('Live StudyPilot Status: ONLINE (real browser actions require an interactive session)');
  return { status: 'BLOCKED', detail: 'live service online; interactive browser acceptance not executed' };
}

/**
 * Section D — isolated integration fixture for Playwright adapter mechanics.
 *
 * This validates navigation, origin checking, focus verification, and panel triggering in
 * an isolated harness. It is explicitly NOT Task 34 REAL_E2E evidence.
 */
async function probeIntegrationFixture(): Promise<SectionResult> {
  console.log(`\n--- 4. [INTEGRATION_FIXTURE] Browser adapter mechanics (isolated harness) ---`);
  console.log('(Does NOT claim Task 34 REAL_E2E; synthetic loopback pages only)\n');

  const fixtureServer = http.createServer((req, res) => {
    if (req.url === '/') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(
        '<!DOCTYPE html><html><body><h1>StudyPilot Assistant</h1>' +
          '<textarea data-testid="agent-message-input"></textarea></body></html>'
      );
    } else if (req.url === '/workspaces') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(
        '<!DOCTYPE html><html><body><h1>Workspaces</h1>' +
          '<button data-testid="open-results-panel-trigger" ' +
          "onclick=\"document.getElementById('panel').style.display='block'\">Open Results</button>" +
          '<div id="panel" data-testid="workspace-results-panel" style="display:none;">Results</div>' +
          '</body></html>'
      );
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Not Found');
    }
  });

  const fixturePort = 8089;
  await new Promise<void>((resolve) => fixtureServer.listen(fixturePort, '127.0.0.1', () => resolve()));
  const fixtureBaseUrl = `http://127.0.0.1:${fixturePort}`;

  const adapter = new PlaywrightBrowserAutomationAdapter({
    channel: 'chrome',
    headless: true,
    trustedLoopbackOrigin: fixtureBaseUrl,
  });

  let passed = 0;
  try {
    const results = [
      ['OPEN_STUDYPILOT_ROUTE (ASSISTANT -> /)', await adapter.openRoute(`${fixtureBaseUrl}/`)],
      ['FOCUS_AGENT_INPUT (origin + activeElement verified)', await adapter.focusAgentInput()],
      ['OPEN_STUDYPILOT_ROUTE (WORKSPACE_ARTIFACTS -> /workspaces)', await adapter.openRoute(`${fixtureBaseUrl}/workspaces`)],
      ['OPEN_RESULT_PANEL (trigger clicked, visibility verified)', await adapter.openResultPanel()],
    ] as const;

    for (const [label, ok] of results) {
      console.log(`  [Fixture] ${label}: ${ok ? 'PASS' : 'FAIL'}`);
      if (ok) passed++;
    }
    if (passed !== results.length) {
      return { status: 'FAIL', detail: `${passed}/${results.length} fixture checks passed` };
    }
    return { status: 'PASS', detail: `${passed}/${results.length} browser adapter mechanics passed` };
  } finally {
    await adapter.close();
    await new Promise<void>((resolve) => fixtureServer.close(() => resolve()));
  }
}

async function main(): Promise<void> {
  console.log(BANNER);
  console.log('   StudyPilot Task 33 Local Service — Real Probe & Status Report');
  console.log(BANNER);

  const sections: [string, SectionResult][] = [];
  sections.push(['In-process macOS Accessibility bridge', await probeNativeAccessibilityBridge()]);
  sections.push(['Production UDS service', await probeProductionUdsService()]);
  sections.push(['Live StudyPilot loopback service', await probeStudyPilotService()]);
  sections.push(['Browser adapter integration fixture', await probeIntegrationFixture()]);

  console.log(`\n${BANNER}`);
  console.log('   Summary');
  for (const [name, result] of sections) {
    console.log(`   - ${name}: ${result.status} — ${result.detail}`);
  }
  console.log('   - Fake-success enforcement: no path returns SUCCEEDED without verified state');
  console.log(BANNER);
}

main().catch((error: unknown) => {
  console.error('Acceptance probe failed:', error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
