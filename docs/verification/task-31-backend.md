# Task 31 验证证据：真实 Token、价格、预算与可观测性

- **执行 Agent**：DeepSeek Harness（后端与 Agent 执行工程师；实际 model ID 在真实调用后回填）
- **测试等级**：局部阶段为 `[UNIT_TEST]`（无网络、无外部依赖）；尚无 `[REAL_E2E]`
- **执行时间**：2026-09-18 18:13–19:10 (Asia/Shanghai)
- **Git 提交**：`55614e5`（中间提交，尚未推送）
- **关联分支**：`agent/deepseek-task-31-usage-budget`
- **工作树**：`/Users/moxiao/IdeaProjects/project-deepseek-task-31`

---

## 0. 范围与文件所有权

本文件只覆盖 Task 31 中属于 DeepSeek Harness 的部分：**用量采集、存储、计价与预算**。
健康页展示（`web/**`）属于 ZCode，其证据写入前端侧文档；Codex 负责计费口径与隐私审核。

| 文件 | 动作 | 归属 |
|---|---|---|
| `backend/.../db/migration/V44__add_assistant_usage_budget.sql` | 新建 | DeepSeek Harness |
| `backend/.../agent/usage/**`（11 个类） | 新建 | DeepSeek Harness |
| `ai-service/app/observability/model_metrics.py` | 修改 | DeepSeek Harness |
| `backend/.../agent/api/AssistantHealthResponse.java` | 待修改 | DeepSeek Harness（字段），ZCode 消费 |
| `ai-service/app/providers/model_factory.py`、`unified_agent/supervisor.py` | 待修改 | DeepSeek Harness |
| `web/src/modules/assistant/AssistantHealthView.vue` | 不属本文件范围 | **ZCode** |

## 1. 运行环境与前置状态

- **操作系统**：macOS darwin arm64
- **Java**：`mvn`（项目 backend 模块，Maven 已可用）
- **Python**：worktree 内没有独立 `.venv`，本地测试复用主工作区解释器
  `/Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/python`，并以 `PYTHONPATH=.` 运行；
  已验证 `import app` 解析到 worktree 的 `app/__init__.py`，不会误用主工作区源码。
- **数据库**：本轮未接数据库（`[UNIT_TEST]`），Flyway/MySQL 回查留待 Step 7
- **使用模型**：本轮未调用真实模型，因此没有 model ID、Token 或成本数据

## 2. TDD 闭环证据

### 2.1 失败测试证据（RED Phase）

Java（先写测试，生产类尚不存在）：

```text
[ERROR] COMPILATION ERROR :
[ERROR] .../AssistantUsageServiceTest.java:[24,19] 找不到符号
[ERROR]   符号:   类 AssistantModelUsageJpaRepository
[ERROR] .../AssistantUsageServiceTest.java:[26,19] 找不到符号
[ERROR]   符号:   类 AssistantUsageBudgetJpaRepository
[ERROR] .../AssistantUsageServiceTest.java:[28,19] 找不到符号
[ERROR]   符号:   类 ModelPricingCatalog
[ERROR] .../AssistantUsageServiceTest.java:[31,19] 找不到符号
[ERROR]   符号:   类 AssistantUsageService
[ERROR] .../ModelPricingCatalogTest.java:[15,19] 找不到符号
[ERROR] Failed to execute goal ...maven-compiler-plugin:3.14.1:testCompile
```

Python（`ai-service`，采集函数尚不存在）：首次运行 `2 failed, 2 passed`。
失败原因是**测试夹具**把 usage 挂在 generation 上，而 LangChain 的真实结构是
`generation.message.usage_metadata`；实现未改动，仅修正夹具后转 GREEN。

### 2.2 成功测试证据（GREEN Phase）

```text
=== GREEN: PYTHON ===
....                                                                     [100%]
4 passed in 0.01s

=== GREEN: JAVA ===
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.036 s -- in ModelPricingCatalogTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.361 s -- in AssistantUsageServiceTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

覆盖到的行为：缓存输入与非缓存输入分开计价、未知模型返回空（不记零）、
reasoning token 缺失不影响估算、reasoning 不重复计费、同 `(executionId, turnId)`
重复回调不重复计费、未知价格仍落库但不写成本、已知价格写入 `DECIMAL` 金额与价格版本、
日预算耗尽拒绝新模型调用、预算内放行、无预算配置不拦截、预算按 owner 隔离。

## 3. 业务数据真实性回查（Data Re-check）

不适用：本轮为纯单元测试。Flyway 迁移 `V44` 已在真实 MySQL 上执行并回查
`assistant_model_usage` / `assistant_usage_budget` 之前，不得声称已落库验证。

## 4. 模型用量与成本统计

- 本轮无真实模型调用。
- **估算成本：不可估算。** `ModelPricingCatalog.official()` 当前为空目录，语义为
  "价格未知"，所有调用落库金额为 NULL，而不是 0。

## 5. 变更文件清单

- `新增`：`backend/src/main/resources/db/migration/V44__add_assistant_usage_budget.sql`
- `新增`：`backend/src/main/java/com/moxiao/studypilot/agent/usage/` 下
  `ModelUsage`、`ModelPricingCatalog`、`AssistantModelUsageEntity`、
  `AssistantModelUsageJpaRepository`、`AssistantUsageBudgetEntity`、
  `AssistantUsageBudgetJpaRepository`、`RecordUsageCommand`、`AssistantUsageRecord`、
  `BudgetDecision`、`AssistantUsageService`、`AssistantUsageConfiguration`
- `修改`：`ai-service/app/observability/model_metrics.py`（新增 `ModelUsageSample` 与
  `extract_model_usage`，兼容 DeepSeek 的 `prompt_cache_hit_tokens` 与新版 `usage_metadata`）
- `测试`：`backend/.../agent/usage/ModelPricingCatalogTest.java`、
  `backend/.../agent/usage/AssistantUsageServiceTest.java`、
  `ai-service/tests/observability/test_model_metrics_usage.py`

## 6. 未覆盖项与已知限制

1. **官方价格数值缺失（需外部输入）**：本环境外部网络不可达，`web_fetch` 对
   `api-docs.deepseek.com`、`raw.githubusercontent.com` 等一律返回
   "resolves to a non-public IP address"。因此 `official()` 暂为空目录，Task 31
   的"估算花费"目前只能回答"不可估算"。需要能访问官方定价页的一方提供**数值 + 版本日期**后填入。
2. **上报通道未实现**：Python 采集出的 `ModelUsageSample` 尚未上报到 Java；
   `supervisor.py` 的轮次边界、`model_factory.py` 的 callback 注入、幂等键
   `(executionId, turnId)` 的端到端串联都还没做。
3. **预算未拦截模型调用**：`checkBudget` 只有服务层判定，缺少内部接口与 supervisor 拦截。
4. **`AssistantHealthResponse` 未扩展**：modelId、缓存 Token、P50/P95、失败率、价格版本字段尚未提供，ZCode 健康页暂时拿不到这些数据。
5. **无 `[REAL_E2E]`**：未执行 Flyway/MySQL 集成、未做真实最小模型调用、未做三端全量回归。
6. worktree 无独立 `.venv`，Python 测试依赖主工作区解释器。

## 7. 下一步交接建议

1. 先补 Python→Java 上报接口与 supervisor 轮次边界接入，让"真实 Token 落库"闭环。
2. 再扩展 `AssistantHealthResponse` 并同步 `AssistantHealthServiceTest`，冻结给 ZCode 消费的字段。
3. 取得官方价格数值后填入 `ModelPricingCatalog.official()`，并在本文件回填价格版本与来源。
4. 最后执行三端全量测试、Flyway/MySQL 集成与一次真实最小模型调用，再由 Codex 验收。
