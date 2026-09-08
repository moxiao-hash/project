# StudyPilot Agent 原生能力矩阵 (v2)

> 版本：2.0.0 (Wave 0 冻结基线)  
> 适用对象：Codex (总架构师)、DeepSeek Harness (后端/模型规划)、Claude/ZCode (前端/体验)  
> 约束规范：本矩阵受 `scripts/verify-agent-capability-matrix.mjs` 自动化强校验。任何未在此矩阵登记的路由或工具，门禁将直接拒绝集成。

---

## 1. 核心定义与枚举规范

### 1.1 能力等级 (Capability Level)
- `AUTO_READ`：允许 Agent 自主调用后端只读工具加载或查询数据，无副作用。
- `AUTO_NAVIGATE`：允许 Agent 通过白名单 UI Action 调度前端视图导航至对应页面，无副作用。
- `PREVIEW_WRITE`：涉及业务写入或高危变更，必须先生成受治理动作卡（`WAITING_CONFIRMATION`），经专用确认接口调用后方可执行。
- `USER_ONLY`：学习真实性底线。必须由真实用户亲自作答、打卡、上传或决策，严禁 Agent 代办。
- `UNSUPPORTED`：系统未开放或仅限内部/未定义操作。

### 1.2 风险级别 (Risk Level)
- `NONE`：无副作用查询或只读动作。
- `LOW`：受控低风险操作（如标记通知已读、刷新日程、白名单依赖检查）。
- `HIGH`：高风险写操作（如修改路线、创建计划、变更设置、工作区登记、代码补丁、Git commit/push、依赖安装）。

### 1.3 学习真实性分类 (Authenticity)
- `AGENT_PERMITTED`：允许 Agent 协助或代理执行。
- `USER_ONLY_SUBMISSION`：作答提交、代码最终提交、知识掌握自评必须由用户亲力亲为。
- `USER_ONLY_REVIEW`：打卡总结、学习反思、错题归因分析必须由用户独立思考并填写。
- `USER_ONLY_DECISION`：实践成果最终接受、长期授权创建/撤销、账号安全决策必须由用户最终确认。

---

## 2. 页面与路由能力矩阵 (全系统 31 个具名路由)

| 路由名 (name) | 路由键 (routeKey) | 能力等级 (capability) | 读取工具 (readTool) | 写入工具 (writeTool) | 风险级别 (risk) | 真实性边界 (authenticity) | 限制说明 / 不代办原因 (reason) |
|---|---|---|---|---|---|---|---|
| login | NONE | USER_ONLY | - | - | NONE | USER_ONLY_DECISION | 用户账户认证凭据输入，严禁 Agent 代办输入密码或伪造登录凭据 |
| register | NONE | USER_ONLY | - | - | NONE | USER_ONLY_DECISION | 新用户注册，必须由用户自主同意服务协议并输入合法密码 |
| assistant | ASSISTANT | AUTO_READ | `learning.context.get` | - | NONE | AGENT_PERMITTED | 统一 Agent 首页，自主加载用户当前全局动态上下文 |
| dashboard | DASHBOARD | AUTO_NAVIGATE | `schedule.today.get` | - | NONE | AGENT_PERMITTED | 工作台页面，展示今日总览与路线进度，支持自动导航与只读展示 |
| roadmap | ROADMAP | AUTO_NAVIGATE | `roadmap.current.get` | `roadmap.enroll` | HIGH | AGENT_PERMITTED | 路线大图，加入新路线属于高风险业务写操作，需预览确认 |
| roadmap-stage | ROADMAP_STAGE | AUTO_NAVIGATE | `roadmap.stage.get` | - | NONE | AGENT_PERMITTED | 路线阶段详情，支持按 stageId 导航与阶段进度查询 |
| roadmap-module | ROADMAP_MODULE | AUTO_NAVIGATE | `roadmap.module.get` | - | NONE | AGENT_PERMITTED | 路线模块详情，支持按 moduleId 导航与模块节点树查询 |
| roadmap-node | ROADMAP_NODE | AUTO_NAVIGATE | `roadmap.node.get` | `roadmap.upgrade` | HIGH | AGENT_PERMITTED | 节点学习页面，节点升级属于高风险写操作，需预览确认 |
| goals | LEARNING_GOALS | AUTO_NAVIGATE | `learning.goals.list` | `learning.goal.create` | HIGH | AGENT_PERMITTED | 学习目标管理，创建长期目标需明确日期与预算，需预览确认 |
| courses | COURSES | AUTO_NAVIGATE | - | - | NONE | AGENT_PERMITTED | 传统阶段 8 课程目录，保留历史只读兼容与导航 |
| course-detail | COURSE_DETAIL | AUTO_NAVIGATE | - | - | NONE | AGENT_PERMITTED | 传统阶段 8 课程详情，保留历史只读兼容与导航 |
| lesson | LESSON | AUTO_NAVIGATE | - | - | NONE | AGENT_PERMITTED | 传统课时学习页，包含课时练习与视频播放，保留历史只读兼容 |
| plans | LEARNING_PLANS | AUTO_NAVIGATE | `learning.plans.list` | `learning.plan.create` | HIGH | AGENT_PERMITTED | 计划列表，创建新学习计划为多日结构化写操作，需预览确认 |
| plan-detail | LEARNING_PLAN | AUTO_NAVIGATE | `learning.plan.get` | `schedule.refresh` | LOW | AGENT_PERMITTED | 计划详情与日程刷新，支持计划内任务重排 |
| today | TODAY | AUTO_NAVIGATE | `schedule.today.get` | `learning.task.update` | HIGH | AGENT_PERMITTED | 今日任务流，将任务标记完成或改期需生成动作卡并专用确认 |
| materials | MATERIALS | AUTO_NAVIGATE | `materials.list` | `materials.text.import` | LOW | AGENT_PERMITTED | 资料管理，文本导入需切片与向量索引处理，需操作卡确认 |
| material-detail | MATERIAL_DETAIL | AUTO_NAVIGATE | `materials.get` | `materials.web.import` | LOW | AGENT_PERMITTED | 资料切片详情，外链资料导入受安全协议白名单校验 |
| quiz | QUIZ | USER_ONLY | `assessment.quiz.get` | - | NONE | USER_ONLY_SUBMISSION | 测验作答是学习掌握度检验核心，必须由用户真实作答，严禁 Agent 伪造答案或代答提交 |
| attempt | QUIZ_ATTEMPT | AUTO_NAVIGATE | `assessment.attempt.get` | - | NONE | AGENT_PERMITTED | 测验结果评估与得分解析报告，支持只读查看与分析 |
| wrong-questions | WRONG_QUESTIONS | AUTO_NAVIGATE | `assessment.wrong_questions.list` | `assessment.wrong_question_review.create` | LOW | AGENT_PERMITTED | 错题本，生成错题复习卷为低风险写操作，可由 Agent 生成待办 |
| mastery | MASTERY | AUTO_NAVIGATE | `assessment.mastery.list` | - | NONE | AGENT_PERMITTED | 知识点掌握度雷达图与弱项分析，只读展示 |
| knowledge | KNOWLEDGE | AUTO_NAVIGATE | - | - | NONE | AGENT_PERMITTED | 传统 RAG 问答入口，已统一合并至 Assistant 架构 |
| agent-plan | PLAN_ASSISTANT | AUTO_NAVIGATE | - | - | NONE | AGENT_PERMITTED | 传统计划生成对话入口，已统一合并至 Assistant 架构 |
| agent-tasks | TASK_ASSISTANT | AUTO_NAVIGATE | `schedule.unfinished.get` | - | NONE | AGENT_PERMITTED | 传统任务助手对话入口，已统一合并至 Assistant 架构 |
| activity | AGENT_ACTIVITY | AUTO_NAVIGATE | `governance.executions.list` | - | NONE | AGENT_PERMITTED | 执行历史与状态流转审计日志，提供完全只读回溯 |
| assistant-health | ASSISTANT_HEALTH | AUTO_NAVIGATE | `governance.health.get` | - | NONE | AGENT_PERMITTED | 运行健康度与个人用量/延迟/成本监控指标；该页数据源为 `governance.health.get`，执行审计明细属 `activity` 页 |
| notifications | NOTIFICATIONS | AUTO_NAVIGATE | `notifications.list` | `notifications.mark_read` | LOW | AGENT_PERMITTED | 系统通知与确认待办，标记已读为低风险操作 |
| settings | LEARNING_SETTINGS | AUTO_NAVIGATE | `settings.learning.get` | `settings.learning.update` | HIGH | AGENT_PERMITTED | 学习节奏与主动自动化规则配置，变更规则需专用确认 |
| settings-ai | AI_SETTINGS | AUTO_NAVIGATE | `settings.ai_status.get` | - | NONE | USER_ONLY_DECISION | AI 凭据与主密钥脱敏状态，API Key 明文提交与删除必须由用户亲自输入，严禁 Agent 代填凭据 |
| workspace-artifacts | WORKSPACE_ARTIFACTS | AUTO_NAVIGATE | `workspaces.list` | `workspaces.register` | HIGH | USER_ONLY_DECISION | 本地工作区与实践成果。登记工作区需确认；成果 70 分 Rubric 评审后最终接受必须由用户手动点击接受 |
| not-found | NONE | UNSUPPORTED | - | - | NONE | AGENT_PERMITTED | 404 兜底路由，不受支持的操作目标 |

---

## 3. Java 类型化工具目录与能力映射 (全量 64 个已注册工具)

### 3.1 只读工具 (43 个)
1. `learning.context.get` - 获取用户全局学习上下文、未完成节点与当天任务概要（EFFECT: READ, RISK: NONE）。
2. `learning.goals.list` - 列出用户历史与当前激活的学习目标（EFFECT: READ, RISK: NONE）。
3. `learning.plans.list` - 列出关联目标的结构化学习计划（EFFECT: READ, RISK: NONE）。
4. `learning.plan.get` - 查询指定计划的任务详情与排期（EFFECT: READ, RISK: NONE）。
5. `roadmap.current.get` - 查询当前主修路线结构、已完成节点与当前进行中节点（EFFECT: READ, RISK: NONE）。
6. `roadmap.stage.get` - 查询路线特定阶段信息（EFFECT: READ, RISK: NONE）。
7. `roadmap.module.get` - 查询路线特定模块信息（EFFECT: READ, RISK: NONE）。
8. `roadmap.node.get` - 查询知识节点核心概念、蓝图与建议练习（EFFECT: READ, RISK: NONE）。
9. `schedule.today.get` - 查询当天排期待执行的任务列表（EFFECT: READ, RISK: NONE）。
10. `schedule.unfinished.get` - 查询历史逾期与尚未完成的任务列表（EFFECT: READ, RISK: NONE）。
11. `assessment.node_quiz_status.get` - 查询指定节点的测验解锁状态与历史最高得分（EFFECT: READ, RISK: NONE）。
12. `assessment.quiz.get` - 获取测验题目列表（严格屏蔽正确答案与评分标准）（EFFECT: READ, RISK: NONE）。
13. `assessment.attempt.get` - 获取已完成测验的评分结果与详细解析（EFFECT: READ, RISK: NONE）。
14. `assessment.wrong_questions.list` - 查询用户的错题归档与复习记录（EFFECT: READ, RISK: NONE）。
15. `assessment.mastery.list` - 查询当前知识图谱掌握度评分（EFFECT: READ, RISK: NONE）。
16. `materials.list` - 列出用户关联的所有资料大纲与状态（EFFECT: READ, RISK: NONE）。
17. `materials.get` - 获取特定资料切片与摘要信息（EFFECT: READ, RISK: NONE）。
18. `notifications.list` - 获取未读与近期通知（EFFECT: READ, RISK: NONE）。
19. `governance.executions.list` - 查询 Agent 执行流转历史与耗时 Token（EFFECT: READ, RISK: NONE）。
20. `governance.audit.list` - 查询高风险操作与专用确认审计日志（EFFECT: READ, RISK: NONE）。
21. `settings.learning.get` - 查询用户每日学习时段与通知偏好（EFFECT: READ, RISK: NONE）。
22. `settings.ai_status.get` - 查询服务端回退与用户 AI 凭据配置状态（脱敏）（EFFECT: READ, RISK: NONE）。
23. `automation.settings.get` - 获取全局主动自动化总开关状态（EFFECT: READ, RISK: NONE）。
24. `automation.rules.list` - 获取所有已配置的主动规则（每日提醒、薄弱点测验等）（EFFECT: READ, RISK: NONE）。
25. `workspaces.list` - 获取已登记绑定的本地开发工作区目录（EFFECT: READ, RISK: NONE）。
26. `artifacts.list` - 查询已提交的路线节点实践成果物记录（EFFECT: READ, RISK: NONE）。
27. `artifacts.get` - 获取指定成果物的敏感扫描与评审记录（EFFECT: READ, RISK: NONE）。
28. `artifacts.evaluate` - 获取成果物 AI Rubric 四维度评分详情（EFFECT: READ, RISK: NONE）。
29. `runner.execution.preview` - 无副作用预览隔离 Runner 执行模板与命令（EFFECT: LOCAL, RISK: NONE）。
30. `developer.file_tree.get` - 读取工作区相对路径下的受控文件树结构（EFFECT: LOCAL, RISK: NONE）。
31. `developer.file.read` - 安全读取工作区单个代码/文本文件内容（脱敏与截断）（EFFECT: LOCAL, RISK: NONE）。
32. `developer.code.search` - 在工作区指定相对路径下进行关键字检索（EFFECT: LOCAL, RISK: NONE）。
33. `developer.git.status` - 安全读取工作区 Git 状态与改动文件列表（EFFECT: LOCAL, RISK: NONE）。
34. `developer.git.diff` - 获取特定修改文件的 Unified Diff（EFFECT: LOCAL, RISK: NONE）。
35. `developer.git.log` - 查询工作区近期的 Git commit 历史（EFFECT: LOCAL, RISK: NONE）。
36. `developer.patch.preview` - 校验并生成 Unified Diff 代码补丁预览与 SHA-256 摘要（EFFECT: LOCAL, RISK: NONE）。
37. `developer.tests.recommend` - 根据 Git 改动与项目文件确定性推荐测试模板（EFFECT: LOCAL, RISK: NONE）。
38. `developer.git.commit.preview` - 预检待提交文件清单与 HEAD，生成 commit 预览（EFFECT: LOCAL, RISK: NONE）。
39. `developer.git.push.preview` - 预检待推送分支与 origin，生成 push 预览（EFFECT: LOCAL, RISK: NONE）。
40. `developer.interface_fallback.preview` - 按照 API 优先原则，确定性生成界面兜底操作预览（EFFECT: LOCAL, RISK: NONE）。
41. `governance.health.get` - 获取当前用户的 Agent 运行健康、执行成功率与已上报用量摘要（EFFECT: READ, RISK: NONE）。
42. `learning.tasks.list` - 按可选日期列出用户学习任务（EFFECT: READ, RISK: NONE）。
43. `assessment.wrong_questions.summary` - 获取错题归档的汇总统计与薄弱知识点分布（EFFECT: READ, RISK: NONE）。

### 3.2 写入与本地执行工具 (20 个)
1. `roadmap.enroll` - 加入或绑定发布版路线（EFFECT: WRITE, RISK: HIGH, 需专用确认）。
2. `roadmap.upgrade` - 升级当前路线版本（EFFECT: WRITE, RISK: HIGH, 需专用确认）。
3. `learning.goal.create` - 创建结构化学习目标（EFFECT: WRITE, RISK: HIGH, 需专用确认）。
4. `learning.plan.create` - 创建多日滚动学习计划（EFFECT: WRITE, RISK: HIGH, 需专用确认）。
5. `schedule.refresh` - 重新平衡与刷新排期（EFFECT: WRITE, RISK: LOW, 授权内自动/预览）。
6. `assessment.node_quiz.generate` - 从节点蓝图生成 5 题针对性测验（EFFECT: WRITE, RISK: LOW）。
7. `assessment.wrong_question_review.create` - 基于历史错题生成专项复习卷（EFFECT: WRITE, RISK: LOW）。
8. `learning.task.update` - 更新任务进度状态（EFFECT: WRITE, RISK: HIGH, 需专用确认）。
9. `materials.text.import` - 导入自定义文本大纲或知识库切片（EFFECT: WRITE, RISK: LOW）。
10. `materials.web.import` - 导入受信任来源的外部技术文档链接（EFFECT: WRITE, RISK: LOW）。
11. `notifications.mark_read` - 标记指定通知已读（EFFECT: WRITE, RISK: LOW）。
12. `settings.learning.update` - 更新学习时段与自动化偏好（EFFECT: WRITE, RISK: HIGH, 需专用确认）。
13. `workspaces.register` - 登记受信任本地项目工作区（EFFECT: WRITE, RISK: HIGH, 需专用确认）。
14. `artifacts.submit` - 提交节点实践成果物并触发敏感文件扫描（EFFECT: WRITE, RISK: HIGH, 需专用确认）。
15. `runner.check.run` - 在断网容器内执行白名单测试构建（EFFECT: LOCAL, RISK: LOW/HIGH）。
16. `runner.dependencies.prepare` - 联网准备测试环境依赖（如 `dependency:go-offline`）（EFFECT: LOCAL, RISK: HIGH, 需逐次专用确认）。
17. `developer.patch.apply` - 原子应用受控 Unified Diff 代码补丁（EFFECT: LOCAL, RISK: HIGH, 需专用确认）。
18. `developer.git.commit` - 独立执行安全 Git commit（EFFECT: LOCAL, RISK: HIGH, 需专用确认）。
19. `developer.git.push` - 独立执行受限 Git push 至 `origin`（EFFECT: LOCAL, RISK: HIGH, 需独立专用确认）。
20. `assessment.node_quiz.retry` - 为路线节点重新生成五题测验（EFFECT: WRITE, RISK: LOW, 受治理写操作）。

### 3.3 导航工具 (1 个)

1. `navigation.resolve` - 校验白名单 routeKey 与实体参数，返回可导航的界面动作（EFFECT: NAVIGATE, RISK: NONE）。

---

## 4. 学习真实性红线

以下事项**绝对不允许**通过 Agent 工具直接代办，系统不提供任何对应写操作 API：
1. **用户测验作答**：严禁代办填写题目选项或编写代码题答案。
2. **打卡总结与反思**：严禁模型自动替用户编写或提交学习打卡。
3. **实践成果最终接受**：AI Rubric 评审达到及格分后，必须由用户在工作台点击 `accept` 按钮才能标记节点完成。
4. **AI 凭据明文录入**：模型不能读取或注入第三方服务 Key。
5. **授权扩张**：模型不能自行增加或修改自己的 Tool Grant 权限。
