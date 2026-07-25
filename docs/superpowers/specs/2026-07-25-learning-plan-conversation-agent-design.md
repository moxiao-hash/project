# 学习计划对话 Agent 设计

## 1. 目标与范围

本迭代实现 StudyPilot 第一条可操作 Agent 闭环：用户围绕一个已有学习目标进行多轮
对话，Agent 在上下文充分时生成结构化计划及任务草案，暂停等待人工确认，确认后通过
Java 事务性接口一次性创建已确认计划和正式任务。

本迭代包含：

- 多轮对话及同会话上下文；
- DeepSeek V4 结构化计划输出；
- 计划与任务的代码级校验；
- 修改、确认和失败恢复；
- Java 原子保存接口；
- Agent 执行记录与审计；
- 不调用真实模型的自动化测试。

本迭代不包含：

- Python 重启后的对话恢复；
- 前端聊天页面及 Java 公共网关；
- 资料正文解析、RAG 和联网搜索；
- 多 Agent、Redis、向量库和消息队列；
- 图片理解、OCR 或 GUI 自动化。

## 2. 参考项目与取舍

本设计参考以下公开项目和规范：

- LangChain 官方 `langgraph-101`：采用单个 StateGraph、短期记忆和人工介入的渐进式
  结构。
- LangChain Agent Protocol：采用 Thread/Run 的资源语义；`conversationId` 对应
  Thread，每次用户消息或确认对应一次 Run。
- `langgraph-interrupt-workflow-template`：采用生成草案后 approve/revise 的人工确认
  模式。
- `fastapi-langgraph-agent-production-ready-template`：借鉴 API、Graph、Schema、
  Provider、Client 的模块分层。
- `vertexai-rag-agent`：借鉴教育计划能力的独立边界，但暂不采用多 Agent 编排。

不会复制这些项目的业务代码。本项目继续保持 Java 为业务事实与权限中心，Python 为
AI 编排中心。

## 3. 总体数据流

```text
调用方创建会话(ownerId, goalId)
        │
        ▼
Python 调用 Java 获取学习上下文并验证目标归属
        │
        ▼
返回 conversationId（LangGraph thread_id）
        │
        ▼
用户发送消息
        │
        ▼
DeepSeek 返回 PlannerTurn 结构化结果
        │
        ├── COLLECTING：追问信息，本轮结束
        │
        └── DRAFT_READY：校验计划与任务
                              │
                              ▼
                   Java 创建 AgentExecution
                              │
                              ▼
                    LangGraph interrupt 暂停
                       │                 │
                  revise              approve
                       │                 │
                       ▼                 ▼
                 重新生成草案      确认执行记录
                                         │
                                         ▼
                              Java 原子创建已确认计划和任务
                                         │
                                         ▼
                               更新执行记录为成功/失败
```

## 4. API 设计

所有 Python 接口位于 `/internal/agent/**`，使用
`X-Internal-Service-Token`。当前通过 curl 测试；前端完成前，Java 公共网关作为后续
独立迭代实现。

### 4.1 创建会话

```http
POST /internal/agent/conversations
```

请求：

```json
{
  "ownerId": "用户 UUID",
  "goalId": "学习目标 UUID"
}
```

响应：

```json
{
  "conversationId": "会话 UUID",
  "ownerId": "用户 UUID",
  "goalId": "学习目标 UUID",
  "status": "COLLECTING",
  "reply": "请介绍你的当前基础和期望的学习节奏。"
}
```

创建时必须调用 Java 学习上下文接口，并确认 `goalId` 属于 `ownerId`。目标不存在时
返回 404，不创建 LangGraph thread。

### 4.2 发送消息

```http
POST /internal/agent/conversations/{conversationId}/messages
```

请求：

```json
{
  "content": "我熟悉 Java 基础，每周可以学习 10 小时。"
}
```

响应始终包含会话状态、回复和可选草案。状态为 `DRAFT_READY` 时返回完整结构化
`draft`。

如果 Graph 正处于确认 interrupt，新的消息被解释为修改反馈，并通过
`Command(resume={"action":"revise","feedback":"..."})` 恢复工作流；不会误当作批准。

### 4.3 查询会话

```http
GET /internal/agent/conversations/{conversationId}
```

返回当前状态和草案，用于刷新页面或调试。它只读取 LangGraph checkpointer，不调用
模型。

### 4.4 确认草案

```http
POST /internal/agent/conversations/{conversationId}/confirm
```

只有 `DRAFT_READY` 可以确认。接口使用
`Command(resume={"action":"approve"})` 恢复 Graph。重复确认已经完成的会话时返回同一
保存结果，不能重复创建计划。

## 5. LangGraph 状态与节点

### 5.1 ConversationState

```text
conversation_id     会话 ID，同时作为 thread_id
owner_id            会话创建后不可改变
goal_id             已验证归属的学习目标
messages            用户和模型的可见消息历史
learning_context    创建会话时读取的 Java 上下文快照
status              COLLECTING / DRAFT_READY / SAVING / COMPLETED / FAILED
reply               最近一次可展示回复
draft               当前结构化草案
execution_id        Java AgentExecution ID
saved_plan          已保存计划与任务摘要
error               可向调用方安全展示的错误
```

模型内部推理内容不进入 `messages`，也不通过 API 返回。

### 5.2 节点

1. `planner`：使用完整可见消息、目标和学习上下文调用 DeepSeek。
2. `validate_draft`：使用 Pydantic 和业务规则校验模型输出。
3. `register_execution`：通过幂等键 `plan-generation:{conversationId}` 创建 Java
   AgentExecution。
4. `await_approval`：调用 `interrupt()`，只接受 `approve` 或 `revise`。
5. `persist_plan`：先确认 AgentExecution，再调用 Java 原子保存接口，最后更新执行
   状态。
6. `mark_failed`：记录安全错误信息，并把 Java AgentExecution 更新为 `FAILED`。

`register_execution` 与 `persist_plan` 分成两个节点，避免 interrupt 恢复时重复执行
副作用。所有 Java 写请求同时具备幂等保护。

### 5.3 内存

第一版使用 `InMemorySaver`。相同 `conversationId` 复用同一个
`configurable.thread_id`，不同会话完全隔离。Python 重启后会话消失，但已经保存到
Java 的计划、任务、执行记录和审计不会丢失。

每个 conversation 使用进程内异步锁，禁止同一 thread 同时运行两个模型请求。不同
会话可以并行。

## 6. 模型结构化输出

DeepSeek 不返回供业务代码解析的自由文本 JSON，而是通过 LangChain
`with_structured_output` 生成 Pydantic 模型：

```text
PlannerTurn
├── reply: str
├── status: COLLECTING | DRAFT_READY
└── draft: PlanDraft | null

PlanDraft
├── title: str
├── start_date: date
├── end_date: date
└── tasks: 1..100 个 PlanTaskDraft

PlanTaskDraft
├── title: str
├── scheduled_date: date
└── estimated_minutes: 5..720
```

规则：

- `COLLECTING` 时 `draft` 必须为空；
- `DRAFT_READY` 时 `draft` 必须存在；
- 起止日期不得超出目标的合理范围；
- 任务日期必须位于计划区间；
- 任务按日期排序；
- 最多 100 个任务，避免一次生成不可审查的大型计划；
- 用户明确提供的信息优先于模型推测；
- 当前资料只有元数据可用，模型不得声称读取了尚未解析的资料正文。

若结构化输出或业务校验失败，只允许进行一次带校验错误摘要的自动修复调用；再次失败
则返回 `FAILED`，防止无限循环和意外费用。

## 7. Java 原子确认契约

新增：

```http
POST /internal/confirmed-learning-plans
```

请求：

```json
{
  "ownerId": "用户 UUID",
  "goalId": "目标 UUID",
  "idempotencyKey": "plan-generation:会话 UUID",
  "title": "Java 后端学习计划",
  "startDate": "2026-07-26",
  "endDate": "2026-12-31",
  "tasks": [
    {
      "title": "学习 Spring Bean 与依赖注入",
      "scheduledDate": "2026-07-27",
      "estimatedMinutes": 60
    }
  ]
}
```

Java 在一个 `@Transactional` 用例中：

1. 校验目标属于用户；
2. 校验日期、任务数量和单项时长；
3. 根据 `ownerId + idempotencyKey` 防止重复创建；
4. 创建 DRAFT 计划并立即执行现有领域确认逻辑；
5. 在计划状态为 `CONFIRMED` 后创建所有正式任务；
6. 返回已确认计划与任务。

任意步骤失败时整个事务回滚。

Python 的 `DRAFT_READY` 是保存在 LangGraph 会话状态中的可修改预览，并未写入
MySQL。独立确认接口就是用户对最终表单的明确确认，所以 Java 落库后的计划直接成为
`CONFIRMED`，不再要求第二次确认。

为持久化幂等键，新增 Flyway 迁移给 `learning_plans` 增加可空的
`generation_idempotency_key VARCHAR(180)`，并建立
`(owner_id, generation_idempotency_key)` 唯一索引。用户手动创建的计划保持该字段
为空，Agent 创建的计划写入会话级幂等键。

Agent 治理同步扩展：

- `AgentScope` 增加 `PLAN_GENERATION`；
- 生成可执行草案时创建 `ExecutionType.PLAN_GENERATION`；
- `RiskLevel.HIGH` 使执行等待确认；
- 用户确认后记录 `EXECUTION_CONFIRMED`；
- 保存过程中记录 `RUNNING`；
- 成功记录 `SUCCEEDED` 和模型名；
- 失败记录 `FAILED` 和脱敏错误；
- Prompt 和模型内部推理不写入审计日志。

新增内部确认契约：

```http
POST /internal/agent-executions/{executionId}/confirm
```

请求只包含 `ownerId`。Java 校验执行记录归属后复用现有 `confirm` 领域逻辑；Python
不能通过普通状态更新来绕过确认步骤。

## 8. 错误处理

- 会话或目标不存在：404。
- 内部令牌错误：401。
- 当前状态不能执行该操作：409。
- 同一会话已有 Run：409。
- 模型配置缺失：503。
- 模型超时、限流或结构化输出失败：502，并保留可重试状态。
- Java 业务校验失败：向调用方返回安全的 422/409，不把 SQL 或内部堆栈暴露出去。
- Java 保存结果未知时，不自动重试非幂等写操作；使用相同 idempotency key 查询或重试。

## 9. 测试策略

所有生产行为遵循 RED → GREEN：

### Python

- 会话创建绑定 owner/goal/thread；
- 相同 conversation 保留消息历史；
- 不同 conversation 上下文隔离；
- 信息不足返回 `COLLECTING`；
- 合法草案进入 `DRAFT_READY` 并产生 interrupt；
- 修改反馈恢复后生成新草案；
- 未确认时 Java 不发生计划写入；
- 确认后只保存一次；
- 模型异常和 Java 异常进入可观察失败状态；
- API Key、内部令牌和推理内容不出现在响应与日志；
- 测试使用 FakePlanner，不调用 DeepSeek。

### Java

- 原子接口校验内部令牌；
- 目标必须属于 owner；
- 合法请求同时创建计划和全部任务；
- 非法任务不留下半成品计划；
- 相同 idempotency key 返回相同结果；
- AgentExecution 确认和状态更新产生审计记录。

### 手动集成

1. 启动 MySQL、Java 8080 和 Python 8000；
2. 使用已有用户和目标创建会话；
3. 连续发送两条消息，验证第二轮记得第一轮内容；
4. 获取 `DRAFT_READY`；
5. 提交修改，验证草案变化；
6. 调用 confirm；
7. 通过 Java API 和 MySQL 验证计划、任务、执行记录与审计。

真实 DeepSeek 测试只由开发者手动触发，自动化测试不得消费 API 额度。

## 10. 后续演进

1. Java 公共对话网关与用户身份透传；
2. PostgreSQL/Redis checkpointer，使 Thread 跨重启恢复；
3. SSE 流式回复和运行进度；
4. 资料解析、RAG、联网搜索及引用；
5. 创建目标、调整任务、生成测验等新工具；
6. 通用意图路由和更多专业 Agent；
7. API 无法完成时，受控启用浏览器或键鼠兜底。
