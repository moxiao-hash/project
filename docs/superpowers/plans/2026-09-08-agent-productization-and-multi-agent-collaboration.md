# StudyPilot Agent 产品化与三 Agent 协作实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把现有“具备统一 Agent 骨架的学习平台”收口为主要业务都能通过自然语言可靠完成、全过程可观察、可确认、可恢复、可审计的 StudyPilot Agent。

**Architecture:** 浏览器继续只调用 Spring Boot；Java 负责身份、工具目录、业务事实、风险、预算和审计，FastAPI/LangGraph 负责模型规划和流式编排，Vue 执行白名单界面动作，Local Runner 处理容器化代码操作。模型只提出计划与工具参数，Java 始终拥有执行决定权。

**Tech Stack:** Java 17、Spring Boot、MySQL/Flyway、Python 3.12、FastAPI、LangGraph、DeepSeek V4 Flash、Vue 3/TypeScript、SSE、Qdrant/Tavily、Docker/Podman、JGit、Vitest/Playwright。

---

## 1. 当前基线与最终验收定义

基线提交为 `c645a8a`。当前已有统一会话、类型化工具、确认治理、可重放事件、Agent 首页、业务导航、自动化、Runner、Rubric、代码补丁和 Git 操作骨架；但以下事实仍成立：

- Supervisor 主要依赖关键词分支，不是通用的模型规划循环。
- 页面动作目前实际只执行 `NAVIGATE`；其他动作协议未形成产品闭环。
- 当前事件接口是有限事件集合，不是持续的 token/工具事件流。
- Task 20 的真实 Token、价格和预算限制未完成。
- Task 25 没有实际 Playwright/IDE 适配器；策略预览不能视为执行。
- Task 26 只完成了 Java→Python→MySQL 的确定性业务冒烟，未完成模型/RAG/前端/Runner/Rubric/Git 的一次串联。

最终“符合用户心目中的 Agent”必须同时满足：

1. 用户对 StudyPilot 的常见目标都有类型化工具或明确的不可代办原因。
2. 查询、导航可自动执行；写操作按风险预览和确认；测验答案、打卡总结与成果最终接受仍由用户完成。
3. 复杂目标能够形成 2～8 步公开计划，逐步调用工具，而不是只能命中固定关键词。
4. 用户能实时看到回答增量、工具开始/完成、待确认动作和最终结果；刷新后从事件序号恢复。
5. Agent 返回“已完成”时，Java 业务数据、执行记录和审计必须能共同证明结果。
6. Developer Agent 只能在登记工作区内读取、补丁、测试、提交和推送；每个高风险阶段独立确认。
7. 每次发布都有真实环境证据，并明确区分单元测试、模拟集成测试和真实端到端测试。

## 2. 三 Agent 组织与质量责任

### Codex：总架构师、计划与验收负责人

- 拥有主计划、系统边界、公共契约、数据库演进和风险策略的最终解释权。
- 在每个 Task 开始前给出验收清单；实现后做规格审查、安全审查和新鲜验证。
- 只在架构阻塞、安全缺陷或跨模块集成问题上直接编码；日常功能优先交给执行 Agent。
- 只有 Codex 可以把 Task 从“待验收”改成“已完成”，并负责合并到 `main`。
- 不以其他 Agent 的完成陈述替代源码检查、测试输出和真实数据回查。

### DeepSeek Harness + DeepSeek V4 Flash：后端与 Agent 执行工程师

- 主责 Python/FastAPI/LangGraph、Java Tool Gateway、治理、用量统计、Runner/Git 后端和自动化测试。
- 适合边界明确、可以通过测试证明的高吞吐实现：DTO、Handler、状态机、重试、幂等、租约、集成脚本和缺陷修复。
- 每次只领取一个 Task 或一个互不交叉的子任务，严格先提交失败测试证据。
- 不独立决定权限放宽、风险降级、数据迁移删除、密钥处理和“全栈已完成”结论。
- 官方资料表明 V4 Flash 支持 1M 上下文、结构化 JSON、工具调用和 Responses API，适合本项目的文本代码与工具编排；运行证据仍必须记录实际 model id，而不是只看 Harness 显示名。

### ZCode + Gemini 3.8 Flash：前端、长上下文审查与体验工程师

- 主责 Vue Agent 首页、真实流式 UI、动作执行器、Playwright E2E、响应式/可访问性和视觉验收。
- 承担跨目录影响分析、接口对照、文档一致性审查和真实浏览器回归。
- 可实现边界清晰的前端/测试子任务；涉及 Java 安全策略或 Python 权限逻辑时只提交审查意见或独立分支改动，交 Codex 验收。
- 官方资料表明 Gemini 3.8 Flash 具有 1M 上下文、结构化输出、函数调用、代码执行及预览状态的 computer use，适合长链路和浏览器验收；预览能力仍不能代替 StudyPilot 自己的白名单与确认体系。
- 运行证据必须记录实际 `gemini-3.8-flash`，ZCode 的界面标签不能单独证明模型映射。

官方能力参考：

- [DeepSeek Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/)
- [DeepSeek Responses API](https://api-docs.deepseek.com/guides/responses_api/)
- [Gemini 3.8 Flash](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash)
- [Gemini Interactions API](https://ai.google.dev/gemini-api/docs/interactions-overview)

## 3. 协作与 Git 规则

1. `main` 只由 Codex 集成；执行 Agent 不直接在 `main` 上编码。
2. 每个任务创建独立 worktree 和分支：
   - DeepSeek Harness：`agent/deepseek-task-<编号>-<短名>`。
   - ZCode：`agent/zcode-task-<编号>-<短名>`。
3. 两个执行 Agent 不得同时修改同一文件。跨模块契约先由 Codex冻结 DTO/Schema，再并行开发。
4. 每个实现提交必须附：RED 测试命令与失败原因、GREEN 命令与结果、改动文件、未覆盖项、提交号。
5. 交接统一更新 `docs/协同开发交接说明.md`；详细证据写入 `docs/verification/task-<编号>.md`，不得把长日志直接塞入交接首页。
6. Codex 验收顺序固定为：规格一致性 → 安全边界 → diff 审查 → 局部测试 → 全量测试 → 真实联调 → 文档口径 → 合并。
7. Mock、H2、Stub、容器策略测试必须在标题中标记；只有实际服务与数据回查才能标记 `REAL_E2E`。
8. 合并冲突由 Codex判断语义并处理；执行 Agent不得使用 `reset --hard`、强推或覆盖另一个 Agent 的工作区。
9. API Key、数据库密码、内部令牌、OAuth/Cookie、个人路径配置不进入 Git、提示词、日志或交接文档。

## 4. 交付波次

```text
Wave 0：Task 27（冻结契约和协作基线）
   ↓
Wave 1：Task 28（模型规划器） + Task 29（真实流式）
   ↓
Wave 2：Task 30（业务工具覆盖） + Task 31（用量与预算）
   ↓
Wave 3：Task 32（Developer Agent 加固） + Task 33（受控界面适配）
   ↓
Wave 4：Task 34（真实全栈验收与发布门禁）
```

Task 28 与 29 可在契约冻结后分支并行；Task 30 与 31 可并行；Task 32 与 33 必须使用不同文件边界。Task 34 只能在前置任务均通过 Codex 验收后开始。

---

## Task 27：能力矩阵、契约冻结与协作门禁

**Owner:** Codex 设计与验收；ZCode 做页面盘点；DeepSeek Harness 做工具盘点。

**Files:**

- Create: `docs/agent-capability-matrix-v2.md`
- Create: `docs/verification/README.md`
- Create: `scripts/verify-agent-capability-matrix.mjs`
- Create: `docs/agent-native-contract.md`
- Modify: `docs/协同开发交接说明.md`
- Test: `web/src/modules/assistant/uiActionDispatcher.spec.ts`
- Test: `backend/src/test/java/com/moxiao/studypilot/agent/tool/AgentToolCoverageTest.java`

- [x] **Step 1: 写能力矩阵失败校验。** 脚本必须读取 Vue route name、Java tool catalog 测试清单和矩阵中的 `routeKey/readTool/writeTool/risk/authenticity`；缺任何已注册页面或工具时以退出码 1 失败。
- [x] **Step 2: 运行 `node scripts/verify-agent-capability-matrix.mjs`。** 预期因矩阵文件尚不存在或覆盖不全而失败。
- [x] **Step 3: 建立完整矩阵。** 每个页面逐项标记 `AUTO_READ / AUTO_NAVIGATE / PREVIEW_WRITE / USER_ONLY / UNSUPPORTED`；所有 `USER_ONLY` 写明原因，例如答题和打卡总结不能代办。
- [x] **Step 4: 冻结 v2 契约。** 固定 `AssistantPlan/AssistantPlanStep/AssistantEvent/UiAction/PendingToolAction/Usage` 字段和枚举；禁止模型产生 URL、CSS selector、SQL、shell 或 ownerId。
- [x] **Step 5: 建立验证证据模板。** 模板必须包含环境、真实/模拟标签、RED/GREEN、数据回查、模型 id、Token/成本、未覆盖项和提交号。
- [x] **Step 6: 运行矩阵脚本、Java coverage test、前端 dispatcher test 与 `git diff --check`。** 预期全部通过。
- [x] **Step 7: 提交。** `docs: define agent productization and collaboration gates`

**完成标准:** 任意开发者可从一个矩阵判断用户意图对应哪个 Java 工具、哪个页面动作、风险和是否允许 Agent 代办。

**Codex 验收（2026-09-09）：已通过。** 初始提交 `21f54b0` 存在生产工具漏盘、导航白名单漂移和 Schema 与运行时不一致；修正提交 `b1c4c5a` 已将门禁切换为生产源码事实源，统一为 31 个页面、64 个工具，并完成 Java 365 项、前端 124 项全量回归。证据见 `docs/verification/task-27.md`。

---

## Task 28：模型驱动的多步 Planner 与确定性策略验证

**Owner:** DeepSeek Harness 实现；Codex 审查规划与权限；ZCode 用长对话做反例审查。

**Files:**

- Create: `ai-service/app/unified_agent/planning_models.py`
- Create: `ai-service/app/unified_agent/planner.py`
- Create: `ai-service/app/unified_agent/policy_validator.py`
- Modify: `ai-service/app/unified_agent/supervisor.py`
- Modify: `ai-service/app/unified_agent/models.py`
- Modify: `ai-service/app/core/settings.py`
- Test: `ai-service/tests/unified_agent/test_planner.py`
- Test: `ai-service/tests/unified_agent/test_policy_validator.py`
- Test: `ai-service/tests/unified_agent/test_supervisor.py`

核心输出必须是结构化计划：

```json
{
  "intent": "TASK",
  "confidence": 0.92,
  "summary": "继续学习并打开测验",
  "steps": [
    {"stepId": "s1", "toolName": "learning.context.get", "arguments": {}, "dependsOn": []},
    {"stepId": "s2", "toolName": "assessment.node_quiz_status.get", "arguments": {"nodeId": "$s1.nextNodeId"}, "dependsOn": ["s1"]}
  ]
}
```

- [ ] **Step 1: 写失败测试。** 覆盖多意图、低置信度澄清、未知工具、模型伪造 ownerId、循环依赖、超过 8 步、两个写步骤、两个联网步骤、资料中的 Prompt Injection 和相同参数重复调用。
- [ ] **Step 2: 运行 `cd ai-service && .venv/bin/python -m pytest -q tests/unified_agent/test_planner.py tests/unified_agent/test_policy_validator.py`。** 预期因 Planner 不存在失败。
- [ ] **Step 3: 实现 Planner。** 向 DeepSeek 只发送裁剪后的动态上下文和 Java catalog；使用 Pydantic 验证结构化输出；模型不能直接执行工具。
- [ ] **Step 4: 实现确定性策略验证器。** 校验工具存在、参数 schema、依赖无环、预算、风险、owner 隔离和真实性限制；验证失败转 `CLARIFY`，不尝试“修复后直接执行”。
- [ ] **Step 5: 将现有关键词分支保留为降级层。** 模型不可用时只允许已证明安全的导航/查询和明确的单写操作，不声称完成复杂计划。
- [ ] **Step 6: 执行计划。** 每一步使用现有 `UnifiedToolGateway`；工具结果只能通过声明的输出字段传给后续步骤，禁止字符串模板注入任意路径。
- [ ] **Step 7: 增加取消和恢复测试。** 取消后不再调用下一工具；确认动作恢复后继续剩余步骤；服务重启可从持久状态恢复。
- [ ] **Step 8: 运行 Python 全量 pytest 和 Ruff。** 预期全部通过。
- [ ] **Step 9: 提交。** `feat: plan multi-step studypilot agent actions`

**完成标准:** “继续昨天的章节，学完后准备测验，并告诉我薄弱点”能够产生公开多步计划；Agent 仍不能代替用户学习、打卡或答题。

---

## Task 29：真正的持续 SSE 与断线恢复

**Owner:** DeepSeek Harness 负责 Python/Java 流；ZCode 负责 Vue 消费与体验；Codex 冻结事件顺序和鉴权。

**Files:**

- Create: `ai-service/app/unified_agent/event_stream.py`
- Modify: `ai-service/app/api/unified_assistant.py`
- Modify: `ai-service/app/unified_agent/supervisor.py`
- Modify: `backend/src/main/java/com/moxiao/studypilot/agent/api/UnifiedAssistantFacadeController.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/agent/application/AssistantEventStreamService.java`
- Modify: `web/src/services/current/assistant.ts`
- Modify: `web/src/modules/assistant/AssistantView.vue`
- Test: `ai-service/tests/api/test_unified_assistant.py`
- Test: `backend/src/test/java/com/moxiao/studypilot/agent/api/AssistantFacadeContractTest.java`
- Test: `web/src/modules/assistant/AssistantView.spec.ts`

- [ ] **Step 1: 写失败测试。** 验证连接先收到 heartbeat/已持久化事件，再持续收到 `ASSISTANT_DELTA/TOOL_STARTED/TOOL_SUCCEEDED/TURN_COMPLETED`；断线以 sequence 恢复且不重复副作用。
- [ ] **Step 2: 运行三端局部测试。** 预期当前有限数组响应不能满足“连接期间新增事件”的断言。
- [ ] **Step 3: Python 实现每会话异步事件总线。** 事件先持久化再发布；队列慢消费者有上限，断开只丢连接、不丢持久事件。
- [ ] **Step 4: Java 使用 `SseEmitter` 或 WebFlux 流式代理。** Bearer 身份转成内部 ownerId；客户端断开时取消订阅，不取消已经进入治理层的业务动作。
- [ ] **Step 5: Vue 使用带 Authorization 的 `fetch` ReadableStream。** 不使用无法稳定携带 Bearer 的原生 EventSource；保存最后 sequence，重连发送 `Last-Event-ID`。
- [ ] **Step 6: 接入 DeepSeek streaming delta。** 最终完整消息和增量共享同一 turnId；刷新后只重放事件，不重复模型调用。
- [ ] **Step 7: 验证 30 秒心跳、网络中断、刷新、重复连接、取消与 Python 重启。** 每种情况记录事件序号和业务执行数量。
- [ ] **Step 8: 运行三端全量测试、typecheck/build、Ruff。** 预期全部通过。
- [ ] **Step 9: 提交。** `feat: stream live assistant events end to end`

**完成标准:** 用户能看到正常 AI 对话式逐步输出；网络恢复后不会丢消息或重复写业务。

---

## Task 30：全部 StudyPilot 能力工具化与完整 UI Action

**Owner:** DeepSeek Harness 负责 Java 工具；ZCode 负责 Vue 动作和页面联调；Codex 审核真实性边界。

**Files:**

- Modify: `backend/src/main/java/com/moxiao/studypilot/agent/tool/AgentReadToolConfiguration.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/agent/tool/AgentWriteToolConfiguration.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/agent/tool/AgentToolBusinessExecutor.java`
- Modify: `ai-service/app/unified_agent/models.py`
- Modify: `ai-service/app/unified_agent/supervisor.py`
- Modify: `web/src/modules/assistant/uiActionDispatcher.ts`
- Modify: `web/src/modules/assistant/AssistantView.vue`
- Test: `backend/src/test/java/com/moxiao/studypilot/agent/tool/AgentToolCoverageTest.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/agent/api/GovernedAgentToolWorkflowTest.java`
- Test: `web/src/modules/assistant/uiActionDispatcher.spec.ts`

- [ ] **Step 1: 从 Task 27 矩阵生成失败覆盖测试。** 每个允许的页面操作必须有工具、Handler、风险、输出 schema 和 route/action；用户专属行为必须拒绝。
- [ ] **Step 2: 补齐业务工具。** 优先完成“继续节点、生成测验、重做错题、调整今日容量、查资料、标记通知、创建目标/计划、查看执行、登记工作区、发起成果评审”。
- [ ] **Step 3: 扩展 UI Action 为 `NAVIGATE / OPEN_MODAL / PREFILL_FORM / REFRESH_RESOURCE / FOCUS_ELEMENT`。** 参数只能来自固定 registry；`PREFILL_FORM` 只填草稿，不能自动提交。
- [ ] **Step 4: 实现动作回执。** Vue 将 `actionId/status/error/currentRoute` 回传 Java，再交 Python 决定重试、降级为人工链接或结束；模型不读取 DOM。
- [ ] **Step 5: 加入前端纵深防御。** 拒绝 URL、HTML、JavaScript、CSS selector、额外参数和未注册实体；ownerId 始终不来自动作。
- [ ] **Step 6: 建立真实性测试。** “替我答题”“替我写打卡总结”“直接接受成果”必须被拒绝并导航到用户操作页面。
- [ ] **Step 7: 运行矩阵校验、Java/Python/Vue 全量测试与构建。** 预期全部通过。
- [ ] **Step 8: 提交。** `feat: operate every studypilot workflow through agent actions`

**完成标准:** 传统菜单保留，但矩阵中所有可代办操作都能从统一 Agent 完成；不能代办的操作能直接把用户带到正确位置并说明原因。

---

## Task 31：真实 Token、价格、预算与可观测性（关闭 Task 20）

**Owner:** DeepSeek Harness 实现；Codex 审核计费口径和隐私；ZCode 完成健康页面。

**Files:**

- Create: `backend/src/main/resources/db/migration/V44__add_assistant_usage_budget.sql`
- Create: `backend/src/main/java/com/moxiao/studypilot/agent/usage/AssistantUsageService.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/agent/usage/ModelPricingCatalog.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/agent/api/AssistantHealthResponse.java`
- Modify: `ai-service/app/providers/model_factory.py`
- Modify: `ai-service/app/observability/model_metrics.py`
- Modify: `ai-service/app/unified_agent/supervisor.py`
- Modify: `web/src/modules/assistant/AssistantHealthView.vue`
- Test: `backend/src/test/java/com/moxiao/studypilot/agent/application/AssistantHealthServiceTest.java`
- Test: `ai-service/tests/unified_agent/test_supervisor.py`
- Test: `web/src/modules/assistant/AssistantHealthView.spec.ts`

- [ ] **Step 1: 写失败测试。** 覆盖缓存/非缓存输入、输出、reasoning token 缺失、未知模型价格、重复回调、跨用户、日预算耗尽、确认中的写操作以及模型调用失败。
- [ ] **Step 2: 保存原始 usage 与价格版本。** 不仅保存总 token；金额用 `DECIMAL`，不能用浮点数；未知价格显示“不可估算”，不能记为零成本。
- [ ] **Step 3: 在模型响应边界采集 usage。** 所有计划、问答、测验、代码评估和 Rubric 统一上报；幂等键绑定 turn/execution，重复事件不重复计费。
- [ ] **Step 4: 增加用户预算。** 支持每日模型调用次数、每日估算费用和单轮最大输出；达到预算后允许纯 Java 查询/导航，拒绝新的模型调用并给出可恢复提示。
- [ ] **Step 5: 健康页展示模型 id、调用量、Token、估算费用、P50/P95 延迟和失败率。** 不展示提示词、Key 或其他用户数据。
- [ ] **Step 6: 用官方价格配置加版本日期。** 价格变化只影响新记录，历史记录保留原估算。
- [ ] **Step 7: 运行 Flyway/MySQL 集成、三端全量测试和真实最小模型调用。** 记录实际 model id 与 usage，不记录正文和 Key。
- [ ] **Step 8: 提交。** `feat: enforce assistant usage and cost budgets`

**完成标准:** Task 20 全部勾选；用户和开发者都能回答“本轮调用了什么模型、用了多少 Token、估算花费多少、为什么被预算阻止”。

---

## Task 32：Developer Agent 安全加固与临时 Git 全链路

**Owner:** DeepSeek Harness 实现；Codex 安全审查；ZCode 做跨文件/错误恢复审查。

**Files:**

- Modify: `backend/src/main/java/com/moxiao/studypilot/agent/developer/WorkspaceDeveloperService.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/agent/developer/DeveloperOutputSanitizer.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/agent/runner/RunnerGovernanceService.java`
- Modify: `ai-service/app/unified_agent/supervisor.py`
- Test: `backend/src/test/java/com/moxiao/studypilot/agent/developer/WorkspaceDeveloperServiceTest.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/agent/developer/DeveloperPatchWorkflowTest.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/agent/developer/DeveloperGitWorkflowTest.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/agent/runner/RunnerGovernanceWorkflowTest.java`

- [ ] **Step 1: 写攻击性失败测试。** 覆盖符号链接、超大文件、二进制、嵌套项目 working directory、敏感内容、预先暂存文件、远端变化、branch/HEAD 变化、push 超时和补丁冲突。
- [ ] **Step 2: 绑定测试工作目录。** 推荐 Maven/npm/pytest 时同时返回经过规范化验证的相对 workingDirectory，Runner 不能总在工作区根目录执行。
- [ ] **Step 3: 加固读取和摘要。** 所有读取限长；文件树和 Git 输出统一敏感扫描；符号链接和工作区外路径在读取前拒绝。
- [ ] **Step 4: 加固 commit/push。** commit 只允许预览清单，push 绑定 remote URL 摘要、branch、HEAD 和超时；确认 commit 永远不隐式 push。
- [ ] **Step 5: 建立一次性临时仓库 REAL_E2E。** 初始化本地工作仓库与本地 bare remote，完成补丁预览→确认→容器测试→diff→commit 确认→push 确认；不使用 StudyPilot 主仓库作为破坏性样本。
- [ ] **Step 6: 验证每个确认前后 Git 状态、HEAD、远端 ref、AgentExecution、通知和审计。** 重复确认不新增提交或推送。
- [ ] **Step 7: 运行 Java、Runner 全量测试和真实容器链路。** 无 Docker/Podman 时明确 BLOCKED，不能降级宿主 shell。
- [ ] **Step 8: 提交。** `fix: harden governed developer agent workflows`

**完成标准:** 用户可以让 Agent 修改登记项目并测试，但任何越界、密钥读取、静默覆盖、自动 commit 或自动 push 都被确定性阻止。

---

## Task 33：受控界面适配器与 Task 25 收口

**Owner:** ZCode 实现浏览器/IDE 体验；DeepSeek Harness 实现签名协议和 Java Handler；Codex 决定白名单。

**Files:**

- Create: `local-automation-service/package.json`
- Create: `local-automation-service/src/server.ts`
- Create: `local-automation-service/src/browserAdapter.ts`
- Create: `local-automation-service/src/ideaAdapter.ts`
- Create: `local-automation-service/src/actionRegistry.ts`
- Create: `local-automation-service/tests/actionRegistry.spec.ts`
- Create: `backend/src/main/java/com/moxiao/studypilot/agent/developer/LocalAutomationClient.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/agent/developer/InterfaceFallbackPolicy.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/agent/developer/InterfaceFallbackPolicyTest.java`

- [ ] **Step 1: 写失败安全测试。** 拒绝任意 URL、selector、脚本、键值、文本输入、文件路径、未登记窗口和过期/重复 nonce。
- [ ] **Step 2: 建立独立本地服务。** 只监听 Unix Socket；Java 发送与 Runner 同等级的签名、过期时间和 nonce；FastAPI 与浏览器无权直接访问。
- [ ] **Step 3: 浏览器适配器只支持注册动作。** 第一版限定 `OPEN_STUDYPILOT_ROUTE`、`FOCUS_AGENT_INPUT`、`OPEN_RESULT_PANEL`；自身业务仍优先使用 Vue UI Action，适配器只作恢复兜底。
- [ ] **Step 4: IDEA 适配器只支持注册动作。** 第一版限定 `OPEN_REGISTERED_FILE`、`FOCUS_RUN_CONFIGURATION`、`SHOW_TEST_RESULT`；不开放任意键鼠、Shell、删除、输入代码或确认对话框。
- [ ] **Step 5: 每个动作返回可验证回执。** 包含 adapter、action、目标摘要、startedAt/finishedAt/status；不得回传屏幕隐私内容或任意 DOM。
- [ ] **Step 6: 运行真实本机最小验收。** 打开 StudyPilot 固定路由、打开临时工作区内固定源码、展示已有测试结果；全过程不输入密钥、不提交表单。
- [ ] **Step 7: 更新能力矩阵和交接，将 Task 25 只在真实动作证据存在时勾选。**
- [ ] **Step 8: 提交。** `feat: execute allowlisted local interface fallbacks`

**完成标准:** API 和 Vue 动作失败时，Agent 能执行极小范围的白名单界面恢复；它仍不是任意电脑控制器。

---

## Task 34：真实全栈验收、发布门禁与 Task 26 收口

**Owner:** ZCode 负责浏览器 E2E/视觉证据；DeepSeek Harness 负责环境脚本和后端证据；Codex 独立复跑并签署结论。

**Files:**

- Create: `scripts/agent-native-real-e2e.py`
- Create: `web/e2e/assistant-native.spec.ts`
- Create: `docs/verification/task-34-real-e2e.md`
- Modify: `docs/agent-native-e2e.http`
- Modify: `docs/agent-native-e2e-result.md`
- Modify: `docs/部署与演示指南.md`
- Modify: `docs/协同开发交接说明.md`
- Modify: `docs/superpowers/plans/2026-09-04-agent-native-studypilot.md`
- Modify: `项目开发步骤.md`

- [ ] **Step 1: 定义 REAL_E2E 场景与隔离数据。** 每次使用唯一账户、临时资料、临时工作区和本地 bare Git remote；所有 ID 自动捕获，禁止硬编码个人正式数据。
- [ ] **Step 2: 验证学习导航。** 对话“继续昨天未完成章节”，Agent 查询真实路线并让 Vue 自动进入唯一节点；无写操作和确认。
- [ ] **Step 3: 验证测验治理。** 对话生成节点测验，页面展示生成状态并进入测验；Agent 不替用户选择答案。
- [ ] **Step 4: 验证 RAG 与联网。** 导入小型大纲，等待 Qdrant 索引，提问本地事实并核对引用；再提时效问题触发 Tavily，记录来源和降级行为。
- [ ] **Step 5: 验证真实 DeepSeek。** 响应记录 `deepseek-v4-flash`、usage、延迟和估算费用；模型失败时 Java 传统业务仍可使用。
- [ ] **Step 6: 验证计划写操作。** 聊天确认不生效，专用确认后业务数据、版本、执行和审计一致；重复确认无副作用。
- [ ] **Step 7: 验证 Developer Agent。** 在临时仓库完成补丁、断网容器测试、Rubric、用户接受、commit 和 push 两次独立确认；远端 ref 是最终事实。
- [ ] **Step 8: 验证流与恢复。** 中断 SSE、刷新浏览器、重启 FastAPI，按 sequence 恢复，不重复模型或业务工具。
- [ ] **Step 9: 验证安全与降级。** Python、DeepSeek、Tavily、Runner 分别停用；记录允许继续的功能和稳定错误，不显示“网络异常”掩盖具体原因。
- [ ] **Step 10: 全量门禁。** 运行 Java clean test、Python pytest/Ruff、Runner pytest/Ruff、Vue Vitest/typecheck/build/Playwright、矩阵校验、Markdown 链接和 `git diff --check`。
- [ ] **Step 11: Codex 独立复跑。** 不接受执行 Agent 生成的结论；从日志、数据库/API、页面和 Git remote 四类证据交叉确认。
- [ ] **Step 12: 更新文档状态。** 只有所有必选场景通过才将 Task 26 标记完成；失败项保留命令、错误、影响与恢复条件。
- [ ] **Step 13: 提交。** `test: certify agent native studypilot release`

**完成标准:** 用户可以只从统一 Agent 完成导航、查询、受治理业务操作和受控开发流程；所有“完成”均能由真实系统状态证明。

---

## 5. 审查清单

Codex 对每个执行 Agent 的提交逐项回答：

- 需求中的每个行为是否有对应测试，而不只是类或工具注册？
- 测试是否先失败，且失败原因确实是缺少目标行为？
- 是否误把模拟 HTTP、H2 或 mock 当成真实环境？
- ownerId 是否只能由 Java 登录态注入？
- 写操作是否具备幂等、预期版本、风险、专用确认、执行和审计？
- 模型输出是否经过 schema 与确定性策略验证？
- 是否读取、输出或提交了密钥、配置、用户正文或绝对个人路径？
- 前端动作是否只使用 route/action registry？
- Runner 是否断网、限制资源且没有宿主机 shell 降级？
- Git commit 和 push 是否分开确认并有真实远端状态证据？
- 文档中的“完成”是否与本轮新鲜验证一致？

## 6. 建议执行顺序与预计节奏

在两个执行 Agent 可以稳定并行的前提下：

- 第 1 周：Task 27，随后启动 Task 28/29。
- 第 2 周：完成 Task 28/29，启动 Task 30/31。
- 第 3 周：完成 Task 30/31，启动 Task 32/33。
- 第 4 周：Task 34 真实全栈验收、修缺和文档收口。

这不是日期承诺。任一安全或契约问题未通过时，后续 Wave 不提前合并。优先级始终是：真实业务闭环 > 可恢复性 > 安全治理 > 用户体验 > 功能数量。

## 7. 明确不在本计划内

- 不扩展为任意电脑控制、任意 Shell、任意网页或任意键鼠自动化。
- 不让 Agent 代答测验、伪造打卡总结、自评或成果最终接受。
- 不迁移到 Spring AI，不推翻现有 Python LangGraph 编排。
- 不在本阶段引入 Redis、Kafka、Celery、Kubernetes 或多租户教师后台。
- 不以增加更多模型作为主要目标；先让一套默认模型的工具闭环可靠可测。
