# Task 27 验证证据：能力矩阵、契约冻结与协作门禁

- **初始执行 Agent**：ZCode（用户配置为 Gemini 3.8 Flash；仓库无法独立证明本次 Harness 实际模型 ID）
- **验收与修正 Agent**：Codex
- **测试等级**：`[STATIC_VALIDATION]` + `[UNIT_TEST]` + `[MOCK_INTEGRATION]`
- **初始提交**：`21f54b0`（`docs: define agent productization and collaboration gates`）
- **Codex 修正提交**：`b1c4c5a`（`fix: align task 27 capability contracts`）
- **关联分支**：`agent/zcode-task-27-matrix-gate`

---

## 1. 运行环境与前置状态

- **操作系统**：macOS darwin arm64
- **Java**：OpenJDK 26.0.1；项目使用 Maven Wrapper
- **Node**：v26.5.0
- **测试数据库**：H2 2.4.240（Java 全量测试）
- **真实服务与模型**：本任务未启动 MySQL、Spring Boot HTTP、FastAPI 或真实模型

## 2. TDD 与代码审查证据

### 2.1 初始实现的 RED/GREEN

ZCode 先验证矩阵文件缺失时门禁失败，再建立矩阵并使原脚本通过。该证据只能证明原脚本覆盖了其自身读取到的测试清单，不能证明生产 Tool Registry 完整。

### 2.2 Codex 验收发现与 RED

源码比对确认生产 Java Tool Registry 实际有 64 个工具，而原脚本只从测试清单读取到 59 个，漏掉：

- `governance.health.get`
- `learning.tasks.list`
- `assessment.wrong_questions.summary`
- `assessment.node_quiz.retry`
- `navigation.resolve`

同时，矩阵声明的 `ASSISTANT`、`COURSES`、`COURSE_DETAIL`、`LESSON` 等导航键未同时存在于 Java 与 Vue 白名单。新增测试后得到预期失败：

```text
SyntaxError: verify-agent-capability-matrix.mjs does not provide export loadProjectInputs
Error: 不受支持的页面动作
java.lang.IllegalArgumentException: 未注册的前端路由: ASSISTANT
```

### 2.3 修正内容

- 门禁改为读取生产 Java 工具配置、Vue Router、Vue Dispatcher 和 Java Navigation Handler，不再把测试清单当作事实来源。
- 增加矩阵门禁自身的 4 个 Node 测试，覆盖未知 routeKey、虚构工具和遗漏生产工具。
- Java 与 Vue 补齐 6 组导航映射，并增加双端测试。
- 工具覆盖测试冻结 64 个唯一生产工具。
- 修正矩阵工具数量、页面工具映射、实际风险等级和协作 Agent 名称。
- 修正 `AssistantPlan` 意图、`UiAction.reason`、待确认状态、SSE 单向语义及未知价格不得记零的 Usage 契约。
- 验证证据模板新增静态校验等级，删除虚构环境版本和“协作模型等于运行时模型”的错误假设。

## 3. GREEN 与全量回归

### 3.1 能力矩阵门禁

```text
node --test scripts/verify-agent-capability-matrix.test.mjs
4 tests passed

node scripts/verify-agent-capability-matrix.mjs
[SUCCESS] 能力矩阵校验通过！覆盖全部 31 个页面路由与 64 个 Java 工具。
```

### 3.2 Java 全量测试

```text
cd backend && ./mvnw test
Tests run: 365, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 3.3 前端全量测试与构建

```text
cd web && npm test -- --run
Test Files 22 passed (22)
Tests 124 passed (124)

npm run typecheck
PASS

npm run build
264 modules transformed
built successfully
```

### 3.4 文档与格式

```text
agent-native-contract.md 中 5 个 JSON 代码块均可被 JSON.parse 解析
git diff --check
PASS
```

## 4. 数据、模型与成本

- 本任务没有业务写入，因此不适用 MySQL 数据回查。
- 本任务没有调用 DeepSeek、Gemini 或 Tavily，Token、延迟与成本均不适用。
- Java Spring 测试使用 H2，属于 `[MOCK_INTEGRATION]`，不能写成真实全栈验收。

## 5. 未覆盖项与已知限制

1. Task 27 只冻结目标契约与静态门禁；多步模型 Planner、持续 SSE 和全页面执行闭环分别属于 Task 28～30。
2. `OPEN_MODAL`、`PREFILL_FORM`、`REFRESH_RESOURCE`、`FOCUS_ELEMENT` 是目标契约保留动作；当前 Dispatcher 仅执行 `NAVIGATE`，其余动作必须明确拒绝。
3. 本次没有真实服务、真实数据库或真实模型调用，不能标记 `[REAL_E2E]`。

## 6. 审批结论

Codex 在修正上述 P1 契约与门禁问题、完成全量回归后批准 Task 27。后续 Task 28/29 必须以修正后的 v2 契约和 64 工具基线为准。
