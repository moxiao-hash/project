# Adaptive Plan Adjustment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 根据真实任务执行偏差生成可审计的计划调整草稿，并按风险和用户授权安全执行。

**Architecture:** Java 保存学习事实、聚合偏差信号、持久化调整草稿并以事务执行；
Python 使用 DeepSeek 生成候选操作并负责手动/午夜编排。模型输出始终经过任务归属、
版本、风险与负荷的确定性校验。

**Tech Stack:** Java 17、Spring Boot、Flyway、MySQL、Python 3.12、FastAPI、
LangChain、APScheduler、DeepSeek、JUnit、pytest

---

### Task 1: 完成任务时记录实际学习分钟数

**Files:**
- Modify: `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/LearningTaskEntity.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/TaskChangeEntity.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/learning/api/InternalChangeTaskStatusRequest.java`
- Modify: `ai-service/app/schemas/learning.py`
- Test: `backend/src/test/java/com/moxiao/studypilot/learning/api/InternalTaskStatusToolContractTest.java`
- Test: `ai-service/tests/agent/test_task_service.py`

- [x] **Step 1: 写失败契约测试**

  ```java
  changeTaskStatus(taskId, completedRequest(ownerId, operationKey, 1, 80))
      .andExpect(jsonPath("$.actualMinutes").value(80));
  ```

  ```python
  assert result.action_draft.actual_minutes == 80
  ```

- [x] **Step 2: 验证测试因字段缺失失败**

  ```bash
  cd backend && ./mvnw -Dtest=InternalTaskStatusToolContractTest test
  cd ai-service && .venv/bin/pytest tests/agent/test_task_service.py -q
  ```

- [x] **Step 3: 增加 Flyway V12 与兼容字段**

  ```sql
  ALTER TABLE learning_tasks ADD COLUMN actual_minutes INT NULL;
  ALTER TABLE task_changes ADD COLUMN actual_minutes INT NULL;
  ```

  `actualMinutes` 仅在 `COMPLETED` 时允许，范围 1～720；省略时保持 `null`。

- [x] **Step 4: 运行局部和全量测试**

  ```bash
  cd backend && ./mvnw test
  cd ai-service && .venv/bin/pytest -q && .venv/bin/ruff check .
  ```

- [x] **Step 5: 提交**

  ```bash
  git commit -m "feat: record actual task study time"
  ```

### Task 2: 聚合执行结果并识别偏差信号

**Files:**
- Create: `backend/src/main/java/com/moxiao/studypilot/learning/api/InternalAdaptationContextController.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/learning/application/LearningAdaptationContextService.java`
- Create: `backend/src/test/java/com/moxiao/studypilot/learning/api/InternalAdaptationContextContractTest.java`
- Modify: `ai-service/app/clients/java_backend.py`

- [x] **Step 1: 写失败测试定义接口**

  ```text
  GET /internal/users/{ownerId}/adaptation-context
      ?analysisDate=2026-07-27&windowDays=14
  ```

  响应必须包含当前计划、用户每日上限、过去任务、未来任务及：
  `OVERDUE_TASKS`、`CONSECUTIVE_SKIPS`、`TIME_ESTIMATE_BIAS`。

- [x] **Step 2: 验证接口返回 404**

  ```bash
  cd backend && ./mvnw -Dtest=InternalAdaptationContextContractTest test
  ```

- [x] **Step 3: 实现确定性聚合**

  ```text
  OVERDUE_TASKS: TODO 且 scheduledDate < analysisDate
  CONSECUTIVE_SKIPS: 最近终态执行序列连续至少 2 个 SKIPPED
  TIME_ESTIMATE_BIAS: 至少 3 个 actualMinutes，且 |sum(actual)-sum(estimate)| /
                      sum(estimate) > 0.30
  ```

- [x] **Step 4: 为 Python 客户端补 camelCase 契约测试并实现调用**

  ```python
  await client.get_adaptation_context(
      owner_id,
      analysis_date=date(2026, 7, 27),
      window_days=14,
  )
  ```

- [x] **Step 5: 全量验证并提交**

  ```bash
  git commit -m "feat: aggregate learning execution deviations"
  ```

### Task 3: 持久化和校验计划调整草稿

**Files:**
- Create: `backend/src/main/resources/db/migration/V13__create_plan_adjustments.sql`
- Create: `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/PlanAdjustmentEntity.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/learning/application/PlanAdjustmentService.java`
- Create: `ai-service/app/agent/adjustment_models.py`
- Test: `backend/src/test/java/com/moxiao/studypilot/learning/api/PlanAdjustmentContractTest.java`
- Test: `ai-service/tests/agent/test_adjustment_models.py`

- [x] **Step 1: 写失败模型与持久化测试**

  ```json
  {
    "operations": [
      {
        "type": "RESCHEDULE_TASK",
        "taskId": "task-1",
        "expectedVersion": 1,
        "scheduledDate": "2026-07-28"
      }
    ]
  }
  ```

- [x] **Step 2: 验证缺少模型和接口导致测试失败**

- [x] **Step 3: 增加 `plan_adjustments` 表**

  保存 `idempotency_key`、owner/plan、analysisDate、triggerType、signals/draft JSON、
  riskLevel、status、executionId、before/afterVersion、error 和时间戳；幂等键唯一。

- [x] **Step 4: 实现 Python 强类型操作和确定性校验**

  小调整最多 3 个任务、最多 1 次拆分、不越过计划结束日期；否则升级为大调整。

- [x] **Step 5: 全量验证并提交**

  ```bash
  git commit -m "feat: persist adaptive plan drafts"
  ```

### Task 4: 原子执行、授权、版本与通知

**Files:**
- Modify: `backend/src/main/java/com/moxiao/studypilot/learning/application/PlanAdjustmentService.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/LearningPlanEntity.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/LearningTaskEntity.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/learning/api/PlanAdjustmentExecutionContractTest.java`
- Test: `ai-service/tests/agent/test_adjustment_service.py`

- [x] **Step 1: 写失败测试覆盖原子执行和风险治理**

  ```text
  小调整 + 有效 SMALL_PLAN_ADJUSTMENT grant -> PENDING -> 自动执行
  小调整 + 无 grant -> WAITING_AUTHORIZATION
  大调整 -> HIGH + WAITING_CONFIRMATION
  任一 expectedVersion 冲突 -> 409 且所有修改回滚
  ```

- [x] **Step 2: 验证测试按预期失败**

- [x] **Step 3: 实现内部原子接口**

  ```text
  POST /internal/plan-adjustments
  POST /internal/plan-adjustments/{id}/execute
  GET  /internal/plan-adjustments/{id}
  ```

  支持重新安排、预计时长修改和拆分；一次调整只增加一次计划版本。

- [x] **Step 4: 保存完整任务快照、通知和审计**

  成功创建 `PLAN_ADJUSTED`；待处理创建 `PLAN_ADJUSTMENT_READY`；失败使用
  `AGENT_FAILED`。

- [x] **Step 5: 全量验证并提交**

  ```bash
  git commit -m "feat: execute governed plan adjustments"
  ```

### Task 5: DeepSeek 分析、确认接口和午夜调度

**Files:**
- Create: `ai-service/app/agent/adjustment_service.py`
- Create: `ai-service/app/api/plan_adjustments.py`
- Create: `ai-service/app/scheduler/nightly_adjustments.py`
- Modify: `ai-service/app/main.py`
- Modify: `ai-service/pyproject.toml`
- Test: `ai-service/tests/api/test_plan_adjustments.py`
- Test: `ai-service/tests/scheduler/test_nightly_adjustments.py`

- [x] **Step 1: 写失败 API 和调度测试**

  ```text
  POST /internal/agent/plan-adjustments/analyze
  GET  /internal/agent/plan-adjustments/{id}
  POST /internal/agent/plan-adjustments/{id}/confirm
  ```

  验证无信号不调用模型、授权小调整自动执行、大调整只返回草稿、午夜遗漏可补跑。

- [x] **Step 2: 验证路由 404、调度模块缺失**

- [x] **Step 3: 实现结构化 DeepSeek 建议和服务编排**

  模型只读取 Java context；输出经 Python 校验后先持久化，再根据 AgentExecution
  初始状态决定自动执行或等待确认。

- [x] **Step 4: 使用 APScheduler 每 15 分钟查询待补跑用户**

  用户本地日期跨日后分析前一天；夜间幂等键：
  `plan-adjustment:nightly:{ownerId}:{analysisDate}`。

- [x] **Step 5: 全量验证并提交**

  ```bash
  git commit -m "feat: schedule adaptive plan analysis"
  ```

### Task 6: 真实端到端联调和阶段文档

**Files:**
- Create: `docs/plan-adjustment-e2e.http`
- Modify: `项目开发步骤.md`
- Modify: `docs/superpowers/plans/2026-07-26-adaptive-plan-adjustment.md`

- [ ] **Step 1: 增加独立 IDEA HTTP Client 流程**

  ```text
  创建计划和多日任务
  → 完成任务并记录 actualMinutes
  → 制造平衡阈值偏差
  → 手动分析
  → 长期授权后自动执行小调整
  → 核验新版本、通知和审计
  → 重复调用验证幂等
  ```

- [ ] **Step 2: 使用本机 MySQL、Spring Boot、FastAPI 和 DeepSeek 真实联调**

- [ ] **Step 3: 运行最终验证**

  ```bash
  cd backend && ./mvnw test
  cd ai-service && .venv/bin/pytest -q && .venv/bin/ruff check .
  git diff --check
  ```

- [ ] **Step 4: 更新阶段 5 进度、结果、已知限制**

- [ ] **Step 5: 精确暂存并提交推送**

  ```bash
  git commit -m "test: complete adaptive plan workflow"
  git push origin main
  ```
