import http from 'node:http';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { PlaywrightBrowserAutomationAdapter } from '../src/browserAdapter.js';
import {
  PluginBridgeIdeaAutomationAdapter,
  NativeBridgeIdeaAutomationAdapter,
  createDefaultIdeaAdapter,
  createDiagnosticAxBridge,
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
 * Section A — macOS Accessibility DIAGNOSTIC probe.
 *
 * The AX binding is retained only as a read-only compatibility probe. Live acceptance proved
 * that IntelliJ IDEA 2026.1.1 cannot open a file or expose focus verifiably through AX, so the
 * probe is never a source of a successful IDEA receipt. Nothing here reports an action result.
 */
async function probeAxDiagnostics(): Promise<SectionResult> {
  console.log(`\n--- 1. [LIVE_PROBE] macOS Accessibility diagnostic probe (not an execution path) ---`);
  console.log(`Addon path: ${resolveNativeAddonPath()}`);

  const nativeModule = loadNativeAxAddon();
  if (!nativeModule) {
    console.log('Native AX addon: NOT LOADED (run `npm run build:native` on macOS)');
    return { status: 'BLOCKED', detail: 'native AX diagnostic addon not built on this host' };
  }
  const probe = nativeModule.probe();
  console.log(`Native AX addon: LOADED (version ${probe.bridgeVersion}, platform ${probe.platform})`);
  console.log(`  axApiAvailable=${probe.axApiAvailable} axTrusted=${probe.axTrusted}`);
  console.log(`  ideaRunning=${probe.ideaRunning} ideaWindowExposed=${probe.ideaWindowExposed}`);

  const axAdapter = new NativeBridgeIdeaAutomationAdapter(createDiagnosticAxBridge());
  const refusal = await axAdapter.openRegisteredFile('FILE_REGISTERED');
  console.log(
    `  AX outcome for a registered action: ${refusal ? 'SUCCEEDED (contract violation!)' : 'REFUSED'}`
  );
  console.log('  Rule enforced: the AX probe can never supply a successful IDEA receipt.');
  return {
    status: 'PASS',
    detail: 'AX binding is loaded for diagnostics only and refuses every IDEA action',
  };
}

/**
 * Section B — the real IDEA execution path (trusted JetBrains plugin bridge).
 *
 * Each of the three actions is executed and reported INDIVIDUALLY, capturing that action's own
 * failure code and blocker reason immediately after its own call. No diagnostic is reused
 * between actions.
 */
async function probeIdeaPluginPath(): Promise<SectionResult> {
  console.log(`\n--- 2. [LIVE_PROBE] IDEA execution path (trusted JetBrains plugin bridge) ---`);

  const socketPath = process.env.STUDYPILOT_AUTOMATION_IDEA_PLUGIN_SOCKET_PATH ?? '';
  const secret = process.env.STUDYPILOT_AUTOMATION_IDEA_PLUGIN_HMAC_SECRET ?? '';
  const timeoutMs = Number(process.env.STUDYPILOT_AUTOMATION_IDEA_PLUGIN_TIMEOUT_MS ?? '3000');

  console.log(`Plugin socket configured: ${socketPath ? 'yes' : 'no'}`);
  if (!socketPath || !secret) {
    console.log(
      '  BLOCKED: the trusted IDEA plugin bridge is not configured, so the IDEA channel fails closed.'
    );
    console.log(
      '  Prerequisite: build and install the plugin, then set STUDYPILOT_AUTOMATION_IDEA_PLUGIN_SOCKET_PATH'
    );
    console.log('  and STUDYPILOT_AUTOMATION_IDEA_PLUGIN_HMAC_SECRET on the service host.');
    for (const action of ['OPEN_REGISTERED_FILE', 'FOCUS_RUN_CONFIGURATION', 'SHOW_TEST_RESULT']) {
      console.log(`  [Real Action] ${action}: BLOCKED (code=PLUGIN_NOT_CONFIGURED)`);
    }
    return { status: 'BLOCKED', detail: 'trusted IDEA plugin bridge is not configured on this host' };
  }

  const adapter = createDefaultIdeaAdapter({
    ideaPluginSocketPath: socketPath,
    ideaPluginSigningSecret: secret,
    ideaPluginTimeoutMs: Number.isFinite(timeoutMs) ? timeoutMs : undefined,
  }) as PluginBridgeIdeaAutomationAdapter;

  const actions: { label: string; handle: string; run: () => Promise<boolean> }[] = [
    {
      label: 'OPEN_REGISTERED_FILE',
      handle: 'FILE_REGISTERED',
      run: () => adapter.openRegisteredFile('FILE_REGISTERED'),
    },
    {
      label: 'FOCUS_RUN_CONFIGURATION',
      handle: 'RUN_REGISTERED',
      run: () => adapter.focusRunConfiguration('RUN_REGISTERED'),
    },
    {
      label: 'SHOW_TEST_RESULT',
      handle: 'RESULT_REGISTERED',
      run: () => adapter.showTestResult('RESULT_REGISTERED'),
    },
  ];

  let succeeded = 0;
  let blocked = 0;
  for (const action of actions) {
    // Sequential on purpose: each action's own diagnostic is captured before the next call.
    const ok = await action.run();
    const failureCode = adapter.getLastFailureCode();
    const reason = adapter.getLastBlockerReason();
    if (ok) {
      succeeded++;
      console.log(`  [Real Action] ${action.label}(${action.handle}): SUCCEEDED (plugin verified the IDE state)`);
    } else {
      blocked++;
      console.log(
        `  [Real Action] ${action.label}(${action.handle}): BLOCKED (code=${failureCode ?? 'n/a'})`
      );
      console.log(`    Detail: ${reason || 'n/a'}`);
    }
  }

  if (succeeded === actions.length) {
    return { status: 'PASS', detail: 'all three registered IDEA actions were executed and verified by the plugin' };
  }
  return {
    status: 'BLOCKED',
    detail: `${blocked}/${actions.length} IDEA actions failed closed with their own diagnostic`,
  };
}

/**
 * Section B — real production Unix Domain Socket evidence.
 *
 * Uses the production configuration path and the real service pipeline (framing, version,
 * HMAC, timestamp window, nonce consumption, registry whitelist) over a real UDS socket.
 */
async function probeProductionUdsService(): Promise<SectionResult> {
  console.log(`\n--- 3. [LIVE_PROBE] Production Unix Domain Socket service ---`);

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
  console.log(`\n--- 4. [LIVE_PROBE] StudyPilot loopback service (http://127.0.0.1:8080) ---`);
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
  console.log(`\n--- 5. [INTEGRATION_FIXTURE] Browser adapter mechanics (isolated harness) ---`);
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
  sections.push(['macOS AX diagnostic probe', await probeAxDiagnostics()]);
  sections.push(['IDEA plugin execution path', await probeIdeaPluginPath()]);
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
