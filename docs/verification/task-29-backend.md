# Task 29 后端验证证据：真正的持续 SSE、断线恢复与模型增量（Python + Java）

- **执行 Agent**：DeepSeek Harness + DeepSeek V4 Flash（后端与 Agent 执行工程师）
- **测试等级**：`[UNIT_TEST]` + `[MOCK_INTEGRATION]`（MockMvc + 模拟 Python 上游 HTTP）；**不构成 `[REAL_E2E]`**（未启动真实 MySQL/FastAPI/浏览器，未做浏览器证据，未做真实模型调用）
- **执行时间**：2026-09-09 16:10:00 (Asia/Shanghai)（含 Codex 首轮整改与第二轮 activeTurn 契约整改）
- **Git 提交**：首轮交付 `ebc27fd feat: stream live assistant events end to end`；首轮整改追加提交 `245089a fix: harden assistant event stream after review`；第二轮整改追加提交 `fix: expose recoverable active turn contract`（均不改写已审查提交）。
- **关联分支**：`agent/deepseek-task-29-sse-backend`（基线 `origin/main` = `da4adfd`）
- **交付状态**：**待 Codex 第三轮验收**。本次只交付 `ai-service/**` 与 `backend/**`；**未改动任何 `web/**` 文件**，也未修改共享总计划与交接文档；Vue 消费与浏览器 E2E 按冻结分工属 ZCode。

---

## 1. 运行环境与前置状态

- **操作系统**：macOS darwin arm64
- **Java 版本**：`openjdk version 26.0.1`（Maven 编译目标 `release 17`，Spring Boot 4.0.7）
- **Python 环境**：Python 3.12.13（FastAPI 0.140.0 / Pydantic 2.13.4）
- **数据库**：Java 测试使用 H2 内存库（`jdbc:h2:mem:studypilot`）；Python 事件持久化使用既有加密 SQLite。**本轮未使用真实 MySQL 3306**
- **服务端口状态**：未启动真实 Spring Boot 8080 / FastAPI 8000；Java 测试通过 `MockMvc` + 本地 `HttpServer` 模拟 Python 上游
- **使用模型**：**本轮零真实模型调用**（增量路径用假模型验证，未消耗 DeepSeek Token）
- **安全边界**：未打印、未提交任何 API Key、数据库口令或提示词正文；未修改 `.env` 与本地私有配置

---

## 2. TDD 闭环证据

### 2.1 失败测试证据 (RED Phase)

第一步：事件总线模块尚不存在。

```bash
cd ai-service
PYTHONPATH=$PWD .venv/bin/python -m pytest -q tests/unified_agent/test_event_stream.py
```

```text
tests/unified_agent/test_event_stream.py:5: in <module>
    from app.unified_agent.event_stream import (
E   ModuleNotFoundError: No module named 'app.unified_agent.event_stream'
!!!!!!!!!!!!!!!!!!! Interrupted: 1 error during collection !!!!!!!!!!!!!!!!!!!!
```

第二步：Supervisor 无 `stream_events` / `event_queue_size`，内部 SSE 端点尚不存在。

```bash
PYTHONPATH=$PWD .venv/bin/python -m pytest -q tests/unified_agent/test_supervisor_stream.py tests/api/test_unified_assistant_stream.py
```

```text
FAILED tests/unified_agent/test_supervisor_stream.py::test_turn_emits_continuous_events_in_contract_order
FAILED tests/unified_agent/test_supervisor_stream.py::test_reconnect_replays_only_missing_events_without_repeating_work
FAILED tests/unified_agent/test_supervisor_stream.py::test_pending_write_turn_emits_action_preview_and_stops
FAILED tests/unified_agent/test_supervisor_stream.py::test_cancel_is_observable_on_the_stream
FAILED tests/unified_agent/test_supervisor_stream.py::test_slow_consumer_is_disconnected_but_history_is_complete
FAILED tests/api/test_unified_assistant_stream.py::test_stream_endpoint_emits_persisted_events_and_heartbeats
FAILED tests/api/test_unified_assistant_stream.py::test_stream_endpoint_honours_last_event_id_header
FAILED tests/api/test_unified_assistant_stream.py::test_stream_endpoint_uses_query_cursor_when_header_is_absent
9 failed, 1 passed in 1.14s
```

第三步（模型增量，计划 Step 6）：`astream` / `stream_message` 尚不存在。

```bash
PYTHONPATH=$PWD .venv/bin/python -m pytest -q tests/knowledge/test_answering.py tests/knowledge/test_service.py tests/unified_agent/test_supervisor_stream.py
```

```text
FAILED tests/knowledge/test_answering.py::test_astream_yields_model_deltas_in_order_with_grounding_prompt
FAILED tests/knowledge/test_answering.py::test_astream_skips_empty_and_joins_list_content_chunks
FAILED tests/knowledge/test_service.py::test_stream_message_forwards_model_deltas_and_commits_same_answer
FAILED tests/knowledge/test_service.py::test_stream_message_falls_back_to_non_streaming_answerer
FAILED tests/unified_agent/test_supervisor_stream.py::test_knowledge_turn_streams_model_deltas_once_with_shared_turn_id
E       AssertionError: 存在 stream_message 时不得回落到非流式调用
5 failed, 22 passed in 1.26s
```

第四步：Java 门面仍是"一次性拉取数组后返回有限字符串"，无法满足持续流断言（旧断言为 `id: 2` 有限重放）。改造后旧断言失效，证明原实现不满足"连接期间新增事件"。

### 2.2 成功测试证据 (GREEN Phase)

```bash
cd ai-service
PYTHONPATH=$PWD .venv/bin/python -m pytest -q
PYTHONPATH=$PWD .venv/bin/ruff check app tests ../scripts/agent-planner-smoke.py

cd ../backend
./mvnw -o test

cd ..
node scripts/verify-agent-capability-matrix.mjs
git diff --check
```

```text
Python pytest: 401 passed, 1 个上游库弃用警告
Python Ruff: All checks passed!
Java Maven: 379 passed（含 Task 29 新增 14 项）
能力矩阵门禁: 31 页面 / 64 工具覆盖通过
git diff --check: clean
```

新增/改写的关键断言：

| 场景 | 断言 |
| --- | --- |
| 事件顺序 | 首事件 `TURN_STARTED`，末事件 `TURN_COMPLETED`；`TOOL_STARTED` 先于 `TOOL_SUCCEEDED`；`UI_ACTION` 先于终态 |
| 先持久化再发布 | 每收到一个在线事件，立刻回查 `list_events`，断言该序号已落库 |
| 序号连续性 | 单会话序号严格 `2..N` 连续，无空洞、无重复 |
| 断线续传 | 从游标 `1` 重放得到 `2..N`；再从 `N` 重连得到空集，且工具/模型调用次数不增加 |
| 待确认写操作 | `ACTION_PREVIEW` 携带 `actionId/riskLevel`，轮次以 `TURN_COMPLETED` 收尾且不自动执行 |
| 取消 | 轮次进行中取消后，流以 `TURN_CANCELLED` 收尾（先 `CANCEL_REQUESTED`、后 `TURN_ABORTED`） |
| 慢消费者 | 队列上限 1 且不消费时订阅被断开（`overflowed`），但持久历史完整，重连可全量补齐 |
| 内部 SSE 端点 | 需内部令牌；`text/event-stream`；`Last-Event-ID` 优先于 `afterSequence`；`: heartbeat` 注释帧；未知会话 404 |
| 模型增量（新增） | `DeepSeekKnowledgeAnswerer.astream` 按模型分片顺序产出；空分片跳过、空白分片保留、列表型 content 正确拼接 |
| 增量与提交一致（新增） | `stream_message` 回调收到的分片顺序等于模型顺序；落库 `answer` 等于分片拼接结果；回答器无 `astream` 时**不伪造**增量 |
| 增量不重复（新增） | Supervisor 知识分支实时推送真实分片后，收尾阶段不再切一次；`ASSISTANT_DELTA.payload.turnId` 与 `TURN_COMPLETED.payload.turnId` 相同 |
| Java 帧解析 | `id/event/data` 解析、注释帧识别、缺字段拒绝、未知字段忽略 |
| Java 过滤 | 事件类型白名单、序号严格递增、会话归属一致、`event` 与 `payload.type` 一致、非法 JSON 拒绝、`ownerId` 剥离 |
| Java 端到端 | MockMvc 异步分发得到 `id:2/3/4` 且 `ACTION_PREVIEW / UI_ACTION / TURN_COMPLETED` 顺序正确；上游路径为 `/events/stream?afterSequence=1&ownerId=<真实用户>`，内部令牌正确 |
| 快照续传字段（第 2 轮） | `lastEventSequence` 在事件发布时已推进；`activeTurnId` 在轮次进行中可见、终态后为 `null`；进程重启后不残留 `activeTurnId` |
| CORS 预检（第 2 轮） | `OPTIONS /api/assistant/conversations/{id}/events` 携带 `Access-Control-Request-Headers: authorization,last-event-id` 时，`Access-Control-Allow-Headers` 含 `Last-Event-ID` |
| 上游健壮性（第 2 轮） | 非 2xx 与正常 EOF 都必须关闭上游响应体；线程池关闭/拒绝新任务时 `open()` 不抛异常 |

### 2.3 本轮修复的真实缺陷

1. **Java 门面原本"假流式"**：`GET /api/assistant/conversations/{id}/events` 一次性读取 Python 数组后返回有限字符串，连接立即结束，无法持续推送。
2. **ASYNC 分发被安全链拒绝**：无状态 Bearer 会话在 ASYNC 分发阶段没有 `SecurityContext`，`AuthorizationFilter` 会对 SSE 二次鉴权并返回 403，事件流在第一次分发后即中断。已在 `SecurityConfig` 放行 `DispatcherType.ASYNC`（初始 REQUEST 仍强制鉴权）。
3. **取消后流无终态**：取消发生在工具执行中时，原实现只发出一次 `TURN_CANCELLED`，随后在途工具仍会补发 `TOOL_SUCCEEDED`，导致流以工具事件收尾、客户端无法判断轮次已结束。现补发终态 `TURN_CANCELLED(reason=TURN_ABORTED)`。
4. **慢消费者会拖垮业务**：改为有界队列 + 溢出断开重连，业务轮次永不因浏览器卡顿而阻塞。
5. **知识问答无法边生成边显示**：原来只有 `ainvoke`，必须等完整回答才返回。新增 `astream` 与 `stream_message`，真实模型分片直接进入 `ASSISTANT_DELTA`；同时保留"最终落库答案 = 分片拼接"的一致性约束。

### 2.4 Codex 首轮验收反馈与后端整改（2026-09-09）

Codex 首轮验收结论为**暂不通过**，检查对象 `agent/deepseek-task-29-sse-backend@ebc27fd`，并留下 `docs/verification/task-29-codex-review.md`（7 项阻断项）。按分工，DeepSeek Harness 只负责其中 3 项：**CORS 放行、Java 连接资源生命周期、Python 一致快照恢复契约**，且**仅修改后端与后端验证报告**。

| 评审项 | 问题 | 后端整改 | 回归断言 |
| --- | --- | --- | --- |
| 1 | `SecurityConfig.allowedHeaders` 缺少 `Last-Event-ID`，5173→8080 重连预检被拒 | `allowedHeaders` 加入 `Last-Event-ID` | `StudyPilotApplicationTests#allowsLastEventIdHeaderOnSsePreflight` 断言 OPTIONS 预检返回该头 |
| 2（后端部分） | 刷新重放旧动作：前端缺少一致快照游标与进行中轮次状态 | `AssistantConversationSnapshot` 新增 `lastEventSequence`、`activeTurnId`；`_emit` 每次发布都同步；重启后强制清空 `activeTurnId` | `test_snapshot_exposes_resume_cursor_and_active_turn_id`、`test_active_turn_id_is_visible_while_the_turn_is_running`、`test_restored_conversation_never_reports_a_stale_active_turn`；Java 门面断言两字段透传 |
| 7（后端部分） | 非 2xx 响应体未关闭；无界 cached workers 缺少销毁 | 只接受 2xx；任何路径都关闭上游响应体；有界线程池（16 线程 + 64 队列）+ `@PreDestroy`；并发满时以错误结束连接而不抛异常 | `nonSuccessUpstreamStatusClosesBodyInsteadOfLeaking`、`successfulUpstreamStreamClosesBodyAfterEof`、`rejectedConnectionDoesNotThrowAndEndsTheStream`、`shutdownWorkersIsIdempotentAndStopsAcceptingNewStreams` |
| 7（协议部分） | 前端解析失败仍可能推进游标 | 后端帧格式不变；`UI_ACTION` 等白名单事件补端到端透传断言 | `AssistantFacadeContractTest` 断言 `ACTION_PREVIEW / UI_ACTION / TURN_COMPLETED` 顺序与 `id` 单调 |

**提交与 RED 口径**：按 Codex 要求，本轮为**追加提交**（不改写已审查提交 `ebc27fd`，不修改共享总计划与交接文档）。这 3 项属于评审后修正，回归断言与修正同轮落地，因此本轮**没有单独保存"修正前失败"的 RED 输出**，也不把它写成 RED/GREEN 闭环；前 3 步（事件总线、SSE 端点、模型增量）的 RED→GREEN 证据见 2.1～2.2。

**不在本次范围（属 ZCode）**：评审项 2/3/4/5/6 的前端部分——轮次状态、消息恢复、导航去重、连接状态与认证回调、SSE 解析校验。

### 2.5 Codex 第二轮整改：可恢复的 activeTurn 契约（第 3 轮，TDD）

Codex 第二轮复核 `245089a` 仍**不通过**，复现了"中断丢前缀"：在 `on_delta("PREFIX_ALREADY_STREAMED")` 之后暂停，`get_conversation` 返回 `cursor=6, activeTurnId=turn-probe`，但快照里没有已推送文本，按 cursor 重放也拿不到前缀。Codex 因此冻结了**增量式契约**：

```json
"activeTurn": {
  "turnId": "turn-probe",
  "userMessage": "解释一下学习顺序",
  "assistantText": "PREFIX_ALREADY_STREAMED",
  "lastDeltaIndex": 0
}
```

语义要求（已实现并有断言）：

1. `activeTurn` 为 `null` 或上述对象；`activeTurnId` 保留（等价于 `activeTurn.turnId`）。
2. `TURN_STARTED` 即写入 `activeTurn`（`assistantText=""`、`lastDeltaIndex=-1`），此后每条 `ASSISTANT_DELTA` 追加文本并更新下标。
3. `lastEventSequence` **精确描述同一状态**：快照里的 `assistantText` 包含该序号及之前的全部增量，因此"前缀取快照 + 后缀从该序号续传"不会重复也不会丢失。
4. 状态在**发布之前**落库（`_emit`：追加事件 → 推进 `activeTurn` → 同步游标 → `_save` → `publish`）。
5. 所有读取/返回都是**深拷贝**，调用方改写不污染服务端。
6. 终态（`TURN_COMPLETED / TURN_FAILED / TURN_CANCELLED(TURN_ABORTED)`）之前清空 `activeTurn`。
7. 进程重启时若持久化快照仍有进行中轮次，**补写 `TURN_FAILED`**（`payload.reason="SERVICE_RESTARTED"`、`payload.turnId`、`payload.partialAssistantText`），而不是只清空 ID。

#### RED 证据（先写测试、先跑、后改实现）

命令：

```bash
cd ai-service
PYTHONPATH=$PWD .venv/bin/python -m pytest -q tests/unified_agent/test_supervisor_stream.py
```

```text
FAILED tests/unified_agent/test_supervisor_stream.py::test_active_turn_id_is_visible_while_the_turn_is_running
FAILED tests/unified_agent/test_supervisor_stream.py::test_refresh_between_deltas_sees_prefix_and_resumes_suffix
FAILED tests/unified_agent/test_supervisor_stream.py::test_restart_mid_stream_persists_turn_failed_for_interrupted_turn
FAILED tests/unified_agent/test_supervisor_stream.py::test_get_conversation_returns_isolated_deep_copy
4 failed, 8 passed in 1.17s
```

失败原因（节选）：

```text
app/unified_agent/supervisor.py:1042 in ... AttributeError        # 快照没有 active_turn
assert 'tampered' != 'tampered'                                    # 读取返回的是内部对象
```

#### GREEN 证据（实现后）

```text
tests/unified_agent/test_supervisor_stream.py: 12 passed
Python pytest 全量: 401 passed, 1 warning
Python Ruff: All checks passed!
```

关键回归断言：

| 场景 | 断言 |
| --- | --- |
| 两段增量之间刷新 | 快照 `activeTurn.assistantText == "PREFIX_ALREADY_STREAMED"`、`lastDeltaIndex == 0`、`activeTurnId` 一致；从 `lastEventSequence` 续传只收到后缀 `["_SUFFIX_FROM_RESUME"]` |
| 答案恰好一次 | `前缀 + 后缀 == TURN_COMPLETED.payload.reply == 返回快照 reply`，且前后缀各只出现一次 |
| `TURN_STARTED` 即有状态 | `activeTurn.userMessage` 是用户原话，`assistantText == ""`，`lastDeltaIndex == -1` |
| 终态清理 | 轮次结束后 `activeTurnId`/`activeTurn` 均为 `None` |
| 重启恢复 | 新实例读取时补写 `TURN_FAILED`，`payload.turnId == "turn-restart"`、`reason == "SERVICE_RESTARTED"`，快照游标等于该事件序号，`activeTurn` 已清空 |
| 深拷贝隔离 | 改写返回快照的 `reply`/`messages` 不影响再次读取 |
| Java 透传 | 门面测试断言 `activeTurn.turnId/userMessage/assistantText/lastDeltaIndex` 原样返回 |

**本轮严格遵守"先 RED 后实现"**：上述 4 个测试在实现前已运行并失败，输出见上；实现后同文件 12 项全绿。

---

## 3. 业务数据真实性回查 (Data Re-check)

- **本轮无业务写操作**：写工具仍只产生 `WAITING_CONFIRMATION` 动作卡，需 Java 专用确认接口才会执行；本轮未调用任何真实业务工具。
- **事件持久化回查**：`test_supervisor_stream.py` 在每个在线事件到达时立即回查 `list_events`，断言"先落库、再推送"；既有 `test_durable_conversations.py` 继续验证服务重建后事件按序号完整重放。
- **知识会话提交回查**：`test_stream_message_forwards_model_deltas_and_commits_same_answer` 在流式结束后重新读取知识会话，断言落库答案与增量拼接完全一致。
- 真实 MySQL 落库、真实模型调用与浏览器回查属于 Task 34（`[REAL_E2E]`），本次不声明通过。

---

## 4. 模型用量与成本统计

- **实际 model id**：不适用（本轮零真实模型调用；增量路径由假模型驱动）
- **Prompt / Completion Tokens**：不适用
- **延迟**：不适用
- **估算成本**：不适用（未调用模型，不写零成本冒充已计量）

---

## 5. 变更文件清单

**Python**

- 新增：`ai-service/app/unified_agent/event_stream.py`（有界队列事件总线、SSE 帧编码、心跳常量、确定性增量分片）
- 修改：`ai-service/app/unified_agent/models.py`（`AssistantConversationSnapshot` 新增 `lastEventSequence` / `activeTurnId` / `activeTurn`；新增 `AssistantActiveTurn` 契约模型）
- 修改：`ai-service/app/unified_agent/supervisor.py`（`_emit` 先持久化后发布、`stream_events` 回放+在线+去重、`_emit_turn_tail`、取消终态、知识分支真实模型增量 + `reply_streamed`、`_sync_stream_state` 同步续传字段、`_advance_active_turn` 维护前缀、终态清理、重启补写 `TURN_FAILED`、读取返回深拷贝）
- 修改：`ai-service/app/unified_agent/tool_gateway.py`（`on_tool_event` 回调 → `TOOL_STARTED/TOOL_SUCCEEDED/TOOL_FAILED`）
- 修改：`ai-service/app/knowledge/answering.py`（抽出 `_messages`；新增 `astream` 与增量文本提取）
- 修改：`ai-service/app/knowledge/service.py`（`send_message` → `_run_message`；新增 `stream_message` 与 `_answer` 回落逻辑）
- 修改：`ai-service/app/api/unified_assistant.py`（新增 `GET /internal/assistant/conversations/{id}/events/stream`，`Last-Event-ID` 优先）
- 修改：`ai-service/app/core/settings.py`（`agent_event_stream_queue_size`、`agent_event_stream_heartbeat_seconds`）
- 修改：`ai-service/app/main.py`（按配置装配事件总线参数）
- 新增：`ai-service/tests/unified_agent/test_event_stream.py`（6 项）
- 新增：`ai-service/tests/unified_agent/test_supervisor_stream.py`（12 项）
- 新增：`ai-service/tests/api/test_unified_assistant_stream.py`（5 项）
- 修改：`ai-service/tests/knowledge/test_answering.py`（+2 项）
- 修改：`ai-service/tests/knowledge/test_service.py`（+2 项）

**Java**

- 修改：`backend/src/main/java/com/moxiao/studypilot/agent/api/UnifiedAssistantFacadeController.java`（`SseEmitter` 流式代理，替换有限字符串响应）
- 新增：`backend/src/main/java/com/moxiao/studypilot/agent/application/AssistantEventStreamService.java`（上游 SSE 解析、白名单过滤、`ownerId` 剥离、断开只取消订阅；仅接受 2xx、必关响应体、有界线程池 + `@PreDestroy`）
- 修改：`backend/src/main/java/com/moxiao/studypilot/agent/application/AgentGatewayService.java`（`openEventStream` 无总超时流式上游请求）
- 修改：`backend/src/main/java/com/moxiao/studypilot/auth/config/SecurityConfig.java`（放行 `DispatcherType.ASYNC`；CORS `allowedHeaders` 增加 `Last-Event-ID`）
- 修改：`backend/src/test/java/com/moxiao/studypilot/agent/api/AssistantFacadeContractTest.java`（SSE 异步分发、续传、`UI_ACTION` 帧与 `lastEventSequence`/`activeTurnId` 透传断言）
- 修改：`backend/src/test/java/com/moxiao/studypilot/agent/api/AgentFacadeControllerTest.java`（流式端点断言与上游路径）
- 修改：`backend/src/test/java/com/moxiao/studypilot/StudyPilotApplicationTests.java`（`Last-Event-ID` CORS 预检）
- 新增：`backend/src/test/java/com/moxiao/studypilot/agent/application/AssistantEventStreamServiceTest.java`（13 项）

**文档**

- 新增：`docs/verification/task-29-backend.md`；更新 `docs/协同开发交接说明.md`、`项目开发步骤.md`

未修改计划文件 `docs/superpowers/plans/2026-09-08-agent-productization-and-multi-agent-collaboration.md`（归 Codex 维护）；**未修改任何 `web/**` 文件**。

---

## 6. 未覆盖项与已知限制 (Uncovered Items & Limitations)

1. **Vue 消费未实现（属 ZCode）**：`web/src/services/current/assistant.ts` 与 `AssistantView.vue` 仍是 HTTP 请求/响应模式，尚未用带 `Authorization` 的 `fetch` + `ReadableStream` 消费事件流、保存最后 sequence 并在重连时发送 `Last-Event-ID`。
2. **增量覆盖范围**：知识问答链路（`解释/查找/搜索/什么是/怎么学`）现在是**真实 DeepSeek 模型增量**；其余轮次（导航、工具执行、计划步骤）的回复本来就是确定性文案，仍由 `chunk_reply_deltas` 分片推送，不产生额外模型调用。
3. **无 `[REAL_E2E]`**：未启动真实 FastAPI/MySQL/浏览器，未做 30 秒心跳的墙钟观测、网络中断、刷新、重复连接与 Python 重启的真实串联验证；这些属于 Task 34。
4. **单进程总线**：事件总线是进程内内存结构，Python 多 worker（`agent_worker_count > 1`）时只有持有会话的进程能推送在线事件；跨进程需要 Redis 之类的分发层，本轮未做（默认单 worker）。
5. **每事件一次全量持久化**：`_emit` 每个事件都重写整段会话负载（含历史事件），事件多时写放大明显；正确性优先，性能优化与用量预算一起归 Task 31/34。
6. **模型增量中途失败的可见性**：若模型在流式过程中报错，已推送的 `ASSISTANT_DELTA` 不会回滚，该轮以 `TURN_FAILED` 收尾且知识会话不提交；快照会先清空 `activeTurn`，失败事件带 `partialAssistantText` 便于诊断，客户端需要丢弃未完成的增量（已写入第 7 节契约）。
7. **Java 侧"客户端断开只取消订阅"未做自动化断言**：代码结构保证 `onCompletion/onTimeout/onError` 只关闭上游输入流，不触发任何业务取消接口；但 MockMvc 无法真实模拟浏览器断开，故仅由代码审查与 Python 侧慢消费者测试间接覆盖。
8. **无服务端主动超时**：`SseEmitter` 使用 `0L`（不超时），依赖 Python 每 30 秒心跳保活与客户端断开；若 Python 侧心跳配置被关闭，连接可能被中间设备静默回收。
9. **Java 上游连接无总超时**：`openEventStream` 不设 `HttpRequest.timeout`，长时间挂起的上游只由客户端断开或上游关闭结束。
10. **并发上限**：事件流工作线程池固定 16 线程 + 64 队列，超出时该连接立即以错误结束（客户端按 `Last-Event-ID` 重连），不是无限排队；如需更高并发应改为异步非阻塞读取或提高上限。

---

## 7. 下一步交接建议

1. **ZCode（前端）必须按下面这份冻结契约实现刷新/重连**（后端已完成，无需再协商）：
   - 消费 `GET /api/assistant/conversations/{id}/events`（`text/event-stream`），用带 `Authorization: Bearer` 的 `fetch` + `ReadableStream`（原生 `EventSource` 无法携带 Bearer）。
   - 刷新恢复算法：读取会话快照 → 若 `activeTurn != null`，用 `activeTurn.userMessage` 渲染用户消息、用 `activeTurn.assistantText` 渲染**已显示的前缀** → 以 `lastEventSequence` 作为 `Last-Event-ID` 打开事件流 → 只把之后的 `ASSISTANT_DELTA.delta` 追加到同一 `turnId` 的消息里。**前缀 + 后缀 = 最终答案，恰好一次**，不要从 0 重放，也不要重新 `POST /messages`。
   - `activeTurn` 形状固定为 `{turnId, userMessage, assistantText, lastDeltaIndex}`；`lastDeltaIndex` 在尚无增量时为 `-1`。`activeTurnId` 保留，等价于 `activeTurn.turnId`。
   - 终态：`TURN_COMPLETED` 用 `payload.reply` 覆盖该 `turnId` 的消息；`TURN_FAILED`/`TURN_CANCELLED` 丢弃该 `turnId` 下未完成的增量并提示未完成。收到终态后 `activeTurn` 必为 `null`。
   - 进程重启恢复：服务端会为该轮补发 `TURN_FAILED`（`payload.reason="SERVICE_RESTARTED"`、`payload.partialAssistantText`），前端按普通失败处理即可，不必自己猜测。
   - 忽略 `:` 开头的注释帧（心跳）；`id:` 单调递增，可直接作为游标。
2. **冻结的线上帧格式**（Python 与 Java 一致）：
   ```text
   id: 7
   event: TOOL_SUCCEEDED
   data: {"sequence":7,"type":"TOOL_SUCCEEDED","conversationId":"<uuid>","payload":{"toolName":"...","status":"success"}}

   : heartbeat
   ```
   事件类型白名单：`HEARTBEAT / TURN_STARTED / CONTEXT_LOADED / PLAN_GENERATED / TOOL_STARTED / TOOL_SUCCEEDED / TOOL_FAILED / ACTION_PREVIEW / ASSISTANT_DELTA / UI_ACTION / TURN_COMPLETED / TURN_FAILED / TURN_CANCELLED`。`ASSISTANT_DELTA.payload = {turnId, index, delta}`，与最终 `TURN_COMPLETED.payload.reply` 共享同一 `turnId`；`index` 从 0 严格递增。
3. **Codex 验收顺序建议**：契约一致性（`activeTurn` 形状与 `lastEventSequence` 语义）→ 安全边界（ASYNC 放行、`ownerId` 剥离、白名单过滤）→ diff 审查 → Python 局部/全量测试（含 RED 复现）→ Java 局部/全量测试 → 能力矩阵门禁 → 文档口径。
4. 合并前请重点确认五处**有意变更**：`SecurityConfig` 新增 `DispatcherType.ASYNC` 与 CORS `Last-Event-ID`；浏览器事件端点由有限响应改为长连接；取消轮次补发终态 `TURN_CANCELLED`；知识问答由 `ainvoke` 改为 `astream`；快照新增 `lastEventSequence` / `activeTurnId` / `activeTurn` 并在重启时补写 `TURN_FAILED`。
5. 若要把真实增量扩展到其他模型生成场景（例如教学讲解），复用同一 `stream_message`/`on_delta` 模式即可，无需改动事件契约。
6. **评审项归属复核**：Codex 首轮 7 项阻断中，1、2（后端契约）、7（后端资源）已整改；第二轮新增的 `activeTurn` 可恢复契约（后端侧）已在本轮完成。2/3/4/5/6 的前端部分（轮次状态与消息恢复、按事件/轮次去重导航、连接状态与认证回调、SSE 解析校验）属 ZCode 的 `web/**` 范围，后端不再改动。
