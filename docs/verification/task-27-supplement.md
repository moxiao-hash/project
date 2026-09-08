# Task 27 补充验证证据：Java 工具盘点补齐与门禁收紧

- **执行 Agent**：DeepSeek Harness + DeepSeek V4 Flash（后端与 Agent 执行工程师）
- **测试等级**：`[UNIT_TEST]`（Java `@SpringBootTest` 注册表精确断言）+ `[STATIC_GATE]`（Node 门禁脚本）
- **执行时间**：2026-09-09 00:02:12 (Asia/Shanghai)
- **Git 提交**：本分支提交 `fix: inventory every registered agent tool`（用 `git log -1 --grep='inventory every registered agent tool'` 定位）
- **关联分支**：`agent/deepseek-task-27-tool-inventory`（基线 `21f54b0`）

---

## 1. 背景与缺口

Task 27 的分工为「Codex 设计与验收；ZCode 盘点页面；**DeepSeek Harness 盘点工具**」。
ZCode 已交付 31 个页面路由盘点、v2 契约冻结、矩阵骨架与门禁脚本，但**工具盘点这一半不完整**：

- Java 注册表实际发布 **64** 个工具（`/internal/agent-tools/catalog` 实测，源码侧 63 个来自
  `AgentReadToolConfiguration`/`AgentWriteToolConfiguration` + `navigation.resolve` 来自
  `NavigationToolHandler`）。
- `AgentToolCoverageTest` 与能力矩阵只覆盖 **59** 个。
- 门禁脚本的工具全集**不是**来自 Java 注册表，而是正则抓取该测试文件里的硬编码名单；
  测试使用 `assertTrue(names.containsAll(...))`，允许注册表存在额外工具。因此门禁长期
  自洽地输出「覆盖全部 59 个」，无法发现第 60～64 个工具。

未登记工具（实测属性）：

| 工具 | effect | risk | 说明 |
|---|---|---|---|
| `assessment.node_quiz.retry` | **WRITE** | LOW | 为路线节点重新生成五题测验（受治理写操作） |
| `assessment.wrong_questions.summary` | READ | NONE | 错题归档汇总统计 |
| `governance.health.get` | READ | NONE | Agent 运行健康与用量摘要 |
| `learning.tasks.list` | READ | NONE | 按日期列出学习任务 |
| `navigation.resolve` | NAVIGATE | NONE | 白名单 routeKey 导航解析 |

其中写工具未进能力/风险/真实性矩阵，属于治理层面的实质缺口。

---

## 2. TDD 闭环证据

### 2.1 失败测试证据 (RED Phase)

先在 `AgentToolCoverageTest` 新增「注册表与清单必须精确一致」的断言（清单仍为当前已盘点的
59 个），运行后失败：

```bash
cd backend
./mvnw -q -o test -Dtest=AgentToolCoverageTest
```

```text
[ERROR] AgentToolCoverageTest.everyRegisteredToolIsInventoriedInTheCapabilityMatrix
org.opentest4j.AssertionFailedError: 注册表与能力矩阵不一致：请同步 docs/agent-capability-matrix-v2.md 与本清单
==> expected: <[59 个已盘点工具]>
    but was: <[64 个注册工具]>
[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
```

实际差异正是上表 5 个工具（`navigation.resolve`、`learning.tasks.list`、
`governance.health.get`、`assessment.wrong_questions.summary`、`assessment.node_quiz.retry`）。

### 2.2 成功测试证据 (GREEN Phase)

```bash
cd backend
./mvnw -o test -Dtest=AgentToolCoverageTest
```

```text
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

```bash
node scripts/verify-agent-capability-matrix.mjs
```

```text
[SUCCESS] 能力矩阵校验通过！覆盖全部 31 个页面路由与 64 个 Java 工具。
```

---

## 3. 变更内容

1. `backend/.../AgentToolCoverageTest.java`：
   - 新增 `INVENTORIED_TOOL_NAMES`（64 个）与精确集合断言
     `everyRegisteredToolIsInventoriedInTheCapabilityMatrix`，注册表与矩阵不再允许漂移；
   - 语义清单同步：只读工具补 `governance.health.get`、`learning.tasks.list`、
     `assessment.wrong_questions.summary`；受治理写工具补 `assessment.node_quiz.retry`。
2. `docs/agent-capability-matrix-v2.md`：
   - 第 3 节标题 58 → **64**；3.1 只读工具 40 → **43**（补 3 项）；3.2 写入与本地执行工具
     18 → **20**（原清单实际已有 19 项，数量标注同时修正，并补 `assessment.node_quiz.retry`）；
     新增 **3.3 导航工具 (1 个)** 登记 `navigation.resolve`；
   - 修正 `assistant-health` 页的读取工具映射：`governance.audit.list` → `governance.health.get`
     （该页数据源为运行健康汇总；执行审计明细属 `activity` 页）。

---

## 4. 业务数据真实性回查 (Data Re-check)

- 本轮为**只读断言与静态门禁**：不调用任何业务写工具，不修改数据库。
- 回查依据是 Java 注册表本身（`AgentToolRegistry.catalog()`）与实时目录接口
  `/internal/agent-tools/catalog`，两者在 64 个工具上一致。
- 未执行任何写操作，因此没有需要回查的业务数据变更。

---

## 5. 模型用量与成本统计

- 本轮**未调用任何模型**，无 Token 消耗、无估算成本。

---

## 6. 变更文件清单

- 修改：`backend/src/test/java/com/moxiao/studypilot/agent/tool/AgentToolCoverageTest.java`
- 修改：`docs/agent-capability-matrix-v2.md`
- 新增：`docs/verification/task-27-supplement.md`
- 修改：`docs/协同开发交接说明.md`、`项目开发步骤.md`

未修改任何生产代码、数据库迁移、接口或配置。

---

## 7. 未覆盖项与已知限制

1. 门禁脚本仍以 Java 测试文件中的清单为工具全集来源，而非直接调用实时目录接口；本次通过
   「测试断言精确集合」把两者绑定，Java 新增工具时会先让测试失败，从而强制更新矩阵。
2. 若未来引入非 `AgentReadToolConfiguration`/`AgentWriteToolConfiguration`/`NavigationToolHandler`
   之外的注册路径，仍需同步扩展测试清单。
3. 页面与工具的语义映射只修正了 `assistant-health` 一处明显错配；其余页面映射沿用 ZCode 盘点，
   未逐页复核。
4. 本轮未运行前端与 Runner 测试（改动不涉及），也未做真实端到端验收。

---

## 8. 下一步交接建议

1. Codex 验收要点：注册表 64 个工具是否与矩阵 3.1/3.2/3.3 一一对应；精确集合断言是否会
   在新增工具时失败（可临时加一个假工具验证，勿提交）。
2. 建议把「工具全集」的权威来源长期收敛到 Java 注册表（例如门禁脚本读取实时目录或生成快照），
   本轮以测试断言作为过渡。
3. 验收合并后再进入 Task 28 验收与 Task 29（流式 SSE）开发。
