# Learning Plan Conversation Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现具有多轮上下文、结构化计划生成、人工确认、Java 原子落库和执行审计的学习计划 Agent。

**Architecture:** Python 使用 LangGraph `InMemorySaver` 管理 thread 级短期上下文，DeepSeek Planner 只生成 Pydantic 结构，`interrupt()` 在写操作前暂停。确认后 Python 调用 Java 内部治理与原子创建接口；Java 在单个事务内创建并确认计划、创建全部任务，MySQL 保存最终业务事实。

**Tech Stack:** Java 17、Spring Boot 4、JPA、Flyway、MySQL/H2、Python 3.12、FastAPI、LangGraph 1.x、LangChain OpenAI、Pydantic 2、HTTPX、Pytest

---

## 文件结构

### Java 新增或修改

- `backend/src/main/resources/db/migration/V10__add_agent_plan_idempotency.sql`：保存 Agent 计划幂等键。
- `backend/src/main/java/com/moxiao/studypilot/learning/api/CreateConfirmedLearningPlanRequest.java`：原子创建请求。
- `backend/src/main/java/com/moxiao/studypilot/learning/api/ConfirmedLearningPlanResponse.java`：计划与任务组合响应。
- `backend/src/main/java/com/moxiao/studypilot/learning/api/InternalLearningToolController.java`：新增原子创建入口。
- `backend/src/main/java/com/moxiao/studypilot/learning/application/ConfirmedLearningPlanService.java`：事务编排。
- `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/LearningPlanEntity.java`：增加幂等字段。
- `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/LearningPlanJpaRepository.java`：幂等查询。
- `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/LearningTaskJpaRepository.java`：按计划查询任务。
- `backend/src/main/java/com/moxiao/studypilot/agent/domain/AgentScope.java`：增加 `PLAN_GENERATION`。
- `backend/src/main/java/com/moxiao/studypilot/agent/api/ConfirmAgentExecutionRequest.java`：内部确认请求。
- `backend/src/main/java/com/moxiao/studypilot/agent/api/InternalAgentExecutionController.java`：新增内部确认入口。
- `backend/src/test/java/com/moxiao/studypilot/learning/api/ConfirmedLearningPlanContractTest.java`：原子性与幂等测试。
- `backend/src/test/java/com/moxiao/studypilot/agent/api/AgentGovernanceWorkflowTest.java`：内部确认审计测试。

### Python 新增或修改

- `ai-service/pyproject.toml`：增加 LangGraph。
- `ai-service/app/agent/models.py`：Planner、Draft、会话状态响应模型。
- `ai-service/app/agent/state.py`：LangGraph TypedDict。
- `ai-service/app/agent/planner.py`：Planner 协议与 DeepSeek 结构化实现。
- `ai-service/app/agent/graph.py`：节点、条件路由、interrupt 和持久化节点。
- `ai-service/app/agent/service.py`：会话注册、thread 配置与并发控制。
- `ai-service/app/api/conversations.py`：四个内部 HTTP 接口。
- `ai-service/app/schemas/agent.py`：Java AgentExecution 契约。
- `ai-service/app/schemas/learning.py`：原子计划请求与响应。
- `ai-service/app/clients/java_backend.py`：治理和计划工具调用。
- `ai-service/app/main.py`：注册 Conversation 路由。
- `ai-service/tests/agent/test_models.py`：草案业务校验。
- `ai-service/tests/agent/test_conversation_service.py`：上下文、修改、确认与幂等。
- `ai-service/tests/api/test_conversations.py`：HTTP 状态与认证。
- `ai-service/tests/clients/test_java_backend.py`：新增 Java 契约测试。

---

### Task 1: Java 原子创建已确认计划和任务

**Files:**
- Create: `backend/src/test/java/com/moxiao/studypilot/learning/api/ConfirmedLearningPlanContractTest.java`
- Create: `backend/src/main/resources/db/migration/V10__add_agent_plan_idempotency.sql`
- Create: `backend/src/main/java/com/moxiao/studypilot/learning/api/CreateConfirmedLearningPlanRequest.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/learning/api/ConfirmedLearningPlanResponse.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/learning/application/ConfirmedLearningPlanService.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/learning/api/InternalLearningToolController.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/LearningPlanEntity.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/LearningPlanJpaRepository.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/LearningTaskJpaRepository.java`

- [ ] **Step 1: 写失败接口测试**

测试先注册用户和目标，再调用：

```java
mockMvc.perform(post("/internal/confirmed-learning-plans")
        .header("X-Internal-Service-Token", "test-internal-token")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
                {
                  "ownerId": "%s",
                  "goalId": "%s",
                  "idempotencyKey": "plan-generation:thread-1",
                  "title": "Java 后端计划",
                  "startDate": "%s",
                  "endDate": "%s",
                  "tasks": [
                    {
                      "title": "学习依赖注入",
                      "scheduledDate": "%s",
                      "estimatedMinutes": 60
                    }
                  ]
                }
                """.formatted(ownerId, goalId, start, end, start))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.plan.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.tasks[0].title").value("学习依赖注入"));
```

同一个请求执行两次，断言 `plan.id` 相同，且 repository 中只存在一个计划和一个任务。
另写非法任务日期测试，断言响应 400 且计划数量未增加。

- [ ] **Step 2: 运行并确认 RED**

Run:

```bash
cd backend
./mvnw -Dtest=ConfirmedLearningPlanContractTest test
```

Expected: FAIL，`/internal/confirmed-learning-plans` 返回 404。

- [ ] **Step 3: 增加 Flyway 幂等字段**

```sql
ALTER TABLE learning_plans
    ADD COLUMN generation_idempotency_key VARCHAR(180);

CREATE UNIQUE INDEX uk_learning_plans_owner_generation_key
    ON learning_plans (owner_id, generation_idempotency_key);
```

`LearningPlanEntity` 增加可空字段、Agent 专用构造参数和 getter；公开手动创建路径传入
`null`。

- [ ] **Step 4: 定义强校验请求**

`CreateConfirmedLearningPlanRequest` 使用 Jakarta Validation：

- owner/goal/idempotency/title 非空；
- title 最大 120；
- tasks 数量 `@Size(min = 1, max = 100)`；
- task title 最大 160；
- minutes 5..720；
- compact constructor 校验 end 不早于 start、任务日期均位于区间。

- [ ] **Step 5: 实现事务服务**

```java
@Transactional
public ConfirmedLearningPlanResponse create(CreateConfirmedLearningPlanRequest request) {
    return planRepository
            .findByOwnerIdAndGenerationIdempotencyKey(
                    request.ownerId(), request.idempotencyKey())
            .map(this::existingResponse)
            .orElseGet(() -> createNew(request));
}
```

`createNew` 必须按顺序调用计划创建、计划确认、任务创建，并在同一个 Spring 事务中返回
组合响应。

- [ ] **Step 6: 验证 GREEN**

Run:

```bash
./mvnw -Dtest=ConfirmedLearningPlanContractTest test
```

Expected: all passed。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main backend/src/test/java/com/moxiao/studypilot/learning/api/ConfirmedLearningPlanContractTest.java
git commit -m "feat: atomically create confirmed agent plans"
```

### Task 2: Java AgentExecution 内部确认与审计

**Files:**
- Modify: `backend/src/main/java/com/moxiao/studypilot/agent/domain/AgentScope.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/agent/api/ConfirmAgentExecutionRequest.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/agent/api/InternalAgentExecutionController.java`
- Modify: `backend/src/test/java/com/moxiao/studypilot/agent/api/AgentGovernanceWorkflowTest.java`

- [ ] **Step 1: 写失败测试**

创建 `PLAN_GENERATION/HIGH/PLAN_GENERATION` execution 后调用：

```java
mockMvc.perform(post("/internal/agent-executions/{id}/confirm", executionId)
        .header("X-Internal-Service-Token", "test-internal-token")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
                {"ownerId":"%s"}
                """.formatted(ownerId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"));
```

随后查询 `/api/audit-logs`，断言存在 `EXECUTION_CONFIRMED`。错误 owner 返回 404。

- [ ] **Step 2: 验证 RED**

Run:

```bash
./mvnw -Dtest=AgentGovernanceWorkflowTest test
```

Expected: FAIL，枚举或内部路由不存在。

- [ ] **Step 3: 最小实现**

增加 `AgentScope.PLAN_GENERATION`，新增：

```java
@PostMapping("/{executionId}/confirm")
public AgentExecutionResponse confirm(
        @PathVariable String executionId,
        @Valid @RequestBody ConfirmAgentExecutionRequest request
) {
    return AgentExecutionResponse.from(
            service.confirm(request.ownerId(), executionId)
    );
}
```

- [ ] **Step 4: 验证 GREEN 并提交**

Run:

```bash
./mvnw -Dtest=AgentGovernanceWorkflowTest test
```

Expected: all passed。

```bash
git add backend/src/main backend/src/test/java/com/moxiao/studypilot/agent/api/AgentGovernanceWorkflowTest.java
git commit -m "feat: confirm agent executions through internal contract"
```

### Task 3: Python Agent 数据模型与业务校验

**Files:**
- Modify: `ai-service/pyproject.toml`
- Create: `ai-service/app/agent/__init__.py`
- Create: `ai-service/app/agent/models.py`
- Create: `ai-service/tests/agent/test_models.py`

- [ ] **Step 1: 增加 LangGraph 依赖并安装**

```toml
"langgraph>=1,<2",
```

Run:

```bash
cd ai-service
.venv/bin/python -m pip install -e '.[dev]'
```

- [ ] **Step 2: 写失败模型测试**

```python
def test_ready_turn_requires_draft() -> None:
    with pytest.raises(ValidationError):
        PlannerTurn(reply="已生成", status="DRAFT_READY", draft=None)


def test_task_must_be_inside_plan_range() -> None:
    with pytest.raises(ValidationError, match="计划日期范围"):
        PlanDraft(
            title="Java",
            start_date=date(2026, 8, 1),
            end_date=date(2026, 8, 31),
            tasks=[
                PlanTaskDraft(
                    title="越界任务",
                    scheduled_date=date(2026, 9, 1),
                    estimated_minutes=60,
                )
            ],
        )
```

- [ ] **Step 3: 验证 RED**

Run:

```bash
.venv/bin/python -m pytest tests/agent/test_models.py -q
```

Expected: FAIL，`app.agent.models` 不存在。

- [ ] **Step 4: 实现 Pydantic 模型**

实现：

- `ConversationStatus` 字符串枚举；
- `PlanTaskDraft`；
- `PlanDraft`，1..100 tasks 和日期交叉校验；
- `PlannerTurn`，status/draft 一致性校验；
- `ConversationSnapshot` 和 API request/response。

- [ ] **Step 5: 验证 GREEN 并提交**

Run:

```bash
.venv/bin/python -m pytest tests/agent/test_models.py -q
```

Expected: all passed。

```bash
git add ai-service/pyproject.toml ai-service/app/agent ai-service/tests/agent/test_models.py
git commit -m "feat: define structured learning plan agent models"
```

### Task 4: 扩展 Python Java 客户端契约

**Files:**
- Create: `ai-service/app/schemas/agent.py`
- Modify: `ai-service/app/schemas/learning.py`
- Modify: `ai-service/app/clients/java_backend.py`
- Modify: `ai-service/tests/clients/test_java_backend.py`

- [ ] **Step 1: 写四个失败契约测试**

使用 `httpx.MockTransport` 验证：

1. `POST /internal/agent-executions` 的枚举和幂等键；
2. `POST /internal/agent-executions/{id}/confirm` 的 ownerId；
3. `PATCH /internal/agent-executions/{id}` 的状态和模型名；
4. `POST /internal/confirmed-learning-plans` 的 camelCase 计划与任务。

计划测试断言：

```python
assert body["idempotencyKey"] == f"plan-generation:{conversation_id}"
assert body["tasks"][0]["estimatedMinutes"] == 60
```

- [ ] **Step 2: 验证 RED**

Run:

```bash
.venv/bin/python -m pytest tests/clients/test_java_backend.py -q
```

Expected: FAIL，客户端方法不存在。

- [ ] **Step 3: 实现类型化方法**

新增：

```python
async def create_agent_execution(...)
async def confirm_agent_execution(...)
async def update_agent_execution(...)
async def create_confirmed_learning_plan(...)
```

所有请求复用 `_request`，所有 JSON 使用
`model_dump(by_alias=True, mode="json", exclude_none=True)`。

- [ ] **Step 4: 验证 GREEN 并提交**

Run:

```bash
.venv/bin/python -m pytest tests/clients/test_java_backend.py -q
```

Expected: all passed。

```bash
git add ai-service/app/clients ai-service/app/schemas ai-service/tests/clients
git commit -m "feat: add agent governance Java client contracts"
```

### Task 5: LangGraph 会话、上下文与人工确认

**Files:**
- Create: `ai-service/app/agent/state.py`
- Create: `ai-service/app/agent/planner.py`
- Create: `ai-service/app/agent/graph.py`
- Create: `ai-service/app/agent/service.py`
- Create: `ai-service/tests/agent/test_conversation_service.py`

- [ ] **Step 1: 定义测试 Fake**

```python
class FakePlanner:
    def __init__(self, turns: list[PlannerTurn]) -> None:
        self.turns = deque(turns)
        self.seen_messages: list[list[str]] = []

    async def generate(self, state: ConversationState) -> PlannerTurn:
        self.seen_messages.append(
            [message.content for message in state["messages"]]
        )
        return self.turns.popleft()
```

Fake Java client记录 `created_plans`、execution 状态调用，不发 HTTP。

- [ ] **Step 2: 写失败上下文测试**

覆盖：

- 创建会话时 owner/goal 不匹配返回 `GoalNotFoundError`；
- 同一 conversation 第二轮能看到第一轮消息；
- 两个 conversation 的消息互不出现；
- `COLLECTING` 不调用任何 Java 写方法。

- [ ] **Step 3: 写失败 HITL 测试**

FakePlanner 第一次返回 `DRAFT_READY`：

```python
snapshot = await service.send_message(conversation_id, "生成计划")
assert snapshot.status == ConversationStatus.DRAFT_READY
assert java.created_plans == []

revised = await service.send_message(conversation_id, "每天改成 60 分钟")
assert revised.draft.tasks[0].estimated_minutes == 60
assert java.created_plans == []

completed = await service.confirm(conversation_id)
assert completed.status == ConversationStatus.COMPLETED
assert len(java.created_plans) == 1
```

重复 `confirm` 断言仍只有一个保存调用或返回同一 idempotent 结果。

- [ ] **Step 4: 验证 RED**

Run:

```bash
.venv/bin/python -m pytest tests/agent/test_conversation_service.py -q
```

Expected: FAIL，Graph/Service 不存在。

- [ ] **Step 5: 实现 State 与 Planner 协议**

`ConversationState` 使用：

```python
class ConversationState(TypedDict, total=False):
    owner_id: str
    goal_id: str
    messages: Annotated[list[AnyMessage], add_messages]
    learning_context: dict[str, Any]
    status: str
    reply: str
    draft: dict[str, Any] | None
    execution_id: str | None
    saved_plan: dict[str, Any] | None
    error: str | None
```

`PlanTurnGenerator` 为可注入 Protocol，使测试不依赖付费 API。

- [ ] **Step 6: 实现 Graph**

图边：

```text
START -> planner
planner -> END (COLLECTING)
planner -> register_execution (DRAFT_READY)
register_execution -> await_approval
await_approval -> planner (revise)
await_approval -> persist_plan (approve)
persist_plan -> END
```

`await_approval` 的 interrupt payload 只包含 JSON 可序列化草案。Java 写调用只存在于
`register_execution` 与 `persist_plan` 节点。

- [ ] **Step 7: 实现 ConversationService**

- UUID conversation registry；
- `conversationId` 用作 thread_id；
- 首次消息传入完整初始 state；
- 后续普通消息使用 plain dict 重新从 START 执行；
- interrupt 状态收到消息时用 `Command(resume={"action":"revise", ...})`；
- confirm 使用 `Command(resume={"action":"approve"})`；
- 每个 conversation 使用 `asyncio.Lock`；锁已占用时抛 `ConversationBusyError`；
- COMPLETED confirm 直接返回现有 snapshot。

- [ ] **Step 8: 验证 GREEN 并提交**

Run:

```bash
.venv/bin/python -m pytest tests/agent/test_conversation_service.py -q
```

Expected: all passed。

```bash
git add ai-service/app/agent ai-service/tests/agent/test_conversation_service.py
git commit -m "feat: add stateful learning plan graph"
```

### Task 6: DeepSeek 结构化 Planner

**Files:**
- Modify: `ai-service/app/agent/planner.py`
- Create: `ai-service/app/prompts/__init__.py`
- Create: `ai-service/app/prompts/learning_plan.py`
- Create: `ai-service/tests/agent/test_planner.py`

- [ ] **Step 1: 写失败 Prompt/Adapter 测试**

注入一个捕获输入的 fake structured model，断言 Prompt 包含：

- 目标标题、截止日期、每周学习小时；
- 用户可见消息历史；
- 现有任务和资料元数据；
- “不得声称读取资料正文”；
- `COLLECTING` / `DRAFT_READY` 输出规则。

断言 adapter 返回 `PlannerTurn`，不把 reasoning content 加入 messages。

- [ ] **Step 2: 验证 RED**

Run:

```bash
.venv/bin/python -m pytest tests/agent/test_planner.py -q
```

Expected: FAIL。

- [ ] **Step 3: 实现 DeepSeekPlanner**

使用已存在的 `create_chat_model(settings)`，再调用：

```python
structured_model = model.with_structured_output(
    PlannerTurn,
    method="json_mode",
)
```

Prompt 必须明确要求 JSON，并把 Java context 作为数据块而不是系统指令。结构校验失败
时最多修复一次；第二次失败抛 `PlannerOutputError`。

- [ ] **Step 4: 验证 GREEN 并提交**

Run:

```bash
.venv/bin/python -m pytest tests/agent/test_planner.py -q
```

Expected: all passed。

```bash
git add ai-service/app/agent/planner.py ai-service/app/prompts ai-service/tests/agent/test_planner.py
git commit -m "feat: generate structured plans with DeepSeek"
```

### Task 7: FastAPI Conversation 接口

**Files:**
- Create: `ai-service/app/api/conversations.py`
- Modify: `ai-service/app/main.py`
- Create: `ai-service/tests/api/test_conversations.py`

- [ ] **Step 1: 写失败 API 测试**

覆盖：

- 四个接口缺少内部令牌均为 401；
- create 返回 201 和 `COLLECTING`；
- message 返回 `COLLECTING` 或 `DRAFT_READY`；
- get 不调用模型；
- confirm 非 DRAFT_READY 返回 409；
- busy 返回 409；
- unknown conversation/goal 返回 404；
- 模型/Java 服务错误映射为 502/503，响应不含 Key、token 或堆栈。

- [ ] **Step 2: 验证 RED**

Run:

```bash
.venv/bin/python -m pytest tests/api/test_conversations.py -q
```

Expected: FAIL，路由不存在。

- [ ] **Step 3: 实现依赖组装和路由**

使用 `app.state.conversation_service` 作为应用级单例，测试通过
`app.dependency_overrides` 注入 Fake Service。所有路由复用
`require_internal_token`。

- [ ] **Step 4: 验证 GREEN 并提交**

Run:

```bash
.venv/bin/python -m pytest tests/api/test_conversations.py -q
```

Expected: all passed。

```bash
git add ai-service/app/api/conversations.py ai-service/app/main.py ai-service/tests/api
git commit -m "feat: expose learning plan conversation API"
```

### Task 8: 全量验证、文档与真实联调

**Files:**
- Modify: `ai-service/README.md`
- Modify: `docs/development-roadmap.md`
- Create: `docs/agent-api-examples.http`

- [ ] **Step 1: 增加 IDEA HTTP Client 示例**

文件包含：

1. Java 注册/登录和创建目标；
2. Python 创建 conversation；
3. 两轮 messages；
4. get conversation；
5. confirm；
6. Java 查询计划、任务和审计。

变量只使用非敏感本地示例，真实 Key 不进入 `.http`。

- [ ] **Step 2: 更新 README**

说明：

- 两个服务的启动顺序；
- 相同 `INTERNAL_SERVICE_TOKEN`；
- 当前内存上下文限制；
- API 测试步骤；
- 真实 DeepSeek 调用会产生费用；
- 代码阅读顺序。

- [ ] **Step 3: Java 全量验证**

Run:

```bash
cd backend
./mvnw test
```

Expected: 全部通过。

- [ ] **Step 4: Python 全量验证**

Run:

```bash
cd ai-service
.venv/bin/python -m ruff format --check app tests
.venv/bin/python -m ruff check app tests
.venv/bin/python -m pytest -q
.venv/bin/python -m compileall -q app
```

Expected: 全部通过，无 warning/error。

- [ ] **Step 5: MySQL 真实联调**

启动 Java 8080 和 Python 8000，按 `.http` 或 curl 完成：

```text
create conversation -> message -> revise -> confirm
```

查询确认：

- `learning_plans.status = CONFIRMED`；
- 任务数量与草案一致；
- generation idempotency key 唯一；
- agent execution 为 SUCCEEDED；
- audit log 包含确认与状态变更。

- [ ] **Step 6: 安全检查**

Run:

```bash
git diff --check
git status --short
rg -n 'sk-[A-Za-z0-9]{12,}' ai-service docs -g '!*.example' || true
```

Expected: 无真实 Key；用户的
`backend/src/main/resources/application.properties` 本地修改仍未暂存。

- [ ] **Step 7: 提交并推送**

```bash
git add ai-service docs/development-roadmap.md docs/agent-api-examples.http
git add backend/src/main/java backend/src/test/java
git add backend/src/main/resources/db/migration/V10__add_agent_plan_idempotency.sql
git commit -m "docs: add learning plan agent integration guide"
git push origin main
```
