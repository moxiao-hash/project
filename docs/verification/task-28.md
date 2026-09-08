# Task 28 验证证据：模型驱动的多步 Planner 与确定性策略验证

- **执行 Agent**：DeepSeek Harness + DeepSeek V4 Flash（后端与 Agent 执行工程师）
- **测试等级**：`[UNIT_TEST]` + `[MOCK_INTEGRATION]`（假模型），**另附一次真实 DeepSeek 最小冒烟**；本轮**不构成 `[REAL_E2E]`**（未串联 Java 门面、MySQL 数据回查与前端）
- **执行时间**：2026-09-08 23:51:36 (Asia/Shanghai)
- **Git 提交**：本分支提交 `feat: plan multi-step studypilot agent actions`（用 `git log -1 --grep='plan multi-step studypilot agent actions'` 定位；提交号不写入自身提交，避免 amend 造成的自引用失效）
- **关联分支**：`agent/deepseek-task-28-model-planner`（基线 `21f54b0`）

---

## 1. 运行环境与前置状态

- **操作系统**：macOS darwin arm64
- **Python 环境**：Python 3.12（复用主工作树 `ai-service/.venv`，以 `PYTHONPATH` 指向本 worktree，避免复制密钥）
- **模型**：`deepseek-v4-flash`（真实响应 `response_metadata.model_name = "deepseek-v4-flash"`）
- **Java 服务**：本机 8080 处于运行状态；真实冒烟从 `/internal/agent-tools/catalog` 读取到 **64 个已发布工具**（未做任何写操作）
- **FastAPI / MySQL / 前端**：本次未启动用于端到端串联
- **安全边界**：未打印、未提交任何 API Key、数据库口令或提示词正文；脚本只读取 `ai-service/.env` 的配置项

---

## 2. TDD 闭环证据

### 2.1 失败测试证据 (RED Phase)

命令：

```bash
cd ai-service
PYTHONPATH=$PWD .venv/bin/python -m pytest -q tests/unified_agent/test_planner.py tests/unified_agent/test_policy_validator.py
```

预期失败（实现尚不存在）：

```text
E   ModuleNotFoundError: No module named 'app.unified_agent.planning_models'
ERROR tests/unified_agent/test_planner.py
ERROR tests/unified_agent/test_policy_validator.py
!!!!!!!!!!!!!!!!!!! Interrupted: 2 errors during collection !!!!!!!!!!!!!!!!!!!!
```

第二步失败（Planner 未接入 Supervisor）：

```bash
PYTHONPATH=$PWD .venv/bin/python -m pytest -q tests/unified_agent/test_supervisor.py
```

```text
E       TypeError: UnifiedAgentSupervisor.__init__() got an unexpected keyword argument 'planner'
6 failed, 23 passed in 0.53s
```

第三步失败（确认后未继续剩余步骤）：

```bash
PYTHONPATH=$PWD .venv/bin/python -m pytest -q tests/unified_agent/test_supervisor.py -k "resume or rejected_plan"
```

```text
E       AssertionError: assert ['learning.context.get', 'settings.learning.update'] ==
                           ['learning.context.get', 'settings.learning.update', 'assessment.mastery.list']
1 failed, 1 passed, 29 deselected
```

### 2.2 成功测试证据 (GREEN Phase)

```bash
cd ai-service
PYTHONPATH=$PWD .venv/bin/python -m pytest -q
PYTHONPATH=$PWD .venv/bin/ruff check app tests ../scripts/agent-planner-smoke.py
```

```text
364 passed, 1 warning in 3.13s
All checks passed!
```

覆盖范围（全部为确定性单元/集成测试，模型与 Java 均为假实现）：

| 场景 | 断言 |
| --- | --- |
| 多意图多步计划 | 合法 2 步计划按 `dependsOn` 顺序执行，工具调用顺序精确匹配 |
| 未知工具 | `admin.delete.everything` 被拒，转 `CLARIFY`，不调用任何工具 |
| 模型伪造 ownerId | `ownerId` / `url` / `sql` / `shell` / `cssSelector` / `xpath` / `script` / `beanName` / `className`（含嵌套）全部拒绝 |
| 循环依赖 | 自引用与前向引用均判 `DEPENDENCY_CYCLE` |
| 超过 8 步 | Pydantic 契约直接拒绝构造；验证器另有 `TOO_MANY_STEPS` |
| 两个写步骤 / 两次联网 / 两个高风险 | 分别触发 `WRITE_BUDGET_EXCEEDED` / `WEB_BUDGET_EXCEEDED` / `HIGH_RISK_BUDGET_EXCEEDED` |
| 相同参数重复调用 | 验证器 `DUPLICATE_TOOL_CALL`；执行层兜底把网关 `DuplicateToolCallError` 转澄清而非 500 |
| 资料中的 Prompt Injection | 注入文本被 `<untrusted-data>` 包裹并进入提示，但注入出的未声明工具仍被拒绝 |
| 低置信度 | 0.0 / 0.3 / 0.4 / 0.69 均转 `CLARIFY` |
| 未注册 routeKey | 验证器 `INVALID_ROUTE_KEY`；`UiAction` 模型另有白名单与参数正则双重校验 |
| 写步骤确认 | 只生成待确认动作并停止后续步骤，不自动执行 |
| 确认后恢复 | 确认成功后继续执行剩余步骤；拒绝则不继续；服务重建后仍可恢复（加密 SQLite） |
| 取消 | 计划中途取消后不再调用下一个工具，状态 `FAILED` |
| 上下文重复调用 | 计划重复 `learning.context.get` 时复用已加载上下文，不触发网关重复调用错误 |

### 2.3 真实 DeepSeek 最小冒烟（非 REAL_E2E）

命令（在 `ai-service` 目录执行，脚本位于仓库根 `scripts/agent-planner-smoke.py`）：

```bash
cd ai-service
PYTHONPATH=$PWD .venv/bin/python ../project-deepseek-task-28/scripts/agent-planner-smoke.py
```

成功输出（两次，均 `status = PLAN`，`issueCodes = []`）：

```json
{
  "status": "PLAN",
  "modelName": "deepseek-v4-flash",
  "catalogSource": "java-catalog",
  "catalogSize": 64,
  "latencyMs": 47168,
  "plan": {
    "intent": "ROADMAP_NAVIGATE",
    "confidence": 0.85,
    "summary": "继续学习路线图中待学的'变量与类型转换'节点，获取学习内容与掌握情况，并为其生成测验以准备后续考核。",
    "steps": ["roadmap.current.get", "roadmap.node.get", "assessment.node_quiz_status.get",
              "assessment.wrong_questions.summary", "assessment.mastery.list",
              "assessment.node_quiz.generate"]
  }
}
```

第二次：`latencyMs = 54037`，6 步，`intent = LEARNING_QUERY`，`confidence = 0.72`，同样通过策略校验。

真实模型暴露并已修复的 4 个问题（这是本轮最有价值的证据）：

1. **`response_format=json_schema` 不可用**：DeepSeek 返回 400 `This response_format type is unavailable now`。已改为 `with_structured_output(..., method="json_mode")`。
2. **思考模型不支持强制 tool_choice**：`function_calling` 返回 400 `Thinking mode does not support this tool_choice`。因此 json_mode 是唯一可行方式。
3. **默认 20 秒超时过短**：思考模型实测 6～61 秒；20 秒必然降级。默认值调整为 90 秒（Java 门面到 Python 的请求超时为 120 秒，留出安全余量）。
4. **模型重复规划 `learning.context.get`**：网关禁止相同参数重复调用，会让整轮失败。执行层改为复用已加载上下文，并在提示中说明该工具本轮已执行。

---

## 3. 业务数据真实性回查 (Data Re-check)

- **本轮无已执行写操作**：写工具只会产生 `WAITING_CONFIRMATION` 动作卡，需专用确认接口才会执行。
- 真实冒烟只调用模型做规划，未调用任何 Java 工具、未写入数据库，因此没有可回查的业务数据变更。
- 写操作的真实落库与审计回查属于 Task 34（`[REAL_E2E]`）范围，本次不声明通过。

---

## 4. 模型用量与成本统计

- **实际 model id**：`deepseek-v4-flash`（服务端返回，非界面标签）
- **Prompt Tokens**：5,936
- **Completion Tokens**：6,657（其中 reasoning 6,452）
- **总 Token**：12,593
- **延迟**：60.7 秒（该次；同一提示另两次为 47.2 秒与 54.0 秒）
- **估算成本**：**未估算**。价格版本、`DECIMAL` 金额与日预算属于 Task 31；本轮不写零成本，也不把该次调用当作账单依据。

---

## 5. 变更文件清单

- 新增：`ai-service/app/unified_agent/planning_models.py`（`AssistantPlan` / `AssistantPlanStep` / `PlanIntent`，字段与枚举对齐 v2 冻结契约）
- 新增：`ai-service/app/unified_agent/policy_validator.py`（确定性策略验证器与问题码）
- 新增：`ai-service/app/unified_agent/planner.py`（模型 Planner、提示构造、按 owner 凭据的工厂）
- 修改：`ai-service/app/unified_agent/supervisor.py`（规划入口、多步执行、确认后恢复、取消、降级）
- 修改：`ai-service/app/unified_agent/models.py`（`ALLOWED_UI_ROUTE_KEYS`、`UiAction` 白名单与参数校验、`ToolDescriptor.is_web_search`）
- 修改：`ai-service/app/core/settings.py`（5 个 planner 配置项，默认超时 90 秒）
- 修改：`ai-service/app/main.py`（按 owner 装配 Planner；不可用自动降级）
- 新增：`ai-service/tests/unified_agent/test_planner.py`（24 项）
- 新增：`ai-service/tests/unified_agent/test_policy_validator.py`（32 项）
- 新增：`ai-service/tests/unified_agent/__init__.py`（与既有 `tests/agent/test_planner.py` 同名冲突的最小修复，沿用 `tests/knowledge`、`tests/teaching` 的既有做法）
- 修改：`ai-service/tests/unified_agent/test_supervisor.py`（+11 项）
- 修改：`ai-service/tests/unified_agent/test_durable_conversations.py`（+1 项重启恢复）
- 新增：`scripts/agent-planner-smoke.py`（真实模型最小冒烟，不输出密钥）
- 文档：`docs/verification/task-28.md`、`docs/协同开发交接说明.md`、`项目开发步骤.md`

相对 Task 28 计划文件清单的偏差（均已在上文说明理由）：额外修改 `app/main.py`（不接线则功能是死代码）、新增 `tests/unified_agent/__init__.py`（同名测试冲突）、新增 `scripts/agent-planner-smoke.py`（真实模型证据）。未修改 `docs/superpowers/plans/2026-09-08-...md`，该文件归 Codex 维护。

---

## 6. 未覆盖项与已知限制

1. **不是 `[REAL_E2E]`**：未串联 Java 门面 → FastAPI → MySQL → 前端；没有真实工具执行、没有数据库回查、没有浏览器证据。
2. **延迟**：思考模型规划实测 6～61 秒，超过 90 秒会降级为关键词层。同步阻塞体验需要 Task 29（持续 SSE）改善，延迟指标与预算需要 Task 31。
3. **工具目录快照**：Planner 在首次使用时冻结 Java 目录；Java 新增工具后需重启 FastAPI 才能进入规划目录。
4. **输出字段引用**：真实 Java 目录当前只发布 `output_schema = {"type": "object"}`，无法静态确认字段。验证器在“可确认时严格、不可确认时放行”，执行时字段缺失会转澄清且不调用工具；Task 30 补齐输出 schema 后自动变严格。
5. **`planId` 放宽**：契约文档要求 `planId` 必填，实现改为服务端生成、模型可省略（非破坏性放宽，已在模块 docstring 说明）。
6. **确认后恢复的范围**：仅恢复服务端持久化且已通过校验的剩余步骤；不接受客户端或模型新增步骤。跨重启恢复已覆盖，跨“Java 侧执行态变化”的补偿不在本轮。
7. **模型质量**：真实模型可能给出参数值为上下文中的真实 ID（如 `node-next`），也可能给出不存在的 ID；后者只会导致该工具失败，不会绕过治理或自动写业务数据。

---

## 7. 下一步交接建议

1. Codex 验收顺序建议：契约一致性（`planning_models.py` 对照 v2 冻结文档）→ 安全边界（`policy_validator.py` 禁止字段与预算）→ diff 审查 → 局部测试 → 全量测试（364 项）→ 真实冒烟复跑 → 文档口径。
2. 合并前请重点确认两处**有意放宽**：`planId` 服务端生成、输出字段不可静态确认时放行（均有运行时兜底与测试）。
3. 若继续 Wave 1，Task 29（持续 SSE）应优先解决规划期间 6～61 秒的同步阻塞；Task 28 的 Planner 接口（`propose` 返回 `PLAN / CLARIFY / UNAVAILABLE`）可直接被流式事件层复用。
4. 复跑真实冒烟前请确认 Java 8080 已启动；脚本在 Java 不可用时退回内置最小目录，此时结果**不能**作为 64 工具目录的规划证据。
