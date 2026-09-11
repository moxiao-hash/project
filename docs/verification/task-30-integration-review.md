# Task 30 集成验收：UI Action、回执与工具治理

- **验收人**：Codex
- **执行时间**：2026-09-11 19:44（Asia/Shanghai）
- **集成分支**：`codex/task-30-integration`
- **输入提交**：后端 `93322a9b4dc53fbe459c8b4f36da65cec6f345dc`；前端 `1f6be10d4063f6166f8d3750f66820bf013165ce`
- **结论**：**Task 30 整体暂不通过；后端保持已验收，NAVIGATE 与回执真实链路通过，四类非导航动作尚未形成可用闭环。**

## 已验证结果

### 真实三端联调 `[REAL_E2E]`

为避免影响既有 8080/8000/5173 服务，集成分支独立启动 Spring Boot 8081、FastAPI 8001、Vue 5174，并使用独立临时 Agent 状态库。Spring Boot 连接本机真实 MySQL，Flyway 成功从 V43 升至 V44；Java 与 Python 健康检查均为 `UP`。

API 探针使用两个隔离测试用户完成以下验证：

- 经 Java 创建会话、监听真实 SSE、发送“打开错题集”，收到 `TURN_STARTED`、工具事件、`UI_ACTION`、增量和 `TURN_COMPLETED`。
- `UI_ACTION` 带服务端稳定 `actionId`，类型为 `NAVIGATE`，目标为 `WRONG_QUESTIONS`。
- 首次成功回执为 200；相同终态重复回执为 200 且响应一致；冲突终态为 409；另一用户访问为 404。

Computer Use 使用隔离测试账号登录真实 Vue 页面，在 Agent 首页发送“打开错题集”后，浏览器实际进入 `/wrong-questions` 并渲染该用户的错题集。修复重复执行问题后再次复验，服务日志显示本轮只有一次已登记动作回执，Python 返回 200，没有未知动作 404。

### Codex 审查修复

前端合入后发现并以失败测试复现三类问题：

1. `AssistantView` 没有提供弹窗、草稿、资源刷新和聚焦执行器，但 dispatcher 会静默返回 `SUCCEEDED`。
2. 回执上报暂时失败后，本地去重会永久阻止同一持久化 SSE 事件重放时重试回执。
3. `OPEN_MODAL`、`REFRESH_RESOURCE`、`FOCUS_ELEMENT` 接受动作专属 Schema 之外的额外字段。

修复后，缺失执行器会形成 `FAILED` 回执；回执失败可在同一动作重放时只重试上报、不重复界面副作用；三类动作执行前严格拒绝额外字段。

真实浏览器复验又发现 REST 消息响应快照含不带 `actionId` 的动作副本，组件会在 SSE 动作之外再次执行，并向 Python 上报未知动作导致 404。新增 RED 后改为只执行带服务端稳定 `actionId` 的动作；再次真实复验仅产生一次 200 回执。

## 阻断项

冻结指派要求五类动作均可执行。当前生产装配仍只向 `dispatchUiAction` 传入 `{ router }`，没有为 `OPEN_MODAL`、`PREFILL_FORM`、`REFRESH_RESOURCE`、`FOCUS_ELEMENT` 接入具体页面执行器；因此这四类动作只能如实失败，不能完成用户界面操作。

同时，Python `UiAction.type` 仍是开放字符串且默认 `NAVIGATE`，Supervisor 的所有生产动作构造路径均只生成导航动作。四类非导航动作没有服务端生成路径，也没有真实跨端场景证据。前端单元测试中的 mock executor 只能证明 dispatcher 抽象可调用，不能证明产品闭环已实现。

在补齐以下内容之前，不应合并至 `main` 或开始 Task 31：

- 为四类动作建立真实、受注册表约束的 Vue 执行适配器，并接入实际页面/Store；
- 将 Python 动作类型收紧为冻结枚举，并从明确的受控业务场景生成四类动作；
- 为每类动作补一条真实三端场景，核对界面结果、终态回执、重放幂等与用户隔离。

## 验证命令

最终新鲜验证结果以本分支提交前的实际输出为准：

```text
web: 23 files / 171 tests passed; vue-tsc + Vite production build passed
ai-service: 420 passed; Ruff app tests passed
backend: 410 passed; BUILD SUCCESS
capability matrix: 31 pages / 64 Java tools passed
git diff --check: passed
```

仓库根目录执行 `ruff check .` 会扫描不属于 Python 项目配置范围的既有 `scripts/agent-planner-smoke.py` 并报错；项目规定且可复现的命令 `cd ai-service && python -m ruff check app tests` 通过。该误用不作为 Task 30 缺陷或通过依据。
