# Task 27 验证证据：能力矩阵、契约冻结与协作门禁

- **执行 Agent**：Claude (ZCode Harness)
- **测试等级**：`[UNIT_TEST]` + `[MOCK_INTEGRATION]`
- **执行时间**：2026-09-08 23:10:00 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-27-matrix-gate`
- **提交信息**：`docs: define agent productization and collaboration gates`

---

## 1. 运行环境与前置状态

- **操作系统**：macOS darwin arm64
- **Node 环境**：Node v22+
- **Java 环境**：Java 26 / OpenJDK 17 兼容编译目标
- **当前分支**：`agent/zcode-task-27-matrix-gate` (切自 `main`，基线 commit `08fa662`)

---

## 2. TDD 闭环证据

### 2.1 失败测试证据 (RED Phase)
- **执行命令**：`node scripts/verify-agent-capability-matrix.mjs`
- **预期失败输出**：
```text
[ERROR] 矩阵校验失败: 能力矩阵文件不存在: /Users/moxiao/IdeaProjects/project/docs/agent-capability-matrix-v2.md
```
*验证说明：确认自动化校验脚本在矩阵缺失时能正确以退出码 1 拦截。*

### 2.2 成功测试证据 (GREEN Phase)
- **执行命令 1（矩阵自动化门禁）**：`node scripts/verify-agent-capability-matrix.mjs`
- **输出结果**：
```text
[SUCCESS] 能力矩阵校验通过！覆盖全部 31 个页面路由与 59 个 Java 工具。
```

- **执行命令 2（Java 工具覆盖测试）**：`cd backend && mvn test -Dtest=AgentToolCoverageTest`
- **输出结果**：
```text
[INFO] Running com.moxiao.studypilot.agent.tool.AgentToolCoverageTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.602 s
[INFO] BUILD SUCCESS
```

- **执行命令 3（前端 UI Action 调度器测试）**：`cd web && npm test -- src/modules/assistant/uiActionDispatcher.spec.ts`
- **输出结果**：
```text
 ✓ src/modules/assistant/uiActionDispatcher.spec.ts (3 tests) 3ms
 Test Files  1 passed (1)
      Tests  3 passed (3)
```

- **执行命令 4（代码格式与空白符检查）**：`git diff --check`
- **输出结果**：
```text
Clean (无格式悬挂与空白符错误)
```

---

## 3. 交付物清单

1. `scripts/verify-agent-capability-matrix.mjs`：自动化矩阵校验脚本，强校验 Vue 路由（31 个）、Java 工具契约（58+ 工具）和能力映射合法性。
2. `docs/agent-capability-matrix-v2.md`：v2 全系统页面与工具能力映射总表，完整定义 `AUTO_READ / AUTO_NAVIGATE / PREVIEW_WRITE / USER_ONLY / UNSUPPORTED` 边界。
3. `docs/agent-native-contract.md`：冻结 v2 核心契约 Schema（`AssistantPlan`、`AssistantPlanStep`、`UiAction`、`PendingToolAction`、`AssistantEvent`、`Usage`）及 6 项模型安全禁令。
4. `docs/verification/README.md`：多 Agent 统一验证证据规范与标准模板。
5. `docs/verification/task-27.md`：本项任务的真实验证证据归档。

---

## 4. 未覆盖项与已知限制

1. 本任务为契约与门禁层交付，不涉及运行时代码逻辑改动。
2. 实际模型规划逻辑将在 Wave 1 的 Task 28 中由 DeepSeek Harness 实现。

---

## 5. 下一步交接建议

- 请 Codex 审查 Task 27 的契约与矩阵完备性，验收通过后合并至 `main`。
- 合并后开启 Wave 1，正式交接给 DeepSeek Harness 启动 **Task 28（模型多步 Planner）** 与 **Task 29（流式 SSE）**。
