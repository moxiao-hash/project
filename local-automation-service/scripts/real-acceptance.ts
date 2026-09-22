import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { PlaywrightBrowserAutomationAdapter } from '../src/browserAdapter.js';
import { NativeBridgeIdeaAutomationAdapter } from '../src/ideaAdapter.js';

async function runRealAcceptance(): Promise<void> {
  console.log('=== StudyPilot Task 33 Real Minimal Acceptance Test ===\n');

  // 1. Test Browser Actions with real Chrome Playwright adapter
  console.log('--- Testing Browser Actions (Playwright DOM) ---');

  // Start a local loopback server serving frozen routes and semantic DOM locators
  const server = http.createServer((req, res) => {
    if (req.url === '/') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(`
        <!DOCTYPE html>
        <html>
          <head><title>StudyPilot Assistant</title></head>
          <body>
            <h1>StudyPilot Assistant</h1>
            <textarea data-testid="agent-message-input" placeholder="输入学习目标"></textarea>
          </body>
        </html>
      `);
    } else if (req.url === '/assistant/health') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end('<!DOCTYPE html><html><body><h1>Assistant Health</h1></body></html>');
    } else if (req.url === '/workspaces') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(`
        <!DOCTYPE html>
        <html>
          <body>
            <h1>Workspaces</h1>
            <button data-testid="open-results-panel-trigger" onclick="document.getElementById('panel').style.display='block'">Open Results</button>
            <div id="panel" data-testid="workspace-results-panel" style="display:none;">Results Panel Content</div>
          </body>
        </html>
      `);
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Not Found');
    }
  });

  const port = 8089;
  await new Promise<void>((resolve) => server.listen(port, '127.0.0.1', () => resolve()));
  const baseUrl = `http://127.0.0.1:${port}`;
  console.log(`Local test loopback server listening on ${baseUrl}`);

  const browserAdapter = new PlaywrightBrowserAutomationAdapter({
    channel: 'chrome',
    headless: true, // Headless in headless execution environments, headed supported
    trustedLoopbackOrigin: baseUrl,
  });

  try {
    // Action 1: OPEN_STUDYPILOT_ROUTE -> ASSISTANT (/)
    const openRes1 = await browserAdapter.openRoute(`${baseUrl}/`);
    console.log(`[1] OPEN_STUDYPILOT_ROUTE (ASSISTANT -> /): ${openRes1 ? 'SUCCEEDED' : 'FAILED'} (target route verified)`);

    // Action 2: FOCUS_AGENT_INPUT -> ASSISTANT_INPUT (verifies origin, focuses, verifies document.activeElement)
    const focusRes = await browserAdapter.focusAgentInput();
    console.log(`[2] FOCUS_AGENT_INPUT (ASSISTANT_INPUT): ${focusRes ? 'SUCCEEDED' : 'FAILED'} (origin and document.activeElement verified)`);

    // Action 3: OPEN_STUDYPILOT_ROUTE -> WORKSPACE_ARTIFACTS (/workspaces)
    const openRes3 = await browserAdapter.openRoute(`${baseUrl}/workspaces`);
    console.log(`[3] OPEN_STUDYPILOT_ROUTE (WORKSPACE_ARTIFACTS -> /workspaces): ${openRes3 ? 'SUCCEEDED' : 'FAILED'} (target route verified)`);

    // Action 4: OPEN_RESULT_PANEL -> WORKSPACE_RESULTS (clicks trigger, verifies resulting visibility)
    const panelRes = await browserAdapter.openResultPanel();
    console.log(`[4] OPEN_RESULT_PANEL (WORKSPACE_RESULTS): ${panelRes ? 'SUCCEEDED' : 'FAILED'} (trigger executed and resulting visibility verified)`);
  } catch (err: unknown) {
    console.error('Browser action error:', err);
  } finally {
    await browserAdapter.close();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }

  // 2. Test IDE Actions with compliant native bridge adapter
  console.log('\n--- Testing IDE Actions (IDEA Accessibility) ---');
  const ideaAdapter = new NativeBridgeIdeaAutomationAdapter(); // Default host configuration (no compliant bridge installed)

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'studypilot-real-ide-'));
  const testFile = path.join(tmpDir, 'Main.java');
  fs.writeFileSync(testFile, 'public class Main {}', 'utf8');

  try {
    console.log(`Native IDEA bridge configured on host: ${ideaAdapter.isBridgeAvailable()}`);

    // Action 5: OPEN_REGISTERED_FILE
    const openFileRes = await ideaAdapter.openRegisteredFile(testFile);
    if (openFileRes) {
      console.log(`[5] OPEN_REGISTERED_FILE: SUCCEEDED (file open verified)`);
    } else {
      console.log(`[5] OPEN_REGISTERED_FILE: BLOCKED - ${ideaAdapter.getLastBlockerReason()} (Fails closed, no simulated success)`);
    }

    // Action 6: FOCUS_RUN_CONFIGURATION
    const focusRunRes = await ideaAdapter.focusRunConfiguration('RUN_CONFIG_DEFAULT');
    if (focusRunRes) {
      console.log(`[6] FOCUS_RUN_CONFIGURATION: SUCCEEDED (window focus verified)`);
    } else {
      console.log(`[6] FOCUS_RUN_CONFIGURATION: BLOCKED - ${ideaAdapter.getLastBlockerReason()} (Fails closed, no simulated success)`);
    }

    // Action 7: SHOW_TEST_RESULT
    const showTestRes = await ideaAdapter.showTestResult('TEST_RESULT_SUMMARY');
    if (showTestRes) {
      console.log(`[7] SHOW_TEST_RESULT: SUCCEEDED (result display verified, no tests executed)`);
    } else {
      console.log(`[7] SHOW_TEST_RESULT: BLOCKED - ${ideaAdapter.getLastBlockerReason()} (Fails closed, no simulated success)`);
    }
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }

  console.log('\n=== Real Minimal Acceptance Complete ===');
}

runRealAcceptance().catch(console.error);
