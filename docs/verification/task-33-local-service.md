# Task 33 验证证据：受控本地界面适配器服务 (TypeScript)

- **执行 Agent**：ZCode (Gemini 3.8 Flash)
- **测试等级**：`[UNIT_TEST]` / `[STATIC_VALIDATION]` / `[INTEGRATION_TEST]` / `[LIVE_ADAPTER_VERIFICATION]`
- **执行时间**：2026-09-22 17:15:00 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-33-local-adapters`
- **关联基础提交**：`9f8537115ac3d9c3bd86ddc1a43128db3a58414b` (Task 32 Codex 最终验收基线)
- **首轮提交**：`428b008170bada9c5d558bae28bf1abf1b6fc398`
- **整改轮次**：Codex 首轮独立验收退回整改（P0 虚假成功与禁用回退、P1 严格 UTC ISO-8601 校验）
- **交付状态**：**待 Codex 重新验收**

---

## 1. Codex 首轮独立审查阻断项与整改对照

| 阻断项 | Codex 审查意见 | ZCode 根因分析与整改实施 |
| :--- | :--- | :--- |
| **P0.1 生产代码禁用 Shell / osascript / AppleScript / child_process** | 契约第 6 条与指令明确禁止使用 ProcessBuilder、宿主 Shell、AppleScript、osascript 或 open 命令 | **彻底移除所有进程派生与脚本执行**：从 `src/ideaAdapter.ts` 与整个 `src/**` 彻底剔除 `node:child_process`、`execFileSync`、`osascript`、`open -a`。新增独立源门禁测试 `tests/sourceGuard.spec.ts`，正则扫描生产源码，断言严禁任何 `child_process`、`exec`、`spawn`、`osascript`、`AppleScript` 或通用键鼠 API。 |
| **P0.2 IDEA 适配器虚假成功与真实合规桥接** | 原实现仅激活前台或检索窗口标题，构成虚假成功；必须使用窄类型化的 Accessibility 桥接，无合规桥接时失败关闭，诚实记录 BLOCKED，严禁模拟成功 | **重写为 NativeBridgeIdeaAutomationAdapter**：定义狭窄类型化接口 `IdeaAccessibilityBridge`（`openFile`, `focusConfiguration`, `showResult`），生产默认宿主若未配置/注入合规本地原生无障碍桥接，三项动作全部**确定性失败关闭**（返回 `false`），并诚实回报 `BLOCKED: No compliant native IDEA accessibility bridge available on host`，杜绝任何内存或模拟伪造成功。 |
| **P0.3 浏览器会话可见性与防虚假成功** | 用户可见恢复必须使用可信可见会话；`FOCUS_AGENT_INPUT` 必须先校验 StudyPilot 注册来源；`OPEN_RESULT_PANEL` 必须先执行固定触发动作再校验面板，仅观察已有面板不构成执行 | **加固 PlaywrightBrowserAutomationAdapter**：① 默认配置支持可信前台会话；② `focusAgentInput` 执行前严格比对 `new URL(page.url()).origin === trustedOrigin`，非注册来源一律失败关闭；③ `openResultPanel` 必须先定位并触发源码固定动作 `[data-testid="open-results-panel-trigger"]`，随后再校验 `[data-testid="workspace-results-panel"]` 的可见性。新增反假成功测试 `tests/falseSuccess.spec.ts`。 |
| **P1 严格规范化 UTC ISO-8601 即时时间戳** | 契约要求 `UTC ISO-8601 instant`，原 `Date.parse` 接受本地时区偏移与非正式日期字符串；且未拒绝 `expiresAt < issuedAt` | **引入严苛正则与时间窗口防线**：定义 `UTC_ISO_8601_REGEX = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/`，强制要求末尾为 `Z`，拒绝 `+08:00`、`-05:00` 等偏移或非正式斜杠格式；`verifier.ts` 明确断言 `if (expiresAtMs < issuedAtMs) return INVALID_TIMESTAMP`；保留 60 秒寿命上限与 10 秒未来时钟漂移规则。 |

---

## 2. 生产源门禁与安全约束验证 (`tests/sourceGuard.spec.ts`)

执行命令：`npx vitest run tests/sourceGuard.spec.ts`

```text
 ✓ tests/sourceGuard.spec.ts (2 tests) 8ms
   ✓ Production Source Guards (Contract Forbidden Fallbacks) (2)
     ✓ strictly forbids child_process, spawn, exec, and shell in production src/** 7ms
     ✓ strictly forbids generic desktop control or arbitrary click/type/keyboard/mouse capabilities 1ms

 Test Files  1 passed (1)
      Tests  2 passed (2)
```
- **检查内容**：扫描 `src/` 下所有 `.ts` 文件，确认：
  1. 0 处 `child_process` / `exec` / `spawn` / `osascript` / `AppleScript` / `JXA` / `open -a` 引用；
  2. 0 处通用键鼠、任意点击、输入或对话框确认暴露。

---

## 3. TDD RED / GREEN 完整闭环证据

### 3.1 RED 阶段失败证据
在整改代码前，先针对缺陷编写失败测试：
1. **P1 严格时间戳测试** (`tests/protocol.spec.ts` & `tests/verifier.spec.ts`)：
   ```text
   FAIL tests/protocol.spec.ts > rejects non-UTC timestamps, timezone offsets, and informal date formats
   AssertionError: expected true to be false
   FAIL tests/verifier.spec.ts > rejects expiresAt earlier than issuedAt
   AssertionError: expected 'EXPIRED_REQUEST' to be 'INVALID_TIMESTAMP'
   ```
2. **生产源门禁测试** (`tests/sourceGuard.spec.ts`)：
   ```text
   FAIL tests/sourceGuard.spec.ts > strictly forbids child_process, spawn, exec, and shell in production src/**
   AssertionError: expected 15 violations to deeply equal [] (caught child_process, osascript in ideaAdapter.ts)
   ```
3. **反假成功防御测试** (`tests/falseSuccess.spec.ts`)：
   ```text
   FAIL tests/falseSuccess.spec.ts > Browser Adapter > rejects FOCUS_AGENT_INPUT when page is not on registered StudyPilot origin
   FAIL tests/falseSuccess.spec.ts > Browser Adapter > requires OPEN_RESULT_PANEL to trigger fixed open action rather than merely observing
   FAIL tests/falseSuccess.spec.ts > IDEA Adapter > fails closed when no compliant native bridge is configured on host
   ```

### 3.2 GREEN 阶段全量测试通过证据
执行命令：`cd local-automation-service && npm test`

```text
 RUN  v5.0.1 /Users/moxiao/IdeaProjects/project-zcode-task-33/local-automation-service

 ✓ tests/sourceGuard.spec.ts (2 tests) 8ms
 ✓ tests/protocol.spec.ts (10 tests) 8ms
 ✓ tests/nonceStore.spec.ts (4 tests) 15ms
 ✓ tests/actionRegistry.spec.ts (6 tests) 18ms
 ✓ tests/verifier.spec.ts (8 tests) 5ms
 ✓ tests/vectors.spec.ts (3 tests) 4ms
 ✓ tests/canonical.spec.ts (2 tests) 7ms
 ✓ tests/falseSuccess.spec.ts (4 tests) 6ms
 ✓ tests/service.spec.ts (4 tests) 25ms
 ✓ tests/server.spec.ts (4 tests) 28ms
 ✓ tests/adapters.spec.ts (4 tests) 57ms

 Test Files  11 passed (11)
      Tests  51 passed (51)
   Start at  08:55:32
   Duration  566ms
```

### 3.3 TypeScript 严格编译构建
执行命令：`cd local-automation-service && npm run typecheck && npm run build`
```text
> local-automation-service@1.0.0 typecheck
> tsc --noEmit

> local-automation-service@1.0.0 build
> tsc
```
- `tsc --noEmit`：0 errors, 0 warnings
- `tsc`：成功输出 `dist/`，零语法或类型错误。

---

## 4. 真实本机最小验收实测 (`scripts/real-acceptance.ts`)

执行命令：`cd local-automation-service && npx tsx scripts/real-acceptance.ts`

```text
=== StudyPilot Task 33 Real Minimal Acceptance Test ===

--- Testing Browser Actions (Playwright DOM) ---
Local test loopback server listening on http://127.0.0.1:8089
[1] OPEN_STUDYPILOT_ROUTE (ASSISTANT -> /): SUCCEEDED (target route verified)
[2] FOCUS_AGENT_INPUT (ASSISTANT_INPUT): SUCCEEDED (origin and document.activeElement verified)
[3] OPEN_STUDYPILOT_ROUTE (WORKSPACE_ARTIFACTS -> /workspaces): SUCCEEDED (target route verified)
[4] OPEN_RESULT_PANEL (WORKSPACE_RESULTS): SUCCEEDED (trigger executed and resulting visibility verified)

--- Testing IDE Actions (IDEA Accessibility) ---
Native IDEA bridge configured on host: false
[5] OPEN_REGISTERED_FILE: BLOCKED - BLOCKED: No compliant native IDEA accessibility bridge available on host (Fails closed, no simulated success)
[6] FOCUS_RUN_CONFIGURATION: BLOCKED - BLOCKED: No compliant native IDEA accessibility bridge available on host (Fails closed, no simulated success)
[7] SHOW_TEST_RESULT: BLOCKED - BLOCKED: No compliant native IDEA accessibility bridge available on host (Fails closed, no simulated success)

=== Real Minimal Acceptance Complete ===
```

### 4.1 动作状态与真实前置条件诚实审计
1. **浏览器通道**：
   - 本机具备真实 Google Chrome (`v153.0.8010.53`) 与 `playwright-core`；
   - 真实启动回环测试服务并派生 Chrome 会话；
   - `OPEN_STUDYPILOT_ROUTE`：真实加载页面并比对 `page.url()` pathname，验证 `SUCCEEDED`；
   - `FOCUS_AGENT_INPUT`：先校验当前页处于已登记回环来源，再执行聚焦，DOM 内比对 `document.activeElement === input`，验证 `SUCCEEDED`；
   - `OPEN_RESULT_PANEL`：先点击源码预埋按钮 `[data-testid="open-results-panel-trigger"]`，随后检测面板节点真实变为可见，验证 `SUCCEEDED`。
2. **IDE 通道**：
   - 本机未部署专用的无进程派生原生无障碍扩展，且契约严格禁止使用 `child_process` / `osascript` / `AppleScript` / `open` 等外挂工具；
   - 本地服务严格**失败关闭**，回报 `BLOCKED: No compliant native IDEA accessibility bridge available on host`；
   - **绝无任何虚假或模拟的 `SUCCEEDED`**，保持最高度客观真实性。

---

## 5. 跨端测试向量（保持不变）

- 32 字节密钥：`0123456789abcdef0123456789abcdef`
- 规范化载荷：`1#136#c28d22db-363d-429a-8c85-618d3632cf4b64#e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85514#PLAYWRIGHT_DOM21#OPEN_STUDYPILOT_ROUTE9#ASSISTANT20#2026-09-22T08:00:00Z20#2026-09-22T08:01:00Z26#dGVzdC1ub25jZS0xMjgtYml0cw`
- 期望 HMAC-SHA256 签名：`e471c52c3d1ee78f52a0178f1ec2e0e09eac84a1354ce45141569336c7fdc943`
- 期望浏览器 `targetDigest`：`d30b1c64274f91e571851b4d15914d7c8f7cb917c0689090be571279bbd9f0eb`
- 期望 IDE `targetDigest`：`362f3a4798eb4b7fc13e2f5a6f2ea3fe76f499bfa5f6cbba50d7a6e191986420`
