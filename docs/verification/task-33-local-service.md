# Task 33 验证证据：受控本地界面适配器服务 (TypeScript)

- **执行 Agent**：MiniMax Code (Mavis) 临时接管（ZCode 配额阻断；Codex 仍为架构与最终验收负责人）
- **测试等级**：`[UNIT_TEST]` / `[STATIC_VALIDATION]` / `[INTEGRATION_TEST]` / `[INTEGRATION_FIXTURE]` / `[LIVE_PROBE]`
- **执行时间**：2026-09-22 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-33-local-adapters`
- **关联基础提交**：`9f8537115ac3d9c3bd86ddc1a43128db3a58414b`（Task 32 Codex 最终验收基线）
- **一轮整改提交**：`50dc65003c2bb0234cf7b561c28c863a34a81b29`（P0/P1 首轮整改）
- **二轮整改提交**：`3ae231ae2967d04935fc86e75724eae5ec8c684f`（删除虚构 HTTP 桥接，改为进程内 AX 绑定；**被 Codex 拒绝**）
- **三轮整改提交**：本文件所属提交（严格结构化身份绑定；SHA 见交接与最终报告）
- **交付状态**：**BLOCKED — 真实 IDEA 正向验收未执行，且真实 IDE 未暴露可绑定身份**

---

## 1. 本轮（三轮整改）背景：Codex 拒绝 `3ae231a` 的三条 P0 假成功路径

Codex 独立复审认为 `3ae231a` 的静态/单元/集成/UDS 证据通过，但**真实 AX 语义不满足契约**，存在三条可产生假成功的路径：

| # | 缺陷 | 后果 |
| :--- | :--- | :--- |
| 1 | `OPEN_REGISTERED_FILE` 把受信路径降级为 `lastPathComponent`，取“第一个同名可见元素”按下，且 `VerifyActiveEditorFile` 接受任何“标题/值**包含** basename”的选中/聚焦元素 | 同名不同目录的两个文件可打开/验证错文件并返回 `SUCCEEDED`；注释声称“exactly one”，但 `FindByExactTitle` 只返回首个匹配、从不校验唯一性 |
| 2 | `FOCUS_RUN_CONFIGURATION` 在**整个应用**内搜索任意同标题可见元素，按下/聚焦后仅回读该任意元素自身的标题/焦点 | 与运行配置同名的编辑器标签或无关控件可返回 `SUCCEEDED` |
| 3 | `SHOW_TEST_RESULT` 接受“精确或包含标题”的任意匹配；在动作**之前**读取可见性，并可调用 `FocusElement`，从而对“本就可见的任意同文本标签”返回 `SUCCEEDED` | 预置可见标签被当作“已展示既有测试结果” |

本轮整改目标：**彻底移除这三条假成功路径**，并把身份判定改为可证伪的结构化约束。

---

## 2. 整改后的身份绑定不变量（`native/idea_ax_bridge.mm`，版本 2.0.0）

1. **遍历根必须是真实 AXWindow**：只接受受信 bundle id `com.jetbrains.intellij` 应用中 role 恰为 `AXWindow` 的焦点/主/首个窗口。**没有应用级或系统级搜索**（源码门禁禁止 `AXUIElementCreateSystemWide`、`kAXFocusedUIElementAttribute`）。
2. **目标必须唯一**：先枚举全部候选，命中 0 个 → `TARGET_NOT_FOUND`；命中 ≥2 个 → `TARGET_AMBIGUOUS`，**都不派发动作**。
3. **身份比较一律精确相等**：源码门禁禁止 `containsString`；不接受 basename 或标题子串作为任何证明。
4. **每个动作有独立的 role 白名单 + 祖先容器证明**：
   - 项目树文件节点：role ∈ {`AXCell`,`AXStaticText`,`AXRow`}，且祖先含 {`AXOutline`,`AXTable`,`AXList`,`AXTree`}；
   - 运行配置选择器：role ∈ {`AXPopUpButton`,`AXComboBox`}，且祖先含 `AXToolbar`；
   - 测试结果视图：role ∈ {`AXTab`,`AXRadioButton`}，且祖先含 `AXTabGroup`。
5. **派发前复验身份**：从快照恢复活元素后，再次读取实际 role/标题并精确比对（`ReverifyLiveIdentity`），不一致 → `IDENTITY_CHANGED_BEFORE_ACTION`。快照与动作之间的树变化不会导致按错控件。
6. **验证只在动作后的快照上进行**：每个动作都取 PRE 快照定位目标 → 执行唯一动作 → 取 POST 快照复验。**动作前的状态永远不能充当动作效果**。
7. **AX 调用有界**：`AXUIElementSetMessagingTimeout` 2 秒 + 快照上限 2000 节点/深度 30；超出即标记截断并失败关闭，忙碌的 IDE 不能挂死服务。

### 2.1 三条动作的精确证明

| 动作 | 唯一注册动作 | 成功必须观测到的 POST 状态 |
| :--- | :--- | :--- |
| `OPEN_REGISTERED_FILE` | 对**唯一**由完整祖先标题链证明为受信规范路径的项目树节点执行一次 `AXPress` | 存在**未隐藏**元素，其 `AXDocument` 或 `AXURL` 规范化为**与规范真实路径完全相等**（`file://` 前缀与百分号解码后逐字节比较）。标题、basename、选中态一律不作为证明 |
| `FOCUS_RUN_CONFIGURATION` | **只请求聚焦**该选择器控件（不按下，故不打开菜单、不改变选择） | POST 快照中同一严格规则定位到的选择器控件 `focused` 或 `selected` 为真，**且**其 `AXValue` 或标题**精确等于**受信句柄，且可见 |
| `SHOW_TEST_RESULT` | 对唯一由 `AXTabGroup` 容器证明的结果视图标签执行一次 `AXPress` | POST 快照中被重新严格定位的同一视图 `selected` 为真、可见，且标题精确等于受信句柄。**不读取动作前可见性**，**不以通用聚焦作为证明** |

### 2.2 规范路径证明（消除同名文件歧义）

- 证明由两部分同时成立构成：
  1. 候选元素自身标题**精确等于**规范真实路径的 basename；
  2. 其**祖先标题链**（自遍历根之下的最外层祖先到候选元素，`/` 连接）必须是规范真实路径**目录部分**的**完整路径分量后缀**（`canonicalDir.endsWith("/" + chain)`，带前导 `/` 保证分量边界）。
- 因此“另一个目录下的同名文件”不满足后缀条件，而“同一目录同名节点重复出现”会命中 ≥2 个 → `TARGET_AMBIGUOUS`。
- 交由原生层的路径始终是 `fs.realpathSync` 规范化后的真实路径（`MacAxIdeaBridge.openFile` 内完成，测试断言传入的是规范路径而非符号链接/显示名）。

---

## 3. 可测性：测试接缝（同一源码、仅测试构建含接缝）

生产附加模块只暴露四个操作且会驱动真实桌面，无法直接单元测试。因此构建产出**两个**由**同一份源码**编译的产物：

| 产物 | 构建方式 | 导出面 |
| :--- | :--- | :--- |
| `native/build/idea_ax_bridge.node` | `npm run build:native`（生产） | 恰好 `probe` / `openRegisteredFile` / `focusRunConfiguration` / `showTestResult` |
| `native/build/idea_ax_bridge.test.node` | 同上，附加 `-DAX_BRIDGE_TEST_SEAM=1` | 上述四个 + `__testEvaluate` |

- `__testEvaluate(op, arg, preFixture, postFixture)` 运行与生产路径**完全相同**的定位、唯一性、容器/角色约束与 POST 验证函数，只是数据源换成夹具树；此外它只测试**决断逻辑**，不触碰桌面。
- 夹具格式（每行一个节点，深度优先）：`depth|role|subrole|identifier|title|document|url|value|flags|size`；`flags` 取 `s`(selected)/`f`(focused)/`h`(hidden)，`size` 为 `WxH`。
- **接缝绝不能进入生产**：`src/nativeAxBridge.ts` 的导出面白名单只允许四个名字，多出任何导出（含 `__testEvaluate`）即拒绝采用。测试同时断言：生产产物恰好四个导出并通过校验，而测试产物含 `__testEvaluate` 且被该白名单**拒绝**。

---

## 4. TDD 证据

### 4.1 RED（新门禁在旧源码上失败）

本轮新增的源码门禁与语义夹具**在上一轮提交 `3ae231a` 的源码上确实失败**（用 `git show HEAD:...` 提取旧源码校验）：

```text
--- RED: forbidden weak patterns present in previously committed source ---
  VIOLATION /containsString/
--- RED: required strict markers absent in previously committed source ---
  MISSING CanonicalDirname / ReverifyLiveIdentity / TARGET_AMBIGUOUS / LocateUnique
  MISSING kAXWindowRole / AXToolbar / AXTabGroup / AXUIElementSetMessagingTimeout / AxSnapshot
--- old weak-identity evidence ---
  lastPathComponent used for identity: true
  VerifyActiveEditorFile accepted selected/focused title-or-value: true
```

### 4.2 GREEN：对抗性夹具语义测试（`tests/nativeAxSemantics.spec.ts`，23 项）

| 场景 | 期望 | 结果 |
| :--- | :--- | :--- |
| 同名不同目录文件（basename 相同） | 不派发动作（`TARGET_NOT_FOUND`） | PASS |
| 完整祖先链证明规范路径 | `ok && verified` | PASS |
| 两个节点证明同一路径 | `TARGET_AMBIGUOUS`，不派发 | PASS |
| POST 树仅有“被选中的项目树 basename”（旧假证明） | `STATE_NOT_VERIFIED` | PASS |
| 文档路径相近但不等（`other.json` / 父目录） | `STATE_NOT_VERIFIED` | PASS |
| 文档为普通绝对路径（无 `file://`） | `verified` | PASS |
| 隐藏编辑器 | 不构成证明 | PASS |
| 同标题编辑器标签（非工具栏） | `TARGET_NOT_FOUND` | PASS |
| 工具栏内同标题静态标签 | `TARGET_NOT_FOUND` | PASS |
| 选择器未聚焦 | `STATE_NOT_VERIFIED` | PASS |
| 选择器聚焦且值等于受信句柄 | `ok && verified` | PASS |
| 选择器报告了**另一个**配置 | `STATE_NOT_VERIFIED` | PASS |
| 两个选择器候选 | `TARGET_AMBIGUOUS` | PASS |
| 工具窗口组外的同标题标签 | `TARGET_NOT_FOUND` | PASS |
| 动作前已可见的同标题标签 | `STATE_NOT_VERIFIED` | PASS |
| 动作前选中、动作后消失 | `STATE_NOT_VERIFIED` | PASS |
| POST 选中且可见的结果视图 | `ok && verified` | PASS |
| 部分标题匹配（`surefire-reports-summary`） | `TARGET_NOT_FOUND` | PASS |
| 未知操作（如 `EXECUTE_SHELL_COMMAND`） | `INVALID_ACTION` | PASS |

### 4.3 源码门禁（`tests/sourceGuard.spec.ts`，6 项）

1. 生产 `src/**` 禁止 `child_process`/spawn/exec/shell/osascript 等；
2. 生产 `src/**` 禁止通用点击/输入/按键/鼠标/任意脚本能力；
3. `.mm` 禁止起子进程、脚本桥、网络传输、URL、`127.0.0.1`、`localhost:<port>`、`63342`、`port`；
4. `.mm` 禁止子串身份与全应用/全系统遍历，并**必须**存在 `CanonicalDirname`/`CanonicalBasename`/`kAXDocumentAttribute`/`ReverifyLiveIdentity`/`TARGET_AMBIGUOUS`/`kAXWindowRole`/`AXToolbar`/`AXTabGroup`/`AXUIElementSetMessagingTimeout`/`LocateUnique`（防止“删功能过门禁”）；
5. 结果视图验证函数必须读 POST 的 `selected` 与可见性，且不得出现通用聚焦能力；
6. `FOCUS_RUN_CONFIGURATION` 体内**不得出现 `PerformPress`**（保持“只聚焦”语义），且必须使用 `kAXFocusedAttribute`。

---

## 5. 全量验证

```text
cd local-automation-service && npm test
 Test Files  15 passed (15)
      Tests  114 passed | 1 skipped (115)

npm run typecheck   -> tsc --noEmit, 0 errors
npm run build       -> tsc, dist/ 生成成功（含 dist/main.js）
npm run build:native-> 0 errors, 0 warnings（生产产物 + 测试接缝产物）
```

唯一 skip 项为“附加模块未构建时诚实失败关闭”的负向分支——本机模块已构建，故按设计跳过；其正向分支（真实模块可用时报告真实 AX 能力）已执行通过。

### 5.1 原生构建与真实探针

```text
probe: {"platform":"darwin","bridgeVersion":"2.0.0","axApiAvailable":true,
        "axTrusted":true,"ideaRunning":true,"ideaWindowExposed":false}

OPEN_REGISTERED_FILE     -> NO_ACTIVE_WINDOW（失败关闭）
FOCUS_RUN_CONFIGURATION  -> NO_ACTIVE_WINDOW（失败关闭）
SHOW_TEST_RESULT         -> NO_ACTIVE_WINDOW（失败关闭）
```

### 5.2 真实 UDS 探针（`scripts/real-acceptance.ts`）

```text
Socket is a Unix domain socket: true
Socket permissions: 600 (owner-only: true)
Signed IDEA request over UDS -> status=FAILED   errorCode=ADAPTER_FAILURE
Replayed request over UDS    -> status=REJECTED errorCode=REPLAY_DETECTED
Tampered signature over UDS  -> status=REJECTED errorCode=INVALID_SIGNATURE
Unregistered target over UDS -> status=REJECTED errorCode=TARGET_NOT_REGISTERED
[PASS] × 6
浏览器适配器隔离夹具 4/4 PASS（不代表 Task 34 REAL_E2E）
```

Unix Socket 传输、0600 权限、协议版本、HMAC、时间窗与 nonce 语义**未改动**。

---

## 6. 真实 IDE 标定证据（本轮新增，关键）

Codex 已启动本机已安装的 IntelliJ IDEA（`com.jetbrains.intellij`，2026.1.1，PID 14285）并尝试打开 `/Users/moxiao/IdeaProjects/project-zcode-task-33`。

- **实测：该 IDEA 进程不向 macOS Accessibility 暴露任何窗口。** 只读探针（独立临时工具，非仓库代码）在 ~4 分钟内按 8 秒间隔轮询 31 次，`AXWindows` **始终为 0**，`AXFocusedWindow`/`AXMainWindow` 均不存在，应用唯一子节点是 `AXMenuBar`：

  ```text
  == app role=AXApplication ==
  AXWindows present=1 count=0
  AXFocusedWindow present=0
  AXMainWindow present=0
  app AXChildren count=1
    app.child[0] role=AXMenuBar title=(null)
  [poll 0..30] windows=0
  ```

- **IDE 侧日志佐证**（`~/Library/Logs/JetBrains/IntelliJIdea2026.1/idea.log`）：14:02:23 以 **text editor processor** 异步打开该工程（`Using processor text editor to open the project ...`），此后日志未见窗口/帧创建或任何 accessibility 相关记录；同时存在 `https.proxyHost=127.0.0.1` 的代理告警。也就是说工程仍处于异步打开/索引阶段，且 JBR 未把窗口桥接到 AX。
- **结论（诚实）**：当前环境下不存在可绑定的身份锚点（无 AXWindow ⇒ 无项目树、无编辑器、无运行配置选择器、无工具窗口层级）。因此**三项 IDEA 动作全部失败关闭**，返回稳定码 `NO_ACTIVE_WINDOW`，**没有任何真实成功证据**。
- **未做的事**：没有为了“让它通过”而放宽验证（例如退回全应用同标题搜索或 basename 匹配）；没有修改用户 IDE 设置或权限；没有降级到 Shell/AppleScript/键鼠模拟。

### 6.1 明确仍未解除的阻塞

1. **真实 IDEA 正向验收仍未执行（BLOCKED）**：`ideaWindowExposed=false`，三动作只有失败关闭证据。
2. **真实 AX 树标定未完成**：动作成功所依赖的具体 AX 属性（项目树容器/单元格 role、选择器的 `AXToolbar` 祖先与 `AXValue`、工具窗口标签的 `AXTabGroup` 祖先与 `AXSelected`、编辑器的 `AXDocument`/`AXURL`）在真实 IntelliJ IDEA 2026.1.1 上的实际暴露情况**尚未观测到**。若真实 IDE 不暴露这些结构，实现会继续失败关闭（诚实失败），但正向路径可能需要按真实树调整 role/容器名单——这是**标定**问题，不是放宽验证的理由。
3. **尚未确认的日历环境前置**：需确认 IDEA 是否需要在设置中启用“屏幕阅读器支持/辅助功能”，或使用 `-Dide.a11y.force.enabled=true` 之类的 JBR 选项，才会把窗口桥接到 AX。本轮未修改任何用户设置。
4. **浏览器通道**：StudyPilot 服务离线，3 项浏览器真实动作同样 `BLOCKED`；夹具证据仍仅为 `[INTEGRATION_FIXTURE]`，不代表 Task 34 `[REAL_E2E]`。

### 6.2 复现正向验收的前置条件

1. macOS 上运行 IntelliJ IDEA（bundle id `com.jetbrains.intellij`），并使其**确实向 Accessibility 暴露窗口**（先运行 `npx tsx scripts/real-acceptance.ts`，确认 `ideaWindowExposed: true`）；
2. 打开含已登记工作区/已登记文件的工程，工程内存在已登记运行配置与已有测试结果视图；
3. 宿主进程已获“系统设置 → 隐私与安全性 → 辅助功能”权限；
4. `npm run build:native && npx tsx scripts/real-acceptance.ts`。

---

## 7. 本轮改动文件

修改：`native/idea_ax_bridge.mm`（重写为版本 2.0.0 的严格结构化身份绑定 + 测试接缝）、`native/build.sh`（同源构建生产 + 接缝两个产物）、`src/nativeAxBridge.ts`（`ideaWindowExposed`）、`src/ideaAdapter.ts`（规范化真实路径后再交原生层）、`scripts/real-acceptance.ts`（新增身份锚点探测与诚实前置说明）、`tests/sourceGuard.spec.ts`、`tests/ideaNativeAx.spec.ts`、`tests/falseSuccess.spec.ts`、本文件。

新增：`tests/nativeAxSemantics.spec.ts`（23 项对抗性夹具语义测试）。

未改动：`src/{protocol,verifier,nonceStore,canonical,server,service,config,main,actionRegistry,browserAdapter}.ts` 的安全语义（UDS-only、0600、协议版本、HMAC、nonce、注册表白名单、独立浏览器动作与来源校验保持不变）。

未触碰 `backend/**`、`web/**`、`ai-service/**`、`runner-service/**`；未合并 `main`；未启动 Task 34。
