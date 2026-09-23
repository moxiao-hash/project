# Task 33 后端验证证据：受控本地界面适配器的签名与 Java Handler

- **任务**：Task 33 受控本地界面适配器 —— Java 侧签名协议、策略迁移、执行 Handler 与治理
- **交付状态**：**待 Codex 验收**（本文件不代表验收通过）
- **执行 Agent**：Mavis（MiniMax Code），后端/Java 实现 Owner
- **测试等级**：`[UNIT_TEST]`（签名、策略、客户端、注册表）+ `[INTEGRATION_TEST]`（真实 Unix Domain Socket 桩 + H2/Spring 治理集成）+ **跨端字节级对齐**（只读比对 ZCode 已交付实现与其固化向量）。**无 `[REAL_E2E]`**：未与对端进程做真实 Socket 握手，Java 侧行为由测试替身验证。
- **执行时间**：2026-09-22 (Asia/Shanghai)
- **分支**：`agent/minimax-task-33-local-handler`
- **工作树**：`/Users/moxiao/IdeaProjects/project-minimax-task-33`
- **共同基线**：`9f8537115ac3d9c3bd86ddc1a43128db3a58414b`（Task 32 验收提交，与派发一致，开工时工作树干净）
- **冻结契约**：`project-main-integration/docs/verification/task-33-frozen-contract.md`（本次未修改任何冻结字段、枚举、传输或所有权边界）
- **首轮交付提交**：`1c5723bc3b9573fa7431c7682c9ac12b91dffb1c`（`feat: execute allowlisted local interface fallbacks`）
- **首轮证据提交**：`b8b04aee2b79f577ced3c8238a5945f66a52eb3f`（`docs: record task 33 backend local interface verification`）
- **Codex 独立验收对象**：`b8b04aee2b79f577ced3c8238a5945f66a52eb3f`（验收结论：三项 P1）
- **P1 整改提交**：`89457c5a4579d1c851fc7588e1e3161d8d8a8e7b`（`fix: close task 33 receipt, framing and nonce protocol gaps`）
- **P1 整改证据提交**：`132b04f067668dfee26c37de0286745b3d05e57e`（`docs: record task 33 protocol gap remediation`）
- **真实握手验证提交**：`6cb2dbfca0bc72d5effe3f6acc4fd7214deed502`（`test: verify task 33 handshake against the real local automation service`）

---

## 0. 范围与文件所有权

只改动 `backend/**`（实现 + 测试 + 配置）、能力矩阵与工具契约（`docs/agent-capability-matrix-v2.md`、
`backend/src/main/resources/agent-contracts/capability-matrix.json`），以及本文件。

- **未改动** `local-automation-service/**`（ZCode Owner）、`web/**`、`ai-service/**`、`runner-service/**`。
- **未合并** `main`，**未开发** Task 34，**未** reset / clean / stash / 改写历史，**未**强推。
- 只推送 `agent/minimax-task-33-local-handler`。

| 文件 | 动作 | 说明 |
|---|---|---|
| `agent/developer/LocalAutomationSigning.java` | 新增 | 规范化、HMAC-SHA256、ownerHash、targetDigest、nonce |
| `agent/developer/LocalAutomationClient.java` | 新增 | 客户端接口 + 稳定错误码常量 |
| `agent/developer/LocalAutomationRequest.java` | 新增 | 不可变已签名请求（冻结 10 字段） |
| `agent/developer/LocalAutomationReceipt.java` | 新增 | 不可变已验证回执（冻结 10 字段） |
| `agent/developer/LocalAutomationStatus.java` | 新增 | `SUCCEEDED / FAILED / REJECTED` |
| `agent/developer/LocalAutomationException.java` | 新增 | 稳定错误码 + 人工恢复提示 |
| `agent/developer/UnixSocketLocalAutomationClient.java` | 新增 | 唯一的 Unix Domain Socket 传输实现 |
| `agent/developer/LocalInterfaceAction.java` | 新增 | 六个冻结动作枚举（带所属通道） |
| `agent/developer/LocalInterfaceRegistry.java` | 新增 | 静态注册表：冻结浏览器目标 + 受信配置的 IDE 符号目标 |
| `agent/developer/LocalInterfaceFallbackService.java` | 新增 | 执行入口（业务 API 优先、失败关闭、失败不写成成功） |
| `agent/developer/LocalInterfaceExecutionResult.java` | 新增 | 可验证执行结果 |
| `agent/developer/InterfaceFallbackPolicy.java` | 修改 | 迁移到六个冻结动作，移除全部旧别名 |
| `agent/developer/InterfaceFallbackDecision.java` | 修改 | 增加 `targetKey` |
| `agent/domain/ExecutionType.java` | 修改 | 增加 `LOCAL_INTERFACE_AUTOMATION` |
| `agent/tool/AgentReadToolConfiguration.java` | 修改 | 预览工具改用 `targetKey`（删除 `arbitraryTarget`） |
| `agent/tool/AgentWriteToolConfiguration.java` | 修改 | 新增 `developer.interface_fallback.execute`（HIGH + 专用确认） |
| `agent/tool/AgentToolOutputSchemas.java` | 修改 | 更新预览 Schema，新增执行 Schema |
| `resources/application.properties` | 修改 | 新增本地适配器配置项（密钥只从环境注入，默认空值失败关闭） |
| `resources/agent-contracts/capability-matrix.json` | 修改 | `workspace-artifacts` 增加新写工具 |
| `docs/agent-capability-matrix-v2.md` | 修改 | 工具目录 64 → 65，新增第 21 个写/本地执行工具条目 |
| `agent/tool/AgentToolCoverageTest.java` | 修改 | 写工具 20 → 21、冻结目录 64 → 65、新增 HIGH 风险断言 |
| `agent/tool/AgentToolOutputSchemasTest.java` | 修改 | 登记 Schema 数 64 → 65 |
| `agent/developer/InterfaceFallbackPolicyTest.java` | 重写 | 六动作迁移与别名清除的失败测试 |
| `agent/developer/LocalAutomationSigningTest.java` | 新增 | 签名/摘要/nonce 单元测试 + 跨端确定性向量（10） |
| `agent/developer/UnixSocketLocalAutomationClientTest.java` | 新增 | 传输/framing/超时/权限/关联测试（20） |
| `agent/developer/LocalInterfaceFallbackWorkflowTest.java` | 新增 | 治理/幂等/隔离/业务 API 优先测试（9） |
| `agent/developer/LocalAutomationStubServer.java` | 新增（测试替身） | 最小 Unix Domain Socket 对端，仅在测试类路径 |

---

## 1. RED 证据（先于生产改动）

### 1.1 RED-A：行为级探针（用迁移前生产 API，断言级失败）

按 TDD 先写临时探针 `LocalInterfaceFallbackRedProbeTest`，**直接调用迁移前的
`InterfaceFallbackPolicy.preview(boolean, String, String, String)`**，验证冻结契约的行为缺口：

```
./mvnw -o -Dtest='LocalInterfaceFallbackRedProbeTest' test
[ERROR] Tests run: 4, Failures: 1, Errors: 2, Skipped: 0
[ERROR] Errors:
[ERROR]   probeFrozenActionsAreAcceptedToday:19 » IllegalArgument 界面兜底不接受任意 URL、选择器、路径或键鼠参数
[ERROR]   probeIdeActionsAreAcceptedToday:37 » IllegalArgument 界面兜底不接受任意 URL、选择器、路径或键鼠参数
[ERROR] Failures:
[ERROR]   probeLegacyAliasesAreAlreadyRejectedToday:30 Expected java.lang.IllegalArgumentException to be thrown, but nothing was thrown.
```

即：迁移前实现**不认** `OPEN_STUDYPILOT_ROUTE` / `OPEN_REGISTERED_FILE`（被当作“任意目标”拒绝），
**仍然接受**旧别名 `OPEN_LOGIN`。探针采集后已删除，不进入交付分支（`git status` 无该文件）。

### 1.2 RED-B：最终交付测试（编译级失败）

```
./mvnw -o -Dtest='InterfaceFallbackPolicyTest' test
[ERROR] InterfaceFallbackPolicyTest.java:[21,19] 找不到符号
[INFO] BUILD FAILURE
```

`LocalInterfaceRegistry` / `LocalInterfaceAction` / `LocalInterfaceResponse*` 等生产类型在 RED 阶段不存在，
因此交付测试先以编译失败记录 RED，再实现生产代码转 GREEN。

### 1.3 RED-C：实现过程中被测试抓出的两个真实缺陷（诚实记录）

| 缺陷 | 首次运行结果 | 修正 |
|---|---|---|
| 测试 Socket 路径超过 `sun_path` 上限 | `UnixSocketLocalAutomationClientTest` 18 个用例 `SocketException: Unix domain path too long` | 测试改用短目录 `/tmp` 短名（`sun_path` 约 104 字节上限），非生产代码问题 |
| **非阻塞 UDS connect 会立即完成** | 修正路径后 20 个用例全部 `LOCAL_ADAPTER_TIMEOUT`（等待永不出现的 `OP_CONNECT`） | `UnixSocketLocalAutomationClient` 改为：`connect()` 返回 `true` 时直接进入写阶段，仅未完成时才注册 `OP_CONNECT` 并等待 |

第二个缺陷是首版实现的真实错误，由测试（而非猜测）发现，已按测试驱动修正。

---

### 1.4 RED-D：Codex 独立验收整改（P1）的失败测试

Codex 对 `b8b04aee2b79f577ced3c8238a5945f66a52eb3f` 的独立验收提出三项 P1。整改前先写失败测试：

```
./mvnw -o -Dtest='LocalAutomationRequestTest,UnixSocketLocalAutomationClientTest' test
[ERROR] Tests run: 26, Failures: 5 -- UnixSocketLocalAutomationClientTest
[ERROR] Tests run: 9, Failures: 6 -- LocalAutomationRequestTest
[ERROR] Tests run: 35, Failures: 11, Errors: 0
```

关键失败（断言级，逐条对应验收意见）：

| 失败用例 | 现象 | 对应 P1 |
|---|---|---|
| `rejectsReceiptsThatOmitTheErrorCodeFieldEntirely` | `Expected LocalAutomationException to be thrown, but nothing was thrown` | 回执 `errorCode` 缺失未被判为非法 |
| `rejectsReceiptFieldsWithWrongJsonTypesInsteadOfCoercingThem` | 同上（11 种错配类型全部被隐式强转放过） | 依赖 `asInt/asText` 强转 |
| `rejectsMalformedTargetDigestFormatBeforeComparison` | `expected: <LOCAL_ADAPTER_PROTOCOL_ERROR> but was: <LOCAL_ADAPTER_RESPONSE_MISMATCH>` | 摘要格式未先校验 |
| `rejectsNonWhitespaceTrailingDataAfterTheReceiptDocument` | `Expected ... to be thrown, but nothing was thrown` | **`readFrame` 在首个换行处返回，静默忽略同一读取块内的尾部字节** |
| `rejectsInjectedNoncesThatBreakTheFrozenBase64Url128BitRule` | 同上（注入的非法 nonce 被放行） | 注入生成器只校验了长度 |
| `LocalAutomationRequestTest` 6 项 | 有效期倒挂、非法 target、非法 ownerHash/签名、非法 requestId、非法 nonce、未知通道/动作组合均未抛异常 | 请求 record 缺少冻结格式不变量 |

---

## 2. 已实现范围与关键设计取舍

### 2.1 签名与规范化（`LocalAutomationSigning`）

- 覆盖且只覆盖冻结契约 §3 的九个字段，顺序固定：
  `version, requestId, ownerHash, channel, action, targetKey, issuedAt, expiresAt, nonce`。
- 长度前缀约定：每个字段写成 `UTF-8 字节长度 + "#" + 值`，UTF-8 拼接后计算 **HMAC-SHA256**，输出小写 hex。
  该编码与 ZCode 已交付的 `local-automation-service/src/canonical.ts`
  （`${Buffer.byteLength(field, 'utf8')}#${field}`）**逐字节一致**，已用对端固化的确定性向量验证（见 §5）。
- `ownerHash = sha256(ownerId)` 小写 hex；请求中**不含**原始 `ownerId`。
- `targetDigest = sha256("channel/action/targetKey")` 小写 hex（冻结契约字面的斜杠拼接形式），
  与 ZCode 的 `calculateTargetDigest` 完全一致，并由跨端向量锁死。
- `nonce` = 16 字节 `SecureRandom` 的 base64url 无填充（≥128 bit）。
- 密钥必须 ≥32 字节且不是文档占位值，否则抛 `IllegalStateException`；客户端在**连接前**失败关闭。

### 2.2 传输（`UnixSocketLocalAutomationClient`，唯一实现）

- 只使用 `SocketChannel.open(StandardProtocolFamily.UNIX)`，**无 TCP、无 HTTP、无任何回退**。
  生产文件内不存在 `ProcessBuilder` / `Runtime.exec` / `InetSocketAddress` / `HttpClient` / AppleScript / AWT Robot
  （已用 grep 复核，命中项只有文档注释中的“禁止”说明）。
- Socket 文件必须存在、非符号链接、且权限不含任何 group/others 位，否则**连接前**拒绝。
- 请求为单行 UTF-8 JSON，字段恰好为冻结 10 字段，以 `\n` 结束，≤16 KiB。
- 连接与读写各自有超时（默认 2000/5000 ms，可配置但被限制在 1–60000 ms）。
- 响应必须 ≤16 KiB、以换行结束、严格合法 UTF-8（`CodingErrorAction.REPORT`）、单个 JSON 文档
  （用 `parser.nextToken() != null` 拒绝“重复 JSON”）。
- 响应字段必须恰好是冻结 10 字段（`errorCode` 可缺省/为 null）：**任何额外字段一律拒绝**，
  包括 `screenshot` / `dom` / `windowTitle` / `filePath` / `url` / `userInput` / `stack` / `secret` / `pageContent`。
- 必须核对 `version`、`requestId`、`adapter == channel`、`action`、`targetDigest`、`status` 枚举、
  ISO-8601 时间戳（`finishedAt >= startedAt`）。
- `errorCode` 必须是稳定码 `[A-Z][A-Z0-9_]{0,63}`；`SUCCEEDED` 不得携带错误码。
- `message` 必须非空、≤200 字符、无控制字符，且不含 URL / `file://` / `javascript:` / HTML 标签 /
  反斜杠路径 / 绝对路径 / 堆栈片段 / `token|secret|password|bearer`。
- `FAILED` 与 `REJECTED` 回执**原样返回给服务层**（由服务层转成失败），客户端绝不把非 `SUCCEEDED` 当成成功。
- 超时、Socket 缺失/权限过宽、协议错误、关联不匹配分别映射为
  `LOCAL_ADAPTER_TIMEOUT / UNAVAILABLE / PROTOCOL_ERROR / RESPONSE_MISMATCH`，
  每条消息都带**人工恢复提示**（“请手动打开目标页面或 IDE 目标后重试，或改用确定性业务 API”），
  且不回显 `ownerId`。

### 2.3 策略迁移与静态注册表

- `LocalInterfaceAction` 恰好六个值，每个动作绑定唯一通道；`InterfaceAutomationChannel` 保持三值不变。
- `LocalInterfaceRegistry`：
  - 浏览器目标**写死在代码里**（`ASSISTANT` / `ASSISTANT_HEALTH` / `WORKSPACE_ARTIFACTS`、
    `ASSISTANT_INPUT`、`WORKSPACE_RESULTS`），配置**无法**放宽（已有测试固定该行为）。
  - IDE 目标只能来自受信本地配置 `studypilot.local-automation.ide-targets`（格式 `ACTION:SYMBOLIC_KEY`），
    **默认空 ⇒ IDE 动作失败关闭**；配置值必须是符号键，路径/URL 直接导致启动期失败。
  - 任何目标都必须匹配 `[A-Z][A-Z0-9_]{0,63}`，因此路径、URL、选择器、自由文本永远无法进入白名单。
- `InterfaceFallbackPolicy.preview(...)` 的校验顺序是**刻意的失败关闭**：
  1. 通道必须是 `PLAYWRIGHT_DOM` / `IDEA_ACCESSIBILITY`（`BUSINESS_API` 不可被请求）；
  2. 动作必须是六个冻结动作之一（旧别名一律 `IllegalArgumentException`）；
  3. 通道与动作必须匹配；
  4. 符号目标必须命中注册表；
  5. **然后**才应用“业务 API 优先”：`businessApiAvailable=true` ⇒ 返回 `BUSINESS_API` 且 `fallbackRequired=false`。
- 取舍说明：先校验、后优先。冻结契约 §4 要求“任何不匹配组合必须失败关闭”，§6 要求业务 API 优先；
  先校验可同时满足两者，并阻止模型用 `businessApiAvailable=true` 去探测未注册/旧动作名。
  代价是“业务 API 可用 + 非法动作名”会报 400 而不是返回 `BUSINESS_API`——这是更严格的一侧。
- 预览工具输入契约由 `arbitraryTarget` 改为冻结的 `targetKey`（删除可能被误用的字段）；
  经 grep 确认 `ai-service/**` 与 `web/**` 没有任何硬编码引用该工具名或旧动作名，无跨端破坏。

### 2.4 执行 Handler 与治理

- 新工具 `developer.interface_fallback.execute`：`EFFECT=WRITE` 元数据的 `GovernedAgentToolHandler`，
  `RISK=HIGH`、`requiredScope=DEVELOPER_MANAGEMENT`、`ExecutionType=LOCAL_INTERFACE_AUTOMATION`、
  强制幂等键、120 s 工具超时、封闭输入/输出 Schema。
- 目标只能由 Java 静态注册表解析；模型只能提交 `channel/actionKey/targetKey` 三个字符串，
  **不可能**透传 URL、选择器、脚本、键鼠、路径或窗口标题。
- 沿用既有治理且**未修改治理代码本身**：`AgentExecution` 创建/确认/终态、`AGENT_ACTION_READY` /
  `AGENT_ACTION_COMPLETED` / `AGENT_FAILED` 通知、审计、持久化幂等键、`REQUIRES_NEW` 成功定稿与租约保护。
- 重复确认：终态动作的再次确认直接回读既有结果，**不重复**调用本地适配器（`verify(times(1))`）。
- 失败不得写成成功：`FAILED` / `REJECTED` 回执与 `LocalAutomationException` 一律抛出，
  由治理路径落为 `FAILED`，`result` 为 `null`；错误文本含稳定错误码与人工恢复提示。
- owner 隔离：确认路径仍走 `findOwnedForUpdate`，他人确认返回 404，且不会触发适配器。

### 2.5 能力矩阵与配置

- 冻结目录 64 → **65** 工具（新增 1 个 `HIGH` 风险写/本地执行工具），矩阵文档与 `capability-matrix.json`
  同步更新为 31 路由 / 65 工具。
- `application.properties` 新增配置项，签名密钥只从 `LOCAL_AUTOMATION_SIGNING_SECRET` 注入，
  默认空值 ⇒ 调用时失败关闭；仓库内**不含任何密钥值**。
- 与 Runner 密钥互相独立（不同属性名、不同环境变量），满足“独立于 Runner 的至少 32 字节密钥”。

---

### 2.6 Codex 验收整改（P1）

1. **回执形状与精确类型**：`REQUIRED_RECEIPT_FIELDS` 现在包含 `errorCode`——字段必须存在，
   只在合法场景下才允许 JSON `null`（`SUCCEEDED` 携带非空错误码仍按不匹配拒绝）。
   解析不再使用 `asInt/asText` 隐式强转：`version` 必须是整数（字符串 `"1"`、小数 `1.5` 一律拒绝），
   `requestId`/`adapter`/`action`/`targetDigest`/`startedAt`/`finishedAt`/`status`/`message` 必须是文本，
   `errorCode` 必须是文本或 `null`；类型错配统一归类为 `LOCAL_ADAPTER_PROTOCOL_ERROR`。
   `targetDigest` 先按 `[0-9a-f]{64}` 校验格式，再做关联比较。
2. **封帧：一次请求只允许一个响应文档**：`readFrame` 不再在首个换行处直接返回。
   终止换行之后的字节只允许 JSON 空白；任何非空白尾部字节（第二个换行分隔的 JSON 对象、
   `{}`、`JUNK` 等）都失败关闭。终止后使用 `selectNow()` 只消费已缓冲数据，
   不因对端保持连接而增加时延；同时把尾部空白填充限制在 64 字节以内，
   避免对端以无限空白流拖住客户端（`MAX_TRAILING_PADDING_BYTES`）。
   **无需与对端变更契约**：只读核对 ZCode `428b008` 的 `formatReceiptFrame`，其对每次请求
   只写出 `JSON.stringify(receipt) + '\n'` 一个文档（正常路径不追加任何字节），
   因此本分支的严格封帧与对端行为一致，不构成双方需要重新冻结的接口变更。
3. **nonce 与请求记录不变量**：新增 `LocalAutomationSigning.requireValidNonce`，
   base64url 无填充且解码后 ≥128 位，**内部生成器与注入生成器走同一校验**；
   `LocalAutomationRequest` 强制固定版本、小写 UUID `requestId`、小写 sha256 hex 的
   `ownerHash`/`signature`、已注册且互相匹配的通道/动作、符号目标、合法 nonce，
   以及不倒挂且 ≤60 秒的有效期。

---

## 3. 已执行命令与结果

| 命令 | 结果 |
|---|---|
| `./mvnw -o -Dtest='LocalAutomationSigningTest' test` | **10 项通过**，0 失败 0 错误（含 ZCode 跨端向量） |
| `./mvnw -o -Dtest='InterfaceFallbackPolicyTest' test` | **9 项通过**，0 失败 0 错误 |
| `./mvnw -o -Dtest='UnixSocketLocalAutomationClientTest' test` | **27 项通过**，0 失败 0 错误（真实 UDS 桩；含封帧尾部数据与类型错配） |
| `./mvnw -o -Dtest='LocalAutomationRequestTest' test` | **9 项通过**，0 失败 0 错误（请求记录冻结格式不变量） |
| `./mvnw -o -Dtest='LocalAutomationSigningTest,InterfaceFallbackPolicyTest,UnixSocketLocalAutomationClientTest,LocalInterfaceFallbackWorkflowTest,LocalAutomationRequestTest' test` | **64 项通过**，0 失败 0 错误 |
| `./mvnw -o -Dtest='LocalInterfaceFallbackWorkflowTest' test` | **9 项通过**，0 失败 0 错误（H2 + Spring 治理） |
| `./mvnw -o -Dtest='AgentToolCoverageTest,AgentToolOutputSchemasTest,AgentToolOutputValidatorTest,AgentToolRegistryTest,AgentToolActionRecoveryTest,AgentToolActionRecoveryRollbackTest' test` | **22 项通过**，0 失败 0 错误 |
| `./mvnw -o test` | **577 项，0 失败 0 错误，7 跳过**，`BUILD SUCCESS`（基线 508 → 新增 62 通过 + 7 跳过）。7 项跳过即 §7 的可选真实握手用例，未配置系统属性时自动跳过 |
| `node scripts/verify-agent-capability-matrix.mjs` | `[SUCCESS] 能力矩阵校验通过！覆盖全部 31 个页面路由与 65 个 Java 工具` |
| `node --test scripts/verify-agent-capability-matrix.test.mjs` | 门禁自身 **4/4 通过** |
| `./mvnw -o -Dtest='LocalAutomationRealHandshakeTest' -Dspl.handshake.enabled=true -Dspl.handshake.socket=/tmp/spl33-hs.sock -Dspl.handshake.secret=<32B> test` | **7 项通过**，0 失败 0 错误（对端真实 `50dc650` 服务，见 §7） |
| `git diff --check` | 无输出（干净）；新文件亦无行尾空白 |
| 生产文件 grep（`ProcessBuilder`/`Runtime.exec`/`InetSocketAddress`/`HttpClient`/AppleScript/Robot） | 仅命中“禁止”注释，无实际调用 |

测试覆盖与派发要求的对应关系：

- 任意字段/额外字段：`rejectsArbitraryUrlsSelectorsScriptsKeysTextAndPaths`、
  `arbitraryLegacyAndExtraFieldsAreRejectedBeforeAnyActionIsCreated`、
  `rejectsResponsesCarryingUnexpectedOrPrivacyBearingFields`、`rejectsUnsanitizedOrOverlongReceiptMessages`
- 错签名/短密钥：`refusesToSignWithShortOrPlaceholderSecretsBeforeConnecting`、`signatureDependsOnTheIndependentSecret`
- 过期/未来时间：`requestWindowIsExactlySixtySecondsAndNeverLonger`、`surfacesExpiredAndFutureDatedRejectionsFromTheService`
- nonce 重放（含重启后）：`noncesAreFreshPerRequestAndReplayStaysRejectedAcrossServiceRestart`（同一 nonce 持久化消费后，**新的**服务实例仍返回 `REJECTED/NONCE_REPLAY`）、`productionNonceSupplierNeverReusesANonceAcrossRequests`
- 通道/动作/目标错误组合：`rejectsEveryChannelActionTargetCombinationThatIsNotFullyRegistered`、`neverRevivesAnyLegacyAlias...`
- Socket 权限过宽：`refusesWorldAccessibleSocketsBeforeConnecting`（且断言**未建立连接**）
- framing/超限/非法 UTF-8/重复 JSON：`rejectsOverlongResponseFramesBeyond16KiB`、`rejectsInvalidUtf8Responses`、`rejectsDuplicateJsonDocumentsInOneFrame`、`requestFrameIsOneUtf8JsonLineWithExactlyTheFrozenFields`
- 超时/不可用：`failsClosedOnReadTimeoutWithoutClaimingSuccess`、`failsClosedWithManualRecoveryWhenTheSocketIsMissing`
- 响应关联/摘要不匹配：`rejectsResponsesThatDoNotCorrelateWithTheRequest`（requestId/adapter/action/targetDigest/status/version）
- 重复确认：`repeatedConfirmationNeverRepeatsTheLocalAction`
- owner 隔离：`anotherOwnerCanNeitherConfirmNorExecuteThisOwnersAction`
- 业务 API 优先：`businessApiPriorityNeverTouchesTheLocalAdapter`、`businessApiAlwaysWinsAndNeverAsksForTheLocalAdapter`
- 失败/拒绝不写成功：`failedAndRejectedReceiptsAreNeverRecordedAsSuccess`、`unavailableAdapterFailsHonestlyWithManualRecoveryGuidance`
- 无业务 API 的合法动作：`everyFrozenIdeActionIsAllowedWhenNoBusinessApiExists`、`acceptsExactlyTheThreeFrozenBrowserActionsWithRegisteredTargets`
- 跨端字节级对齐：`matchesZcodeDeliveredCrossLanguageVectorsByteForByte`、`targetDigestUsesTheLiteralSlashJoinedForm`
- **Codex P1 整改**：回执字段缺失/类型错配（`rejectsReceiptsThatOmitTheErrorCodeFieldEntirely`、`rejectsReceiptFieldsWithWrongJsonTypesInsteadOfCoercingThem`）；
  摘要格式（`rejectsMalformedTargetDigestFormatBeforeComparison`）；封帧尾部数据
  （`rejectsNonWhitespaceTrailingDataAfterTheReceiptDocument`、`toleratesWhitespaceOnlyFramingPaddingAfterTheReceiptDocument`、
  `rejectsAnUnboundedWhitespaceFloodAfterTheReceiptDocument`）；
  nonce 规则（`rejectsInjectedNoncesThatBreakTheFrozenBase64Url128BitRule`）；请求记录不变量（`LocalAutomationRequestTest` 9 项）

---

## 4. 明确未实现 / 未验证范围

1. **真实 Java↔TypeScript UDS 握手已执行**（对端整改 `50dc650`，详见 §7）；
   但其中界面适配器是**注入的测试替身**，因此只证明跨语言传输/签名/白名单/nonce/封帧/回执关联一致，
   **未观察真实浏览器或 IDE 界面状态，不构成 OS 界面 `REAL_E2E`**。
2. **未做**真实本机最小验收（打开固定路由、打开临时登记源码、聚焦预登记运行配置、展示既有测试结果）。
   该项需与 ZCode 服务真实联调后由 Codex 组织。
3. **未修改** `docs/协同开发交接说明.md` 与冻结计划（非本 Agent 所有权）。
   其中 `docs/协同开发交接说明.md:449/467` 仍写“64 个工具”，需 Codex 在其所有权内更新为 65。
4. **未运行** `ai-service` 与 `runner-service` 测试套件：本分支未改动这两个目录（`git status` 可证），
   按派发要求无需运行。
5. IDE 通道在生产默认配置下**失败关闭**（未登记任何符号目标），因此当前生产环境只会走浏览器通道。

---

## 5. 与 ZCode 已交付本地服务的跨端字节级对齐（真实对端证据）

ZCode 已在 `/Users/moxiao/IdeaProjects/project-zcode-task-33`（分支
`agent/zcode-task-33-local-adapters`）交付本地服务并固化跨语言确定性向量；当前复审修订为
`50dc650fd41298c8cb64fe085a578bd37a2f24e8`（首轮交付 `428b008`）。该整改**未改动** `canonical.ts`，
因此下表的规范化与摘要对齐结论继续成立。
**本分支只读取对端文件用于对齐，未修改其任何文件**（`git status` 无 `local-automation-service/**`）。

对齐结论（对端来源：`local-automation-service/src/canonical.ts`、`tests/vectors.spec.ts`、
`docs/verification/task-33-local-service.md` §4）：

| 项目 | 对端实现 | 本分支实现 | 结果 |
|---|---|---|---|
| 规范化载荷 | `${Buffer.byteLength(field,'utf8')}#${field}` 九字段顺序拼接 | `LocalAutomationSigning.canonicalRequestPayload` | **逐字节一致** |
| HMAC-SHA256 | `crypto.createHmac('sha256', secret)` hex | `LocalAutomationSigning.signature` | **一致**：`e471c52c…c943` |
| 浏览器 `targetDigest` | `sha256("PLAYWRIGHT_DOM/OPEN_STUDYPILOT_ROUTE/ASSISTANT")` | 同上 | **一致**：`d30b1c64…f0eb` |
| IDE `targetDigest` | `sha256("IDEA_ACCESSIBILITY/OPEN_REGISTERED_FILE/FILE_SAMPLE")` | 同上 | **一致**：`66914972…171e` |
| 回执字段集 | `version/requestId/adapter/action/targetDigest/startedAt/finishedAt/status/errorCode\|null/message` | 严格解析这 10 个字段 | **一致** |
| 请求/回执封帧 | 单行 UTF-8 JSON、≤16 KiB、换行结束 | 同上 | **一致** |
| 动作与目标 | 六动作；浏览器目标 `ASSISTANT/ASSISTANT_HEALTH/WORKSPACE_ARTIFACTS/ASSISTANT_INPUT/WORKSPACE_RESULTS`；IDE 目标来自配置注册句柄 | 六动作枚举 + 冻结浏览器目标 + 受信配置 IDE 句柄 | **一致** |

**对齐过程中修正的真实缺陷（诚实记录）**：本分支首版 `targetDigest` 误用了与签名同族的
`len#value` 拼接，算出 `ca91ad47…`，与对端 `d30b1c64…` **不匹配**。读取对端 `canonical.ts` 后按契约字面
（斜杠拼接）修正，并新增 `LocalAutomationSigningTest.matchesZcodeDeliveredCrossLanguageVectorsByteForByte`
与 `targetDigestUsesTheLiteralSlashJoinedForm` 两个测试锁死。这属于**实现修正**，
未改动任何冻结字段、枚举或传输定义。

对端文档 §4.3 中 IDE `targetDigest` 的硬编码期望值 `362f3a47…` 与其自身 `calculateTargetDigest`
实际输出 `66914972…` 不一致（对端 `tests/vectors.spec.ts` 对 IDE 用例是**自算**校验而非固定资产）。
本分支以**对端实现**为准，并已在该测试注释中标注来源，供 Codex 复核。

**仍未闭合的一项**：IDE 通道的符号键字符串必须与对端配置的注册句柄完全一致
（对端示例为 `FILE_SAMPLE`）。Java 侧通过 `studypilot.local-automation.ide-targets` 登记，
默认空 ⇒ 失败关闭。真实联调时需由 Codex 统一两侧配置值。

**对端修订与时间戳加固（已核对，兼容）**：`50dc650` 新增 `isValidUtcIsoInstant`，
要求 `issuedAt`/`expiresAt` 是**以 `Z` 结尾的规范 UTC ISO-8601**（允许 1–9 位小数秒），
并新增“`expiresAt` 早于 `issuedAt` 拒绝”。本分支请求使用 `Instant.truncatedTo(SECONDS).toString()`，
输出形如 `2026-09-22T08:00:00Z`，与之兼容；真实握手 7/7 通过即为实测证明。
对端工作树仍有**未提交**的后续改动（`actionRegistry.ts`、`browserAdapter.ts`、`ideaAdapter.ts` 等），
本文件的结论只针对**已推送的 `50dc650`**。

---

## 6. 遗留与下一步

- 代码质量意见（可能影响验收）：`developer.interface_fallback.execute` 为 `HIGH` 风险，
  因此“业务 API 优先”分支（不产生任何本地副作用）同样需要一次专用确认。
  这是失败关闭的选择——宁可多一次确认，也不允许该工具在无确认下进入执行路径。
  如 Codex 认为该无副作用分支不应要求确认，需要修改的是工具风险分级契约，请由 Codex 决定。
- 已完成：三项 P1 整改（§2.6）、真实 Java↔TypeScript UDS 握手（§7）、
  跨端字节级对齐复核（§5）。
- 仍待 Codex 决定 / 组织：
  1. **OS 界面真实验收**：真实浏览器路由/输入聚焦/结果面板与真实 IDE 动作需要真实界面证据，
     本分支只能提供协议与传输证据，不得替代。
  2. **IDE 符号键统一**：两侧需配置同一组 IDE 符号句柄（本分支默认空 ⇒ 失败关闭）。
  3. `docs/协同开发交接说明.md` 中“64 个 Java 工具”需更新为 65（非本 Agent 所有权）。
  4. 上述 HIGH 风险无副作用分支是否仍需确认（工具风险分级契约，由 Codex 决定）。

---

## 7. 真实 Java↔TypeScript UDS 握手（已执行，对端 `50dc650`）

ZCode 已推送整改 `50dc650fd41298c8cb64fe085a578bd37a2f24e8`
（`fix: remediate local automation service P0 and P1 blocking issues`）。本分支据此执行了真实握手。

### 7.1 方法（不改动对端任何文件）

1. 用 `git archive 50dc650 local-automation-service` 把对端**已提交**源码导出到
   `/tmp/spl33-hs-1`（不写入对端工作树，不受对端未提交 WIP 影响），软链其 `node_modules` 并用
   对端自带 `tsc` 构建出 `dist`。
2. 用本分支测试支撑脚本 `backend/src/test/resources/local-automation/handshake-server.mjs`
   启动对端**真实** `LocalAutomationServer`，只注入**测试适配器**（记录调用并返回布尔结果，
   不触碰任何真实界面）；Socket 为 `/tmp/spl33-hs.sock`、nonce 库为 `/tmp/spl33-hs-nonce.db`。
3. Java 侧用**真实** `UnixSocketLocalAutomationClient`（真实密钥与 socket 路径）执行
   `LocalAutomationRealHandshakeTest`（7 项，未配置系统属性时自动跳过，因此常规全量测试不受影响）。

```
LOCAL_AUTOMATION_SERVICE_DIST=/tmp/spl33-hs-1/local-automation-service/dist \
HANDSHAKE_SECRET=task-33-real-handshake-secret-key-32b \
HANDSHAKE_SOCKET=/tmp/spl33-hs.sock HANDSHAKE_NONCE_DB=/tmp/spl33-hs-nonce.db \
HANDSHAKE_BASE_URL=http://127.0.0.1:8099 \
node backend/src/test/resources/local-automation/handshake-server.mjs
# 另开终端
./mvnw -o -Dtest='LocalAutomationRealHandshakeTest' -Dspl.handshake.enabled=true \
  -Dspl.handshake.socket=/tmp/spl33-hs.sock \
  -Dspl.handshake.secret=task-33-real-handshake-secret-key-32b test
```

结论：`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`（0.5 s）。
对端 Socket 文件实际权限为 `srw-------`（0600），满足 Java 侧所有者权限校验。

### 7.2 覆盖内容

| 类别 | 用例 | 结果 |
|---|---|---|
| 成功（浏览器） | `OPEN_STUDYPILOT_ROUTE`/`ASSISTANT`、`FOCUS_AGENT_INPUT`/`ASSISTANT_INPUT`、`OPEN_RESULT_PANEL`/`WORKSPACE_RESULTS` | 全部 `SUCCEEDED` |
| 成功（IDE） | `OPEN_REGISTERED_FILE`/`SOURCE_PRIMARY`、`FOCUS_RUN_CONFIGURATION`/`RUN_DEFAULT`、`SHOW_TEST_RESULT`/`TEST_LATEST` | 全部 `SUCCEEDED` |
| 拒绝 | 未注册目标、动作与目标不匹配、未注册 IDE 句柄 | `REJECTED` 且带稳定错误码 |
| 重放 | 同一 nonce 第二次请求 | `REJECTED`（对端持久化 nonce 生效） |
| 时间窗 | 客户端时钟回拨 600 s（过期请求） | `REJECTED` |
| 签名 | 使用不同密钥签发的请求 | `REJECTED` |
| 关联 | 每个回执的 `requestId`/`adapter`/`action`/`targetDigest` | 与本次请求三元组逐项一致 |
| 客户端前置 | 通道/动作不匹配、路径型目标 | 本地即拒绝，未发出请求 |

### 7.3 对端适配器调用日志（证明只执行了窄动作，且拒绝路径零副作用）

```
HANDSHAKE_ADAPTER_CALLS [
 {"adapter":"browser","method":"focusAgentInput"},
 {"adapter":"browser","method":"openResultPanel"},
 {"adapter":"browser","method":"openRoute","route":"http://127.0.0.1:8099/"},
 {"adapter":"browser","method":"openRoute","route":"http://127.0.0.1:8099/"},
 {"adapter":"idea","method":"openRegisteredFile",
  "filePath":"/private/var/folders/.../spl33-ws-ZnUUpz/Prima.java"},
 {"adapter":"idea","method":"focusRunConfiguration","handle":"RUN_DEFAULT"},
 {"adapter":"idea","method":"showTestResult","handle":"TEST_LATEST"}]
```

要点：驱动全部来自对端**受信回环配置**解析出的 `http://127.0.0.1:8099/`，而不是请求报文里的任意 URL；
拒绝用例（未注册目标、重放、过期、错密钥）**没有产生任何适配器调用**，符合“验签或校验失败不得产生界面副作用”。

### 7.4 证据口径（诚实声明）

- 本项可标记为：**真实 Java↔TypeScript Unix Domain Socket 传输与协议一致性**。
- **不得**标记为 OS 界面 `REAL_E2E`：界面适配器是注入的测试替身，未观察真实浏览器/IDE 状态变化。
- 真实桌面浏览器的 `real-acceptance.ts`（对端 Playwright headless 脚本）与真实 IDE 原生桥
  （对端明确 `BLOCKED`，宿主未装合规桥）都未在本分支验证。

### 7.5 可重复性与 nonce 持久化（本次自查修正的真实缺陷）

首版握手用例使用**固定 nonce** 验证重放，导致对端 nonce 库已消费该值后**第二次运行必然失败**——
这会让证据不可重复复现。已改为每次运行生成全新随机 nonce（仍用于同一 nonce 的两次调用），
并在**同一个长驻真实服务 + 同一持久化 nonce 库**上连续运行两次：

```
run #1: Tests run: 7, Failures: 0, Errors: 0, Skipped: 0  (0.740 s)
run #2: Tests run: 7, Failures: 0, Errors: 0, Skipped: 0  (0.392 s)
```

两次运行的对端适配器日志合计正好 14 次调用（2 × 7），全部是窄动作；
重放/过期/错密钥/未注册目标等拒绝用例在两次运行中都**没有**产生任何适配器调用，
说明对端 nonce 持久化在服务重启与重复运行后仍然有效，且拒绝路径始终零界面副作用。

---

## 8. 契约 §8 修订：登录态只读成果列表 `GET /api/roadmap-artifacts`

### 8.1 范围

按冻结契约 §8（2026-09-23 Codex 浏览器产品页对齐修订）新增的唯一后端生产项：
`/workspaces` 只读实践成果结果面板所需的、基于登录态的成果列表入口。

- 使用既有 `RoadmapArtifactService.artifacts(ownerId)`，**未修改** service、未新增查询路径。
- `ownerId` 只来自 `@AuthenticationPrincipal AuthenticatedUser`，**不接受** query、请求体或请求头传入。
- **未改变**任何提交 / 评审 / 接受的写治理，**未改变**单项
  `GET /api/roadmap-artifacts/{artifactId}`、`POST`、`review-previews`、`evaluate`、`accept`、`reject`。
- **未触碰**六个本地自动化动作、wire 协议、签名、Socket、Handler、工具 Schema 与风险等级；
  本文件 §2–§7 的结论不受本节影响。
- 改动文件：`RoadmapArtifactController.java`（新增一个 `@GetMapping`）、
  新增 `RoadmapArtifactSummaryResponse.java`、新增 `RoadmapArtifactListApiTest.java`。
  **零改动** `web/**`、`local-automation-service/**`、`ai-service/**`、`runner-service/**`。

### 8.2 RED（先于生产代码）

```
./mvnw -o -Dtest='RoadmapArtifactListApiTest' test
[ERROR] Tests run: 6, Failures: 5, Errors: 0, Skipped: 0
[ERROR]   RoadmapArtifactListApiTest.listsOnlyTheAuthenticatedOwnersArtifacts:80 Status expected:<200> but was:<405>
[ERROR]   RoadmapArtifactListApiTest.neverAcceptsCallerSuppliedOwnerIdFromQueryOrHeaders:125 ... but was:<405>
[ERROR]   RoadmapArtifactListApiTest.returnsARealEmptyListForAnOwnerWithoutArtifacts:101 ... but was:<405>
[ERROR]   RoadmapArtifactListApiTest.listingHasNoWriteSideEffectOnArtifactOrReviewState:157 ... but was:<405>
[ERROR]   RoadmapArtifactListApiTest.resultPanelPayloadExcludesPathsEvidenceAndRawReviewPayloads:184 ... but was:<405>
```

`405 Method Not Allowed`：集合路径当时只有 `@PostMapping`，没有列表读入口；
未认证用例（`requiresAnAuthenticatedPrincipal`）在 RED 阶段即返回 401，因为
`SecurityConfig` 的 `anyRequest().authenticated()` 覆盖该路径。

### 8.3 GREEN 与响应隐私

列表入口只返回**面板所需的只读投影** `RoadmapArtifactSummaryResponse`：

| 分类 | 字段 | 说明 |
|---|---|---|
| 保留 | `id`、`workspaceId`、`createdAt` | 标识与时间（`workspaceId` 是不透明 id，不是路径） |
| 保留 | `status`、`submissionVersion`、`evaluationMode` | 成果状态 |
| 保留 | `roadmapNode`（节点/模块/阶段 id 与标题） | 所属节点 |
| 保留 | `rubricScore`、`rubricFeedback`、`sensitiveScanPassed`、`acceptedAt` | 适合展示的评测信息 |
| 保留 | `reviewHistory`：`toStatus`、`eventType`、`score`、`createdAt` | 评审事件的最小投影 |
| **排除** | `canonicalPath` | 本机绝对路径；契约禁止出现在该浏览器结果面 |
| **排除** | `relativePath` | 文件路径；面板不需要 |
| **排除** | `testEvidence` | 测试证据；契约禁止 |
| **排除** | `description` | 成果内容描述；契约禁止 |
| **排除** | `sensitiveFindings` | 敏感扫描明细（隐私字段） |
| **排除** | `rubricBreakdownJson`、`reviewHistory[].details` | 原始评审载荷 |
| **排除** | `ownerId` | 归属由登录态决定，不回传 |

实现不写任何日志，不记录路径、证据或用户内容。

**需要 Codex 确认的跨端对齐项**：§8 未冻结该入口的响应字段集。本分支选择了上面的最小只读投影，
理由是 §8 明确禁止把绝对路径、测试证据、成果内容与隐私字段带到该浏览器结果面。
若 Codex 更希望与单项 GET 保持同构（复用完整 `RoadmapArtifactResponse`，含 `canonicalPath`/`testEvidence`），
这是单点可回退的改动；但在该决定落地前，本分支按更严格的一侧实现。
ZCode 的前端面板需按上述字段集对接（`web/src/services/roadmap.ts` 由 ZCode 所有）。

### 8.4 测试覆盖（7 项，全部通过）

| 用例 | 断言 |
|---|---|
| `listsOnlyTheAuthenticatedOwnersArtifacts` | 只返回登录用户自己的成果；他人成果 id 不出现 |
| `returnsARealEmptyListForAnOwnerWithoutArtifacts` | 无成果时返回真实空列表（支撑真实空状态） |
| `neverAcceptsCallerSuppliedOwnerIdFromQueryOrHeaders` | 携带 `ownerId`/`owner_id` query 与 `X-Owner-Id`/`X-Owner-Id-Override`/`ownerId` 头仍只返回登录用户成果 |
| `requiresAnAuthenticatedPrincipal` | 未认证返回 401 |
| `listingHasNoWriteSideEffectOnArtifactOrReviewState` | 列表调用前后实体 `status`/`submissionVersion`/`acceptedAt`、评审事件数与单项 GET 读模型完全一致 |
| `resultPanelPayloadExcludesPathsEvidenceAndRawReviewPayloads` | 禁止字段全部缺失；响应不含工作区绝对路径、成果文件内容、测试证据文本；出现的字段都在允许集合内 |
| `reviewedArtifactExposesEvaluationInfoWithoutLeakingRawPayloads` | 评审后仍只暴露评测信息，不含 `details`/`rubricBreakdownJson`/路径 |

### 8.5 已执行命令与结果

| 命令 | 结果 |
|---|---|
| `./mvnw -o -Dtest='RoadmapArtifactListApiTest' test` | **7 项通过**，0 失败 0 错误 |
| `./mvnw -o -Dtest='com.moxiao.studypilot.roadmap.**' test` | **181 项通过**，0 失败 0 错误（既有成果/评审/工作区行为无回归） |
| `./mvnw -o test` | **584 项通过，0 失败 0 错误，7 跳过**，`BUILD SUCCESS`（7 跳过为既有可选真实握手用例） |
| `node scripts/verify-agent-capability-matrix.mjs` | `[SUCCESS] 31 个页面路由与 65 个 Java 工具`（本次改动不涉及工具契约，门禁不受影响） |
| `node --test scripts/verify-agent-capability-matrix.test.mjs` | 门禁自测 4/4 通过 |
| `git diff --check` | 无输出（干净） |

### 8.6 诚实边界

1. 本节只交付**后端只读入口**。真实浏览器三动作属于 ZCode 与 Codex 的验收范围，
   本分支**没有**执行也没有声称任何浏览器页面证据；**Task 33 整体仍未验收**。
2. 该入口未做真实浏览器/前端联调：没有运行前端 `5173`、没有渲染面板，
   因此“面板能正确展示真实数据”仍待 Codex 在真实产品页复跑确认。
3. 响应字段集未由 §8 冻结，需与 ZCode 前端对齐（见 8.3）。
4. `sensitiveScanPassed` 作为成果状态布尔量保留；若 Codex 认为它属于隐私字段，
   移除是单点改动。
