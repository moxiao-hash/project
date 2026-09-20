# Task 31 验证证据：真实 Token、价格、预算与可观测性

- **执行 Agent**：DeepSeek Harness（后端与 Agent 执行工程师）
- **测试等级**：`[UNIT_TEST]` + `[INTEGRATION_TEST]`（真实 MySQL 9.6 + Flyway）+ `[REAL_E2E]`（真实 DeepSeek 模型调用 1 次）
- **执行时间**：2026-09-20 19:59–20:15 (Asia/Shanghai)
- **关联分支**：`agent/deepseek-task-31-usage-budget`（已推送到 origin）
- **工作树**：`/Users/moxiao/Desktop/Deepseek Harness/workspaces/studypilot-task31`
- **实现提交**：`f6da5c6892ff5b54582bdfa7f6b7da539fdfe6b9`（本文件随后作为文档提交 `docs: verify task 31 usage budget` 落库）

---

## 0. 范围与文件所有权

本文件只覆盖 Task 31 中属于 DeepSeek Harness 的部分：**用量采集、存储、计价、预算与健康接口**。
`web/**` 健康页展示属于 ZCode，本分支未改动 `web/**`，也未合并 `main`、未启动 Task 32。

| 文件 | 动作 | 归属 |
|---|---|---|
| `backend/.../db/migration/V45__add_assistant_usage_budget.sql` | 新建（由 V44 改名，Task 30 占用 V44） | DeepSeek Harness |
| `backend/.../agent/usage/**` | 新建/修改 | DeepSeek Harness |
| `backend/.../agent/api/AssistantHealthResponse.java`、`AssistantModelUsageStats.java` | 修改/新建 | DeepSeek Harness（字段），ZCode 消费 |
| `backend/.../agent/application/AssistantHealthService.java` | 修改 | DeepSeek Harness |
| `ai-service/app/observability/usage.py`、`model_metrics.py` | 新建/修改 | DeepSeek Harness |
| `ai-service/app/providers/budget.py`、`model_factory.py` | 新建/修改 | DeepSeek Harness |
| `ai-service/app/unified_agent/supervisor.py`、各模型边界 | 修改 | DeepSeek Harness |
| `web/src/modules/assistant/AssistantHealthView.vue` | 未改动 | **ZCode** |

## 1. 运行环境

- **操作系统**：macOS darwin arm64
- **Java**：OpenJDK 26.0.1 + Maven 3.9.16（`backend/pom.xml` 编译目标 17）
- **Python**：3.12.13，复用主工作区解释器
  `/Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/python`，以 `PYTHONPATH=.` 运行；
  worktree 内无独立 `.venv`，测试不会误用主工作区源码（`import app` 解析到本 worktree）。
- **MySQL**：本机 9.6.0，`studypilot`/`studypilot`，库 `studypilot`
- **真实模型**：`deepseek-v4-flash`（官方页说明的旧名，实际由 DeepSeek-V4.1-Flash 提供服务并按 Flash 价格计费），
  `MODEL_BASE_URL=https://api.deepseek.com`，Key 来自本机已被 Git 忽略的运行时 `.env`，未写入仓库或日志。

## 2. 实现内容

### 2.1 迁移与存储

- 迁移文件重命名为 **`V45__add_assistant_usage_budget.sql`**，全仓引用（计划文档、验证文档）同步更新。
- `assistant_model_usage`：主键 `id`（AI 侧 `usageId`，幂等键），记录
  `owner_id / conversation_id / turn_id / execution_id / provider / model_name / purpose / status /
  prompt_tokens / cached_prompt_tokens / completion_tokens / reasoning_tokens / total_tokens /
  latency_ms / estimated_cost DECIMAL(18,8) / currency / price_version / price_window / occurred_at`。
  `total_tokens = prompt + completion`，reasoning 只是 completion 的子集，不重复相加。
- `assistant_usage_budget`：`daily_model_calls / daily_estimated_cost / max_output_tokens_per_turn /
  currency / updated_at / row_version`。

### 2.2 幂等与并发

- `AssistantUsageService.record` 先按 `usageId` 查重，重复返回 `duplicate=true` 且不再写库。
- 并发重复由主键约束兜底：`saveAndFlush` 抛 `DataIntegrityViolationException` 后回读已存在记录。
  方法刻意不加外层事务，插入冲突事务回滚后仍能在新事务回读。
- AI 侧 `usageId = uuid5(owner|conversation|turn|purpose|run_id)`，每次逻辑调用唯一；
  同一 LangChain run 的重复回调按 `run_id` 去重，上报 HTTP 重试复用同一 `usageId`。

### 2.3 价格目录（官方定价页，核对日期 2026-09-20）

来源：https://api-docs.deepseek.com/quick_start/pricing/ （USD / 每百万 token）

| 模型（别名） | 版本 | 缓存命中 高峰/非高峰 | 缓存未命中 高峰/非高峰 | 输出 高峰/非高峰 |
|---|---|---|---|---|
| `deepseek-flash`、`deepseek-v4-flash`、`deepseek-v4-flash-vision-exp` | DeepSeek-V4.1-Flash | 0.006 / 0.003 | 0.30 / 0.15 | 1.20 / 0.60 |
| `deepseek-v4-pro` | DeepSeek-V4-Pro-0813 | 0.044 / 0.022 | 1.32 / 0.66 | 3.96 / 1.98 |

- 高峰时段：**周一至周五 01:00–04:00、06:00–10:00 UTC**（起始含、结束不含），其余时间非高峰。
- 目录带 `version=deepseek-pricing-2026-09-20`、`effectiveFrom=2026-09-20`、`sourceUrl`；
  调价只影响新记录，历史记录保留自己的金额与价格版本。
- 未列入目录的模型返回 `Optional.empty()`，落库金额为 `NULL`、`priceStatus=UNKNOWN`，绝不当成 0。
- 单测覆盖全部时段边界（00:59:59 / 01:00 / 03:59:59 / 04:00 / 05:59:59 / 06:00 / 09:59:59 /
  10:00 / 23:59:59、周六、周日）。

### 2.4 采集边界与 Python→Java 上报

- `create_chat_model` 为每个模型装配 `ModelUsageCallback`；采集点覆盖：
  Agent Planner/工具轮次、知识问答、测验生成、代码评估、Rubric/成果评分、资料分析、计划调整、
  任务识别、教学问答。
- `ModelUsageCallback` 从 LangChain 响应解析缓存/非缓存输入、输出与 `completion_tokens_details.
  reasoning_tokens`；无事件循环的执行器线程里用一次性事件循环同步上报（真实调用实测触发该分支）。
- `AssistantUsageReporter` 带有限重试 + Prometheus 指标（`success/retry/failure`）+ 结构化日志；
  上报失败只影响可观测性，**不会**把一次成功的模型调用改判为失败。
- 内部接口 `POST /internal/assistant-usage`、`GET /internal/assistant-usage/budget`
  与其它 `/internal/**` 共用 `InternalServiceTokenFilter`；请求体只接受用量数字段，
  不接受提示词、正文或 Key。

### 2.5 预算

- `AssistantUsageService.checkBudget` 使用显式配置的时区
  `studypilot.assistant.budget.timezone`（默认 `Asia/Shanghai`，可用 `ASSISTANT_BUDGET_TIMEZONE` 覆盖），
  不使用 `ZoneId.systemDefault()`；日窗口为本地 `[当天 00:00, 次日 00:00)`，次日边界由本地日期
  加一天再取当日起点，能正确处理夏令时切换。
- 拦截点：Supervisor 在每轮模型调用前查询预算；后台任务（资料、代码评估、三类测验、计划调整）
  在按 owner 构造模型前调用 `ModelBudgetGuard.require`；两者都在模型真正发起前完成。
- 预算被拒绝时：Supervisor 跳过 Planner 与知识模型调用，仍执行 `learning.context.get`、
  `navigation.resolve` 等纯 Java 查询/导航，并返回可恢复提示（例如打开学习资料页）。
- 单轮输出上限 `maxOutputTokensPerTurn` 由预算接口下发，通过 `bind_max_output_tokens` 绑定到
  Planner、知识回答与各结构化模型请求的 `max_tokens`。
- 预算接口不可达时按“放行并记日志”处理（预算与模型凭据共用同一个 Java 后端，凭据不可用时模型
  本身也无法创建）；Java 明确返回 `allowed=false` 时严格拦截。

### 2.6 健康接口

`GET /api/assistant/health`（Bearer 用户鉴权）在原有执行层字段之外新增：

- 模型层：`modelCalls / failedModelCalls / modelFailureRate / modelPromptTokens /
  modelCachedPromptTokens / modelUncachedPromptTokens / modelCompletionTokens /
  modelReasoningTokens / modelTotalTokens / unknownPriceCalls / usageEstimatedCost /
  currency / priceStatus / priceVersion / p50LatencyMs / p95LatencyMs / models[]`。
- `models[]` 每个模型给出调用量、失败数/失败率、四类 token、估算费用、币种、价格状态与版本、
  P50/P95 延迟。
- 金额一律 `BigDecimal`；`unknownPriceCalls>0` 时 `priceStatus=UNKNOWN` 且金额为 `null`，
  不会被静默当作 0。
- 所有查询都按登录用户的 owner 过滤，跨用户严格隔离。

## 3. 验证命令与结果

### 3.1 三端可运行检查（本机实测）

```text
# Java 全量
cd backend && mvn test
[INFO] Tests run: 416, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

# Python 全量
cd ai-service && PYTHONPATH=. <venv>/python -m pytest -q
423 passed

# Ruff
cd ai-service && <venv>/python -m ruff check app tests
All checks passed
```

其中 Task 31 新增/改写的定向测试：

```text
[INFO] Tests run: 2  -- InternalAssistantUsageContractTest   # 幂等、未知价格、失败计数、owner 隔离、内部令牌
[INFO] Tests run: 12 -- AssistantUsageServiceTest            # 重复回调、并发冲突、预算时区边界、金额
[INFO] Tests run: 20 -- ModelPricingCatalogTest              # 官方价格、别名、高峰边界、未知模型
[INFO] Tests run: 4  -- AssistantHealthServiceTest           # 模型级统计、P50/P95、未知价格、owner 隔离
```

Python 新增：`test_usage_reporting.py`（缓存/非缓存、reasoning、重复回调、失败状态、上报重试、
无事件循环路径、幂等键稳定性）、`test_budget_guard.py`、`test_supervisor_budget.py`
（预算耗尽仍可纯 Java 导航、等待确认不受影响、预算内正常规划）。

### 3.2 V45 真实 MySQL / Flyway 证明

Task 30 已占用 `V44__add_agent_tool_action_recovery.sql`。证明方式：取本分支全部迁移，叠加 Task 30 的
`V44__add_agent_tool_action_recovery.sql` 形成 45 个迁移的合并目录（两分支迁移目录 diff 仅差这两个文件），
对已应用 Task 30 V44 的真实 MySQL `studypilot` 库执行 Flyway：

```text
DbValidate  : Successfully validated 45 migrations
DbMigrate   : Current version of schema `studypilot`: 44
DbMigrate   : Migrating schema `studypilot` to version "45 - add assistant usage budget"
DbMigrate   : Successfully applied 1 migration to schema `studypilot`, now at version v45
migrationsExecuted=1  targetSchemaVersion=45
flyway_schema_history: 44 | V44__add_agent_tool_action_recovery.sql | success=1
flyway_schema_history: 45 | V45__add_assistant_usage_budget.sql   | success=1
重复版本查询: 空（无任何重复版本）
```

随后用 `SPRING_FLYWAY_LOCATIONS` 指向合并目录启动真实 Spring Boot：

```text
Flyway: Successfully validated 45 migrations；Schema `studypilot` is up to date（v45）
Hibernate: Initialized JPA EntityManagerFactory for persistence unit 'default'   # ddl-auto=validate 通过
Tomcat started on port 18080
Started StudyPilotApplication in 2.651 seconds
```

并完成一次真实 HTTP 端到端（注册用户 → 内部上报真实模型用量 → 重复上报 → 读取健康接口）：

```text
register   201  owner=eb61840d-...
record     201  estimatedCost=1.635E-5 USD priceVersion=deepseek-pricing-2026-09-20
                priceWindow=OFF_PEAK priceStatus=KNOWN duplicate=false
duplicate  201  duplicate=true
budget     200  {allowed:true, reason:NO_BUDGET_CONFIGURED, timezone:Asia/Shanghai}
health     200  modelCalls=1 priceStatus=KNOWN usageEstimatedCost=1.635E-5 USD unknownPriceCalls=0
                model: prompt=37 cached=0 uncached=37 completion=18 reasoning=16 total=55
                       p50=658ms p95=658ms estimatedCost=1.635E-5 USD
```

### 3.3 真实最小模型调用 `[REAL_E2E]`

```text
model_name=deepseek-v4-flash  provider=deepseek
response=OK
usage:
  purpose=KNOWLEDGE_QA status=SUCCEEDED
  promptTokens=37 cachedPromptTokens=0 completionTokens=18 reasoningTokens=16
  latencyMs=658 occurredAt=2026-09-20T12:09:14Z
```

- 该调用发生在 2026-09-20（周日，非高峰），按 Flash 非高峰价估算
  `37×0.15/1e6 + 18×0.60/1e6 = 1.635E-5 USD`，与健康接口回放一致。
- 未记录提示词正文或 Key；日志只含模型名、用途、token 与状态。
- 真实调用首次暴露出“LangChain 在执行器线程调用同步回调、无运行中事件循环”的上报缺口，
  已修复为无循环时同步上报，并补了 `test_sync_callback_without_event_loop_still_reports`。

## 4. 业务数据真实性回查

- 真实 MySQL 中已存在 `assistant_model_usage` / `assistant_usage_budget` 两张表，列与实体一致
  （见 §3.2）。端到端上报写入真实行，健康接口读出该行；重复上报未新增行（`modelCalls=1`）。

## 5. 已知限制

1. **节假日日历未内置**：官方页注明中国法定节假日全天按非高峰计价。当前实现只按“周一至周五
   01:00–04:00、06:00–10:00 UTC”判定高峰，未内置节假日表；需要时由部署方按年维护。该限制不影响
   边界测试与金额可追溯性。
2. **预算判定频度**：Supervisor 在每轮开始查询一次预算，后台任务在按 owner 构造模型前查询；
   同一轮内的多次模型调用不会在每次调用前重复查询。每日调用次数与费用按上报逐条累积，超限后的
   下一轮/下一个任务会被拦截。
3. **预算接口不可达时放行**：优先级是“不因预算服务抖动中断所有 AI”，但会记录
   `assistant.budget.unavailable` 日志；预算与模型凭据共用同一 Java 后端，因此该窗口很窄。
4. **未知价格不进费用预算**：未知价格调用金额为 `NULL`，只计入每日调用次数上限，不计入费用上限。
5. **本 worktree 无独立 `.venv`**：Python 测试复用主工作区解释器。
6. 未运行 `web/**` 测试；健康页展示属于 ZCode，本分支不触碰 `web/**`。

## 6. 变更文件清单（Task 31 第三批）

- 迁移：`V44__add_assistant_usage_budget.sql` → `V45__add_assistant_usage_budget.sql`
- 后端新增：`AssistantModelUsageStats.java`、`AssistantBudgetProperties.java`
- 后端修改：`AssistantUsageService`、`ModelPricingCatalog`、`AssistantUsageConfiguration`、
  `AssistantModelUsageEntity`、`AssistantModelUsageJpaRepository`、`AssistantUsageRecord`、
  `AssistantUsageResponse`、`BudgetDecision`、`AssistantBudgetResponse`、`RecordUsageCommand`、
  `RecordAssistantUsageRequest`、`ModelUsage`、`AssistantHealthResponse`、`AssistantHealthService`、
  `application.properties`；删除 `AssistantPricingProperties`
- 后端测试：新增 `InternalAssistantUsageContractTest`；更新 `AssistantUsageServiceTest`、
  `ModelPricingCatalogTest`、`AssistantHealthServiceTest`
- AI 新增：`app/observability/usage.py`、`app/providers/budget.py`、`tests/observability/test_usage_reporting.py`、
  `tests/providers/test_budget_guard.py`、`tests/unified_agent/test_supervisor_budget.py`
- AI 修改：`model_metrics.py`（Ruff I001 + reasoning 明细）、`model_factory.py`、`java_backend.py`、
  `main.py`、`supervisor.py`、各模型边界（planner/answering/generator/evaluation/evaluator/analysis 等）
  与 3 个既有测试的 fake 签名
- 文档：`docs/verification/task-31-backend.md`、计划文档 V45 引用

## 7. 提交

- 实现提交：`f6da5c6892ff5b54582bdfa7f6b7da539fdfe6b9`
- 验证文档提交：`docs: verify task 31 usage budget`（本次提交）
- 远端：`origin/agent/deepseek-task-31-usage-budget`（已推送）
