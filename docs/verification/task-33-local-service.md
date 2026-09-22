# Task 33 验证证据：受控本地界面适配器服务 (TypeScript)

- **执行 Agent**：MiniMax Code (Mavis) 临时接管（ZCode 配额阻断；Codex 仍为架构与最终验收负责人）
- **测试等级**：`[UNIT_TEST]` / `[STATIC_VALIDATION]` / `[INTEGRATION_TEST]` / `[INTEGRATION_FIXTURE]` / `[LIVE_PROBE]`
- **执行时间**：2026-09-22 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-33-local-adapters`
- **关联基础提交**：`9f8537115ac3d9c3bd86ddc1a43128db3a58414b`（Task 32 Codex 最终验收基线）
- **前次整改提交**：`50dc65003c2bb0234cf7b561c28c863a34a81b29`
- **交付状态**：**BLOCKED — 真实 IDEA 正向验收未执行**（P0/P1 代码缺陷已按契约实际整改并具备静态/单元/集成/真实 UDS 证据；缺少真实运行的 IntelliJ IDEA 环境，3 项 IDE 正向动作未取得真实成功证据）

---

## 1. P0 缺陷整改：移除虚构的 127.0.0.1:63342 HTTP 控制通道

### 1.1 原缺陷

前次整改提交 `50dc650` 新增 `DirectLocalIdeaBridge`，其行为违反冻结契约：

1. 虚构了 IntelliJ IDEA 的“本地 API”端点 `http://127.0.0.1:63342`——端口 63342 不是可用的 Accessibility 控制 API；
2. 只要 HTTP 响应为 2xx 即返回 `{ success: true, verified: true }`，把网络可达误判为界面状态已验证；
3. 引入了 TCP/HTTP 控制路径，而冻结契约第 2 节明确要求本地服务“不开放 TCP/HTTP 端口”，第 6 节禁止任何 Shell/AppleScript/非受控回退。

### 1.2 整改结果：彻底删除，改为进程内真实 macOS Accessibility 集成

| 项目 | 整改前 | 整改后 |
| :--- | :--- | :--- |
| IDEA 控制通道 | `fetch('http://127.0.0.1:63342/api/...')` | 进程内 N-API Objective-C++ 附加模块，直接调用 ApplicationServices AX API |
| 成功判定 | HTTP 2xx 即 `verified: true` | `ok`（动作已投递）与 `verified`（已观测到目标 AX 状态）分离，二者同时为真才可能 `SUCCEEDED` |
| 网络面 | 出站 TCP 到 63342 | 零网络调用；`src/**` 已无 `fetch(`、`node:http`、`node:https`、URL 字面量 |
| 失败语义 | 服务离线时返回 HTTP 错误信息 | 返回稳定错误码 `IDEA_NOT_RUNNING` / `AX_NOT_TRUSTED` / `AX_API_DISABLED` / `TARGET_NOT_FOUND` / `STATE_NOT_VERIFIED` / `ACTION_NOT_DISPATCHED` |

已删除并锁死：`DirectLocalIdeaBridge`、`DirectIdeaBridgeConfig`、`ServiceConfig.ideaLocalApiBaseUrl`，以及生产源码中任何 `63342` / `http(s)://` / `127.0.0.1` 引用。

---

## 2. 真实、窄类型的 macOS Accessibility 集成（`native/idea_ax_bridge.mm`）

### 2.1 性质

- **真实原生绑定**：Objective-C++ N-API 模块，直接链接 `ApplicationServices` / `AppKit` / `Foundation`，通过 `AXUIElementCopyAttributeValue`、`AXUIElementPerformAction`、`AXUIElementSetAttributeValue`、`AXIsProcessTrusted` 等 AX API 与 macOS 辅助功能服务器通信。
- **无任何被禁通道**：不使用 shell、`child_process`、`osascript`、AppleScript、JXA、`open` 命令、`NSTask`、`NSWorkspace openURL`、`system()`、`popen()`、`fork()`、`CGEventCreate*`（通用键鼠模拟）或 `AXUIElementCreateSystemWide`（全系统通用元素）。
- **进程内**：不监听任何网络套接字，不产生子进程。
- **应用定位唯一来源**：受信在源码内写死的 bundle identifier `com.jetbrains.intellij`，经 `NSRunningApplication` 解析 PID。不接受请求方提供的进程 ID、窗口标题、路径或选择器。

### 2.2 唯一暴露的四个导出

`probe()`、`openRegisteredFile(realFilePath)`、`focusRunConfiguration(configHandle)`、`showTestResult(resultHandle)`。

导出面由 `src/nativeAxBridge.ts::validateNativeAxModuleSurface` 结构校验：四个导出必须全部存在且为函数，且不得存在任何额外导出；否则模块不可采用（失败关闭）。测试已覆盖 `clickAt` / `typeText` / `pressKey` / `runShell` / `openPath` / `osascript` 等被夹带能力的拒绝。

### 2.3 三个动作及真实 AX 状态核验

| 动作 | 输入来源 | 执行的唯一注册动作 | 返回成功前必须观测到的 AX 状态 |
| :--- | :--- | :--- | :--- |
| `OPEN_REGISTERED_FILE` | 注册表解析后的真实、位于登记工作区内、无符号链接的常规文件绝对路径 | 对标题等于该文件名的 AX 元素执行一次 `AXPress`（失败时先 `AXSelected=true` 再按父行） | 存在被选中/聚焦且标题或值匹配该文件名的可见元素，或当前聚焦元素的 `AXDocument`/标题匹配该文件名 |
| `FOCUS_RUN_CONFIGURATION` | 预注册运行配置句柄（符号键映射的受信值） | 定位标题精确等于该句柄的可见 AX 元素并请求聚焦 | 回读该元素 `AXFocused` 为真，且标题/值与受信句柄一致，且元素可见 |
| `SHOW_TEST_RESULT` | 预注册测试结果句柄（仅展示既有结果） | 仅激活标题匹配该句柄的既有结果视图元素 | 该元素可见且处于选中/聚焦状态 |

三者的共同不变量：**只激活由受信注册值命名的单一元素**；不启动测试、不运行命令、不输入文本、不确认对话框、不读取页面正文。

### 2.4 原生构建产物

执行命令：`npm run build:native`（构建期脚本 `native/build.sh`；运行时服务从不调用它）

```text
clang++ -std=c++20 -fobjc-arc -fblocks -O2 -Wall -DNODE_GYP_MODULE_NAME=idea_ax_bridge \
  -bundle -undefined dynamic_lookup -I<node headers> \
  -framework AppKit -framework ApplicationServices -framework Foundation \
  -o native/build/idea_ax_bridge.node native/idea_ax_bridge.mm

build:native OK -> .../native/build/idea_ax_bridge.node
（0 errors, 0 warnings）
```

真实加载与探针（`node -e`，实测输出）：

```json
exports: focusRunConfiguration,openRegisteredFile,probe,showTestResult
probe: {"platform":"darwin","bridgeVersion":"1.0.0","axApiAvailable":true,"axTrusted":true,"ideaRunning":false}
nonexistent path: {"ok":false,"verified":false,"code":"INVALID_REGISTERED_PATH","detail":"registered path is not an existing regular file"}
relative path:    {"ok":false,"verified":false,"code":"INVALID_REGISTERED_PATH","detail":"registered path must be absolute"}
focus cfg:        {"ok":false,"verified":false,"code":"IDEA_NOT_RUNNING","detail":"trusted IntelliJ IDEA application is not running"}
test result:      {"ok":false,"verified":false,"code":"IDEA_NOT_RUNNING","detail":"trusted IntelliJ IDEA application is not running"}
```

宿主实测：本进程已获得 macOS“辅助功能”权限（`axTrusted: true`），AX API 可用（`axApiAvailable: true`）；IntelliJ IDEA 当前未运行，因此三项 IDEA 动作全部确定性失败关闭。

---

## 3. 生产 UDS 服务入口与配置

新增 `src/config.ts`（环境变量 → 冻结 `ServiceConfig`）与 `src/main.ts`（生产入口，`npm start` → `node dist/main.js`）：

- 传输**仍然只允许 Unix Domain Socket**：入口只构造 `LocalAutomationServer`，不创建 TCP/HTTP 监听；socket 文件权限强制 `0600`（`0o077` 位必须为 0，否则启动失败）。
- 协议版本（`version: 1`）、16 KiB 单行 JSON 帧、≥32 字节独立 HMAC-SHA256、严格 UTC ISO-8601、60 秒寿命、10 秒未来漂移、SQLite 原子 nonce 消费等语义**未做任何改动**。
- 配置校验失败即拒绝启动（失败关闭，不降级）：缺失或过短密钥、非回环基础地址、相对 socket/DB 路径、非不透明符号句柄、非绝对注册文件路径。
- 密钥只从本机环境注入，不写日志、不进模型、不提交仓库；启动诊断行仅输出传输性质与协议版本。

---

## 4. 保留并复核的既有整改（逐 diff 审查结论）

| 前次整改项 | 审查结论 |
| :--- | :--- |
| ActionRegistry 保护并传递受信映射值（`FOCUS_RUN_CONFIGURATION` / `SHOW_TEST_RESULT` 返回受控值而非原始 `targetKey`） | **正确，保留**。`resolveIdeaAction` 返回 `configuredRunConfigs[targetKey]` / `registeredTestResults[targetKey]`，服务把它作为句柄传给桥接；请求仍只允许不透明 `targetKey`。 |
| 浏览器动作可独立乱序调用、校验来源、触发前置展开 | **正确，保留**。`focusAgentInput` / `openResultPanel` 在不在目标路由时先导航到 `${trustedOrigin}/` 或 `${trustedOrigin}/workspaces`，随后校验 origin 并核对 `document.activeElement` / 面板可见性。 |
| `openRoute` 的来源信任 | **追加纵深加固**：原实现会在未配置时用入参 URL 的 origin 反向建立信任。现已改为“未配置受信回环来源即拒绝；URL origin 与受信来源不一致即拒绝”，并新增 RED 测试锁定。 |
| 严格 UTC ISO-8601 即时时间戳、脚本性质诚实标记 | **保留**（本次未改动）。 |
| `real-acceptance.ts` 的 63342 HTTP 探测 | **已删除并重写**为“真实原生 AX 探测 + 真实 UDS 端到端探测 + 回环服务可用性探测 + 集成夹具”。 |

### 4.1 回执诚实性增强

服务在 IDEA 通道失败时按适配器提供的稳定码区分：

- `ADAPTER_FAILURE` —— 适配器不可用或动作未能投递（如 IDEA 未运行、AX 权限缺失）；
- `UNVERIFIED_TARGET_STATE` —— 动作已投递但未观测到目标 AX 状态。

两者都不会写成 `SUCCEEDED`；浏览器通道失败语义保持不变。

---

## 5. TDD 证据（先 RED 后 GREEN）

新增测试先以模块缺失/行为缺失失败（`Cannot find module '../src/config.js'`、`'../src/nativeAxBridge.js'`，以及 `DirectLocalIdeaBridge` 仍被导出），实现后转绿。覆盖点：

1. 生产源码不得出现 `63342` / `ideaLocalApiBaseUrl` / `DirectLocalIdeaBridge`；
2. 生产源码不得出现 `node:http`、`node:https`、`fetch(`、`XMLHttpRequest`；
3. IDEA 适配器表面不得出现 HTTP 响应式或 2xx 判定（`res.ok`、`response.ok`、`status()`、`statusCode`、URL 字面量、`127.0.0.1`、`localhost`）；
4. **精确状态核验**：`ok:true, verified:false` 一律失败（`UNVERIFIED_TARGET_STATE`）；`ok:false, verified:true` 一律失败（`ADAPTER_FAILURE`）；仅 `ok:true && verified:true` 才成功；桥接抛错失败关闭；
5. 原生模块导出面白名单校验（含夹带通用能力时拒绝）；
6. 默认装配使用真实 AX 桥接，附加模块缺失时诚实失败关闭；
7. 生产入口与配置解析：环境变量校验、UDS-only 结构断言、`npm start` 入口存在；
8. 真实 UDS 上的签名/重放/篡改/未注册目标行为。

---

## 6. 全量测试、类型检查与构建

执行命令：`cd local-automation-service && npm test`

```text
 Test Files  14 passed (14)
      Tests  87 passed | 1 skipped (88)
   Duration  ~0.6s
```

说明：唯一 skip 项为“附加模块未构建时诚实失败关闭”的负向分支——本机模块已构建，故该分支按设计跳过；其正向对应分支（真实模块可用时报告真实 AX 能力）已执行通过。

`npm run typecheck`（`tsc --noEmit`）：0 errors。
`npm run build`（`tsc`）：成功输出 `dist/`，含 `dist/main.js` 生产入口。

---

## 7. 生产源门禁

执行命令：`npx vitest run tests/sourceGuard.spec.ts`

```text
 ✓ tests/sourceGuard.spec.ts (3 tests)
   ✓ strictly forbids child_process, spawn, exec, and shell in production src/**
   ✓ strictly forbids generic desktop control or arbitrary click/type/keyboard/mouse capabilities
   ✓ native Accessibility addon never shells out, opens a port, or drives the desktop generically
```

第三项门禁直接扫描 `native/idea_ax_bridge.mm`，禁止 `osascript`/`AppleScript`/`JXA`/`child_process`/spawn/exec/`system(`/`popen(`/`fork(`/`NSTask`/`openURL`/`open -a`/`CGEventCreate*`/`AXUIElementCreateSystemWide`/任何 URL、`127.0.0.1`、`localhost`、`63342`、`port`，并要求 bundle identifier 与 `kAXPressAction` 存在于源码中。

---

## 8. 真实探针与集成夹具实测

执行命令：`cd local-automation-service && npx tsx scripts/real-acceptance.ts`

```text
--- 1. [LIVE_PROBE] In-process macOS Accessibility bridge ---
Native AX addon: LOADED (version 1.0.0, platform darwin)
  macOS Accessibility API available: true
  macOS Accessibility permission granted to this process: true
  Trusted IntelliJ IDEA (com.jetbrains.intellij) running: false
  [Real Action 1] OPEN_REGISTERED_FILE:     BLOCKED (fail closed)  BLOCKED: IDEA_NOT_RUNNING
  [Real Action 2] FOCUS_RUN_CONFIGURATION:  BLOCKED (fail closed)  BLOCKED: IDEA_NOT_RUNNING
  [Real Action 3] SHOW_TEST_RESULT:         BLOCKED (fail closed)  BLOCKED: IDEA_NOT_RUNNING

--- 2. [LIVE_PROBE] Production Unix Domain Socket service ---
Socket is a Unix domain socket: true
Socket permissions: 600 (owner-only: true)
Signed IDEA request over UDS     -> status=FAILED   errorCode=ADAPTER_FAILURE
Replayed request over UDS        -> status=REJECTED errorCode=REPLAY_DETECTED
Tampered signature over UDS      -> status=REJECTED errorCode=INVALID_SIGNATURE
Unregistered target over UDS     -> status=REJECTED errorCode=TARGET_NOT_REGISTERED
  [PASS] socket is a Unix domain socket
  [PASS] socket permissions are owner-only
  [PASS] no adapter ever reports SUCCEEDED while IDEA is unavailable
  [PASS] replay rejected
  [PASS] tampered signature rejected
  [PASS] unregistered target rejected

--- 3. [LIVE_PROBE] StudyPilot loopback service (http://127.0.0.1:8080) ---
Live StudyPilot Status: OFFLINE -> 3 项浏览器真实动作 BLOCKED

--- 4. [INTEGRATION_FIXTURE] Browser adapter mechanics (isolated harness) ---
  [Fixture] OPEN_STUDYPILOT_ROUTE (ASSISTANT -> /):                       PASS
  [Fixture] FOCUS_AGENT_INPUT (origin + activeElement verified):          PASS
  [Fixture] OPEN_STUDYPILOT_ROUTE (WORKSPACE_ARTIFACTS -> /workspaces):   PASS
  [Fixture] OPEN_RESULT_PANEL (trigger clicked, visibility verified):     PASS
```

---

## 9. 诚实边界与未完成项（BLOCKED 声明）

1. **真实 IDEA 正向验收未执行（BLOCKED）**：本机未运行 IntelliJ IDEA，三项 IDE 动作只取得“确定性失败关闭”的真实证据，**未取得任何真实成功证据**。因此本任务**不能**声明完成，也不能把本文件标记为已验收。
2. **未对真实 IDE 的 AX 树做正向标定（已知限制）**：动作成功所依赖的具体 AX 属性（编辑器标签/聚焦文档的标题与 `AXDocument`、运行配置选择器的 `AXFocused`、结果视图的可见性）在真实 IntelliJ IDEA（2026.1.1）上的实际暴露情况**尚未标定**。若真实 IDE 不暴露所需属性，实现会返回 `TARGET_NOT_FOUND` / `STATE_NOT_VERIFIED` 并失败关闭——即“诚实失败”，绝不虚构成功；但这意味着正向路径可能仍需在真实环境中调整。
3. **未执行 `[REAL_E2E]`**：本文件所有夹具证据均为 `[INTEGRATION_FIXTURE]` 或 `[LIVE_PROBE]`，不代表也不替代 Task 34 的真实全栈验收。
4. **StudyPilot 前端/服务离线**：3 项浏览器真实动作同样为 `BLOCKED`（前置条件缺失），仅有隔离夹具的力学验证。
5. **其他未覆盖项**：未在 Windows/Linux 上验证（非 darwin 平台按设计直接失败关闭）；未验证 IDE 侧真实运行配置/测试结果数据；未与 Java 端在本机完成真实进程握手（由 Task 33 真实联调阶段进行）。

### 9.1 复现正向验收所需前置条件

- macOS 上安装并**运行** IntelliJ IDEA（bundle id `com.jetbrains.intellij`），打开包含已登记工作区与已登记文件的工程；
- 为运行本地自动化服务的宿主进程授予“系统设置 → 隐私与安全性 → 辅助功能”权限；
- 该工程中存在已登记的运行配置与已有测试结果视图；
- 先执行 `npm run build:native`，再执行 `npx tsx scripts/real-acceptance.ts`。

---

## 10. 本轮改动文件

新增：`native/idea_ax_bridge.mm`、`native/build.sh`、`src/nativeAxBridge.ts`、`src/config.ts`、`src/main.ts`、`tests/ideaNativeAx.spec.ts`、`tests/entrypoint.spec.ts`。

修改：`src/ideaAdapter.ts`（删除虚构 HTTP 桥接，改为真实 AX 适配器）、`src/service.ts`（默认装配 + 失败码）、`src/types.ts`（移除 `ideaLocalApiBaseUrl`）、`src/browserAdapter.ts`（来源纵深加固）、`src/index.ts`、`package.json`、`.gitignore`、`scripts/real-acceptance.ts`、`tests/sourceGuard.spec.ts`、`tests/falseSuccess.spec.ts`、`tests/adapters.spec.ts`、`tests/reviewFindings.spec.ts`、本文件。

未触碰 `backend/**`、`web/**`、`ai-service/**`、`runner-service/**`；未合并 `main`；未启动 Task 34。
