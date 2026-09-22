# Task 33 验证证据：受控本地界面适配器服务 (TypeScript)

- **执行 Agent**：MiniMax Code (Mavis) 临时接管（ZCode 配额阻断；Codex 仍为架构与最终验收负责人）
- **测试等级**：`[UNIT_TEST]` / `[STATIC_VALIDATION]` / `[INTEGRATION_TEST]` / `[INTEGRATION_FIXTURE]` / `[LIVE_PROBE]`
- **执行时间**：2026-09-22 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-33-local-adapters`
- **关联基础提交**：`9f8537115ac3d9c3bd86ddc1a43128db3a58414b`（Task 32 Codex 最终验收基线）
- **整改提交历史**：`50dc650`（首轮 P0/P1）→ `3ae231a`（删除虚构 HTTP 桥接，**被 Codex 拒绝**）→ `f520800`（严格结构化身份绑定，**被 Codex 以“未按真实 IDE 标定、过度收紧”拒绝**）→ **本轮提交**（按真实 IntelliJ IDEA 2026.1.1 AX 树重新标定）
- **交付状态**：**BLOCKED — 三项真实 IDEA 动作全部失败关闭；正向验收仍未取得任何成功证据，Task 33 不得声明完成**

---

## 1. 本轮起点：Codex 提供的真实 IDE 标定事实

Codex 在 `IntelliJIdea2026.1/idea.properties` 中启用 JetBrains 官方 macOS 设置 `ide.support.screenreaders.enabled=true` 并重启 IDEA 后，运行中的 IntelliJ IDEA 2026.1.1 首次向 AX 暴露窗口，并给出权威实测树（本轮全部实现以该树为准）：

| 区域 | 真实结构 |
| :--- | :--- |
| 主窗口 | `AXWindow` → `AXGroup desc='根窗格'` |
| 运行配置 | `AXGroup desc='帧标题'` → 嵌套 `AXGroup` → `AXButton desc='<配置名>'`；同级为 `AXButton desc="运行 '<配置名>'"`、`desc="调试 '<配置名>'"`、`desc='更多操作'`。**不存在 `AXToolbar`/`AXPopUpButton`/`AXComboBox`** |
| 项目树 | `AXGroup desc='项目 工具窗口'` → `AXScrollArea` → `AXOutline desc='项目结构树'` → **扁平的 `AXRow subrole=AXOutlineRow` 兄弟节点**，嵌套靠 `AXDisclosureLevel`；`.java` 等源码扩展名被省略；文件夹带 `, <类型>` 后缀 |
| 活动编辑器 | `AXTabGroup desc='TelersWebManagementApplicationTests.java'` + `AXTextArea desc='<文件> 的编辑器'`；**该 build 不提供 `AXDocument`/`AXURL`** |

我另外用只读探针（一次性临时工具，非仓库代码）独立复现了上述结构，并补齐了关键细节（见 §2）。

---

## 2. 本轮实测发现与根因

### 2.1 关键缺陷：`AXUIElementCopyMultipleAttributeValues` 选项用错，导致所有属性读取失败

SDK 头文件 `AXUIElement.h` 只定义了一个选项：

```c
typedef CF_OPTIONS(UInt32, AXCopyMultipleAttributeOptions) {
    kAXCopyMultipleAttributeOptionStopOnError = 0x1
};
```

前两轮代码传的是 `1`，即 **StopOnError（遇错即整体放弃）**，因此每个节点只要有一个属性不受支持（例如根节点上的 `AXDisclosureLevel`），整次读取就失败。这正是 `f520800` 报 `AX_SNAPSHOT_TRUNCATED`／“no project-view row proves ...” 的真实原因：快照里 `nodes` 全为空。

**修复**：传 `0`（按头文件语义：`options = 0` 时逐位置返回错误或 CFNull）。修复后同一窗口可稳定读出 **365 个节点（未截断）**、42 个项目树行、帧标题组与编辑器标签组。

### 2.2 项目树是“扁平行 + 缩进级别”，且单子包被压缩

实测项目树行（节选，`level` 即 `AXDisclosureLevel`）：

```text
level=0 desc=[web-ai-project-learning -01<U+2009>~/IdeaProjects/web-ai-project-learning -01, 模块]
level=1 desc=[telers-web-management, 模块]   level=2 desc=[src]   level=3 desc=[main]
level=4 desc=[java, 源根]                    level=5 desc=[com.itmoxiao]
level=6 desc=[controller]                    level=7 desc=[DeptController]
```

同时发现两点必须处理的事实：

1. **单子包被压缩**：`com.itmoxiao` 一个行对应磁盘上的 `com/` 与 `itmoxiao/` **两个**目录分量；
2. **level-0 模块行把名称与真实路径用 U+2009 THIN SPACE 分隔**（字节 `20 e2 80 89`，不是两个 ASCII 空格），ASCII 空格切分会失败。

此外所有 42 行的 `AXSelected` **恒为真**，因此“行被选中”在本 build 中不具区分度（仍按要求校验，但不作为主要证据）。

### 2.3 真实动作语义（实测）

- 项目树行的**唯一支持动作是 `AXPress`**（`AXUIElementCopyActionNames` 返回 1 项）；`AXConfirm` 返回 `-25200`（`kAXErrorActionUnsupported`）。
- `AXPress` 之后，编辑器 `AXTabGroup` 仍为原先打开的文件；**连续两次 `AXPress` 也不打开文件**。即 IDEA 的 AX `AXPress` 在项目树行上只做“选中”，不执行“打开”。
- 运行配置按钮接受 `AXUIElementSetAttributeValue(kAXFocusedAttribute, true)` 与 `AXPress`（均返回成功），但之后**从不报告 `AXFocused`/`AXSelected`**；窗口也不提供 `AXFocusedUIElement`。即焦点状态在本 build 不可观测。

---

## 3. 按真实树重新实现的窄绑定（`native/idea_ax_bridge.mm` v3.0.0）

### 3.1 三项动作的绑定与成功条件

| 动作 | 唯一注册动作 | 成功必须观测到的 POST 状态 |
| :--- | :--- | :--- |
| `OPEN_REGISTERED_FILE` | 对**唯一**经完整路径证明的项目树行执行一次 `AXPress` | ① 该行在 POST 快照中仍被**唯一**证明且 `selected`；② 活动 `AXTabGroup`/`AXTextArea` 的身份**精确等于**注册 basename（或省略已登记源码扩展名后的显示名） |
| `FOCUS_RUN_CONFIGURATION` | **只请求聚焦**（从不按下，避免打开菜单或改变选择） | POST 快照中同一严格规则定位到的控件**唯一**且报告 `focused` 或 `selected` |
| `SHOW_TEST_RESULT` | 对唯一由 `AXTabGroup` 容器证明的结果视图 `AXTab` 执行一次 `AXPress` | POST 快照中该视图**选中**且可见（编辑器标签角色被显式排除） |

### 3.2 项目树路径证明（消除同名歧义，处理真实显示名）

1. **候选**：`role=AXRow` 且 `subrole=AXOutlineRow`，其直接父节点必须是 `AXOutline`。
2. **链构建**：仅用 `AXDisclosureLevel` 与轮廓顺序，从该行回溯到 level 0；任何无法解析的层级跳跃直接失败（不猜测）。
3. **叶行**：显示名必须**精确等于**注册 basename，或等于“去掉**已登记源码扩展名**（`kOmittedSourceExtensions`）”后的名字；其它任何变换都不接受。
4. **对齐唯一性**：把整条链分量级对齐到注册规范路径——每行消费 ≥1 个连续分量，且消费分量的 `.` 连接必须**精确等于**该行显示名（因此 `com.itmoxiao` 可消费 `com`+`itmoxiao`）。**必须恰好存在 1 种完整对齐**；0 种视为未证明，≥2 种视为歧义，两者都失败关闭。
5. **level-0 锚点**：若按名对齐失败，仅当该行描述中内嵌的真实路径（U+2009 分隔，支持 `~` 展开）是注册规范路径的**分量边界前缀**、且**位于受信注册工作区根之内**时才接受。
6. **唯一性**：整棵轮廓中必须只有一行通过上述证明；否则 `TARGET_AMBIGUOUS`。

### 3.3 其它加固

- **修剪编辑器内容子树**（不进入 `AXTextArea` 内部），预算 20000 节点 / 深度 40，超限显式报 `AX_SNAPSHOT_TRUNCATED`（不再把“空快照”误报为截断）。
- **`CopyVerifiedElement`**：按快照记录的真实子索引回到活元素，并在**派发前**重新读取 role + 身份做精确复核，树变化不会导致按错控件。
- **读取选项修正**（§2.1）与 **U+2009/非 ASCII 空白感知的裁剪**（`StripSpaceLike`）。
- 仍然：遍历根必须是受信 bundle id 的**真实 AXWindow**；无应用级/系统级搜索；无 `containsString`；无 shell / AppleScript / 键鼠模拟；AX 调用 2 秒有界。

---

## 4. 真实 IDE 实测结果（本轮核心证据）

真实运行环境：IntelliJ IDEA 2026.1.1（PID 14285），已打开工程 `web-ai-project-learning -01`，辅助功能权限已授予。

```text
probe : {"platform":"darwin","bridgeVersion":"3.0.0","axApiAvailable":true,
         "axTrusted":true,"ideaRunning":true,"ideaWindowExposed":true}

op1 OPEN_REGISTERED_FILE(<真实工程内 DeptController.java>, 工作区根)
  -> {"ok":true,"verified":false,"code":"STATE_NOT_VERIFIED",
      "detail":"registered file row was activated but the active editor view was not confirmed"}

op1 OPEN_REGISTERED_FILE(<不在该工程内的文件>)
  -> {"ok":false,"verified":false,"code":"TARGET_NOT_FOUND",
      "detail":"no project-view row proves the registered canonical path"}

op2 FOCUS_RUN_CONFIGURATION('TelersWebManagementApplication')
  -> {"ok":true,"verified":false,"code":"STATE_NOT_VERIFIED",
      "detail":"the run-configuration control did not report the registered configuration as focused after the action"}

op3 SHOW_TEST_RESULT('Run')
  -> {"ok":false,"verified":false,"code":"RESULT_VIEW_NOT_IDENTIFIED",
      "detail":"no tool-window result view with the exact registered identity is present"}
```

**解读（逐条诚实说明）**

- **身份绑定已真正跑通**：`ok:true` 表示唯一目标已被证明并成功派发。项目树路径证明、`AXDisclosureLevel` 链、压缩包对齐、U+2009 解析、唯一性拒绝都已在真实树上验证；工程外文件被正确拒绝（`TARGET_NOT_FOUND`），说明没有 basename/子串兜底。
- **`OPEN_REGISTERED_FILE` 仍无法取得成功证据**：`AXPress` 已投递到唯一被证明的行，但 IDEA 仅“选中”而不“打开”（§2.3 实测），因此要求的“活动编辑器为该文件”状态不会出现 → `STATE_NOT_VERIFIED`，回执为 `UNVERIFIED_TARGET_STATE`，**绝不写 SUCCEEDED**。
- **`FOCUS_RUN_CONFIGURATION` 仍无法取得成功证据**：控件身份与 Run/Debug 兄弟绑定均已验证通过且聚焦请求被接受，但本 build 不暴露焦点状态 → `STATE_NOT_VERIFIED`。
- **`SHOW_TEST_RESULT` 保持失败关闭**：真实树中没有既有测试结果视图，按 Codex 要求不臆造角色。

**结论**：三条真实动作在**本 IDE build 的可用 AX 能力内均不可达成已证实的成功**。这不是验证被放宽，也不是实现未接通——恰恰相反，本轮把身份绑定做到了可证伪的精确程度；被阻塞的是“IDEA 是否愿意通过 AX 执行并暴露这些状态”这一外部能力。按契约，宁可失败关闭也绝不虚构成功。

---

## 5. 测试、构建与门禁

```text
cd local-automation-service && npm test
 Test Files  15 passed (15)
      Tests  122 passed | 1 skipped (123)

npm run typecheck  -> tsc --noEmit, 0 errors
npm run build      -> tsc, dist/ 成功（含 dist/main.js）
npm run build:native -> 0 errors, 0 warnings（生产产物 + 测试接缝产物）
```

- 唯一 skip 项仍是“附加模块未构建时诚实失败关闭”的负向分支（本机已构建，故按设计跳过）。
- **`tests/nativeAxSemantics.spec.ts`（31 项）**：夹具直接复刻真实树形状，覆盖压缩包对齐、U+2009 模块行、已登记源码扩展名省略（含 `.txt` **不得**省略的反例）、两个节点证明同一路径 → `TARGET_AMBIGUOUS`、仅选中 basename 不构成编辑器证明、编辑器显示别的文件、部分 basename 不匹配、内嵌根路径锚点及其**受信工作区根**约束、编辑标签角色（AXRadioButton）不得作为结果视图、动作前已可见但未选中 → 失败、动作后消失 → 失败、工具窗口外同标题 → `RESULT_VIEW_NOT_IDENTIFIED`、运行控件必须同时具备精确 Run/Debug 兄弟、运行控件**永不按下**、动作后未聚焦 → 失败等。
- **`tests/sourceGuard.spec.ts`（6 项）**：除 shell/网络/键鼠门禁外，新增“身份必须结构化”门禁——禁止 `containsString`、`AXUIElementCreateSystemWide`、`AXFocusedUIElementAttribute`、参数化/观察者 API；**必须存在** `RowNameMatchesBasename`/`kOmittedSourceExtensions`/`CountAlignments`/`ProveOutlinePath`/`StripSpaceLike`/`AXDisclosureLevel`/`kSubroleOutlineRow`/`kFrameTitleGroupLabel`/`CopyVerifiedElement`/`TARGET_AMBIGUOUS`/`AX_SNAPSHOT_TRUNCATED`/`kRoleWindow`/`AXUIElementSetMessagingTimeout`（防止“删功能过门禁”）；运行配置动作体内**不得出现 `PerformPress`**；源码中**不得出现**编辑标签角色。
- **真实 UDS 探针**：Unix Socket + `0600` + 签名请求 `FAILED`（失败关闭）+ `REPLAY_DETECTED` + `INVALID_SIGNATURE` + `TARGET_NOT_REGISTERED`，6/6 PASS；浏览器适配器隔离夹具 4/4 PASS（不代表 Task 34 REAL_E2E）。
- UDS-only 传输、0600 权限、协议版本、16 KiB 帧、HMAC、60 秒寿命、10 秒漂移、SQLite 原子 nonce 语义**均未改动**。

---

## 6. 明确未完成项（BLOCKED 声明）

1. **三项真实 IDEA 动作均无成功证据**（§4）。Task 33 **不得**标记完成，也不得把本文件当作已验收证据。
2. **`OPEN_REGISTERED_FILE`**：需要 IDEA 通过 AX 提供“打开/激活”能力（而不只是选中），否则按契约只能失败关闭。
3. **`FOCUS_RUN_CONFIGURATION`**：需要 AX 暴露该控件（或窗口）的焦点/选中状态；本 build 未提供。
4. **`SHOW_TEST_RESULT`**：需要真实存在且被 AX 暴露的既有测试结果工具窗口，才能完成角色标定与正向验收。
5. StudyPilot 服务离线，3 项浏览器真实动作同样 `BLOCKED`。
6. 未与 Java 端在本机完成真实进程握手（由 Task 33 真实联调阶段进行）。
7. 未在非 darwin 平台验证（按设计直接失败关闭）。

### 6.1 真实性边界（本轮未做、也不应做的事）

- 未为“让动作通过”而放宽验证（未恢复 basename/子串匹配、未恢复全应用搜索、未把“选中”当作“已打开”）；
- 未新增 shell / `osascript` / AppleScript / 键鼠模拟 / 通用自动化回退；
- 未修改用户的 IDE 设置或系统权限（`ide.support.screenreaders.enabled=true` 由 Codex 设置）；
- 未把夹具或探针结果写成真实成功。

---

## 7. 本轮改动文件

修改：`native/idea_ax_bridge.mm`（v3.0.0，按真实树重标定 + 读取选项根因修复 + 修剪/有界快照）、`src/nativeAxBridge.ts`、`src/ideaAdapter.ts`（规范化真实路径 + 受信工作区根）、`src/types.ts`、`src/actionRegistry.ts`（返回命中的注册工作区根）、`src/service.ts`（传递受信工作区根）、`tests/sourceGuard.spec.ts`、`tests/falseSuccess.spec.ts`、`tests/nativeAxSemantics.spec.ts`（重写为真实树夹具）、本文件。

未改动 `src/{protocol,verifier,nonceStore,canonical,server,config,main,browserAdapter}.ts` 的安全语义。

未触碰 `backend/**`、`web/**`、`ai-service/**`、`runner-service/**`；未合并 `main`；未启动 Task 34。
