# Task 31 验证证据：真实 Token、价格、预算与可观测性

- **执行 Agent**：DeepSeek Harness（后端与 Agent 执行工程师）
- **测试等级**：`[UNIT_TEST]` + `[INTEGRATION_TEST]`（真实 MySQL 9.6 + Flyway）+ `[REAL_E2E]`（真实 DeepSeek 模型调用 1 次，见 §3.4，属于基线轮次证据）
- **执行时间**：2026-09-20 20:15–20:45 (Asia/Shanghai)
- **关联分支**：`agent/deepseek-task-31-usage-budget`
- **工作树**：`/Users/moxiao/Desktop/Deepseek Harness/workspaces/studypilot-task31`
- **基线实现提交**：`f6da5c6892ff5b54582bdfa7f6b7da539fdfe6b9`
- **本次评审修复提交**：`5723f6319c4fef9d0c74dcf139b47f3a86a186b6`（`fix: enforce usage budget on every provider call`）
- **本验证文档提交**：`docs: verify task 31 review fixes`（本次提交）

---

## 0. 范围与文件所有权

本文件只覆盖 Task 31 中属于 DeepSeek Harness 的部分：**用量采集、存储、计价、预算预占与健康接口**。
`web/**` 健康页展示属于 ZCode，本分支未改动 `web/**`，也未合并 `main`、未启动 Task 32。

| 文件 | 动作 | 归属 |
|---|---|---|
| `backend/.../db/migration/V45__add_assistant_usage_budget.sql` | 修改（新增 `assistant_usage_reservation`；Task 30 占用 V44，本分支不碰） | DeepSeek Harness |
| `backend/.../agent/usage/**` | 新建/修改（预占实体、仓储、服务、DTO、内部接口） | DeepSeek Harness |
| `backend/.../agent/api/AssistantHealthResponse.java`、`AssistantModelUsageStats.java` | 未改动 | DeepSeek Harness（字段），ZCode 消费 |
| `backend/.../agent/application/AssistantHealthService.java` | 未改动 | DeepSeek Harness |
| `ai-service/app/providers/budgeted_model.py` | 新建（唯一预算执行边界） | DeepSeek Harness |
| `ai-service/app/providers/budget.py`、`model_factory.py` | 修改 | DeepSeek Harness |
| `ai-service/app/observability/usage.py` | 修改（有界去重、预占幂等键、关停排空） | DeepSeek Harness |
| `ai-service/app/unified_agent/supervisor.py` 及各模型边界 | 修改 | DeepSeek Harness |
| `web/src/modules/assistant/AssistantHealthView.vue` | 未改动 | **ZCode** |

## 1. 运行环境

- **操作系统**：macOS darwin arm64
- **Java**：OpenJDK 26.0.1 + Maven 3.9.16（`backend/pom.xml` 编译目标 17）
- **Python**：3.12.13，复用主工作区解释器
  `/Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/python`，以 `PYTHONPATH=.` 运行；
  worktree 内无独立 `.venv`。
- **MySQL**：本机 9.6.0，`studypilot`/`studypilot`，库 `studypilot`
- **真实模型**：本轮评审未再次调用真实模型（本 worktree 无 `DEEPSEEK_API_KEY` / `.env`）。
  §3.4 的真实调用证据来自基线提交 `f6da5c6` 的验证，未在本次评审重跑，不能当作本次修复的直接证据。

## 2. 实现内容

### 2.1 迁移与存储

- `assistant_model_usage`：主键 `id`（AI 侧 `usageId`，幂等键），记录
  `owner_id / conversation_id / turn_id / execution_id / provider / model_name / purpose / status /
  prompt_tokens / cached_prompt_tokens / completion_tokens / reasoning_tokens / total_tokens /
  latency_ms / estimated_cost DECIMAL(18,8) / currency / price_version / price_window / occurred_at`。
  `total_tokens = prompt + completion`，reasoning 只是 completion 的子集，不重复相加。
- `assistant_usage_budget`：`daily_model_calls / daily_estimated_cost / max_output_tokens_per_turn /
  currency / updated_at / row_version`。
- `assistant_usage_reservation`（**本次 V45 修订新增**）：每次真实 provider 调用的预占许可。
  `id` 复用 `usageId`；保存 `owner_id / conversation_id / turn_id / purpose / provider / model_name /
  state(RESERVED|FINALIZED|RELEASED) / max_output_tokens / reserved_cost / actual_cost /
  reserved_at / expires_at / finalized_at`。V45 尚未合入 `main`，因此直接修订；Task 30 的
  `V44__add_agent_tool_action_recovery.sql` 原样保留。

### 2.2 幂等与并发（数据库级许可）

- **预占原子性**：`AssistantUsageService.reserve` 在事务内先
  `findByOwnerIdForUpdate`（`PESSIMISTIC_WRITE`）锁定该 owner 的预算行，再统计
  "当日已终结用量 + 未过期 RESERVED 预占"，然后插入预占。没有预算行的 owner 不存在上限，
  仍写入预占以统一幂等与终结语义。行锁在预占事务提交时释放，因此同一 owner 的并发预占被串行化，
  "先查计数再调用"的竞态不能突破每日调用/费用上限。
- **幂等**：预占 `id` 即 AI 侧 `usageId`；同一 `usageId` 重试命中同一条记录并原样回放许可，
  不会重复占用配额。用量回调也使用同一 `usageId`，`record` 命中主键时返回 `duplicate=true`
  且不再写库，同时按实际金额终结预占；未产生用量的调用由边界显式 `release`，
  重复释放与重复终结都是无操作。进程崩溃遗留的 RESERVED 预占由 `expires_at`（默认 900 秒）
  兜底失效，不再占用配额。
- **真实数据库并发证明**：`AssistantUsageReservationConcurrencyTest`（`@SpringBootTest`，真实
  数据库事务与行锁语义）用两个线程跨栅栏同时预占"仅剩 1 个名额"的 owner：恰好 1 个
  `allowed=true`、1 个 `DAILY_MODEL_CALLS_EXHAUSTED`、预占行恰好 1 条。另有幂等重试、
  终结释放、显式释放、在途输出成本上界触发费用上限四组行为测试。

### 2.3 价格目录与生效时刻

来源：https://api-docs.deepseek.com/quick_start/pricing/ （USD / 每百万 token）

| 模型（别名） | 版本 | 缓存命中 高峰/非高峰 | 缓存未命中 高峰/非高峰 | 输出 高峰/非高峰 |
|---|---|---|---|---|
| `deepseek-flash`、`deepseek-v4-flash`、`deepseek-v4-flash-vision-exp` | DeepSeek-V4.1-Flash | 0.006 / 0.003 | 0.30 / 0.15 | 1.20 / 0.60 |
| `deepseek-v4-pro` | DeepSeek-V4-Pro-0813 | 0.044 / 0.022 | 1.32 / 0.66 | 3.96 / 1.98 |

- 高峰时段：**周一至周五 01:00–04:00、06:00–10:00 UTC**（起始含、结束不含），其余时间非高峰。
- 目录版本 `deepseek-pricing-2026-09-20`，核对时间 `2026-09-20T00:00:00Z`；
  **官方 V4 单价生效时刻 `2026-08-16T16:00:00Z`**（`PriceSpec.effectiveAt`）。
  `estimate` 只在 `occurredAt >= effectiveAt` 时计价，生效瞬间之前一律 `Optional.empty()`，
  落库金额 `NULL`、`priceStatus=UNKNOWN`，绝不当成 0。
- `outputHoldCost` 用较高的输出单价乘 `maxOutputTokensPerTurn` 作为预占成本上界；
  未知模型或未配置输出上限时返回空，只靠调用次数上限。
- 单测覆盖全部时段边界（00:59:59 / 01:00 / 03:59:59 / 04:00 / 05:59:59 / 06:00 / 09:59:59 /
  10:00 / 23:59:59、周六、周日）以及生效时刻边界（`effectiveAt-1ns` 未知、
  `effectiveAt` 计价、`effectiveAt+1ns` 计价）。

### 2.4 唯一的模型调用边界

- `create_chat_model` 统一返回 **`BudgetedChatModel`**，包装底层 `ChatOpenAI`。
  `invoke` / `ainvoke` / `astream` 以及 `with_structured_output`、`bind` 返回的包装器都在同一边界内，
  不存在绕过路径（此前 `DeepSeekQuizGenerator.generate_node_quiz` 直接 `await model.ainvoke(...)`
  正是被该边界修复的漏网点）。
- 每次真实调用都：① 用当前 owner 向 Java 原子预占许可；② 用**当次许可**的
  `maxOutputTokensPerTurn` 绑定 `max_tokens`；③ 把预占 id 写入用量回调上下文，
  使用量记录与预占共享同一幂等键；④ provider 抛错时释放许可，成功时由用量回调按实际用量终结。
- 服务构造阶段（`main.py` 的 `build_owner_*`、后台 worker 工厂）与 Supervisor 轮次开始时
  的预算查询已删除；预算只在每次 provider 调用前判定，不再存在"轮次级陈旧判定"。
- 预算拒绝或不可用时 `ModelBudgetExceededError` 冒泡。Supervisor 捕获后仍执行
  `learning.context.get`、`navigation.resolve` 等纯 Java 查询/导航，并返回可恢复提示；
  等待确认的轮次在任何模型/预算路径之前返回，不查询预算也不调用模型。

### 2.5 失败关闭

- `ModelBudgetGuard.reserve` 在预占接口不可达、返回非字典、缺 `allowed`/`reason`、
  放行却缺 `reservationId`、字段类型非法时，一律返回
  `allowed=false, reason=BUDGET_CHECK_UNAVAILABLE`；模型边界随即抛出，不发起任何计费调用。
  预算服务与模型凭据共用同一个 Java 后端不再作为放行理由。单测覆盖 8 种非法回执与连接失败。

### 2.6 采集边界、有界去重与关停排空

- `ModelUsageCallback` 从 LangChain 响应解析缓存/非缓存输入、输出与
  `completion_tokens_details.reasoning_tokens`；无事件循环的执行器线程里用一次性事件循环同步上报。
- 去重表与开始时间表改为**有界**（默认保留最近 512 个 run，FIFO 淘汰），长驻缓存模型不会无限增长；
  最近的重复回调仍被去重。
- 派发出去但未完成的上报登记在进程级集合；FastAPI `lifespan` 的 `finally` 在关闭调度器后
  调用 `drain_pending_usage_reports()`，优雅关停不会丢失一次成功调用的用量证据。
- `AssistantUsageReporter` 仍带有限重试 + Prometheus 指标 + 结构化日志；上报失败只影响可观测性，
  **不会**把一次成功的模型调用改判为失败。
- 内部接口新增 `POST /internal/assistant-usage/reservations` 与
  `POST /internal/assistant-usage/reservations/{id}/release`；`GET /internal/assistant-usage/budget`
  保留为只读诊断接口，不再参与拦截。请求体只接受归属与用量数字段，不接受提示词、正文或 Key。

### 2.7 健康接口

`GET /api/assistant/health`（Bearer 用户鉴权）沿用基线字段：模型调用量、失败率、四类 token、
未知价格计数、估算费用、价格版本、P50/P95 延迟与 `models[]`。金额一律 `BigDecimal`；
`unknownPriceCalls>0` 时 `priceStatus=UNKNOWN` 且金额为 `null`；所有查询按登录用户 owner 过滤。
本轮未改动健康接口与响应字段。

## 3. 验证命令与结果

### 3.1 三端可运行检查（本机实测）

```text
# Java 全量
cd backend && mvn -o test
[INFO] Tests run: 427, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

# Python 全量
cd ai-service && PYTHONPATH=. <venv>/python -m pytest -q
449 passed

# Ruff
cd ai-service && <venv>/python -m ruff check app tests
All checks passed!

# 空白检查
git diff --check
（无输出）
```

Task 31 相关的定向测试：

```text
[INFO] Tests run: 5  -- AssistantUsageReservationConcurrencyTest  # 并发预占/幂等/终结/释放/费用上界（本次新增）
[INFO] Tests run: 3  -- InternalAssistantUsageContractTest        # 幂等、未知价格、失败计数、owner 隔离、预占接口
[INFO] Tests run: 12 -- AssistantUsageServiceTest                 # 重复回调、并发冲突、预算时区边界、金额
[INFO] Tests run: 25 -- ModelPricingCatalogTest                   # 官方价格、别名、高峰边界、生效时刻边界、未知模型
[INFO] Tests run: 4  -- AssistantHealthServiceTest                # 模型级统计、P50/P95、未知价格、owner 隔离
```

Python 新增/改写：

```text
tests/providers/test_budgeted_model.py            12 passed  # 中央边界：invoke/ainvoke/astream/结构化包装、
                                                             # 每调用一次预占、并发单许可、失败关闭、
                                                             # 独立 owner、全部模型边界 max_tokens
tests/providers/test_budget_guard.py              12 passed  # 放行/拒绝/不可达/8 种非法回执/释放
tests/observability/test_usage_lifecycle.py        4 passed  # 有界去重、预占幂等键、关停排空、lifespan 排空
tests/unified_agent/test_supervisor_budget.py      5 passed  # 拒绝后纯 Java 导航、单轮两次检查、确认零调用
```

### 3.2 真实 MySQL / Flyway（全新数据库）

为排除"已应用迁移的校验和缓存"，本次在真实 MySQL 上重建库后从零执行全部迁移。
被测迁移目录 = 本分支迁移 + Task 30 的 `V44__add_agent_tool_action_recovery.sql`
（取自 `e40575b`），共 45 个文件；`SPRING_FLYWAY_LOCATIONS` 指向该目录，
`backend` 以真实 MySQL 启动（`spring.jpa.hibernate.ddl-auto=validate`）：

```text
Flyway: Migrating schema `studypilot` to version "44 - add agent tool action recovery"
Flyway: Migrating schema `studypilot` to version "45 - add assistant usage budget"
Flyway: Successfully applied 45 migrations to schema `studypilot`, now at version v45 (execution time 00:01.103s)
Hibernate: Initialized JPA EntityManagerFactory for persistence unit 'default'   # ddl-auto=validate 通过
Tomcat started on port 18081
Started StudyPilotApplication in 4.677 seconds

flyway_schema_history: 45 | add assistant usage budget | success=1
show columns from assistant_usage_reservation:
  id / owner_id / conversation_id / turn_id / purpose / provider / model_name /
  state / max_output_tokens / reserved_cost / actual_cost / reserved_at / expires_at / finalized_at
```

真实 HTTP 端到端（注册用户 → 写入 `daily_model_calls=1` 预算 → 预占/释放 → 上报 → 健康接口）：

```text
reserve-1        201 {reservationId:r1, allowed:true, reason:WITHIN_BUDGET, maxOutputTokensPerTurn:1024}
reserve-1 retry  201 {reservationId:r1, allowed:true, ...}          # 同 usageId 幂等，不占第二个名额
reserve-2        201 {allowed:false, reason:DAILY_MODEL_CALLS_EXHAUSTED}
release r1       200 {reservationId:r1, released:true}
reserve-3        201 {reservationId:r3, allowed:true}
record r3        201 {estimatedCost:0.00042240, priceVersion:deepseek-pricing-2026-09-20,
                      priceWindow:PEAK, priceStatus:KNOWN, duplicate:false}
record r3 again  201 {duplicate:true}                               # 重复回调不重复计费
health           200 modelCalls=1 modelTotalTokens=1200 modelReasoningTokens=50
                      usageEstimatedCost=0.0004224 priceStatus=KNOWN

价格生效边界（真实 HTTP）：
occurredAt=2026-08-16T15:59:59.999Z → priceStatus=UNKNOWN, estimatedCost=null
occurredAt=2026-08-16T16:00:00Z     → priceStatus=KNOWN,   priceWindow=OFF_PEAK,
                                      estimatedCost=0.00002100
```

`assistant_usage_reservation` 真实落库：

```text
id  state      max_output_tokens  reserved_cost  actual_cost
r1  RELEASED   1024               0.00122880     NULL
r3  FINALIZED  1024               0.00122880     0.00042240
```

> 真实库在本次验证前已备份到 `/tmp/studypilot-backup-before-task31-review.sql`（不进仓库），
> 随后重建为空库以验证全新迁移；上表是本轮新写入的真实行。

### 3.3 金额复核

- `record r3`（周一 02:00 UTC，高峰 Flash）：非缓存输入 600 × 0.30/M + 缓存命中 400 × 0.006/M
  + 输出 200 × 1.20/M = 0.00018 + 0.0000024 + 0.00024 = **0.00042240 USD**，与接口返回一致。
- `occurredAt=effectiveAt`（2026-08-16T16:00:00Z 为周日，非高峰）：非缓存输入 100 × 0.15/M
  + 输出 10 × 0.60/M = 0.000015 + 0.000006 = **0.00002100 USD**，与接口返回一致。
- reasoning 已包含在 completion 内，`total_tokens = prompt + completion`，两侧都没有重复计费。

### 3.4 真实最小模型调用 `[REAL_E2E]`（基线轮次，本次未重跑）

```text
model_name=deepseek-v4-flash  provider=deepseek
response=OK
usage:
  purpose=KNOWLEDGE_QA status=SUCCEEDED
  promptTokens=37 cachedPromptTokens=0 completionTokens=18 reasoningTokens=16
  latencyMs=658 occurredAt=2026-09-20T12:09:14Z
```

该调用发生在 2026-09-20（周日，非高峰），按 Flash 非高峰价估算
`37×0.15/1e6 + 18×0.60/1e6 = 1.635E-5 USD`。本轮评审未重新调用真实模型：
本 worktree 没有 `DEEPSEEK_API_KEY` / `.env`。新的预算边界改由确定性假 provider
（`test_budgeted_model.py` 的 `RecordingRunnable`）加真实 MySQL/Flyway/HTTP 预占链路验证，
不以旧的真实调用证据冒充本次修复证据。

## 4. 业务数据真实性回查

真实 MySQL 中 `assistant_model_usage`、`assistant_usage_budget`、`assistant_usage_reservation`
三张表存在且列与实体一致（§3.2）。真实 HTTP 上报写入 `assistant_model_usage`
（`r3` / `price-at` / `price-before` 三行），预占接口写入 `assistant_usage_reservation`
（`r1` RELEASED、`r3` FINALIZED 且 `actual_cost` 等于真实估算），健康接口读出真实行；
重复上报未新增行。

## 5. 已知限制

1. **节假日日历未内置**：官方页注明中国法定节假日全天按非高峰计价。当前实现只按
   "周一至周五 01:00–04:00、06:00–10:00 UTC" 判定高峰，未内置节假日表；需要时由部署方按年维护。
2. **预占成本上界只覆盖输出**：`reserved_cost` 用高峰输出价乘 `maxOutputTokensPerTurn`
   作为保守上界，不估算输入 token；因此费用上限对并发调用是"输出侧保守、输入侧事后再算"。
   调用次数上限始终精确。终结时按实际用量写 `actual_cost`。
3. **预占过期窗口**：进程崩溃遗留的 RESERVED 预占在 `expires_at`（默认 900 秒）后才失效；
   窗口内会占用配额。TTL 可由 `studypilot.assistant.budget.reservation-ttl-seconds` 配置。
4. **未知价格不进费用预算**：未知价格调用金额为 `NULL`，只计入每日调用次数上限，不计入费用上限。
5. **`GET /internal/assistant-usage/budget` 仅是诊断接口**：不再参与拦截；
   真正的强制点是每次 provider 调用前的预占。
6. **本 worktree 无独立 `.venv`**：Python 测试复用主工作区解释器。
7. **未运行 `web/**` 测试；未重跑真实模型调用**：健康页展示属于 ZCode，本分支不触碰 `web/**`；
   真实模型证据沿用基线轮次，本次未重跑（见 §3.4）。

## 6. 变更文件清单（Task 31 评审修复）

- 迁移：`V45__add_assistant_usage_budget.sql`（新增 `assistant_usage_reservation`）
- 后端新增：`BudgetPermit`、`ReserveUsageCommand`、`ReserveAssistantUsageRequest`、
  `AssistantBudgetPermitResponse`、`AssistantUsageReservationEntity`、
  `AssistantUsageReservationJpaRepository`、`AssistantUsageReservationReleaseResponse`
- 后端修改：`AssistantUsageService`（原子预占/终结/释放）、`ModelPricingCatalog`（生效时刻）、
  `AssistantUsageBudgetJpaRepository`（悲观锁）、`InternalAssistantUsageController`（预占/释放接口）、
  `AssistantBudgetProperties`（预占 TTL）、`application.properties`
- 后端测试：新增 `AssistantUsageReservationConcurrencyTest`；更新 `InternalAssistantUsageContractTest`、
  `AssistantUsageServiceTest`、`ModelPricingCatalogTest`
- AI 新增：`app/providers/budgeted_model.py`、`tests/providers/test_budgeted_model.py`、
  `tests/observability/test_usage_lifecycle.py`
- AI 修改：`providers/budget.py`（失败关闭 + 预占）、`providers/model_factory.py`（统一包装）、
  `observability/usage.py`（有界去重、预占幂等键、关停排空）、`clients/java_backend.py`（预占客户端）、
  `unified_agent/supervisor.py`（删除轮次预检、拒绝后纯 Java 降级）、`unified_agent/planner.py`
  （owner 归属 + 预算异常冒泡）、`main.py`（删除服务构造期预算检查、关停排空）及各模型边界
  （删除 `bind_max_output_tokens`）
- AI 测试：更新 `test_budget_guard.py`、`test_supervisor_budget.py`、`test_planner.py`、
  `test_usage_reporting.py`
- 文档：`docs/verification/task-31-backend.md`

## 7. 提交

- 基线实现提交：`f6da5c6892ff5b54582bdfa7f6b7da539fdfe6b9`
- 本次评审修复提交：`5723f6319c4fef9d0c74dcf139b47f3a86a186b6`（`fix: enforce usage budget on every provider call`）
- 验证文档提交：`docs: verify task 31 review fixes`（本次提交）
- 远端：`origin/agent/deepseek-task-31-usage-budget`
