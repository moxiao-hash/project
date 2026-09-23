# Task 33 验证证据：受控本地界面适配器服务 (TypeScript) — 插件桥修订版

- **执行 Agent**：MiniMax Code (Mavis)（ZCode 配额阻断期间临时接管；Codex 仍是架构与最终验收负责人）
- **测试等级**：`[UNIT_TEST]` / `[STATIC_VALIDATION]` / `[INTEGRATION_TEST]` / `[INTEGRATION_FIXTURE]` / `[LIVE_PROBE]`
- **执行时间**：2026-09-22 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-33-local-adapters`
- **关联契约**：`project-main-integration/docs/verification/task-33-frozen-contract.md`（2026-09-22 架构修订版）
- **起始已验收基线 HEAD**：`2a023416af053dbce2a6bdf424f27d37d8c2de13`
- **交付状态**：**待 Codex 验收 —— 插件已构建但未安装，真实 IDEA 正向验收仍未执行**

---

## 1. 本轮修订的背景与结论

上一轮用 macOS Accessibility（AX）实现三项 IDEA 动作。真实 IntelliJ IDEA 2026.1.1 验收证明：

- 项目树行的**唯一支持动作是 `AXPress`**（`AXConfirm` 返回 `-25200 kAXErrorActionUnsupported`），而 `AXPress` **只选中、不打开**文件；
- 运行配置控件接受聚焦请求，但**从不报告 `AXFocused`/`AXSelected`**，窗口也不提供 `AXFocusedUIElement`；
- 无既有结果视图时无法展示测试结果。

因此 AX 无法可靠**执行并验证**三项正向动作，Task 33 未通过。按修订后的冻结契约，生产执行路径改为**窄权限 JetBrains 插件**，AX 仅保留为失败关闭的兼容探针。

**保持不变**（外部契约零改动）：Java-facing Unix Domain Socket、六个冻结动作、枚举、16 KiB 单行 JSON 帧、HMAC/时间窗/nonce 语义、浏览器适配器，以及外部 adapter 标识 **`IDEA_ACCESSIBILITY`**。

---

## 2. 修订后的架构

```text
Java 后端 ──HMAC 域 A──▶ 本地自动化服务 ──HMAC 域 B──▶ 受信 JetBrains 插件 ──▶ IntelliJ Platform API
                (Java-facing UDS)            (插件私有 UDS)             (EDT + 执行后验证)
本地自动化服务 ──▶ macOS AX 探针（只读诊断，永不产生 SUCCEEDED）
```

### 2.1 两条 Socket 的强制分离

| 维度 | Java-facing Socket | 插件私有 Socket |
| :--- | :--- | :--- |
| 监听方 | 本地自动化服务 | JetBrains 插件 |
| 客户端 | 仅 Java 后端 | 仅本地自动化服务 |
| 密钥 | `STUDYPILOT_AUTOMATION_HMAC_SECRET`（≥32B） | `STUDYPILOT_AUTOMATION_IDEA_PLUGIN_HMAC_SECRET`（≥32B，**必须不同**） |
| 协议域 | 九字段（含 ownerHash/channel） | 七字段 + 域标记 `studypilot-idea-plugin-v1` |
| 有效期 | 60 秒 | **15 秒**（更短） |
| 重放存储 | 服务侧 SQLite | 插件侧持久化 nonce 台账 |
| 权限 | 0600，父目录 owner-only | 0600，父目录**禁止其他用户写入** |

服务侧 `config.ts` 会**显式拒绝**插件 Socket 路径与 Java-facing 路径相同、或两把密钥相同，二者都不允许启动。

### 2.2 插件请求仅含不透明句柄

请求字段固定为 `version, requestId, action, targetKey, issuedAt, expiresAt, nonce, signature`。路径、运行配置名、自由文本、选择器、IntelliJ Action ID、进程 ID、窗口标题**在结构上不可能出现**：`action` 必须是三个冻结动作之一，`targetKey` 必须匹配 `^[A-Z0-9_]{1,64}$`（服务端客户端在发送前再次校验，路径类字符串直接失败关闭）。

插件用自己的**本机可信注册表**（`~/Library/Application Support/StudyPilot/idea-plugin.tsv`，owner-only，制表符分隔）把句柄解析为具体目标、项目根与类型，并在执行时再次校验项目与目标身份。

### 2.3 三项动作的真实语义与执行后验证

| 动作 | 唯一动作 | 执行后必须观测到 |
| :--- | :--- | :--- |
| `OPEN_REGISTERED_FILE` | `LocalFileSystem` 解析 → 校验常规文件/非符号链接/规范路径一致/位于注册项目根内/同名兄弟唯一 → `OpenFileDescriptor` | `FileEditorManager.getSelectedFiles()` 含**该规范 VirtualFile**，且 `getSelectedEditor(file) != null` |
| `FOCUS_RUN_CONFIGURATION` | `RunManager.getAllSettings()` 中**恰好一个**同名既有配置 → `setSelectedConfiguration` | `RunManager.getSelectedConfiguration()` 身份等于注册名；**不运行、不调试、不修改** |
| `SHOW_TEST_RESULT` | `ToolWindowManager` 取注册工具窗口 → 既有内容**恰好一个** → `activate` + `setSelectedContent` | 该内容仍存在、为 `getSelectedContent()`、工具窗口可见。**从不启动/重跑测试** |

失败关闭条件：零/多目标、项目不匹配、符号链接、IDE disposing、UI 超时、验证 API 不可用、执行后状态无法证明。

### 2.4 安全与线程边界

- 顺序：**签名/版本/时间窗 → 注册表解析 → 持久化 nonce 原子消费 → EDT 执行 → 执行后验证**，全部检查在界面副作用之前；校验失败不消费 nonce。
- 所有 IDE 操作经 `IdeUiExecutor` 在 **IntelliJ UI 线程**执行并有硬超时（默认 2 秒，250–30000 可配）；超时→`UI_THREAD_TIMEOUT`，disposing→`IDE_DISPOSING`，异常→`INTERNAL_ERROR`。
- **只有** `dispatched && verified` 才产生 `SUCCEEDED`；已投递但未证实→`FAILED/UNVERIFIED_TARGET_STATE`；校验期拒绝→`REJECTED`。
- 不使用 Robot、键鼠合成、`ActionManager`/任意 Action ID、`DataContext` 通用动作、`ProcessBuilder`、Shell、AppleScript、反射、TCP/HTTP。

---

## 3. TDD 证据

### 3.1 RED（决定性失败，非编译失败）

插件协议/注册表/派发器与自检harness先行。第一版派发器**故意**只按“已投递”判成功、忽略执行后验证、且在副作用之后才消费 nonce：

```text
$ javac ... && java -cp ... com.studypilot.automation.idea.PluginSelfTest
PluginSelfTest: passed=44 failed=16
  FAILED: dispatch: unverified file open fails      (expected FAILED, actual SUCCEEDED)
  FAILED: dispatch: unverified file code            (expected UNVERIFIED_TARGET_STATE, actual null)
  FAILED: dispatch: unverified run focus fails
  FAILED: dispatch: unverified run code
  FAILED: replay: same nonce rejected               (second use returned SUCCEEDED)
  FAILED: replay: rejected after restart
  FAILED: ui: timeout fails / timeout code
  FAILED: ui: disposed fails / disposed code
  FAILED: ui: exception fails / exception code
  FAILED: dispatch: ui thread used
```

16 项失败全部落在**假成功、重放与 UI 线程**这三类安全规则上。修正派发器后：

```text
$ bash idea-plugin/build-local.sh
PluginSelfTest: passed=110 failed=0
```

### 3.2 自检覆盖面（`idea-plugin/src/test/java/.../PluginSelfTest.java`）

- **framing**：合法帧、超 16 KiB、内嵌换行、重复键、额外字段、缺字段、版本不符、数组值、路径型句柄、非法 UTF-8；
- **auth**：正确签名、错签名、短密钥、过期、未来漂移、寿命超 15 秒、**Java 域签名被插件域拒绝**（域分离）；
- **replay**：同 nonce 二次使用被拒、**新台账实例（进程重启）后仍被拒**；
- **registry**：未注册句柄、**动作与句柄类型不匹配**（不派发、不降级）、非法句柄注册即拒绝；
- **三项执行后验证**：三项各自 `verified` 成功、三项各自 `已投递但未证实` 必须 `FAILED`；
- **无既有结果**：`RESULT_VIEW_NOT_PRESENT` 诚实失败；
- **项目/唯一性**：`PROJECT_MISMATCH`、`TARGET_AMBIGUOUS` 一律失败；
- **过期请求零副作用**：注册表/平台调用次数为 0；
- **UI 线程/处置**：超时、disposing、异常三种失败码；
- **回执卫生**：requestId/action 关联、单行、≤16 KiB、**不回传路径或 URL**、消息 ≤200 字符；
- **架构护栏（50 项）**：源码级禁止 TCP/HTTP、通用 Action ID、run/debug/test 执行、Robot/键鼠、反射、Shell，以及 Java-facing Socket 变量名；并**必须存在** `StandardProtocolFamily.UNIX`/`OpenFileDescriptor`/`FileEditorManager`/`RunManager`/`setSelectedConfiguration`/`getSelectedConfiguration`/`ToolWindowManager`/`setSelectedContent`/`getSelectedContent`。

### 3.3 跨语言冻结向量

同一份向量在 **Java 插件自检**与**TypeScript 协议测试**中同时断言，任何一侧改坏规范化载荷都会立刻失败：

```text
payload    = 25#studypilot-idea-plugin-v1|1#1|36#11111111-2222-4333-8444-555555555555|20#OPEN_REGISTERED_FILE|15#FILE_REGISTERED|20#2026-09-22T10:00:00Z|20#2026-09-22T10:00:10Z|22#abcdefghijklmnopqrstuv|
signature  = a8fd5b27819a20254c450a7a1eee54609eb8acb334a22f0818600e7643f1060c
secret     = studypilot-plugin-secret-32-bytes!!
```

---

## 4. 服务侧测试（`tests/ideaPlugin*.spec.ts`、`tests/pluginArchitecture.spec.ts`）

- `tests/ideaPluginProtocol.spec.ts`：冻结向量、域分离、短密钥/超寿命拒绝、请求仅含不透明句柄、响应严格校验（超限/非法 JSON/额外字段/缺字段/多行/未知枚举/动作不匹配）、**只有 SUCCEEDED 才是 verified** 的映射规则；
- `tests/ideaPluginClient.spec.ts`：**真实 Unix Domain Socket** 集成测试——测试内起一个假插件服务端，独立按文档重算 HMAC 后应答，覆盖成功、已投递未证实、拒绝、超限响应、非法响应、额外字段、关联不匹配、签名不符、超时、socket 不存在、未配置，以及“绝不转发路径/配置名”；
- `tests/pluginArchitecture.spec.ts`：IDEA 通道必须走插件适配器、AX 适配器**不得**调用 AX 取结果、服务端只转发 `targetKey`、两条 Socket/两把密钥分离校验、插件源码禁令与必备 API、ZIP 构建输入齐备；
- AX 规则：`NativeBridgeIdeaAutomationAdapter` 三项操作**永远失败关闭**并标记 `AX_DIAGNOSTIC_ONLY`，即使 AX 桥接声称 `ok && verified` 也不得成功（有专门测试，且断言**从未调用** AX 桥接）。

一处真实缺陷修复：`scripts/real-acceptance.ts` 原先三个 await 在数组构造时**全部先执行**，导致打印时三行共用最后一次的诊断。现改为**逐个动作顺序执行并立即捕获该动作自己的 `code` 与 blocker**。

---

## 5. 构建与产物

### 5.1 主路径（可复现 Gradle + IntelliJ Platform）

```text
$ cd local-automation-service/idea-plugin
$ ./gradlew buildPlugin        # Gradle wrapper 9.7.1 已随仓库提交
BUILD SUCCESSFUL
> Task :compileJava / :jar / :prepareSandbox / :buildPlugin
```

仓库提交了 `gradlew` 与 `gradle/wrapper/**`（Gradle 9.7.1）以便在任何机器上复现；本项目仅面向 macOS，Windows 的 `gradlew.bat` 未纳入（其 CRLF 行尾会污染 `git diff --check`）。

本机实测（构建用 IDE 自带 JBR 执行）：

```text
$ /tmp/t33gradle/gradle-9.7.1/bin/gradle buildPlugin --no-daemon \
    -Dorg.gradle.java.home="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
BUILD SUCCESSFUL in 1m 36s
11 actionable tasks: 11 executed
```

- 平台：本机已安装 IntelliJ IDEA `IU-261.23567.138`（2026.1.1），通过 `platformLocalPath` **本地解析**，不下载 IDE 分发。
- **工具链约束（真实记录）**：本机无预装 Gradle；Gradle **8.13 无法运行**在可用 JVM 上（系统 JDK 26、IDE 自带 JBR 25），报 `What went wrong: 25.0.2`；改用 **Gradle 9.7.1 + IDE JBR 25** 后构建成功。IntelliJ Platform Gradle Plugin 2.5.0 可用，官方提示可升级到 2.19.0（本轮保持已验证版本）。

### 5.2 备选路径（本机离线可用的 javac 打包）

```text
$ bash idea-plugin/build-local.sh
build-local: compiling plugin against IntelliJ IDEA.app
build-local: running self test
PluginSelfTest: passed=110 failed=0
build-local: artifact .../distributions/study-pilot-automation-bridge-1.0.0-local.zip
build-local: sha256 9e856f03ebccecf0a9b4808b63a9d4d8ba0fb5bd4df699f9b3ba7e1376c20f27
build-local: INSTALLATION IS NOT PERFORMED — Codex review and explicit user confirmation are required first.
```

### 5.3 产物（可安装 ZIP）

| 来源 | 文件 | SHA-256 | 可复现性 |
| :--- | :--- | :--- | :--- |
| **Gradle（主，权威产物）** | `idea-plugin/build/distributions/study-pilot-automation-bridge-1.0.0.zip` | `b5dffe8d4c44a95280afc92fb73d75be80ef97cd466687958b86632ca19d2455` | **可复现**：连续两次 `buildPlugin` 校验和逐字节一致 |
| 本机 javac（备选） | `idea-plugin/build/distributions/study-pilot-automation-bridge-1.0.0-local.zip` | 每次构建不同（示例 `95babc42db204beb39b7320b4580fb183c7fdb8c76a0afad4224ca20c38b138a`） | **不可复现**：`jar` 写入构建时刻时间戳 |

> 审查与安装应以 **Gradle 产物** 为准（校验和固定）；备选产物仅用于本机离线可用性，其校验和随后续构建变化。

ZIP 结构符合 IntelliJ 插件分发包布局，且 `META-INF/plugin.xml` 位于主 jar 内：

```text
study-pilot-automation-bridge/
study-pilot-automation-bridge/lib/study-pilot-automation-bridge-1.0.0.jar
  └── META-INF/plugin.xml, com/studypilot/automation/idea/**
```

> `idea-plugin/build/` 与 `idea-plugin/.gradle/` 已加入 `.gitignore`；产物本身不提交，由上述命令复现。

---

## 6. 本轮验证结果

```text
cd local-automation-service && npm test
 Test Files  18 passed (18)
      Tests  178 passed | 1 skipped (179)

npm run typecheck            -> tsc --noEmit, 0 errors
npm run build                -> tsc, dist/ 成功（含 dist/main.js）
npm run build:native         -> 0 errors, 0 warnings
npm run test:plugin          -> PluginSelfTest 123/123 + PluginHardeningSelfTest 88/88
gradle selfTest / check      -> 两个 harness 全绿, BUILD SUCCESSFUL
gradle buildPlugin           -> BUILD SUCCESSFUL；产物校验和两次一致
git diff --check             -> 干净
npx tsx scripts/real-acceptance.ts -> 见下
```

真实探针（本机当前状态）：

```text
--- 1. macOS AX diagnostic probe (not an execution path) ---
Native AX addon: LOADED (version 3.0.0, platform darwin)
  axApiAvailable=true axTrusted=true ideaRunning=true ideaWindowExposed=true
  AX outcome for a registered action: REFUSED

--- 2. IDEA execution path (trusted JetBrains plugin bridge) ---
Plugin socket configured: no
  [Real Action] OPEN_REGISTERED_FILE:     BLOCKED (code=PLUGIN_NOT_CONFIGURED)
  [Real Action] FOCUS_RUN_CONFIGURATION:  BLOCKED (code=PLUGIN_NOT_CONFIGURED)
  [Real Action] SHOW_TEST_RESULT:         BLOCKED (code=PLUGIN_NOT_CONFIGURED)

--- 3. Production UDS service ---
Unix socket + 0600 + FAILED(ADAPTER_FAILURE) + REPLAY_DETECTED + INVALID_SIGNATURE + TARGET_NOT_REGISTERED -> 6/6 PASS
--- 4. StudyPilot loopback service: OFFLINE -> 3 项浏览器真实动作 BLOCKED
--- 5. [INTEGRATION_FIXTURE] 浏览器适配器夹具 4/4 PASS（不代表 Task 34 REAL_E2E）
```

---

## 7. 明确的剩余门禁与限制

1. **插件未安装**：按指令本轮**只构建、不安装、不运行**新插件。Codex 需先审查构建产物；安装必须由用户确认后执行。
2. **真实 IDEA 正向验收未执行**：由于插件未安装，三项动作目前仍为 `PLUGIN_NOT_CONFIGURED` 失败关闭，**没有任何真实成功证据**，Task 33 不得声明完成。
3. **插件执行后验证仅在假实现上验证过**：三项 `FileEditorManager` / `RunManager` / `ToolWindowManager` 验证路径的**正向**行为尚未在真实 IDE 中触发；`SHOW_TEST_RESULT` 还要求宿主存在既有的测试结果工具窗口内容。
4. **需要宿主提供插件注册表与密钥**：`~/Library/Application Support/StudyPilot/idea-plugin.tsv`（socketPath、ledgerPath、projectRoot、句柄行，owner-only）+ 环境变量 `STUDYPILOT_IDEA_PLUGIN_HMAC_SECRET`；缺失即插件不启动、服务失败关闭。
5. **服务侧需配置** `STUDYPILOT_AUTOMATION_IDEA_PLUGIN_SOCKET_PATH` / `..._HMAC_SECRET`（路径与密钥都必须与 Java-facing 不同，否则服务拒绝启动）。
6. StudyPilot 服务离线，3 项浏览器真实动作同样 `BLOCKED`；夹具证据不代表 REAL_E2E。
7. 未与 Java 端在本机完成真实进程握手（属 Task 33 真实联调阶段）。
8. 未在非 macOS 平台验证（按设计失败关闭）。

### 7.1 真实安装与验收步骤（待用户确认后执行）

1. Codex 审查 `build/distributions/study-pilot-automation-bridge-1.0.0.zip` 与其 SHA-256（见 §5.3）。
2. **用户确认后**在 IDEA 中 `Settings → Plugins → ⚙ → Install Plugin from Disk…` 选择该 ZIP，重启 IDE。
3. 以 owner-only 权限创建 `~/Library/Application Support/StudyPilot/idea-plugin.tsv`（含 `socketPath`、`ledgerPath`、`projectRoot=` 与三个句柄行），并通过环境变量提供 `STUDYPILOT_IDEA_PLUGIN_HMAC_SECRET`（≥32 字节，与 Java-facing 不同）。
4. 在服务侧设置 `STUDYPILOT_AUTOMATION_IDEA_PLUGIN_SOCKET_PATH` 与 `..._HMAC_SECRET`，重启本地自动化服务。
5. 复跑 `npx tsx scripts/real-acceptance.ts`：三项动作应各自报告其真实结果；只有三项均 `SUCCEEDED` 才具备正向验收证据。
6. 全过程不输入文字、不提交表单、不运行命令、不读取隐私内容。

---

## 8. Codex 安装前审查（`4dcdbd45`）四项阻断的整改

### 8.1 阻断 1：`PluginConfig.load` 对任何真实注册表都不可用

**RED（实测）**：写一份含目标行的真实配置并调用 `PluginConfig.load`：

```text
RED blocker 1: load a real config that HAS target rows
  load FAILED -> java.io.IOException: projectRoot must be declared before target rows
```

根因：第一遍解析时 `projectRootValue` 仍为 `null`，任何三列目标行都会先抛错。

**整改**：改为严格两阶段解析——先做严格 UTF-8 解码与框架校验、收集并校验标量（`socketPath`/`ledgerPath`/`projectRoot`/`uiTimeoutMs`/`secretFile`），建立**规范化**项目根之后才注册目标行，因此**行序无关**。新增拒绝项：重复标量键、未知标量键、重复句柄、列数不符、非法 UTF-8、缺失 `projectRoot`。

**GREEN**：0 非 0 通过——含三类目标（FILE/RUN_CONFIGURATION/TEST_RESULT）的真实配置可加载并逐一解析；行序颠倒同样加载；上述非法情形全部按配置错误拒绝。

### 8.2 阻断 2：`SHOW_TEST_RESULT` 的身份缺口（假成功）

原实现把 TEST_RESULT 句柄只当作工具窗口 ID，然后接受“恰好存在的那一个内容”，因此任何单个无关内容都能成功。

**整改**：
- 注册表要求 TEST_RESULT 必须**同时**绑定固定的结果工具窗口与**精确的内容显示名**（4 列：`handle / TEST_RESULT / 工具窗口ID / 精确内容名`），缺失或多余列即配置错误；
- 新增纯判定类 `TestResultContentMatcher`：只接受 `valid` 且组件类名属于**受支持的测试框架 UI 包** `com.intellij.execution.testframework` 的内容；**精确**显示名匹配（非子串）；0 个 → `NOT_FOUND`，≥2 个 → `AMBIGUOUS`，同名但非测试组件 → `NOT_A_TEST_RESULT`，注册身份为空或超长 → `INVALID_REGISTRATION`。**没有“唯一内容即接受”的兜底**；
- 执行前解析出**唯一**内容对象，`setSelectedContent` 后校验**同一个对象**仍然存在、仍是 `getSelectedContent()`、工具窗口可见且身份不变。

**RED 说明（诚实）**：该阻断的 RED 以“缺失类/缺失身份绑定”编译失败与旧实现的“唯一内容即接受”设计缺口体现，未能在旧代码上跑出运行时断言失败；整改后 12 项对抗性用例全绿——唯一命中、零命中、同名重复（歧义）、**无关单内容**、同名的 console 内容、同名的编辑器内容、无效内容、无效+有效重复、空/超长注册身份、部分标题不匹配。

### 8.3 阻断 3：单帧强制不完整（双向）

**RED（实测，真正的假成功）**：假插件服务端在**同一次写入**中返回“合法帧 + 尾随字节/第二个完整帧”：

```text
FAIL rejects a valid frame followed by trailing bytes in the same write
AssertionError: expected true to be false        <-- 修复前返回了成功
FAIL rejects a valid frame followed by a second complete frame
AssertionError: expected true to be false        <-- 修复前返回了成功
```

**整改**：
- 服务侧 transport：新帧后仍有任何尾随字节 → `PluginResponseInvalidError` → `PLUGIN_RESPONSE_INVALID`；超限响应同样归入该码；并暂停 socket 防止下一段帧被忽略；
- 插件侧 `PluginSocketServer.readFrame`：终止换行之后不得有任何字节（**同一次写入**或**socket 缓冲中已就绪**的第二个帧都会判 `INVALID_FRAME`），请求必须是**恰好一个**换行结束的单行帧；16 KiB 上限与严格 UTF-8 保持不变。

**GREEN**：真实 UDS 上“合法帧 + 尾随 JSON”与“合法帧 + 第二个帧”均返回 `INVALID_FRAME`（插件侧）/`PLUGIN_RESPONSE_INVALID`（服务侧），而单个合法帧仍正常应答（证明不是一刀切拒绝）。

### 8.4 阻断 4：安装前的规范化文件系统绑定

**整改**：
- 新增 `PathBinding`：`canonical()` 先拒绝**任何用户符号链接分量**再做完全解析（`toRealPath`），使 macOS 自带的 `/var`、`/tmp`、`/etc` 系统链接在两侧得到同一规范形式（该例外以精确绝对路径白名单声明）；`contains()` 在**规范化 Path** 上按分量边界判定（`/work/project2` 不属于 `/work/project`）；`requireRegularFile`/`requireDirectory` 拒绝符号链接与非常规文件；
- `PluginConfig` 对配置文件本身、`projectRoot`、socket/ledger 父目录与 FILE 目标全部规范化，FILE 目标还必须位于注册项目根内；
- `IdeaPlatformOperations` 改用 `PathBinding.contains`（不再用字符串前缀或原始 VFS 路径），并要求注册根**等于**打开工程的**规范基础路径**（嵌套在别的工程之下属于不匹配）；
- `PluginSocketServer.start()` 在 socket 路径上遇到**非 socket 对象（常规文件/目录）时拒绝且绝不删除**，`stop()` 也只在**自己绑定过**该路径时才删除。

**RED/GREEN 与发现的两个真实缺陷**：新增的加固自检在实现过程中确实抓到两处真实缺陷并促使修复——

1. 配置里重复句柄原本抛出 `IllegalArgumentException` 而非配置级错误 → 现在统一转为 `IOException`（`invalid target row rejected`）；
2. `stop()` 会删除 socket 路径上的**既有对象**（即使 `start()` 已因“非 socket”而拒绝）→ 现在以 `boundSocketFile` 标记只在成功绑定后删除。

对抗性用例：父目录符号链接、点段（`/./sub/..`）、前缀兄弟目录（`project2`）、不相关根、非常规/符号链接 FILE 目标、symlink 配置文件、socket 父目录其他用户可写、socket 路径上的常规文件（**并断言其未被删除**）、真实 socket 的 0600 权限。

---

## 9. Codex 复核（`e2eaf2c`）两项最终阻断的整改

### 9.1 阻断 A：`npm run acceptance:real` 在干净环境不可用

**RED（实测）**：

```text
$ npm run acceptance:real
> tsx scripts/real-acceptance.ts
sh: tsx: command not found          # 退出码 127；tsx 未在 package.json / package-lock.json 中声明
```

**整改**：将 `tsx` 作为**精确固定**的 devDependency 声明并更新锁文件：

```text
package.json  devDependencies.tsx = "4.23.15"
package-lock.json  packages[""].devDependencies.tsx = "4.23.15"，node_modules/tsx 已锁定
```

**GREEN（仓库内验证命令，无需 npx 下载未声明工具）**：

```text
$ npm run acceptance:real
  [Real Action] OPEN_REGISTERED_FILE:     BLOCKED (code=PLUGIN_NOT_CONFIGURED)
  [Real Action] FOCUS_RUN_CONFIGURATION:  BLOCKED (code=PLUGIN_NOT_CONFIGURED)
  [Real Action] SHOW_TEST_RESULT:         BLOCKED (code=PLUGIN_NOT_CONFIGURED)
  [Real Action 4..6] 浏览器动作 BLOCKED（服务离线）
  退出码 0
```

**新增护栏测试**：解析 `package.json`，凡脚本中调用的工具（`tsx`/`vitest`/`tsc`）必须在 `dependencies`/`devDependencies` 中声明，且 `tsx` 必须为精确版本号；否则测试失败。

### 9.2 阻断 B：单帧强制仍依赖写入时序

原实现的问题：插件在“看到换行”后立即受理，且只做一次非阻塞的 1 字节探测；服务端在“看到第一个换行”后立刻 `pause` 并接受响应。因此**后一次写入**才到达的尾随字节/第二个帧可能被漏检，从而在额外数据到达前就产生一次动作。

**RED（实测）**：把假插件服务端改为“读到 EOF 再应答”后，客户端**全部 17 项用例失败**（每项都等到超时），因为旧客户端从不半关闭写端——这直接证明旧协议依赖时序、且服务端无法确定请求已结束。

**整改（确定性，非 sleep、非一次性探测）**：

- **服务侧客户端**：发送请求后**半关闭写端**（`socket.end(frame)`），随后**缓冲到对端关闭**，只在关闭后校验整段负载——必须以**恰好一个换行结尾**、正文内不得再有换行/回车、长度 ≤16 KiB，并跨分块用 `TextDecoder('utf-8', {fatal:true})` **严格解码**；任何尾随字节（无论落在哪个分块）都判 `PLUGIN_RESPONSE_INVALID`。
- **插件侧服务端**：`readFrame` **读到 EOF** 才受理；要求负载中**恰好一个换行且位于最后一个字节**，否则 `INVALID_FRAME`；超 16 KiB 仍为 `OVERSIZE_FRAME`。读取有 2 秒上限，未在期限内关闭即 `INVALID_FRAME`（客户端不半关闭时确定性失败，而不是猜测）。
- **无副作用保证**：加固自检对“合法帧 + 延迟写入的尾随字节”“合法帧 + 第二个完整帧”两类用例，除断言错误码外还断言**假平台调用次数未增加**——即在验证失败路径上绝不产生界面副作用。

**GREEN（真实 UDS）**：

```text
PluginHardeningSelfTest: passed=55 failed=0
  frame: single valid frame still answered            (对照，证明不是一刀切拒绝)
  frame: single valid frame produced an effect        (假平台调用 +1)
  frame: delayed trailing bytes rejected              INVALID_FRAME
  frame: delayed trailing bytes produced no effect    调用次数不变
  frame: second frame rejected                        INVALID_FRAME
  frame: second frame produced no effect              调用次数不变
```

服务侧同样覆盖“同一次写入的尾随字节/第二个帧”与“**后续分块**才到达的尾随字节/第二个帧”，并新增一个用例断言客户端确实**半关闭了写端**（由假服务端在对端 `end` 事件观测）。

---

## 10. Codex 真实安装后复核（安装包 `e2eaf2c`/`1e5d6d5`）：两项实测生产缺陷的整改

用户在真实 IntelliJ IDEA 2026.1.1 中安装并加载了插件；私有 UDS 存在、属主为用户、权限 0600，`{}`+LF 可立即得到 `MISSING_FIELD`（说明 bind/listen 与基础传输正常），但真实签名动作不通过。实测两项根因如下。

### 10.1 缺陷 1：响应帧缺少终止 LF

**实测**：签名 `OPEN_REGISTERED_FILE` 请求在 5015 ms 后收到 237 字节的 `FAILED/UI_THREAD_TIMEOUT` 响应，且 `ends_newline=false`；服务侧客户端随后一律判 `PLUGIN_RESPONSE_INVALID`。

**根因**：`PluginProtocol.format()` 的注释写明返回以换行结束的帧，但实际返回 `buildLine(...)`（纯 JSON 对象）；`PluginSocketServer.writeFrame()` 原样写出，因此**响应永不带 LF**。

**RED（实测，12 项失败）**：把 `format()` 临时还原为“无 LF”，针对**真实 Java UDS 服务端**的新增帧边界断言全部失败：

```text
PluginHardeningSelfTest: passed=73 failed=12
  framing: rejected  response: ends with exactly one LF / exactly one newline in total / body is exactly one JSON object
  framing: succeeded response: ... （同上三项）
  framing: failed    response: ... （同上三项）
  framing: oversize  response: ... （同上三项）
```

**整改**：`format()` 现在返回 `line + "\n"`（并按含换行的字节数做 16 KiB 上限判断）；请求侧帧契约保持不变。

**GREEN**：同一真实 UDS 连接上，**rejected / succeeded / failed / oversize** 四类响应均满足「恰好一个 LF、且为最后一个字节、正文是单个 JSON 对象、无第二个帧」（`assertSingleResponseFrame`）。

**同时修正一处被写反的旧断言**：`PluginSelfTest` 原本断言 `response: single line frame`＝“响应中**不含**换行”，正是把缺陷当作期望；现改为「恰好一个换行且位于最后一个字节」。

**跨语言传输复测**：TS 客户端新增「响应缺少终止 LF」用例——必须 `PLUGIN_RESPONSE_INVALID`（该用例在修复前后都通过，证明客户端从一开始就是正确的，缺陷纯在 Java 生产者一侧）；TS 解析器与请求侧单帧强制均未放宽。

### 10.2 缺陷 2：真实 UI 执行器从不运行

**实测**：签名请求到达 dispatch 后返回 `UI_THREAD_TIMEOUT`（配置 5000 ms），而 `jcmd` 显示 AWT 事件线程空闲。

**根因**：`IdeUiExecutor` 调用 `application.invokeLater(future, condition -> !application.isDisposed())`。`invokeLater(Runnable, Condition)` 的第二个参数是**过期（expired）判定**：返回 true 表示“放弃执行”。`!isDisposed()` 在 IDE 健康时恒为 true，因此**每个任务都被判定过期而丢弃**，永不执行。

**本地权威性说明（诚实）**：本机 IDEA 分发中不包含平台源码或 sources jar，`javap` 只能看到签名、无法取到该参数的语义文档；因此整改**不依赖**该语义——直接**不再传入任何过期条件**，使该失效模式在结构上不可能出现。`Condition` 参数的含义由实测（线程空闲 + 恒定超时）与 Codex 的独立复核共同确定。

**整改**：
- 新增窄接口 `UiScheduler`（`schedule(Runnable)` + `isDisposed()`），**不暴露任何过期条件**；
- `ApplicationUiScheduler` 使用 `invokeLater(runnable, ModalityState.any())`——无 Condition，且不会被模态对话框无限阻塞（后台恢复动作不应等待模态结束）；
- `IdeUiExecutor`：排队前检查 `isDisposed()` → `IDE_DISPOSING`；等待期间观察到 disposal 同样失败关闭；**超时后 `future.cancel(false)`**，使 UI 线程稍后取到该 `FutureTask` 时成为空操作；派发仍在 UI 线程、socket 线程只做有界等待（**不改同步**）。

**RED/GREEN 与不变量证明**（`uiSchedulerTests`，5 项）：
- 健康的非 disposed IDE：排队可调用**确实执行**并返回值（旧实现下必然超时）；
- UI 线程阻塞：确定性 `UI_THREAD_TIMEOUT`（失败关闭保留）；
- **超时后迟到执行不产生副作用**：延迟到超时之后再运行队列中的任务，断言副作用计数仍为 0（由 `future.cancel(false)` 保证）；
- disposed：抛 `IDE_DISPOSING`，且**不排队任何任务**（计数为 0）。

**新增护栏**：源码级禁止 `invokeLater(future,` 与 `invokeLater(runnable, condition` 形态；要求存在 `ModalityState.any()`、`future.cancel(false)`、`UiScheduler`、`ApplicationUiScheduler`；并要求 `PluginProtocol` 含 `return line + "\n"`。

### 10.3 本轮验证与产物

```text
npm test                      -> 18 files / 179 tests (178 passed, 1 skipped)
npm run typecheck             -> 0 errors
npm run build / build:native   -> OK / 0 warnings
npm run test:plugin           -> PluginSelfTest 117/117 + PluginHardeningSelfTest 85/85 = 202/202
gradle clean check buildPlugin（连续两次） -> BUILD SUCCESSFUL，产物校验和逐字节一致
npm run acceptance:real       -> 见下（诚实环境标注）
git diff --check              -> 干净
```

- **权威产物（Gradle）**：`study-pilot-automation-bridge-1.0.0.zip`，SHA-256 `b5dffe8d4c44a95280afc92fb73d75be80ef97cd466687958b86632ca19d2455`（连续两次 `clean check buildPlugin` 一致，可复现）。
- **本机探针环境（诚实标注）**：`Environment: host=darwin serviceIdaPluginEnv=unset`、`Plugin socket on disk: not configured on this host`；因此本机三项 IDEA 动作仍为 `PLUGIN_NOT_CONFIGURED`。**这不是对已安装插件环境的验收结论**——探针已自报环境并声明其作用域仅限本机。

### 10.4 本轮未做与仍需执行

- **未自行安装或重启 IDEA**，**未修改**用户的 operator 配置与密钥（按指令）。
- 由于插件二进制已变更，Codex 需要用新 ZIP 重新安装后复测；正向验收仍取决于真实 IDE 的执行后验证路径。
- 仍未取得任何真实成功证据；Task 33 保持 BLOCKED，不得声明验收通过。

---

## 11. Codex 真实 IDE 验收（`2590c12` 安装后）：第 3 个动作的识别假设未标定

### 11.1 实测结果

- `OPEN_REGISTERED_FILE`：**SUCCEEDED**（插件侧执行后验证通过）
- `FOCUS_RUN_CONFIGURATION`：**SUCCEEDED**
- `SHOW_TEST_RESULT`：**失败关闭** —— 初始 `RESULT_VIEW_NOT_PRESENT`；用 IDEA 的 `Run → Test History` 打开既有 `LogTest.testLog`（2026-09-15 16:18）后变为 **`RESULT_VIEW_NOT_TEST_RESULT`**

注册表行为：`RESULT_REGISTERED<TAB>TEST_RESULT<TAB>Run<TAB>LogTest.testLog`。真实 AX 树可见 `LogTest.testLog` 工具窗口、`LogTest (com.itmoxiao)`、`1 test passed`、`testLog() passed` —— 即**结果视图真实存在**，但插件仍拒绝，说明识别谓词本身未标定。

### 11.2 根因：用“包装组件类名前缀”判断测试结果

`TestResultContentMatcher` 原先要求 `Content.getComponent().getClass().getName()` 以 `com.intellij.execution.testframework` 开头。Run 工具窗口的内容组件通常是**普通容器包装**，其类名不在该包内，因此真实测试结果被误判为“非测试结果”。

### 11.3 本地权威标定（不依赖宿主观测）

从**本机安装的 IDEA 分发**中枚举与反编译得到的事实（`lib/intellij.platform.smRunner.jar` 等）：

```text
com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView
    public abstract class ... implements ConsoleView, ObservableConsoleView, HelpIdProvider   <-- 测试控制台基类
com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
    extends BaseTestsOutputConsoleView
com.intellij.execution.ui.RunContentDescriptor
    public ExecutionConsole getExecutionConsole()
    public JComponent getComponent()
com.intellij.execution.ui.RunContentManager
    public List<RunContentDescriptor> getAllDescriptors()
```

结论：真正的类型信号在**控制台对象**上，而不在包装组件类名上。

### 11.4 整改：类型/API 支撑的识别（不再看包装类名）

1. 固定结果工具窗口按**精确 id** 解析（不变）；
2. 工具窗口内**恰好一个** `Content` 的 `displayName` **精确等于**注册内容名（不变）；
3. 按**组件同一性**把该 `Content` 关联到 `RunContentDescriptor`（`RunContentManager.getAllDescriptors()`）；
4. 要求 `descriptor.getExecutionConsole() instanceof BaseTestsOutputConsoleView` —— 编译期检查的真实类型判断，而非类名前缀；
5. 执行后复验：**同一对象**仍在、仍 `getSelectedContent()`、仍**重新通过类型判定**、窗口可见；
6. 未知布局一律失败关闭。

**包装类名仅用于诊断**，绝不参与识别。

### 11.5 RED / GREEN

- **RED（真实生产实测）**：真实存在 `LogTest.testLog` 测试结果视图时返回 `RESULT_VIEW_NOT_TEST_RESULT` —— 这是最强的 RED 证据，且旧谓词对任何非 `testframework` 包名的包装类型都必然失败（`javax.swing.JPanel` 等）。
- **RED（本地）**：把识别从「类名前缀」改为「类型/描述符」后，`ContentView` 形状变化导致测试编译失败；随后新增对应用例。
- **GREEN（88 项加固自检中的 13 项匹配器用例）**：
  - **实测包装类场景**：`wrapperType=javax.swing.JPanel` + `descriptorLinked=true` + `testConsole=true` → **MATCHED**（旧谓词在此失败）
  - 无关单内容 → `NOT_FOUND`（无“唯一内容”兜底）
  - 同名重复 → `AMBIGUOUS`
  - 同名普通 console（`descriptorLinked=true, testConsole=false`）→ `NOT_A_TEST_RESULT`
  - 同名编辑器/无关内容（无描述符）→ `NOT_A_TEST_RESULT`
  - 已关联但控制台非测试类型 → `NOT_A_TEST_RESULT`
  - 测试控制台但**未关联描述符** → `NOT_A_TEST_RESULT`
  - 无效内容被忽略；无效+有效同名不产生歧义
  - 空/超长注册名 → `INVALID_REGISTRATION`；部分标题 → `NOT_FOUND`
  - **未知布局**（既未关联又非测试控制台）→ 失败关闭

### 11.6 精确诊断步骤（供 Codex 在宿主上确认；不改协议）

识别失败时，插件**自动**向 IDE 日志写入一行**仅含类型/结构**的诊断（无路径、无正文、无密钥、无选择器、无请求数据）：

```text
StudyPilot result diagnostic: toolWindow=<id> contents=<n> registeredName=<name>
  expectedBaseType=com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView
  | name=<content display name> valid=<bool> descriptorLinked=<bool> testConsole=<bool> wrapperType=<class name>
  ... (最多 12 条内容)
```

查看方式（Codex 侧，只读）：`~/Library/Logs/JetBrains/IntelliJIdea2026.1/idea.log` 中检索 `StudyPilot result diagnostic`。复现一次失败请求（例如 `npm run acceptance:real`，注册行为 `RESULT_REGISTERED<TAB>TEST_RESULT<TAB>Run<TAB>LogTest.testLog`）即会写入该行。

若该行显示 `descriptorLinked=false`，说明此版本的内容未通过组件同一性关联到 `RunContentDescriptor`（需要改用其它受支持关联方式）；若显示 `testConsole=false` 而 `descriptorLinked=true`，说明该版本的结果视图**不是** `BaseTestsOutputConsoleView` 子类（例如新的非 SM 测试视图），此时按契约**失败关闭**并在证据中记录精确阻塞点，而不是放宽识别。

### 11.7 本轮验证与产物

```text
npm test                      -> 18 files / 179 tests (178 passed, 1 skipped)
npm run typecheck / build / build:native -> 0 errors / OK / 0 warnings
npm run test:plugin           -> PluginSelfTest 123/123 + PluginHardeningSelfTest 88/88 = 211/211
gradle clean check buildPlugin（连续两次） -> BUILD SUCCESSFUL，产物校验和逐字节一致
npm run acceptance:real       -> 本机环境未配置插件（诚实标注），三项 IDEA 动作 PLUGIN_NOT_CONFIGURED
git diff --check              -> 干净
```

- **权威产物（Gradle）**：`study-pilot-automation-bridge-1.0.0.zip`，SHA-256 `b5dffe8d4c44a95280afc92fb73d75be80ef97cd466687958b86632ca19d2455`（两次干净构建一致）。
- 本机探针环境（诚实）：`host=darwin serviceIdaPluginEnv=unset`、`Plugin socket on disk: not configured on this host`；探针自报作用域仅限本机。

### 11.8 未完成与待 Codex

- 本轮**未自行安装/重启 IDEA**，未改动 operator 配置与密钥。
- 第 3 个动作的**正向验收仍未取得**：需要 Codex 用新 ZIP 安装后复跑；若仍失败，请附上 `StudyPilot result diagnostic` 行，即可确定是关联方式还是控制台类型问题。
- Task 33 仍未通过验收；`OPEN_REGISTERED_FILE` 与 `FOCUS_RUN_CONFIGURATION` 的 SUCCEEDED 属 Codex 侧实测，本轮未改动其路径。

---

## 12. 修订后的插件配置格式

制表符分隔、`#` 注释；标量行 2 列、目标行 3/4 列，**行序无关**：

```text
socketPath      /abs/path/plugin.sock
ledgerPath      /abs/path/plugin.ledger
projectRoot     /abs/path/of/the/open/project
uiTimeoutMs     2000                       # 可选，250..30000
secretFile      /abs/path/secret           # 可选；否则用环境变量

FILE_REGISTERED      FILE              /abs/path/inside/project/App.java
RUN_REGISTERED       RUN_CONFIGURATION StudyPilotApplication
RESULT_REGISTERED    TEST_RESULT       Run   surefire-reports
```

密钥优先取环境变量 `STUDYPILOT_IDEA_PLUGIN_HMAC_SECRET`（与 Java-facing 密钥不同，≥32 字节）。

---

## 13. 本轮改动文件

新增：`idea-plugin/**`（`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`gradlew` + wrapper、`build-local.sh`、`src/main/java/**` 协议/注册表/派发/平台层、`src/main/resources/META-INF/plugin.xml`、`src/test/java/**` 两个自检 harness）、`src/ideaPluginProtocol.ts`、`src/ideaPluginClient.ts`、`tests/ideaPluginProtocol.spec.ts`、`tests/ideaPluginClient.spec.ts`、`tests/pluginArchitecture.spec.ts`。

审查整改轮新增：`idea-plugin/src/main/java/.../platform/PathBinding.java`、`.../platform/TestResultContentMatcher.java`、`idea-plugin/src/test/java/.../PluginHardeningSelfTest.java`、`idea-plugin/src/test/java/.../PluginTestFrames.java`。

复核整改轮修改：`package.json` + `package-lock.json`（声明并锁定 `tsx@4.23.15`）、`src/ideaPluginClient.ts`（半关闭写端 + 缓冲到 EOF + 严格 UTF-8 + 恰好一帧）、`idea-plugin/.../PluginSocketServer.java`（读到 EOF + 恰好一个末尾换行）、`tests/ideaPluginClient.spec.ts`、`tests/pluginArchitecture.spec.ts`。

真实安装后复核轮修改：`idea-plugin/.../protocol/PluginProtocol.java`（响应帧以恰好一个 LF 结束）、`idea-plugin/.../platform/IdeUiExecutor.java`（`UiScheduler`、无过期条件、超时取消、显式 disposal）、新增 `.../dispatch/UiScheduler.java` 与 `.../platform/ApplicationUiScheduler.java`、`PluginHardeningSelfTest.java`、`PluginSelfTest.java`、`PluginTestFrames.java`、`scripts/real-acceptance.ts`。

真实 IDE 验收轮修改：`idea-plugin/.../platform/TestResultContentMatcher.java`（类型/描述符支撑的识别，废弃包装类名前缀）、`.../platform/IdeaPlatformOperations.java`（`RunContentManager` 关联 + `BaseTestsOutputConsoleView` 类型判定 + 执行后复验 + 仅类型的有界失败诊断）、`PluginHardeningSelfTest.java`（实测包装类与对抗性用例）、`PluginSelfTest.java` 与 `tests/pluginArchitecture.spec.ts`（禁止类名前缀识别的新护栏）、本文件。

---

## 14. 2026-09-23 浏览器产品页对齐与真实验收（ZCode 实施）

### 14.1 背景与契约修订（Codex 第 8 节）

Codex 独立验收指出：
1. `src/browserAdapter.ts` 之前依赖的选择器（`[data-testid="agent-message-input"]`、`[data-testid="open-results-panel-trigger"]`、`[data-testid="workspace-results-panel"]`）在真实 Vue 页面中不存在。`AssistantView.vue` 只有普通 `<textarea class="composer-input">`；`WorkspaceArtifactsView.vue` 只有登记和工作区列表，无结果面板与触发器；
2. 真实验收脚本原先只探测后端 8080 端口，未真正驱动网页执行真实浏览器动作，且用合成夹具冒充真实验收；
3. 契约第 8 节明确授权 ZCode 仅在 `web/src/modules/assistant/AssistantView.vue`、`web/src/modules/roadmap/WorkspaceArtifactsView.vue`、`web/src/services/roadmap.ts`、`web/src/types/roadmap.ts` 及相应聚焦测试范围内实施真实对齐，保持其他所有规则与所有权边界严格不变。

### 14.2 前端实现与 TDD 证据（RED & GREEN）

#### RED 阶段
1. `web/src/modules/assistant/AssistantView.spec.ts`：新增测试断言 `textarea[data-testid="agent-message-input"]` 存在且聚焦，初始失败（`expected false to be true`）；
2. `web/src/modules/roadmap/WorkspaceArtifactsView.spec.ts`：编写失败测试断言：
   - 触发按钮 `data-testid="open-results-panel-trigger"` 存在；
   - 初始时结果面板 `data-testid="workspace-results-panel"` 不展示；
   - 点击触发按钮后面板展开，展示 API 驱动的真实成果节点、版本、得分、反馈及状态；
   - 当 API 发生网络/500/认证失败时，彻底移除 `.catch(() => [])` 吞错行为，展示明确的错误状态 `data-testid="workspace-results-error"` 与重试按钮，严禁伪造成空状态；
   - 当 API 成功返回空列表时，面板展示诚实的真实空状态（`尚未提交实践成果`，`data-testid="workspace-results-empty"`）。
   初始执行测试按预期失败。
3. `local-automation-service/tests/falseSuccess.spec.ts`：增加防假成功断言：当面板处于 `data-testid="workspace-results-error"` 错误状态时，`openResultPanel()` 必须失败关闭（返回 `false`），初始执行失败（RED）。

#### GREEN 阶段
1. **`web/src/modules/assistant/AssistantView.vue`**：在消息输入框上增加稳定语义定位器 `data-testid="agent-message-input"`；
2. **`web/src/types/roadmap.ts`**：增加 `RoadmapArtifactSummaryItem` 接口，严格对齐 MiniMax 正在构建的隐私最小化汇总结构（包含 `id`, `workspaceId`, `status`, `submissionVersion`, `roadmapNode`, `rubricScore`, `rubricFeedback`, `acceptedAt`, `createdAt`；彻底省略 `description`、`testEvidence`、`relativePath`、`canonicalPath`，严禁推断“测试已验证”）；
3. **`web/src/services/roadmap.ts`**：增加 `listArtifacts()` 方法调用 `GET /api/roadmap-artifacts`；
4. **`web/src/modules/roadmap/WorkspaceArtifactsView.vue`**：
   - 增加真实的成果评测结果面板 `[data-testid="workspace-results-panel"]` 与展开触发器 `[data-testid="open-results-panel-trigger"]`；
   - 彻底移除错误吞噬：加载失败时保留错误信息并渲染 `[data-testid="workspace-results-error"]` 与重试按钮；
   - 加载成功且有记录时，按隐私最小化摘要渲染成果节点、版本（`版本 v${artifact.submissionVersion}`）、状态、得分（`rubricScore`）与反馈（`rubricFeedback`）；
   - 无成果时明确展示真实 200 空列表状态（`[data-testid="workspace-results-empty"]`），无任何 mock 或硬编码数据；
   - 点击触发器驱动真实 Vue 响应式状态展开。
5. **`local-automation-service/src/browserAdapter.ts`**：
   - `openResultPanel()` 在点击触发器后增加目标状态安全自检：若面板渲染了 `[data-testid="workspace-results-error"]`，判定为目标状态未验证，确定性失败关闭并记录原因。
6. **测试验证**：
   - `web/src/modules/assistant/AssistantView.spec.ts`：32 项全绿；
   - `web/src/modules/roadmap/WorkspaceArtifactsView.spec.ts`：3 项全绿；
   - `web` 全量回归：35 个测试文件、323 项测试 100% 全部通过；
   - `vue-tsc --noEmit`：0 错误；
   - `vite build`：生产打包通过；
   - `local-automation-service`：18 个测试文件、179 项测试全部通过（1 项跳过）。

### 14.3 浏览器真实验收脚本修订

在 `local-automation-service/scripts/real-acceptance.ts` 中修正浏览器真实验收逻辑：
1. **探测目标校正**：通过 `STUDYPILOT_AUTOMATION_WEB_URL`（开发/部署默认 `http://127.0.0.1:5173`）探测前端 Web 服务，不再错误探测后端 8080 端口；
2. **真实动作执行**：当前端在线时，通过 `PlaywrightBrowserAutomationAdapter` 真实执行全部 3 项浏览器动作（`OPEN_STUDYPILOT_ROUTE`、`FOCUS_AGENT_INPUT`、`OPEN_RESULT_PANEL`），核对精确路由、输入焦点和面板展开可见性；若离线或认证重定向，诚实报告 `BLOCKED` 并说明前置条件；
3. **夹具独立标注**：第 5 节合成夹具明确标为 `[INTEGRATION_FIXTURE]`，明确声明不作为 Task 34 REAL_E2E 证据；
4. **零泄漏保障**：日志与回执中绝不回传页面正文、成果内容、绝对路径、测试证据、隐私字段或密钥。

### 14.4 验收命令与执行结果

```text
cd web && npm test -- --run                     -> 35 files / 323 tests passed
cd web && npm run typecheck && npm run build    -> 0 errors / built in 1.00s
cd local-automation-service && npm test         -> 18 files / 179 tests (178 passed, 1 skipped)
cd local-automation-service && npm run typecheck && npm run build -> 0 errors / tsc built
cd local-automation-service && npm run acceptance:real ->
  - macOS AX diagnostic probe: PASS (refuses every IDEA action)
  - IDEA plugin execution path: BLOCKED (PLUGIN_NOT_CONFIGURED on this host)
  - Production UDS service: PASS (real UDS, 0600, HMAC, nonce replay, registry whitelist)
  - Live StudyPilot loopback service: BLOCKED (web frontend offline at http://127.0.0.1:5173)
  - Browser adapter integration fixture: PASS (4/4 mechanics passed)
  - Fake-success enforcement: no path returns SUCCEEDED without verified state
```

### 14.5 本轮文件所有权与改动清单

本次改动严格限定于授权范围：
- `web/src/modules/assistant/AssistantView.vue`
- `web/src/modules/assistant/AssistantView.spec.ts`
- `web/src/modules/roadmap/WorkspaceArtifactsView.vue`
- `web/src/modules/roadmap/WorkspaceArtifactsView.spec.ts`
- `web/src/services/roadmap.ts`
- `web/src/types/roadmap.ts`
- `local-automation-service/src/browserAdapter.ts`
- `local-automation-service/scripts/real-acceptance.ts`
- `local-automation-service/tests/falseSuccess.spec.ts`
- `local-automation-service/tests/reviewFindings.spec.ts`
- `docs/verification/task-33-local-service.md`

未改动任何 `backend/**`、未合并 `main`、未启动 Task 34。最终 Task 33 仍由 Codex 在真实产品页面独立复跑后判定。

未触碰 `backend/**`、`ai-service/**`、`runner*/**`、`web/**`、其他共享文档与 Obsidian；未合并 `main`；未启动 Task 34；未安装插件。
