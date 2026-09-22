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
PluginSelfTest: passed=103 failed=0
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
- **架构护栏（43 项）**：源码级禁止 TCP/HTTP、通用 Action ID、run/debug/test 执行、Robot/键鼠、反射、Shell，以及 Java-facing Socket 变量名；并**必须存在** `StandardProtocolFamily.UNIX`/`OpenFileDescriptor`/`FileEditorManager`/`RunManager`/`setSelectedConfiguration`/`getSelectedConfiguration`/`ToolWindowManager`/`setSelectedContent`/`getSelectedContent`。

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
PluginSelfTest: passed=103 failed=0
build-local: artifact .../distributions/study-pilot-automation-bridge-1.0.0-local.zip
build-local: sha256 9e856f03ebccecf0a9b4808b63a9d4d8ba0fb5bd4df699f9b3ba7e1376c20f27
build-local: INSTALLATION IS NOT PERFORMED — Codex review and explicit user confirmation are required first.
```

### 5.3 产物（可安装 ZIP）

| 来源 | 文件 | SHA-256 | 可复现性 |
| :--- | :--- | :--- | :--- |
| **Gradle（主，权威产物）** | `idea-plugin/build/distributions/study-pilot-automation-bridge-1.0.0.zip`（45,878 B） | `973047a5663229727269504aa875d866ecd08cfd005c346e4f2c84d56980156e` | **可复现**：Gradle 归档使用固定时间戳（1980-02-01） |
| 本机 javac（备选） | `idea-plugin/build/distributions/study-pilot-automation-bridge-1.0.0-local.zip` | 每次构建不同（示例 `b64c41a700a9e7f6f2a894397a2a934a99d4594bc3347f0269c4a2640f973fc6`） | **不可复现**：`jar` 会写入构建时刻时间戳 |

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
      Tests  165 passed | 1 skipped (166)

npm run typecheck            -> tsc --noEmit, 0 errors
npm run build                -> tsc, dist/ 成功（含 dist/main.js）
npm run build:native         -> 0 errors, 0 warnings
npm run build:plugin         -> PluginSelfTest passed=103 failed=0 + ZIP
npm run test:plugin          -> 同上（插件自检）
gradle selfTest              -> PluginSelfTest passed=103 failed=0 (BUILD SUCCESSFUL)
gradle check buildPlugin     -> BUILD SUCCESSFUL (11 + 16 actionable tasks)
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

## 8. 本轮改动文件

新增：`idea-plugin/**`（`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`gradlew` + wrapper、`build-local.sh`、`src/main/java/**` 协议/注册表/派发/平台层、`src/main/resources/META-INF/plugin.xml`、`src/test/java/**` 自检）、`src/ideaPluginProtocol.ts`、`src/ideaPluginClient.ts`、`tests/ideaPluginProtocol.spec.ts`、`tests/ideaPluginClient.spec.ts`、`tests/pluginArchitecture.spec.ts`。

修改：`src/ideaAdapter.ts`（插件适配器为生产路径；AX 改为仅诊断且拒绝一切 IDEA 结果）、`src/service.ts`（默认装配插件适配器；只转发 `targetKey`）、`src/types.ts`（插件配置字段；`openRegisteredFile(handle)`）、`src/config.ts`（插件 Socket/密钥/超时与两条链路分离校验）、`src/index.ts`、`package.json`（`build:plugin`/`test:plugin`/`build:plugin:gradle`）、`.gitignore`、`scripts/real-acceptance.ts`（逐动作独立诊断）、`tests/{ideaNativeAx,reviewFindings,falseSuccess}.spec.ts`（迁移到新架构）、本文件。

未触碰 `backend/**`、`ai-service/**`、`runner*/**`、`web/**`、其他共享文档与 Obsidian；未合并 `main`；未启动 Task 34。
