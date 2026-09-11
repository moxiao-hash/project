# Task 30 后端验证证据：全量严格输出 Schema、动作回执闭环与运行时超时治理（Java + Python）

- **执行 Agent**：DeepSeek Harness（后端与 Agent 工具/治理工程师）
- **测试等级**：`[UNIT_TEST]` + `[MOCK_INTEGRATION]`（MockMvc + Mockito 网关 + H2）+ `[STATIC_VALIDATION]`；**不构成 `[REAL_E2E]`**（未启动真实 MySQL/FastAPI/浏览器，未做真实模型调用）
- **执行时间**：2026-09-11（首轮交付 11:06；第一次复审整改 11:40；第二次复审整改 12:20，Asia/Shanghai）
- **关联分支**：`agent/deepseek-task-30-tools`
- **关联基础提交**：`59a3578`（`origin/main`，含 Task 29 验收成果）
- **提交**：
  - 首轮交付：`9f747f2 feat: expose strict tool schemas and governed action receipts`
  - 第一次复审整改：`49a9d88 fix: enforce strict tool output contracts and runtime timeouts`
  - 第二次复审整改：`fix: fence governed write timeouts and roll back failed receipt saves`（见 `git log -1`，未改写前两个提交）
- **交付状态**：**待 Codex 复验**。只交付 `backend/**`、必要的 `ai-service/app/unified_agent/**`、`ai-service/app/api/unified_assistant.py`、对应测试与本文档；**未改动任何 `web/**`**，未修改共享矩阵、总计划、交接文档、凭据或 `.env`。

---

## 1. Codex 复审阻塞项与整改对照

### 1.1 第二次复审阻塞项（本轮）

| # | 复审阻塞项 | 整改 |
| :--- | :--- | :--- |
| A | 写超时不安全：`AgentToolActionService` 在 `Future.cancel(true)` 后立即把动作标记 FAILED，而忽略中断的 JDBC/阻塞事务可能稍后提交 | 新增 `AgentToolTimeoutGuard.callFenced`：超时后先在宽限期内等待事务真正结束；结果已知按真实结果定稿；结果未知则返回 `RUNNING`（非终态）并由工作线程延迟定稿。**终态 FAILED 只在事务已结束（回滚）后发布**；`RUNNING` 期间重复确认不会再次执行。线程池改为有界（最多 64 个守护线程）。新增 `GovernedWriteTimeoutSafetyTest` 生产级回归 |
| B | Python 保存失败回滚：`record_action_receipt` 先改内存（attempts、action_receipts、ui_actions_by_id、events/stream 状态）再 `_save`；失败后幽灵回执仍在内存中权威 | 在锁内对四类状态做快照，`_save`/`_emit` 抛出时 `_restore_receipt_state` 全量回滚（回执、动作表含 attempts、events、`lastEventSequence`/`activeTurnId`）；新增“失败后内存与保存前完全一致 + 之后可恰好持久化/发布一次”的回归测试 |

### 1.2 第一次复审阻塞项（已整改并保留）

| # | 复审阻塞项 | 整改 |
| :--- | :--- | :--- |
| 1 | 输出 Schema 的 `required` 为空、嵌套对象开放、数组 `items:{}` | 重写 `AgentToolOutputSchemas` 为递归严格建模：对象声明 always-present 必填键、嵌套对象闭合、数组声明元素类型、受控映射声明值类型；新增 4 项负例 + 64 项全量结构校验 |
| 2 | 截断后被替换为 `{warning,originalBytes}`，未按契约校验最终载荷 | 裁剪信封改为显式登记契约 `{warning,originalBytes,truncated}`，`AgentToolRegistry` 在返回前校验**最终**载荷；新增强制 >65KB 的回归测试 |
| 3 | `_schedule_ui_action_retry` 先发布、后落库 | 重试改为锁内登记 → `_emit` **先 `_save` 再 `publish`**；新增“持久化前不可见/保存失败不发布/并发回执不可提前观察”三项测试 |
| 4 | `timeoutMillis` 仅元数据，未在运行时强制 | 新增 `AgentToolTimeoutGuard` + `AgentToolTimeoutException`：读工具在注册表边界、写工具在治理执行边界按 `descriptor.timeoutMillis()` 强制；超时映射 504（读）/ 非终态 RUNNING 或真实结果（写）；新增慢 Handler 回归测试 |
| 5 | 证据文档的 `.venv/bin/python` 在干净工作树不可复现 | 文档记录真实前置条件（本工作树无 `ai-service/.venv`，以软链复用主工程 venv）与实际执行命令 |

**保持不变的已接受设计**：`UI_ACTION` 事件载荷的加法式 `actionId`；java 负责认证/请求体/route name/错误裁剪/会话归属、Python 原子校验动作归属的职责划分；用户隔离行为。

### 1.3 `required` 与 `non_null` 的建模口径

`backend/src/main/resources/application.properties` 配置了
`spring.jackson.default-property-inclusion=non_null`，因此**值为 null 的字段不会出现在工具输出 JSON 中**
（既有测试 `InternalAgentToolControllerTest` 断言的 `$.data.roadmap doesNotExist` 即为证据）。
据此：Schema 的 `required` 只包含“每次序列化都会出现的键”；可空字段声明为可空类型但不进入 `required`
（声明形如 `{"type":["string","null"]}`）。这样 `{}`、缺失必填键、错误嵌套类型、任意数组元素都会被真实拒绝，
同时不会因为合法省略可空字段而误报。

---

## 2. 运行环境与真实前置条件（修正项 5）

- **操作系统**：macOS darwin（arm64）
- **Java**：`openjdk 26.0.1`（Maven `release 17`，Spring Boot 4.0.7）
- **Python**：3.12.13（FastAPI / Pydantic 2.13.4）
- **Python 环境真实情况**：本 worktree **不包含** `ai-service/.venv`（被 `.gitignore` 忽略）。
  本次实际执行的前置命令为复用主工程虚拟环境的软链：

  ```bash
  cd /Users/moxiao/IdeaProjects/project-deepseek-task-30/ai-service
  ln -sfn /Users/moxiao/IdeaProjects/project/ai-service/.venv .venv
  ```

  干净 checkout 必须自备一个 Python 3.12 虚拟环境并安装依赖（依据 `ai-service/pyproject.toml`），
  例如 `python3.12 -m venv .venv && .venv/bin/python -m pip install -e '.[dev]'`；
  仓库当前没有一键 bootstrap 脚本，这是环境限制而非代码缺陷。
- **数据库**：Java 测试使用 H2 内存库；Python 回执恢复测试使用既有加密 SQLite（`AgentPersistence`）。未使用真实 MySQL
- **服务端口**：未启动真实 Spring Boot 8080 / FastAPI 8000；Java 侧用 MockMvc + Mockito 网关或本地 `HttpServer`
- **使用模型**：**本轮零真实模型调用**
- **未打印/未提交**任何 API Key、口令或提示词正文；未修改 `.env`

---

## 3. TDD 闭环证据（均为实际运行输出）

### 3.1 复审整改的 RED

**(1) 输出 Schema 不够严格**（新增测试，运行于生产改动之前）

```bash
cd backend
./mvnw -o -q -Dtest='AgentToolOutputSchemasTest' test
```

```text
[ERROR] Tests run: 4, Failures: 4, Errors: 0, Skipped: 0
AssertionFailedError: 空对象必须被拒绝 ==> Expected java.lang.IllegalStateException to be thrown, but nothing was thrown.
AssertionFailedError: 嵌套对象陌生字段必须被拒绝 ==> Expected ... but nothing was thrown.
AssertionFailedError: 任意数组元素必须被拒绝 ==> Expected ... but nothing was thrown.
AssertionFailedError: artifacts.evaluate 必须声明 always-present 必填键 ==> expected: <true> but was: <false>
```

**(2) 截断载荷未按契约校验**

```bash
./mvnw -o -q -Dtest='AgentToolRegistryTest#truncatedOutputIsAnExplicitSchemaValidEnvelope' test
```

```text
Tests run: 1, Failures: 1
AssertionFailedError: 截断输出必须显式标记 truncated ==> expected: <true> but was: <false>
```

**(3) 重试先发布后落库**（Python，三项同时失败）

```bash
cd ai-service
PYTHONPATH=$PWD .venv/bin/python -m pytest -q tests/unified_agent/test_supervisor_receipts.py \
  -k "durably_persisted or durable_save_fails or concurrent_receipt_cannot_observe"
```

```text
AssertionError: assert AssistantEvent(sequence=11, type='UI_ACTION', ... 'retry': True}) is None
FAILED test_retry_action_is_durably_persisted_before_it_is_published
FAILED test_retry_is_not_published_when_durable_save_fails
FAILED test_concurrent_receipt_cannot_observe_action_before_durable_registration
3 failed
```

**(4) `timeoutMillis` 未在运行时强制**

```bash
./mvnw -o -q -Dtest='AgentToolRegistryTest#slowHandlerIsInterruptedAtTheDescriptorTimeout' test
```

```text
Tests run: 1, Failures: 1, Time elapsed: 3.147 s
AssertionFailedError: 慢 Handler 必须在运行时被超时中断，而不是阻塞到自然返回 ==> expected: not <null>
```

**(5) 写超时先报 FAILED、事务稍后提交**（第二次复审，生产级）

```bash
./mvnw -o -q -Dtest='GovernedWriteTimeoutSafetyTest' test
```

```text
Body = {...,"status":"FAILED",...,"error":"Agent 工具执行超时（上限 1000ms）"}
Tests run: 2, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 5.498 s
AssertionFailedError: 事务在宽限期内提交时必须报告 SUCCEEDED，而不是先报 FAILED ==> expected: <SUCCEEDED> but was: <FAILED>
AssertionFailedError: 事务结果未知时绝不能报告终态 FAILED，否则之后仍可能提交 ==> expected: not equal but was: <FAILED>
```

**(6) 保存失败后内存残留幽灵回执**（第二次复审，Python）

```bash
cd ai-service
PYTHONPATH=$PWD .venv/bin/python -m pytest -q tests/unified_agent/test_supervisor_receipts.py \
  -k failed_save_rolls_back
```

```text
AssertionError: assert {'97e7c7f9-...': {'decision': 'RETRY_SCHEDULED', ... 'status': 'FAILED'}} == {}
Left contains 1 more item: ... 'RETRY_SCHEDULED'
FAILED test_failed_save_rolls_back_every_in_memory_receipt_mutation
1 failed
```

### 3.2 复审整改后的 GREEN（关键断言）

- `AgentToolOutputSchemasTest`（4）：空对象/缺必填/嵌套陌生字段/嵌套类型错误/任意数组元素/数组元素类型错误全部拒绝；
  64 个登记 Schema 均为“根类型明确 + 闭合 + 必填非空 + 数组元素带类型”。
- `AgentToolOutputValidatorTest`（4）：闭合对象、必填、类型、数组元素、可空类型的正反例；错误信息不泄漏业务值。
- `AgentToolRegistryTest`（5）：参数校验、输出契约、**截断信封符合登记契约**、**慢 Handler 超时中断**。
- `AgentToolTimeoutGuardTest`（3）：慢任务确定性超时（错误含上限）、快任务返回值、业务异常原样透传。
- `GovernedWriteTimeoutSafetyTest`（2）：**宽限期内提交 → SUCCEEDED 而非 FAILED**；
  **结果未知 → 保持 RUNNING、拒绝重复执行、事务结束后按真实结果定稿为 SUCCEEDED**。
- `AgentToolCoverageTest`（6）：64 工具 exactly-once、effect/风险与矩阵一致、写工具治理、输入/输出封闭且必填。
- Python `test_supervisor_receipts.py`（14）：动作归属/幂等/409/失败不成功/有界重试/人工入口、
  **并发只提交一个终态**、**重启恢复**、**重试先持久化再发布**、**保存失败不发布**、**并发不可提前观察**、
  **保存失败后内存全量回滚且之后恰好持久化/发布一次**。

### 3.3 全量命令与结果

```bash
cd ai-service
ln -sfn /Users/moxiao/IdeaProjects/project/ai-service/.venv .venv   # 见 §2 前置条件
PYTHONPATH=$PWD .venv/bin/python -m pytest -q                        # 420 passed, 1 warning
PYTHONPATH=$PWD .venv/bin/python -m ruff check app tests             # All checks passed!

cd ../backend
./mvnw -o test                                                       # 407 passed, 0 failures, 0 errors
                                                                     # BUILD SUCCESS（Task 30 首轮基线 374）

cd ..
node scripts/verify-agent-capability-matrix.mjs                      # 31 页面 / 64 工具覆盖通过
git diff --check                                                     # 退出码 0
```

---

## 4. 实现要点与安全边界

### 4.1 严格输出契约（Java）

- `AgentToolOutputSchemas`：递归 `Spec` 建模，`required` 只含 always-present 键；嵌套对象闭合；
  数组声明元素类型；受控映射（`additionalProperties` 为带类型 Schema）用于 `navigation.resolve.params`
  与 `assessment.attempt.get.results[].evaluation` 这类键不固定的受控值。
- `AgentToolOutputValidator`：支持 `type`（含可空数组形式）、`required`、`additionalProperties`
  （布尔闭合或值类型映射）、`items`、`enum`；错误信息只含字段名与期望类型。
- `AgentToolRegistry`：读工具在返回前校验真实载荷；超过 65,536 字节时返回登记裁剪信封
  `{warning,originalBytes,truncated}` 并校验该**最终**载荷；写工具执行结果同样校验。

### 4.2 运行时超时（Java）

- `AgentToolDescriptor.timeoutMillis`：只读/导航 15s，写 120s（1s–10min 校验）。
- `AgentToolTimeoutGuard`：有界守护线程池（最多 64 个线程，超出拒绝而不是无限堆积）。
  - 读路径 `call(...)`：`Future.get(timeout)`；超时 `cancel(true)` 并抛 `AgentToolTimeoutException`，
    由 `GlobalExceptionHandler` 映射为 **504**（只读无副作用，立即失败安全）。
  - 写路径 `callFenced(...)`：超时后先在**宽限期**（`studypilot.agent-tool-timeout-grace-millis`，默认 5s）
    等待写事务真正结束。
    - 事务在宽限期内结束 → 按真实结果定稿 `SUCCEEDED`/`FAILED`（提交则成功，回滚才失败）。
    - 宽限期后仍未结束 → 返回 `RUNNING`（非终态），由工作线程在事务真正结束后**延迟定稿**。
- **安全完成/围栏保证**：终态 `FAILED` 只在事务已结束且回滚后发布；`RUNNING` 期间
  `claimConfirmation` 不会再次执行，`finalize*` 仅在 `status == RUNNING` 且持有行锁时生效，
  因此不会出现“已报 FAILED、之后又提交”的窗口，也不会产生重复副作用。
- 读工具在 `AgentToolRegistry` 边界受控；写工具在 `AgentToolActionService.executeClaim` 通过
  `callFenced` 包裹 `AgentToolBusinessExecutor.execute`（`REQUIRES_NEW` 事务在被调度线程上开启）。
- **不再存在“超时后可能迟提交却已报失败”的限制**：该窗口已由上述围栏语义消除；
  剩余物理限制只是“忽略中断的底层调用可能占用工作线程直到返回”，但调用方与数据库终态一致。

### 4.3 动作回执（Java → Python）

- 公共 `POST /api/assistant/conversations/{id}/actions/receipt`；内部
  `POST /internal/assistant/conversations/{id}/actions/receipt`（内部令牌）。
- 请求体恰为 `actionId/status/error/currentRoute`；`status ∈ {SUCCEEDED,FAILED,REJECTED}`；
  `currentRoute` 是 31 个前端 route name；`error` 裁剪 ≤500 且丢弃堆栈/包名/请求头/凭据/URL/HTML。
- Java 先确认会话归属（跨用户/未知 404 且不落回执），再按 owner 转发；Python 侧 `extra="forbid"`。
- Python：动作归属原子校验；相同终态幂等；冲突 409 不覆盖；失败永不升级为成功；
  `NAVIGATE/REFRESH_RESOURCE` 最多 1 次重试（先落库再发布新 `UI_ACTION`），否则人工入口。
- Python 保存失败原子回滚：回执、动作表（含 attempts）、事件列表与流游标在锁内快照，`_save`/`_emit`
  抛错时全量恢复，避免“未落库却生效”的幽灵回执。
- 未新增 SSE 事件类型；`UI_ACTION` 事件载荷的加法式 `actionId` 由前端既有可选字段消费。

### 4.4 治理与真实性边界（未改动）

- 读/导航自动；`WRITE/LOCAL` 继续经 `AgentExecution`、授权、幂等、通知与审计；HIGH 风险继续专用确认。
- 未新增答题、打卡总结、成果接受代办能力。聊天文本不构成确认。

---

## 5. 变更文件

**第二次复审整改提交**
- `agent/tool/AgentToolTimeoutGuard.java`（新增 `callFenced`/`FencedResult`、有界线程池、宽限期与延迟定稿）
- `agent/tool/AgentToolActionService.java`（写路径围栏：`RUNNING` 非终态 + 延迟定稿 + 日志）
- 新增 `GovernedWriteTimeoutSafetyTest`
- `app/unified_agent/supervisor.py`（保存失败全量内存回滚）
- `tests/unified_agent/test_supervisor_receipts.py`（+1 回滚测试）

**第一次复审整改提交（已保留）**
- `agent/tool/AgentToolOutputSchemas.java`（递归严格建模）
- `agent/tool/AgentToolOutputValidator.java`（受控映射值校验）
- `agent/tool/AgentToolRegistry.java`（裁剪信封 + 读超时）
- `agent/tool/AgentToolTimeoutGuard.java`、`agent/tool/AgentToolTimeoutException.java`（新增）
- `shared/api/GlobalExceptionHandler.java`（504 映射）
- 新增 `AgentToolOutputSchemasTest`、`AgentToolTimeoutGuardTest`；修改 `AgentToolRegistryTest`、`AgentToolCoverageTest`
- `app/unified_agent/supervisor.py`、`tests/unified_agent/test_supervisor_receipts.py`（重试先持久化再发布）

**文档**
- `docs/verification/task-30-backend.md`（本文件）

**未修改**：`web/**`、`docs/agent-capability-matrix-v2.md`、`docs/协同开发交接说明.md`、总计划、`.env`、
数据库迁移。首轮 `9f747f2` 的回执/隔离实现保持不变。

---

## 6. 模型 ID 与真实端到端声明

- **真实模型 ID：明确不可用**。本轮未发起任何真实模型调用，不以 UI 标签或配置默认值冒充。
- 本轮为 `[UNIT_TEST] / [MOCK_INTEGRATION] / [H2] / [STATIC_VALIDATION]`，**不是** `[REAL_E2E]`：
  未启动真实 MySQL、未做跨进程 Java↔FastAPI 回执联调、未做浏览器动作回执联调、未做真实 SSE 断线续传。

---

## 7. 已知限制与待确认项

1. **动作归属校验位置**：动作是否属于该会话由 Python 权威存储原子校验；Java 负责认证、请求体、
   route name、错误裁剪与会话归属。若需 Java 侧镜像动作表，请先冻结新契约。
2. **额外字段**：Java 拒绝 `ownerId/DOM/URL/HTML/JS/selector/模型字段`；Python 内部模型 `extra="forbid"`。
3. **超时中断语义**：见 §4.2；安全完成/围栏语义已消除“先报失败、后提交”的窗口，不再作为限制接受。
4. **契约加法**：`timeoutMillis` 与 `UI_ACTION.actionId` 为加法式扩展；Codex 已接受 `actionId`，
   `timeoutMillis` 现已真实强制。
5. **Schema 真实数据覆盖**：H2 全量套件覆盖优先能力工具；`developer.*`、`runner.*`、`artifacts.*`
   的 Schema 通过结构校验与静态建模保证，未在真实 MySQL/浏览器链路上验证。
6. **矩阵风险矛盾（未擅自改动）**：矩阵 §2 页面表与生产代码一致地把
   `learning.goal.create`/`learning.plan.create` 记为 `LOW`，§3.2 目录写为 `HIGH`；请 Codex 裁定。

## 8. Git 与交付

- 第二次复审整改为**新提交**（未 amend `9f747f2`/`49a9d88`），只提交自有文件，只推送 `agent/deepseek-task-30-tools`。
- 不合并 `main`，不领取 Task 30 整体验收，不启动 Task 31。
