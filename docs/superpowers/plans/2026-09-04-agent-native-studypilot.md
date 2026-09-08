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

- [x] Task 21：Runner 执行预览、固定模板、风险分类、专用确认、通知和审计。
- [x] Task 22：协议、Unix Socket、容器策略与 Maven/npm/pytest 真实容器执行均已验收。
- [x] Task 23：Runner 证据、文件清单确认、敏感扫描、DeepSeek 固定 Rubric 和用户最终验收闭环已完成。
- [x] Task 24：受控文件树、读取、搜索、Git 状态和 Unified Diff 补丁预览/冲突保护。
- [ ] Task 25：测试与独立 commit/push 已交付；Playwright/IDE 目前只有兜底预览策略，实际适配器未完成。
- [ ] Task 26：部分完成。公共 API 真实业务冒烟已通过，完整模型/前端/容器链路仍待验收。

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

## Task 21 验收证据

- [x] 提供固定命令模板：只读检查（MAVEN_TEST、MAVEN_COMPILE、NPM_TEST、PYTEST）与高危依赖准备（PREPARE_DEPENDENCIES），严禁任意自由 Shell 命令。
- [x] 实现无副作用 Runner 执行预览接口 `POST /api/runner/preview` 及只读工具 `runner.execution.preview`，支持预先获取风险等级、模板说明、执行指令、超时时间和确认需求。
- [x] 实现受治理 Runner 执行接口 `POST /api/runner/executions` 及写工具 `runner.check.run`、`runner.dependencies.prepare`。
- [x] 强制对齐能力矩阵：`PREPARE_DEPENDENCIES` 归为高风险，持久绑定原始工作区、模板、命令令牌和超时后进入 `WAITING_CONFIRMATION`，且仅专用确认 API 可执行；只读检查自动流转并记录审计。
- [x] Runner 提交要求客户端幂等键，相同请求即使工作区随后移动也返回原结果且不重复执行；执行记录支持按 owner 查询，首次执行或待确认执行前校验工作区目录身份，路径或身份变化时冲突终止。
- [x] `RunnerGovernanceWorkflowTest` 覆盖预览无副作用、精确工作区/模板确认、重复确认、幂等冲突、跨 owner、工作区变化、低风险精确执行、拒绝幂等及失败状态一致性。

## Task 23 验收证据

- [x] 已有成果敏感扫描、人工接受/拒绝接口和路线节点推进骨架。
- [x] 已删除按 `testEvidence` 中 `pass/success/ok` 等字符串伪造及格分的 `ArtifactReviewRubricEvaluator`；评审和接受接口在真实 AI 评审接入前返回 409，不会错误推进路线。
- [x] 只有归属相同、晚于成果提交且真实成功的 Maven/npm/pytest Runner 记录可以作为评审证据。
- [x] Java 先扫描并展示待发送文件；`.env`、应用配置、密钥、缓存和构建输出不会进入模型请求。
- [x] 用户调用专用确认接口后才把清单内源码发送给 FastAPI；确认前文件变化会拒绝执行。
- [x] DeepSeek V4 Flash 按 40/25/20/15 固定 Rubric 返回结构化评分，Python 与 Java 双重校验总分。
- [x] AI 达到 70 分后仍保持 `SUBMITTED`，只有用户调用最终 `accept` 接口才进入 `ACCEPTED`。
- [x] 预览、确认、成功和失败均写入审计；完成或失败会创建通知，重复确认不重复调用模型。
- [x] 真实 DeepSeek 最小联调返回 80 分且四项之和为 80，模型标识为 `deepseek-v4-flash`。
- [x] V43 在本地 MySQL 9.6 上成功迁移，数据库版本由 38 升至 43。
- [x] Java 346 项、AI 服务 299 项、Runner 21 项测试通过；两套 Ruff 与 `git diff --check` 通过。

## Task 22 验收证据

- [x] Java 不再包含 `ProcessBuilder` 或宿主机降级路径，只能通过所有者专用 Unix Socket 调用独立 Runner。
- [x] 完整签名信封包含用户、工作区、模板、风险、确认时间、资源限制和命令令牌；默认密钥和短密钥会被拒绝。
- [x] 独立 Runner 实现 HMAC-SHA256 验签、10 分钟有效期、SQLite 持久化 nonce、防篡改和高风险确认时间校验。
- [x] 规范路径、允许根目录与符号链接逃逸防护已实现，外层 workspace/template 必须与签名内容一致。
- [x] 固定 Docker/Podman 命令采用断网、只读根、非 root、capability、进程、CPU、512MB 内存、超时及输出上限；源码只读挂载后复制到容器 tmpfs。
- [x] Java/Python 共享长度前缀签名协议，并通过固定黄金签名验证互操作；Runner 协议与容器策略测试无需 Docker 即可运行。
- [x] 当前开发机使用 Colima + Docker CLI 构建三个固定镜像；Python 与 npm 在断网容器内测试通过。
- [x] Maven 使用高风险联网 `dependency:go-offline` 准备独立缓存，随后在 `--network none` 容器内编译测试通过。
- [x] macOS 容器虚拟机不可见宿主 `/tmp` 的问题已通过用户缓存目录暂存解决；暂存目录可用 `STUDYPILOT_RUNNER_STAGING_ROOT` 覆盖。

## Task 24 验收证据

- [x] 新增受 owner 隔离的文件树、文本读取、代码搜索、Git status/diff/log 和补丁预览工具；模型不能传入任意 URL、命令或工作区绝对路径。
- [x] `.env`、应用配置、密钥文件、构建目录、二进制文件及符号链接均不会进入 Developer Agent 上下文；普通源码和 Git 输出中的常见凭据会统一脱敏。
- [x] Unified Diff 只允许修改已登记工作区内的单个既有文本文件，限制文件与补丁大小，并确定性校验文件头、hunk 行号、上下文和行数声明。
- [x] 预览返回原文件与结果文件 SHA-256；确认执行前再次预检，执行时再次比较摘要，文件被用户修改后返回冲突且绝不覆盖新内容。
- [x] 应用补丁固定为 `HIGH` 风险，只有专用确认接口可执行；准备、确认、成功或失败复用 AgentExecution、通知、审计和幂等治理。
- [x] 补丁采用同目录临时文件和原子替换，并保留原文件 POSIX 权限；重复确认返回原执行结果，不重复写文件。
- [x] Task 25 的测试选择、Git commit/push 和浏览器/IDE 兜底未提前混入本任务。
- [x] Java 全量 355 项测试通过，`git diff --check` 通过。

## Task 25 验收证据

- [x] 新增 `developer.tests.recommend`，只根据登记工作区、真实 Git 改动和项目标志文件推荐 Maven、npm 或 pytest 固定模板；模型不能生成命令令牌。
- [x] Unified Agent 对“运行修改后的测试”先读取工作区与 Git 状态，再调用推荐工具，最终仅通过既有隔离 Runner 执行第一项白名单测试；多个工作区时要求用户明确选择。
- [x] 新增 Git commit/push 预览与执行契约。commit 只接受明确的 1～50 个安全相对路径，拒绝敏感文件和预先暂存内容，并绑定 HEAD 与改动摘要。
- [x] `developer.git.commit` 与 `developer.git.push` 是两个独立 `HIGH` 风险动作；确认 commit 不会 push，push 只允许 `origin` 和预览时的分支/HEAD。
- [x] Git 执行继续复用专用确认、幂等、AgentExecution、通知与审计；确认前 HEAD、文件内容或分支变化会返回冲突。
- [x] 新增 API 优先的界面兜底策略：存在业务 API 时强制选择 `BUSINESS_API`；只有无 API 时才允许预注册的 Playwright DOM 或 IDEA Accessibility 动作。
- [x] 界面兜底拒绝任意 URL、CSS selector、文件路径、脚本和键鼠参数；当前只发布安全预览策略，不把宿主机任意控制能力暴露给模型。
- [x] Java 全量 361 项、AI 服务 302 项、Runner 21 项、前端 124 项测试通过；两套 Ruff、TypeScript、生产构建和 `git diff --check` 通过。

## Task 26 验收证据（2026-09-08 重新核验）

- [x] 修正模拟上游的错误 DTO 字段；测试更名 `AssistantFacadeContractTest`，仅代表 H2/模拟上游门面契约。
- [x] 真实 Java 治理测试验证任务确认后状态、完成时间、版本、唯一历史、执行及审计；重复确认不新增记录。
- [x] 新用户无路线的真实上下文集成测试先复现事务回滚，再修复为可恢复提示。
- [x] `scripts/agent-native-smoke.py` 真实 Java→Python→MySQL 验证学习时长 60→30、聊天不确认、专用确认、重复确认、事件游标精确续传。
- [x] 更正 HTTP 脚本、交接记录、演示指南和结果报告；独立列明测试层级。
- [ ] DeepSeek、Tavily、Qdrant、Vue、容器 Runner、Rubric 和安全临时 Git 仓库的一次完整串联验收。
- [ ] 浏览器/IDE 真实适配器执行证据；仅有预览策略不算完成。
- [ ] 持续实时 SSE 推送与完整演示录像；当前仅验证有限事件重放。

旧 2026-09-07 全栈 PASS 结论撤回，以 [最新结果](../../agent-native-e2e-result.md) 为准。
自动化套件通过与真实端到端覆盖是两个不同维度。

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
