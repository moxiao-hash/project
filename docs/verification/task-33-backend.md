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
- **交付提交**：`1c5723bc3b9573fa7431c7682c9ac12b91dffb1c`（`feat: execute allowlisted local interface fallbacks`）
- **本验证文档提交**：`docs: record task 33 backend local interface verification`（本次提交）

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

## 3. 已执行命令与结果

| 命令 | 结果 |
|---|---|
| `./mvnw -o -Dtest='LocalAutomationSigningTest' test` | **10 项通过**，0 失败 0 错误（含 ZCode 跨端向量） |
| `./mvnw -o -Dtest='InterfaceFallbackPolicyTest' test` | **9 项通过**，0 失败 0 错误 |
| `./mvnw -o -Dtest='UnixSocketLocalAutomationClientTest' test` | **20 项通过**，0 失败 0 错误（真实 UDS 桩） |
| `./mvnw -o -Dtest='LocalInterfaceFallbackWorkflowTest' test` | **9 项通过**，0 失败 0 错误（H2 + Spring 治理） |
| `./mvnw -o -Dtest='AgentToolCoverageTest,AgentToolOutputSchemasTest,AgentToolOutputValidatorTest,AgentToolRegistryTest,AgentToolActionRecoveryTest,AgentToolActionRecoveryRollbackTest' test` | **22 项通过**，0 失败 0 错误 |
| `./mvnw -o test` | **554 项通过，0 失败 0 错误**，`BUILD SUCCESS`（基线 508 → 新增 46） |
| `node scripts/verify-agent-capability-matrix.mjs` | `[SUCCESS] 能力矩阵校验通过！覆盖全部 31 个页面路由与 65 个 Java 工具` |
| `node --test scripts/verify-agent-capability-matrix.test.mjs` | 门禁自身 **4/4 通过** |
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

---

## 4. 明确未实现 / 未验证范围

1. **无真实 Socket 握手联调**。ZCode 的 `local-automation-service` 已交付（`428b008`），本分支只读其
   源码与固化向量用于字节级对齐，未修改其任何文件，也**没有**启动真实对端进程，因此**没有**声称 `REAL_E2E`。
   Java 侧行为由真实 UDS 桩与真实 Spring/H2 治理链路验证；跨端一致性见 §5。
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
`agent/zcode-task-33-local-adapters`，HEAD `428b008`）交付本地服务，并固化了跨语言确定性向量。
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

---

## 6. 遗留与下一步

- 代码质量意见（可能影响验收）：`developer.interface_fallback.execute` 为 `HIGH` 风险，
  因此“业务 API 优先”分支（不产生任何本地副作用）同样需要一次专用确认。
  这是失败关闭的选择——宁可多一次确认，也不允许该工具在无确认下进入执行路径。
  如 Codex 认为该无副作用分支不应要求确认，需要修改的是工具风险分级契约，请由 Codex 决定。
- 下一步（不由本 Agent 执行）：ZCode 交付本地服务后，由 Codex 按“规格 → 安全 → diff → 局部测试 →
  全量测试 → 真实联调 → 文档 → 合并”验收，并在真实最小验收中确认上述字节级约定。
