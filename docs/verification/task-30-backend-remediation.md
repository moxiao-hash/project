# Task 30 整改后端验证证据：五类动作严格 Schema、受控请求与稳定 actionId

- **执行 Agent**：DeepSeek（后端与 Agent 工具/治理）
- **执行时间**：2026-09-12（Asia/Shanghai）
- **目标 worktree**：`/Users/moxiao/IdeaProjects/project-deepseek-task-30-remediation`
- **目标分支**：`agent/deepseek-task-30-remediation`（已确认，HEAD = `4408251`）
- **交付状态**：**未提交、未推送**；见下文“环境阻塞”。改动已在隔离临时副本中通过完整测试与 Ruff。

---

## 0. 环境阻塞（未解决，必须由调度方处理）

本会话的 DSH 文件沙箱为 `workspace-write`，只允许写入会话工作区
`/Users/moxiao/Desktop/Deepseek Harness`。任务目标 worktree 位于
`/Users/moxiao/IdeaProjects/**`，属于沙箱外路径：

- `write`/`edit` 工具写入目标 worktree 被拒绝：
  `[sandbox: file access denied under workspace-write mode]`；
- `bash -c 'touch <worktree>/...'` 被拒绝：`Operation not permitted`；
- 一次沙箱升级尝试返回：
  `sandbox escalation to "danger-full-access" requires approval, but no approval channel is available`。

按会话策略，无审批通道时拒绝即为最终结果，因此本次**无法在目标 worktree 落地代码、运行
该 worktree 的测试、或 commit/push 分支**。为不丢失交付，下列改动在隔离副本
`/tmp/t30/ai-service`（源文件由目标 worktree 只读复制）中完成并通过验证，同时生成可直接
`git apply` 的补丁 `/tmp/t30/task30-remediation.patch`。

> 说明：`/tmp/t30` 中的 `backend/`、`infra/`、`web/Dockerfile`、`web/nginx.conf` 仅用于让
> `tests/test_infra_compose.py` 读取仓库根文件，未被修改。

---

## 1. 交付物

| 文件 | 变更 | 说明 |
| :--- | :--- | :--- |
| `ai-service/app/unified_agent/ui_action_schema.py` | 新增 | 五类动作闭集、逐动作注册表、严格校验、受控请求解析 |
| `ai-service/app/unified_agent/models.py` | 修改 | `UiAction` 改为闭集枚举 + 逐动作校验 + 可选稳定 `actionId` |
| `ai-service/app/unified_agent/supervisor.py` | 修改 | 受控请求确定性分支；`_register_ui_actions` 在发布前登记稳定 actionId；计划续跑同样登记 |
| `ai-service/app/unified_agent/policy_validator.py` | 修改 | 白名单常量改从 schema 模块导入（单一来源） |
| `ai-service/tests/unified_agent/test_ui_action_schema.py` | 新增 | 28 项 Schema/受控请求/稳定 actionId/隔离测试 |
| `scripts/task30-remediation-probe.py` | 新增 | 真实 Java 公开门面三端探针（非 mock），覆盖 16 个受控场景 + 跨用户隔离 |
| `docs/verification/task-30-backend-remediation.md` | 新增 | 本文档 |

未改动 `web/**`、`backend/**`、调度器、其他 worktree 或 `main`。Java 公开回执路径与四字段
请求体保持不变。

---

## 2. 冻结契约对齐

### 2.1 五类动作闭集与逐动作 Schema

- `UiActionType` 为五值闭集；未知类型（第六类）在 `validate_ui_action` 处被拒绝。
- `NAVIGATE` 保留既有 routeKey 白名单与 route-specific 标识参数（`nodeId`/`quizId`/
  `stageId`/`moduleId`/`planId`/`materialId`/`attemptId`/`courseSlug`/`lessonId`）；出现未知键或
  非法标识值即拒绝，但保留既有“可省略可选路径参数”的行为。
- `OPEN_MODAL`：`routeKey → modalKey` 一对一（`LEARNING_GOALS→CREATE_GOAL`、
  `LEARNING_PLANS→CREATE_PLAN`、`MATERIALS→IMPORT_MATERIAL`），只允许 `modalKey` 一个键。
- `PREFILL_FORM`：`routeKey → formKey` 对齐；标题长度上限 100/120/180，允许中文与空格；
  可选 `targetDate`/`startDate`/`endDate` 必须是 ISO 日期，`weeklyStudyHours` 必须是 1–40 的
  整数字符串，`goalId` 必须是安全业务标识符，`content` 长度 1–2000。
- `REFRESH_RESOURCE`：八个 routeKey 与八个 resourceKey 一对一，只允许 `resourceKey`。
- `FOCUS_ELEMENT`：`ASSISTANT→MESSAGE_INPUT`、`LEARNING_PLANS→PLAN_TITLE_INPUT`，
  只允许 `elementKey`。
- 所有参数值保持字符串；`ownerId`、URL、HTML、JavaScript、原生选择器、未知字段、未知注册表
  键一律拒绝。草案文本禁用 `<> { } [ ] ( ) ;`、`http(s)://`、`javascript:`、`data:`、
  `vbscript:` 与控制字符。

### 2.2 受控请求（用户场景）

`resolve_ui_action_request` 只匹配明确措辞：弹窗/面板 + 新建/导入、`预填`、`刷新`、`聚焦`。
因此“新建学习目标”“保存学习计划”等真实写入请求不会被改写成草稿请求。
覆盖冻结表中的全部 16 个受控场景（3 弹窗 + 3 预填 + 8 刷新 + 2 聚焦）。

### 2.3 稳定 registered actionId 与回执

- `_register_ui_actions` 在 UI_ACTION 发布前为每个新动作分配并登记 `actionId`；同一动作对象
  已带 `actionId` 时复用（重放一致）。
- REST 会话快照与 SSE 事件共用同一 `actionId`（此前 REST 快照缺少 `actionId`）。
- 业务重试（`NAVIGATE`/`REFRESH_RESOURCE`）仍由 `_build_retry_action` 生成**新**注册 ID。
- 回执按 owner/conversation/actionId 隔离；已有 200 幂等 / 409 冲突 / 404 他人语义未改动。

### 2.4 持久化与隔离

沿用既有加密 `AgentPersistence`：`uiActionsById` 与快照一并落库；遗留快照缺失 `actionId`
时 `action_id=None`，不会被登记，也不会被自动执行。跨所有者回执在第一道会话归属校验即 404。

---

## 3. 实际验证证据（在 `/tmp/t30/ai-service` 执行）

前置：复用主工程 venv
`/Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/python`（Python 3.12.13，pydantic 2.13.4）。

```bash
cd /tmp/t30/ai-service
PYTHONPATH=$PWD <venv>/bin/python -m pytest -q tests/unified_agent/test_ui_action_schema.py
```

```text
28 passed in 0.41s
```

```bash
PYTHONPATH=$PWD <venv>/bin/python -m pytest -q tests/unified_agent
```

```text
162 passed in 1.93s
```

```bash
PYTHONPATH=$PWD <venv>/bin/python -m pytest -q
```

```text
448 passed, 1 warning in 3.91s
```

```bash
<venv>/bin/python -m ruff check app tests
```

```text
All checks passed!
```

探针脚本可通过 CLI 自检（未连接真实服务）：

```bash
cd /tmp/t30
<venv>/bin/python scripts/task30-remediation-probe.py --help   # exit 0
<venv>/bin/python scripts/task30-remediation-probe.py          # 缺少令牌，exit 2，不伪造回执
```

`tests/test_infra_compose.py` 的 5 项在首轮临时副本中因缺少仓库根文件失败；补齐只读的
`infra/`、`backend/`、`web/Dockerfile`、`web/nginx.conf` 后全部通过，证明与本改动无关。

---

## 4. 未完成 / 未验证事项

- **未提交、未推送**：沙箱拒绝写入目标 worktree（见 §0）。
- **无 `[REAL_E2E]`**：未启动真实 MySQL/FastAPI/Java/浏览器，未运行
  `scripts/task30-remediation-probe.py` 的真实三端链路；该探针只完成 CLI 语法与缺令牌路径验证。
- **未改动 Java**：本次未修改 `backend/**`；五类动作的**前端执行适配器**属于 ZCode 范围，
  后端只负责生成受控动作、稳定 actionId 与回执语义。
- 遗留快照中历史动作不带 `actionId`，需与 ZCode 确认其“抑制历史副作用”逻辑一致。
- 由于无法运行目标 worktree 的 pytest，上表证据来自目标 worktree 源文件的隔离副本；
  补丁应用到真实分支后应重跑同一组命令。

---

## 5. 真实模型 route-enum 跟进（2026-09-17，TDD）

### 5.1 现象与根因

真实浏览器验收中，「Navigate to the learning goals page.」「打开学习目标页面」以及目标表单类请求
都以 `PLANNER_INVALID_ROUTE_KEY` 结束。确定性白名单 `ui_action_schema.ALLOWED_UI_ROUTE_KEYS`
本身正确；根因是 `planner.py` 的系统提示只带 Java 工具目录，没有把冻结的 `routeKey` 枚举与
逐动作参数矩阵告诉真实模型。模型因此猜测别名（如 `LEARNING_GOAL`、`learning-goals`），
再被 `PlanPolicyValidator` 正确拒绝。

### 5.2 TDD 闭环

失败测试（RED）：`ai-service/tests/unified_agent/test_planner_route_contract.py`。
在只新增 `render_ui_action_contract()`、尚未接入 Planner 时执行，3 项针对 Planner 系统消息的
断言失败、2 项注册表派生断言通过，直接证明缺口在 Planner 系统消息：

```bash
cd ai-service
PYTHONPATH=$PWD /Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/python \
  -m pytest -q tests/unified_agent/test_planner_route_contract.py
# FAILED test_planner_system_message_registers_every_frozen_navigation_route_key
# FAILED test_planner_system_message_exposes_all_five_action_matrices
# FAILED test_planner_system_message_states_guessed_aliases_are_rejected
# 3 failed, 2 passed in 0.54s
```

接入 Planner 后（GREEN）：

```bash
cd ai-service
PYTHONPATH=$PWD /Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/python \
  -m pytest -q tests/unified_agent/test_planner_route_contract.py
# 6 passed
```

### 5.3 实现

- `ai-service/app/unified_agent/ui_action_schema.py`：新增 `UI_ACTION_ALIAS_RULE` 与
  `render_ui_action_contract()`。契约完全由既有冻结注册表（`ALLOWED_UI_ROUTE_KEYS`、
  `NAVIGATION_PARAM_KEYS`、`MODAL_ACTION_KEYS`、`FORM_ACTION_SPECS`、
  `RESOURCE_ACTION_KEYS`、`FOCUS_ACTION_KEYS`）派生，白名单保持单一来源。
- `ai-service/app/unified_agent/planner.py`：`_system_message()` 在系统提示中插入该契约，再附
  Java 工具目录 JSON。未改动 `PlanPolicyValidator`、`UiAction` 校验或 supervisor 治理逻辑；
  非法别名仍由原校验器拒绝，不做自动纠正，也不放开任意 URL/选择器。
- `ai-service/tests/unified_agent/test_planner_route_contract.py`：6 项回归，覆盖 28 个 routeKey、
  四类逐动作矩阵、NAVIGATE 标识参数、别名拒绝说明、工具目录保留，以及猜测别名仍被校验器拒绝。

### 5.4 验证命令与结果

```bash
cd ai-service
PYTHONPATH=$PWD /Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/python \
  -m pytest -q tests/unified_agent/test_planner_route_contract.py
# 6 passed in 0.48s

PYTHONPATH=$PWD /Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/python -m pytest -q
# 492 passed, 1 warning in 4.32s

/Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/ruff check app tests
# All checks passed!

git diff --check
# 通过（无输出）
```

环境说明：本会话 DSH 文件沙箱为 `workspace-write`，不能直接写入目标 worktree，且升权
`danger-full-access` 无审批通道；本轮在隔离克隆中完成同样的 TDD 与全量验证，并推送回目标分支。

### 5.5 提交与推送

- 分支：`agent/deepseek-task-30-remediation`
- 修复提交：`934af4d9e9cc76e155cca8431c0f2ef20d0a6455`
  （`fix(ai-service): expose frozen ui action route matrix to planner`）
- 已推送；`git ls-remote` 核对 `origin/agent/deepseek-task-30-remediation` 与本地 HEAD 一致。

### 5.6 未完成 / 边界

- 证据等级 `[UNIT_TEST]`：本轮没有真实模型调用，也没有真实 MySQL/FastAPI/Java/浏览器三端串联；
  真实模型是否按新契约选择精确 `routeKey` 仍需 Codex 在真实环境复验。
- 未改动 `web/**`、`backend/**` 与 `main`，未开发 Task 31。
- 调度任务书写的目标分支是 `agent/deepseek-task-30-tools`；该分支是已验收的旧后端分支
  （`93322a9`），不含本次整改所需的 `ui_action_schema.py`，向其提交会重写已验收历史。
  按 `docs/verification/task-30-remediation-contract.md` 对 DeepSeek 的授权，本次提交落在
  `agent/deepseek-task-30-remediation`。
