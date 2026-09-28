# StudyPilot Assistant: 只读计划事实回复与执行面板折叠修复验证记录

- **执行 Agent**：ZCode (Gemini 3.8 Flash)
- **修复时间**：2026-09-27
- **关联分支**：`agent/zcode-task-33-local-adapters`
- **基线提交**：`b73e07f05ca49e96a1bcf0cb2b6c3bde740b9053`
- **所有权范围**：`ai-service/app/unified_agent/**`、`web/src/modules/assistant/**` 及对应测试

---

## 1. 缺陷 1：只读多步计划结果硬编码及超限截断误判“未加入路线”

### 1.1 问题根因与真实实测场景
1. **硬编码步骤计数**：当用户提问“我当前的学习进度是？”等结果查询时，Planner 生成并成功执行了 `learning.context.get`、`roadmap.current.get`、`assessment.mastery.list`、`assessment.wrong_questions.summary`、`learning.tasks.list` 等只读工具步骤，工具数据均存放在 `outputs` 字典中。但 `_run_plan` 在没有待确认动作时直接硬编码返回 `f"已按计划完成 {executed} 个步骤。"`，导致真实业务事实从未回显给用户。
2. **工具输出超限截断导致虚假“未加入路线”**：真实长路线（如 123 节点的 Java+AI 路线）在 Java 端 `AgentToolRegistry` 中超过 65536 字节上限，Java 契约截断为 `{"warning": "工具输出超过安全上限，已裁剪...", "originalBytes": 143142, "truncated": true}`。在旧逻辑中，截断载荷缺少 `title` 字段，直接落入 `else` 分支并向用户宣称“你尚未加入任何学习路线，暂无学习进度记录”，向真实已报名的学员报出虚假事实。

### 1.2 修复方案与事实边界
1. **真实数据驱动的回复生成**：
   - 在 `UnifiedAgentSupervisor._compose_completed_plan_reply` 中从真实 `outputs` 提取事实；
   - **学习路线与进度（防截断与防虚假未报名）**：
     - 若 `roadmap.current.get` 遭遇截断，同轮有效的 `context.roadmap` 摘要存在时，自动回退并提取可信路线事实；
     - 若直接查询与上下文均遭遇截断超限，诚实说明“已读取学习路线，但返回数据超出长度限制导致内容不完整，未能解析出具体进度；你可以在学习路线页面查看完整进度”，**严禁捏造未加入路线或零进度**；
     - 仅当可信上下文明确包含 `roadmap=null` 且直接结果为 `null`/空且无截断时，才向用户报告未加入学习路线；
     - 若返回异常缺少 `title` 的畸形字典，诚实说明未能确认具体进度，绝不武断认定未报名；
     - 提取有效路线信息时，如实展示路线名称、已完成必修数/总数与百分比。若节点为 `AVAILABLE`，严禁伪称“当前正在学习”，诚实标为“下一个待学习节点”；仅状态为 `IN_PROGRESS` 时才标记为当前学习节点；
   - **任务情况**：如包含 `learning.tasks.list` 或 `schedule.today.get`，未带日期参数时作为“任务整体”汇报，带明确日期时按具体日期汇报；统计已完成数（`status == "COMPLETED"`）与待完成数；若全部为 `SKIPPED`，严禁宣称“全部完成”；
   - **掌握度与错题**：按真实 Java DTO 契约字段 `score`（掌握度）与 `activeCount`（错题活跃数）汇报薄弱点与待复习错题；
   - **学习资料**：提取 `materials.list` 资料数量与名称；
   - **限制陈述**：对未提取到结构化事实的只读查询，诚实说明“已执行查询步骤，但未获取到可展示的具体学习数据”，绝不以空洞的步骤计数冒充有效事实。
2. **截断状态在步骤摘要中明确呈现**：
   - 当 `invocation.truncated` 或载荷携带 `truncated: true` 时，步骤摘要标明 `已执行计划步骤 {step_id}（结果已超出上限截断）`，避免将裁剪结果掩盖为普通未裁剪，同时保持写操作确认卡片的治理逻辑不变。
3. **写操作与治理保持不变**：
   - 写操作生成的待确认卡片与提示语保持完全不受影响。

### 1.3 TDD RED/GREEN 证据
- **RED 测试** (`ai-service/tests/unified_agent/test_supervisor_read_only_reply.py`)：
  - `test_read_only_plan_learning_progress_composes_factual_reply`: 断言包含真实路线名称、5/64、下一个待学习节点、薄弱点类型转换(45%)及3道错题，初始失败（`AssertionError: assert '已按计划完成 5 个步骤。' not in result.reply`）；
  - `test_read_only_plan_no_enrollment_reports_honest_empty_state`: 断言未报名路线时诚实说明无路线，初始失败；
  - `test_read_only_plan_tasks_query_composes_factual_task_summary`: 断言未定日期的任务作为整体任务汇报，初始失败；
  - `test_read_only_plan_dated_tasks_query_labels_date_factually`: 断言指定日期的任务按具体日期汇报，初始失败；
  - `test_read_only_plan_skipped_tasks_does_not_claim_all_completed`: 断言全跳过任务不误报为全部完成，初始失败；
  - `test_read_only_plan_materials_query_composes_materials_summary`: 断言资料库数量与代表性名称，初始失败；
  - `test_read_only_plan_unhandled_query_reports_limitation_honestly`: 断言无数据只读查询诚实陈述限制，初始失败；
  - `test_read_only_plan_truncated_roadmap_uses_valid_context_fallback`: 断言直接路线截断时回退使用有效上下文，初始失败（误判未加入路线）；
  - `test_read_only_plan_both_outputs_truncated_reports_too_large_limitation`: 断言双重截断时诚实陈述数据过大限制，初始失败（误判未加入路线）；
  - `test_read_only_plan_malformed_dict_without_title_does_not_claim_no_enrollment`: 断言畸形无 title 载荷不误判未加入路线，初始失败；
  - `test_plan_tool_steps_distinguish_truncated_result_in_summary`: 断言公共步骤摘要体现截断标识，初始失败。
- **GREEN 验证**：
  ```bash
  cd ai-service
  PYTHONPATH=$PWD /Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/pytest tests/unified_agent/test_supervisor_read_only_reply.py -q
  # 12 passed in 1.74s
  PYTHONPATH=$PWD /Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/ruff check app tests
  # All checks passed!
  PYTHONPATH=$PWD /Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/pytest -q
  # 568 passed in 6.86s
  PYTHONPATH=$PWD /Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/pytest tests/unified_agent/test_policy_validator.py tests/unified_agent/test_planner.py -q
  # 58 passed in 1.36s
  ```

---

## 2. 缺陷 2：执行过程面板在视口中过高（~448px）挤占聊天历史

### 2.1 问题根因
`AssistantView.vue` 原先把 5 个步骤的执行过程平铺展开在 `.process-panel` 中，每个步骤包含图标、标题、工具名、徽章与外边距，累计高度超过 400px，在 783px 等常规视口中推挤了消息流，导致用户看不清聊天历史。

### 2.2 修复方案与可访问性
1. **默认紧凑折叠摘要行**：
   - 默认以紧凑横条展示整体执行结果与步骤计数；Codex 在 783px 高的真实浏览器视口实测收起时约 45px（原面板约 448px）；
   - 结果标签区分：全成功显示 `全部成功`（`badge-success`）；执行中显示 `执行中`（`badge-warning`）；存在失败显示失败摘要（`badge-danger`）；
   - 若存在失败步骤（`FAILED`），面板外框标红（`.has-failure`）且摘要直接标明失败步骤名称，确保失败绝不被折叠掩盖；
2. **支持展开与平滑滚动**：
   - 点击 `data-testid="toggle-process-steps"` 展开详细列表（`data-testid="process-steps-list"`）；
   - 展开列表限制 `max-height: 200px; overflow-y: auto;`，即使 8 步全开也绝不撑破视口；
   - 提供 `aria-expanded` 与 `aria-controls` 属性支持无障碍屏幕阅读器与键盘操作；
3. **轮次切换自动重置**：
   - 用户发送新消息启动新一轮次（`send()`）或 `activeTurnId` 变化时，自动将 `isProcessExpanded` 重置为 `false`，防止上一轮的展开状态长期遮盖新对话。

3. **状态语义真实性防线（严格避免虚假“全部成功”）**：
   - `AssistantToolStep.status` 是开放字符串，可能包含 `WAITING_CONFIRMATION`、`REJECTED`、`EXPIRED`、`CANCELLED`；
   - 严格仅在**每一步均为 `SUCCEEDED`**（`isAllSucceeded`）时，摘要徽章才显示 `全部成功` 并标记绿色 `✓`；
   - 存在 `WAITING_CONFIRMATION` 时，摘要徽章显示黄色 `待确认`（`badge-warning`），图标显示 `⏸`，并显示“等待确认：{summary}”；
   - 存在 `REJECTED`/`EXPIRED`/`CANCELLED` 时，按中断/失败处理，显示红色徽章与高亮红框（`.has-failure`），严禁任何未完成或已拒绝动作被误报为成功；
   - 独立的确认卡片（`.action-preview`）保持醒目完整。

### 2.3 TDD RED/GREEN 证据
- **RED 测试** (`web/src/modules/assistant/AssistantView.spec.ts`)：
  - `renders process-panel as a compact collapsed disclosure by default and expands on click`: 初始未折叠失败；
  - `prominently displays failure badge in process-panel summary when any step fails`: 初始无失败高亮类失败；
  - `automatically collapses process-panel on a new turn so large detail list does not re-cover chat`: 初始未重置失败；
  - `displays truthful waiting/pending state in collapsed process summary when a step is WAITING_CONFIRMATION and preserves confirmation card`: 初始误报全部成功失败；
  - `displays failure/rejection badge in process summary and does not claim success when a step is REJECTED`: 初始误报全部成功失败。
- **GREEN 验证**：
  ```bash
  cd web && npm test -- src/modules/assistant/AssistantView.spec.ts
  # 37 passed in 1.75s
  npm test -- --run
  # 35 test files / 328 passed
  npm run typecheck && npm run build
  # vue-tsc 0 errors, vite build OK in 1.02s
  ```

---

## 3. 修改文件清单

- `ai-service/app/unified_agent/supervisor.py`: 实现 `_compose_completed_plan_reply`，从工具输出构建真实事实回复，陈述限制，严禁伪造。
- `ai-service/tests/unified_agent/test_supervisor_read_only_reply.py`: 12 项 Python 行为测试，覆盖进度事实、无报名诚实空状态、任务整体 vs 具体日期标注、全跳过任务不误报、资料库查询、限制陈述、写操作预览保留、长路线截断回退上下文、双重截断限制陈述、无 title 异常字典及截断步骤摘要标识。
- `web/src/modules/assistant/AssistantView.vue`: 紧凑折叠执行过程面板、严谨状态语义映射（仅全 SUCCEEDED 显示全部成功，待确认显示 ⏸+待确认，拒绝显示 ✗+失败）、轮次自动折叠重置、无障碍属性支持与最大高度约束。
- `web/src/modules/assistant/AssistantView.spec.ts`: 5 项聚焦前端组件测试，覆盖折叠/展开、失败显式呈现、换轮重置、待确认状态防假成功与已拒绝状态防假成功。

## 4. Codex 独立验收（2026-09-27）

- 基于 `c887b9a` 独立复跑：AI 服务 564 passed；Web 35 文件 / 328 passed；`npm run typecheck` 与 `npm run build` 通过；`git diff b73e07f..HEAD --check` 通过。
- 将本地 AI 服务切换到本分支后，在真实 StudyPilot 页面重新提问“我当前的学习进度是？”，回复为“你尚未加入任何学习路线，暂无学习进度记录。任务整体暂无待办学习任务。暂无知识点掌握度记录。当前没有待复习的错题。”；符合该新账号当前空数据状态，且未退化为步骤计数。
- 同一页面实测执行过程面板默认收起高度约 45px；展开约 253px，详情可见，随后可再次收起。旧服务的内存会话在重启后失效，刷新页面建立新会话后完成复测。旧对话历史不会被回写。
