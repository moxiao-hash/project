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
- [ ] Task 16：加密 SQLite 会话、轮次和事件持久化；Java SSE 代理、断线续传和重启恢复。
- [ ] Task 17：新增 `/assistant` 首页、全局快捷入口、过程卡片和白名单 UI Action Dispatcher。
- [ ] Task 18：补齐路线、今日、测验、错题、掌握度、资料、计划、通知、设置、工作区全部页面能力。
- [ ] Task 19：主动自动化规则、租约、授权内低风险执行、高风险通知和全局暂停。
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

## 提交映射

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
