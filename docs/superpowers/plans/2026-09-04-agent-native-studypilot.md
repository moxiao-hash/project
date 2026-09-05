# StudyPilot Agent 原生化执行清单

> 依据 2026-09-04 已确认总方案执行。每个 Task 必须遵循失败测试、最小实现、局部与全量验证、文档、独立提交和推送。

## 已有基础

- [x] Task 1～5：V2 路线、模块和节点导航。
- [x] Task 6～10：打卡、测验、日程、诊断和页面学习闭环。
- [x] Task 11：登记工作区与实践成果证据。

## Agent 原生化

- [x] Task 12：固化工具、UI Action、SSE 事件和页面能力矩阵；明确学习真实性和高风险边界。
- [x] Task 13：Java 类型化 Tool Registry、内部目录/调用接口和动态学习上下文；先接入只读与导航工具。
- [x] Task 14：业务写工具接入 AgentExecution、Grant、通知、审计、幂等和专用确认。
- [x] Task 15：Unified LangGraph Supervisor 复用现有子图，增加循环、预算、取消、注入和失败恢复。
- [x] Task 16：加密 SQLite 会话、轮次和事件持久化；Java SSE 代理、断线续传和重启恢复。
- [x] Task 17：新增 `/assistant` 首页、全局快捷入口、过程卡片和白名单 UI Action Dispatcher。
- [x] Task 18：补齐路线、今日、测验、错题、掌握度、资料、计划、通知、设置、工作区全部页面能力。
- [x] Task 19：主动自动化规则、租约、授权内低风险执行、高风险通知和全局暂停。
- [ ] Task 20：固定 Agent 评测集、越权和 Prompt Injection 测试、循环与成本限制、健康指标页面。

## Runner 与 Developer Agent

- [ ] Task 21：Runner 执行预览、固定模板、风险分类、专用确认、通知和审计。
- [ ] Task 22：Docker/Podman Runner、Unix Socket、签名信封、nonce、断网、资源和环境隔离。
- [ ] Task 23：测试后文件清单与敏感扫描、DeepSeek Rubric、70 分阈值和用户最终接受。
- [ ] Task 24：受控文件树、读取、搜索、Git 状态和 Unified Diff 补丁预览/冲突保护。
- [ ] Task 25：白名单测试、独立 commit/push 确认和 API 优先的 Playwright/IDE 兜底。
- [ ] Task 26：真实 MySQL、Java、FastAPI、DeepSeek、Tavily、Vue、Qdrant、容器 Runner 全链路验收与文档。

## Task 12 验收证据

- [x] 工具效果限定为 `READ / NAVIGATE / WRITE / LOCAL`，风险限定为 `NONE / LOW / HIGH`。
- [x] 单轮上限固定为 8 次工具、1 次联网、1 个写事务、1 个高风险待确认。
- [x] UI Action 只能使用五种白名单动作，禁止 URL、JavaScript、HTML 和任意 CSS selector。
- [x] SSE 事件具有严格递增序号和断线恢复契约，不暴露模型内部推理。
- [x] 当前业务页面均具有 route key、查询能力、写能力和学习真实性标记。
- [x] 用户答案、打卡总结、成果最终接受和授权扩张不能委托给 Agent。
- [x] 删除凭据、依赖准备、补丁、commit、push 固定逐次确认。

## Task 15 验收证据

- [x] FastAPI 新增统一 Supervisor 内部会话入口，旧计划、任务、知识问答和课内导师接口继续保留。
- [x] Supervisor 每轮先从 Java 重新加载动态学习上下文，不信任前端传入的用户或实体归属。
- [x] “继续未完成章节”从 Java 路线状态确定节点，并通过白名单 `ROADMAP_NODE` 动作导航。
- [x] “打开错题集并重做五题”只创建受治理动作预览，普通聊天中的“确认”不会执行写操作。
- [x] 单轮强制最多 8 次工具、1 次联网、1 次写操作和 1 个高风险动作，并拒绝相同参数重复调用。
- [x] 工具只能来自 Java 发布的类型化目录，Python 不接受模型构造的 URL、SQL 或任意调用目标。
- [x] 结构化上下文中的自然语言仅作不可信数据，Supervisor 不把资料或网页正文解释成工具指令。
- [x] Python 全量 260 项测试和 Ruff 校验通过。

## Task 16 验收证据

- [x] 统一会话快照、消息、幂等轮次结果和可重放事件写入既有 AES-GCM 加密 SQLite。
- [x] FastAPI 服务对象重建后可以按会话 ID 和 owner 恢复，其他 owner 得不到密文数据。
- [x] 事件使用严格递增 sequence，支持 `afterSequence` 游标，只返回尚未消费的事件。
- [x] Java 提供 `/api/assistant/conversations/**` Bearer 公共门面，并覆盖创建、消息、查询、确认、拒绝和取消。
- [x] SSE 门面发送心跳、事件 ID、事件类型和 JSON 数据；`Last-Event-ID` 会转换为内部续传游标。
- [x] 用户身份只从 Bearer Token 注入，浏览器伪造的 `ownerId` 不会传给 Python。
- [x] 工具失败会保存 `TURN_FAILED` 事件并释放活动轮次；相同幂等键在服务重启后仍返回原结果。
- [x] Java 全量 302 项、Python全量 269 项测试通过，Ruff 校验通过。

## Task 17 验收证据

- [x] 登录后的 `/` 改为统一 Agent 主入口，原工作台保留在 `/dashboard`。
- [x] 侧边栏固定提供 Agent 首页入口，传统路线、任务、资料、错题等菜单全部保留。
- [x] 页面展示快捷指令、Markdown 对话、模型名称、公开工具步骤、警告和风险确认卡片。
- [x] 助手消息使用安全 Markdown；用户消息继续按纯文本插值显示。
- [x] UI Action Dispatcher 只接受固定 route key 和固定实体 ID 参数，拒绝 URL、脚本和多余参数。
- [x] 写操作只能通过确认卡片调用专用 confirm/reject API，不能发送聊天文本代替确认。
- [x] 前端全量 119 项测试、TypeScript、生产构建和 `git diff --check` 通过。

## Task 18 验收证据

- [x] Java 工具目录已覆盖路线、计划、今日安排、测验、错题、掌握度、资料、通知、审计、设置、工作区和成果的真实查询能力。
- [x] 路线加入/升级、目标与计划创建、日程刷新、测验生成、错题重做、任务更新、资料导入、设置、工作区和成果写入统一经过治理层。
- [x] 路线变更、设置、工作区与成果均为高风险动作，模型只能产生预览，必须调用专用确认接口后执行。
- [x] 统一 Agent 能从真实业务状态打开今日任务、当前测验、掌握度、资料、通知、执行审计和工作区页面。
- [x] 知识搜索委托现有 RAG/Tavily/DeepSeek 服务，并把引用和降级警告带回统一会话；同一统一会话的连续追问复用同一知识子会话。
- [x] 前端 Route Registry 已覆盖现有页面，工作区与成果新增真实管理页面；引用只允许安全 HTTP/HTTPS 外链。
- [x] 用户答题、打卡总结、自评、AI 凭据明文输入和成果最终接受仍只允许用户在专用页面操作，不发布为模型工具。
- [x] Java 304 项、Python 274 项、前端 121 项测试全部通过；Ruff、TypeScript 和生产构建通过。

## Task 19 验收证据

- [x] 用户可在设置页创建、暂停、恢复、改期和删除主动自动化规则，并可一键暂停全部规则。
- [x] 规则只描述用户意图，不会创建或扩大授权；Java 每次领取任务前重新验证有效长期授权。
- [x] 主动任务使用 MySQL 持久化队列、worker/token 租约、心跳、超时恢复和最多三次重试。
- [x] 任务领取时创建 `AgentExecution`，完成或失败同步更新状态，并写入通知和审计记录。
- [x] Python Worker 复用现有计划调整、日程、测验和通知能力，旧夜间扫描不再形成第二套主动执行链。
- [x] 统一 Agent 可查询规则与全局暂停状态，但规则管理仍是用户专属决定。
- [x] 修改规则时区或执行时间会重新安排尚未执行的任务，已完成历史不会被覆盖。
- [x] Java 307 项、Python 278 项、前端 122 项测试全部通过；Ruff、TypeScript 和生产构建通过。

## Task 20 当前进度（2026-09-05）

- [x] 新增个人执行统计 API `/api/assistant/health` 与“运行健康”页面。
- [x] 成功率仅包含已成功/失败记录；用量、成本、延迟返回样本数，未采集显示“暂无数据”。
- [x] 统一 Agent 增加有限标签的轮次、工具结果和延迟指标。
- [x] 固定指令回归覆盖路线、测验、薄弱点和自动化设置，以及注入和失败预算边界。
- [x] 修复失败调用不消耗预算的问题，失败后相同工具参数也禁止重放。
- [ ] 接通统一会话的真实模型 Token、计费口径及成本预算限制；当前页面仅累计执行记录已上报字段。
- [x] 扩大安全评测集与鲁棒性治理：补全工具调用中途取消中断、确认请求非 SUCCEEDED 终态对齐及操作幂等、自动化 Worker 心跳失效并发中断、租约过期达到最大重试时阻断并告警标记 FAILED。

Task 20 尚未整体完成，后续验证完成后才勾选总任务。

验证：Java 全量 308 项及新增统计单测 1 项通过；Python 295 项、前端 124 项通过，
Ruff、TypeScript、生产构建和 `git diff --check` 通过。未执行真实模型计费联调。

## 提交映射（按任务）

| Task | 提交信息 |
|---|---|
| 12 | `docs: define agent native application contracts` |
| 13 | `feat: expose governed application tool catalog` |
| 14 | `feat: govern unified business tool actions` |
| 15 | `feat: orchestrate unified studypilot agent` |
| 16 | `feat: stream durable assistant conversations` |
| 17 | `feat: make assistant the primary application entry` |
| 18 | `feat: operate studypilot through conversation` |
| 19 | `feat: automate authorized learning workflows` |
| 20 | `test: validate unified agent reliability` |
| 21 | `feat: govern local runner executions` |
| 22 | `feat: run project checks in isolated containers` |
| 23 | `feat: review and accept roadmap artifacts` |
| 24 | `feat: let developer agent propose safe code changes` |
| 25 | `feat: complete governed developer workflows` |
| 26 | `test: complete agent native studypilot workflow` |
