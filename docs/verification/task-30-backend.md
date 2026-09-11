# Task 30 后端验证证据：全量工具输出 Schema、动作回执闭环与治理边界（Java + Python）

- **执行 Agent**：DeepSeek Harness（后端与 Agent 工具/治理工程师）
- **测试等级**：`[UNIT_TEST]` + `[MOCK_INTEGRATION]`（MockMvc + Mockito 网关 + H2）+ `[STATIC_VALIDATION]`；**不构成 `[REAL_E2E]`**（未启动真实 MySQL/FastAPI/浏览器，未做真实模型调用）
- **执行时间**：2026-09-11 11:10:00 (Asia/Shanghai)
- **关联分支**：`agent/deepseek-task-30-tools`
- **关联基础提交**：`59a3578`（`origin/main`，含 Task 29 验收成果；后端工作树交付前干净）
- **交付状态**：**待 Codex 验收**。本轮只交付 `backend/**`、必要的 `ai-service/app/unified_agent/**` 与 `ai-service/app/api/unified_agent`、对应测试与本文档；**未改动任何 `web/**`**，未修改共享矩阵、总计划、交接文档、凭据或 `.env`。

---

## 1. 规范要求与实现对照

| 规划 Step | 要求 | 本后端实现 | 证据 |
| :--- | :--- | :--- | :--- |
| Step 1 | 从 Task 27 矩阵生成失败覆盖测试 | `AgentToolCoverageTest` 由“只查名字”升级为 43 只读 + 20 写入 + 1 导航的 effect/risk/超时/输入输出 Schema/治理断言；受控实验记录了 RED | §3.1、§3.2 |
| Step 2 | 补齐业务工具 | 全部 64 个已登记工具补齐**封闭且带类型的输出 JSON Schema**（`AgentToolOutputSchemas`），并在调用边界真实校验；未新增矩阵外工具（矩阵校验器要求双向一致） | §4.1 |
| Step 3 | UI Action 扩展为 5 类白名单 | 属 ZCode 前端；后端冻结 `currentRoute` 为 31 个前端 route name，并保持 `UiAction` 冻结契约不变 | §4.3 |
| Step 4 | 动作回执：`actionId/status/error/currentRoute` | Java 公共 `POST /api/assistant/conversations/{id}/actions/receipt` + 归属确认 + 内部转发；Python 幂等落库并决定结束/重试/人工入口 | §4.2、§4.4 |
| Step 5 | 纵深防御 | 请求体**恰为四个字段**；拒绝 `ownerId/DOM/URL/HTML/JS/selector/任意模型字段/额外字段`；URL 与 routeKey 大写形式拒绝；错误裁剪脱敏 | §4.3 |
| Step 6 | 真实性测试 | 写操作继续经过 AgentExecution/授权/专用确认/幂等/通知/审计；本后端不提供答题、打卡总结、成果接受代办；本测试断言 HIGH 写动作必须专用确认 | §3.2、§4.5 |
| Step 7 | 矩阵校验 + 三端全量 | Java 全量、Python 全量、Ruff、矩阵校验、`git diff --check` 见 §3.3 | §3.3 |

---

## 2. 运行环境与前置状态

- **操作系统**：macOS darwin（arm64）
- **Java**：`openjdk 26.0.1`（Maven `release 17`，Spring Boot 4.0.7）
- **Python**：3.12.13（FastAPI / Pydantic 2.13.4）；`ai-service/.venv` 以软链复用主工程虚拟环境（`.gitignore` 忽略，未提交）
- **数据库**：Java 测试使用 H2 内存库；Python 回执恢复测试使用既有加密 SQLite（`AgentPersistence`）。**未使用真实 MySQL**
- **服务端口**：未启动真实 Spring Boot 8080 / FastAPI 8000；Java 侧用 MockMvc + Mockito 网关或本地 `HttpServer`
- **使用模型**：**本轮零真实模型调用**；回执与工具覆盖均为确定性逻辑，不依赖模型

---

## 3. TDD 闭环证据

### 3.1 RED 阶段（均为实际运行）

**(a) Python 回执模块尚不存在**

```bash
cd ai-service
PYTHONPATH=$PWD .venv/bin/python -m pytest -q tests/unified_agent/test_supervisor_receipts.py
```

```text
ImportError: cannot import name 'AssistantActionNotFoundError' from 'app.unified_agent.supervisor'
ERROR tests/unified_agent/test_supervisor_receipts.py
1 error in 0.69s
```

**(b) 工具输出 Schema 尚未补齐（受控实验：临时让 `schemaFor` 退回空 `{"type":"object"}`）**

```bash
cd backend
./mvnw -o -q -Dtest='AgentToolCoverageTest' test
```

```text
[ERROR] AgentToolCoverageTest.everyToolDeclaresStrictInputTimeoutRiskAndClosedOutputSchema -- FAILURE!
org.opentest4j.AssertionFailedError: 输出 schema 必须封闭: artifacts.evaluate ==> expected: <false> but was: <true>
[ERROR] Tests run: 6, Failures: 1, Errors: 0, Skipped: 0
```

**(c) Java 公共回执接口尚不存在（受控实验：临时移除 `@PostMapping("/{id}/actions/receipt")` 路由）**

```bash
cd backend
./mvnw -o -q -Dtest='AssistantActionReceiptContractTest' test
```

```text
[ERROR] Tests run: 8, Failures: 5, Errors: 0, Skipped: 0
java.lang.AssertionError: Status expected:<200> but was:<404>
java.lang.AssertionError: Status expected:<400> but was:<404>
java.lang.AssertionError: Status expected:<409> but was:<404>
```

### 3.2 GREEN 阶段与关键行为断言

- Python 回执：`tests/unified_agent/test_supervisor_receipts.py`（10 项）覆盖
  未知动作 404、跨用户/跨会话拒绝、成功结束、重复终态幂等、冲突终态 409 不覆盖、
  失败绝不视为成功、有界重试（导航/刷新最多 1 次）、拒绝降级为人工入口、
  **并发三终态只落库一个**、**进程重启后回执与已下发动作仍可恢复**。
- Java 回执：`AssistantActionReceiptContractTest`（8 项，`[UNIT/MOCK]`）覆盖
  恰转发 4 字段且 ownerId 来自会话、拒绝 8 类越权/额外字段、非法 status/URL/大写 routeKey、
  错误裁剪（堆栈/包名/凭据被丢弃）、跨用户会话 404 且不落任何回执、冲突 409、未认证 401。
- 工具覆盖：`AgentToolCoverageTest`（6 项）覆盖 64 工具 exactly-once、effect 与
  页面矩阵 §2 风险一致、写工具必须是 `GovernedAgentToolHandler`、输入封闭、输出封闭有类型、超时有效。
- 输出校验：`AgentToolOutputValidatorTest`（4 项）证明运行时会拒绝未声明字段、缺失必填、类型错误与数组元素错误，
  且错误信息不泄漏真实业务值。
- 优先能力行为：`AgentToolCapabilityBehaviorTest`（2 项，H2）通过统一工具真实执行
  继续未完成节点（`learning.context.get`）、任务/目标/计划查询、今日容量调整
  （`settings.learning.get` + HIGH 动作卡 + 专用确认）、资料与错题查询、执行/审计查询、
  通知列表、工作区登记（HIGH 动作卡 + 专用确认 + 真实落库）、目标创建；并验证跨用户读取计划 404、
  未确认的跨用户工作区不落库。

### 3.3 全量命令与结果

```bash
cd ai-service
PYTHONPATH=$PWD .venv/bin/python -m pytest -q          # 416 passed, 1 warning
PYTHONPATH=$PWD .venv/bin/python -m ruff check app tests  # All checks passed!

cd ../backend
./mvnw -o test                                          # 396 passed, 0 failures, 0 errors
                                                        # BUILD SUCCESS（基线 374，+22）

cd ..
node scripts/verify-agent-capability-matrix.mjs         # 31 页面 / 64 工具覆盖通过
git diff --check                                        # 退出码 0
```

> Java 全量用例数由基线 374 增至 396：新增回执契约 8、优先能力行为 2、
> 工具输出校验 4、覆盖测试 3，另含小幅改写的既有用例。

---

## 4. 新增/修改的接口、Schema 与安全边界

### 4.1 工具输出 Schema 与超时（Java）

- 新增 `AgentToolOutputSchemas`：为全部 64 个已登记工具登记**封闭**输出 Schema
  （`type` 明确为 `object`/`array`，`additionalProperties:false`，逐字段声明类型，`?` 表示可空）。
  取代此前无信息量的 `{"type":"object"}`。
- 新增 `AgentToolOutputValidator`：`AgentToolRegistry` 在真实输出离开工具边界前校验
  `type/required/additionalProperties/items/enum`；不合格即失败关闭，且错误信息只含字段名与期望类型。
- `AgentToolDescriptor` 新增 `timeoutMillis`（README 阈值 1s–10min；只读/导航 15s，写 120s）。
  该字段是**加法式**契约扩展，随 `GET /internal/agent-tools/catalog` 一并暴露给 Python；
  Python `ToolDescriptor` 当前忽略未知字段（`extra=ignore`），不影响既有契约。**请 Codex 确认该加法式扩展**。
- 输出 Schema 的 `required` 暂留空数组：DTO 中可空字段较多，强制必填会产生误报；
  封闭字段集 + 逐字段类型已能捕获字段漂移。此为**有意的严格度取舍**。

### 4.2 动作回执接口（Java → Python）

- 公共：`POST /api/assistant/conversations/{id}/actions/receipt`（Bearer，ownerId 只来自会话）。
- 内部：`POST /internal/assistant/conversations/{id}/actions/receipt`（`X-Internal-Service-Token`）。
- 请求体恰为 `actionId/status/error/currentRoute`；`status ∈ {SUCCEEDED,FAILED,REJECTED}`；
  `currentRoute` 必须是 31 个前端 route name 之一（不是 URL）；`error` 裁剪至 ≤500 字符并丢弃
  堆栈/包名/请求头/凭据/URL/HTML。
- Java 在转发前调用权威存储确认会话归属（未知/跨用户会话 404，**不写入任何回执**），
  再以 owner 作用域转发；Python 侧 `extra="forbid"`。

### 4.3 回执语义与冻结契约

- `UiAction`（Java/Python 冻结模型）**未改动**：仍为 `type/routeKey/params/reason`。
- 为让回执可校验，`UI_ACTION` **事件 payload** 新增服务端生成的稳定 `actionId`
  （事件 `payload` 在契约中是开放对象；前端 `AssistantUiAction.actionId` 早已可选并优先使用它）。
  这是**事件载荷扩展**，非冻结 `UiAction` 模型变更；**请 Codex 确认**。
- 未新增任何 SSE 事件类型：回执结果通过同步 HTTP 响应返回，需要重试时复用已登记的 `UI_ACTION`。
- Java 无法凭现有契约独立校验“动作属于该会话”——动作登记表在 Python 权威存储中，
  `GET /internal/assistant/conversations/{id}` 不返回动作清单。因此边界为：
  **Java 校验认证用户、请求体、route name、错误裁剪与会话归属；Python 原子校验动作归属、幂等与冲突**。
  若 Codex 要求 Java 也持有动作清单，属于新契约，需先冻结，本轮不擅自实现。

### 4.4 回执状态机（Python）

- 成功 → `FINISHED`；拒绝 → `MANUAL_ENTRY`（返回人工 `manual_route_key`）；
  失败 → 仅 `NAVIGATE/REFRESH_RESOURCE` 允许**最多 1 次**自动重试（生成新 `actionId` 并复用 `UI_ACTION`），
  否则/超限 → `MANUAL_ENTRY`。失败永不升级为成功。
- 幂等/冲突：相同 `actionId` 相同终态返回同一结果；不同终态抛冲突 → 内部映射 409，历史结果不被覆盖。
- 并发：`_Conversation.lock` 串行化；恢复：`uiActionsById` 与 `actionReceipts` 随会话一起加密持久化并重启恢复。

### 4.5 治理与真实性边界

- 查询/导航自动；所有 `WRITE/LOCAL` 工具仍必须提供幂等键、授权范围、执行类型与动作摘要，
  经 `AgentToolActionService`/`AgentExecution` 治理；HIGH 风险继续生成动作卡并需**专用确认**。
- 聊天文本不构成确认；新增回执入口只处理前端白名单动作终态，不执行任何业务写。
- 未新增答题、打卡总结、成果接受代办能力；测试断言成果/高风险写仍走专用确认。

---

## 5. 修改文件与所有权

**Java（新增）**
- `backend/src/main/java/com/moxiao/studypilot/agent/tool/AgentToolOutputSchemas.java`
- `backend/src/main/java/com/moxiao/studypilot/agent/tool/AgentToolOutputValidator.java`
- `backend/src/main/java/com/moxiao/studypilot/agent/api/AssistantActionReceiptValidator.java`
- `backend/src/main/java/com/moxiao/studypilot/agent/application/AssistantActionReceiptService.java`

**Java（修改）**
- `.../agent/api/UnifiedAssistantFacadeController.java`（回执路由）
- `.../agent/tool/AgentToolDescriptor.java`（`timeoutMillis`）
- `.../agent/tool/AgentToolRegistry.java`（输出校验）
- `.../agent/tool/AgentReadToolConfiguration.java`、`AgentWriteToolConfiguration.java`、`NavigationToolHandler.java`（输出 Schema + 超时）

**Java 测试**
- 新增 `AssistantActionReceiptContractTest`、`AgentToolCapabilityBehaviorTest`、`AgentToolOutputValidatorTest`
- 修改 `AgentToolCoverageTest`、`AgentToolRegistryTest`、`InternalAgentToolControllerTest`

**Python**
- `ai-service/app/unified_agent/models.py`（回执契约、route name 白名单、错误脱敏）
- `ai-service/app/unified_agent/supervisor.py`（UI_ACTION actionId、回执状态机、持久化/恢复）
- `ai-service/app/api/unified_assistant.py`（内部回执端点与错误映射）
- 新增 `ai-service/tests/unified_agent/test_supervisor_receipts.py`；修改 `tests/api/test_unified_assistant.py`

**文档**
- `docs/verification/task-30-backend.md`（本文件）

**未修改**：`web/**`、`docs/agent-capability-matrix-v2.md`、`docs/协同开发交接说明.md`、总计划、`.env`、数据库迁移。

---

## 6. 模型 ID 与真实端到端声明

- **真实模型 ID：明确不可用**。本轮未发起任何真实模型调用，因此没有服务端实际返回的 model id；
  不以 UI 标签或配置默认值冒充。Task 31 的用量/模型可观测性落地后再补真实证据。
- 本轮为 `[UNIT_TEST] / [MOCK_INTEGRATION] / [H2] / [STATIC_VALIDATION]`，
  **不是** `[REAL_E2E]`：未启动真实 MySQL、未做跨进程 Java↔FastAPI 回执联调、未做浏览器动作回执联调、
  未做真实 SSE 断线续传。

---

## 7. 已知限制与给 Codex 的待确认项

1. **契约加法待确认**：`AgentToolDescriptor.timeoutMillis` 与 `UI_ACTION` 事件 `actionId` 为加法式扩展，
   未改动任何冻结字段/枚举/路径；若 Codex 不接受，请指出，我按冻结流程调整。
2. **风险级别矛盾（未擅自改动）**：矩阵 §2 页面表将 `learning.goal.create`/`learning.plan.create`
   记为 `LOW`（与生产代码一致），而 §3.2 工具目录写为 `HIGH/需专用确认`。本测试以 §2 为准，
   未放宽或收紧现有风险。请 Codex 决定是否需要在 Task 31/后续统一。
3. **动作归属校验位置**：受现有契约限制，动作是否属于该会话由 Python 权威存储原子校验；
   Java 负责认证、请求体、route name、错误裁剪与会话归属。若需 Java 侧镜像动作表，请先冻结新契约。
4. **输出 Schema 严格度**：`required` 暂为空以容忍 DTO 可空字段；如需强制必填，可按 DTO 可空性逐字段收紧。
5. **执行时限**：`timeoutMillis` 为声明式上界；同步工具调用的真实硬上界仍由网关 120s 超时与
   Runner 模板超时承担，本轮未在 Java 线程层强制中断工具执行。
6. **未覆盖**：真实 MySQL Flyway（无迁移）、真实模型增量、`materials.search` 语义（矩阵未登记搜索工具，
   查询由 `materials.list/get` 承担）、Task 31 用量预算。

## 8. Git 提交

- 提交：`feat: expose strict tool schemas and governed action receipts`（见 `git log -1`）。
- 仅提交自有文件并只推送 `agent/deepseek-task-30-tools`；不合并 `main`，不领取 Task 30 整体验收，不启动 Task 31。
