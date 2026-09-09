# Task 29 验证证据：前端持续流式（Vue 侧）接入

- **执行 Agent**：ZCode (Gemini 3.8 Flash)
- **测试等级**：`[UNIT_TEST]` / `[STATIC_VALIDATION]`
- **执行时间**：2026-09-09 14:35:00 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-29-sse-frontend`
- **关联基础提交**：`da4adfd` (origin/main)

---

## 1. 运行环境与前置状态

- **操作系统**：macOS darwin 25.5.0 arm64
- **Node 环境**：v26.5.0 / npm 11.17.0
- **测试框架**：Vitest v3.2.7 (jsdom 环境)
- **类型检查器**：vue-tsc
- **构建工具**：Vite v7.3.6
- **隔离工作树**：`/Users/moxiao/IdeaProjects/project-zcode-task-29`
- **依赖隔离**：复用主工作区 `node_modules` 软链接至隔离工作树，不污染其他分支。

---

## 2. TDD 闭环证据

### 2.1 失败测试证据 (RED Phase)

#### 2.1.1 SSE 客户端测试失败
- **执行命令**：`cd web && npm test -- tests/assistant-sse.spec.ts`
- **预期失败输出**：
```text
FAIL tests/assistant-sse.spec.ts (6 tests | 6 failed)
× Assistant SSE 事件流解析 (assistantApi.subscribeEvents) > 发送正确的 Accept 与 Authorization 认证头
  → TypeError: assistantApi.subscribeEvents is not a function
× Assistant SSE 事件流解析 (assistantApi.subscribeEvents) > 指定 lastEventId 时，请求头附带 Last-Event-ID
  → TypeError: assistantApi.subscribeEvents is not a function
× Assistant SSE 事件流解析 (assistantApi.subscribeEvents) > 正确解析分片传输（跨 chunk 边界）与包含标准 id/event/data 的事件流
  → TypeError: assistantApi.subscribeEvents is not a function
× Assistant SSE 事件流解析 (assistantApi.subscribeEvents) > 支持结构化 TOOL_*、ACTION_PREVIEW、UI_ACTION 与 TURN_CANCELLED 事件解析
  → TypeError: assistantApi.subscribeEvents is not a function
× Assistant SSE 事件流解析 (assistantApi.subscribeEvents) > 网络中断时支持使用最后接收到的 sequence 发起自动重连
  → TypeError: assistantApi.subscribeEvents is not a function
× Assistant SSE 事件流解析 (assistantApi.subscribeEvents) > 401 未认证响应时清除 Token 并通知错误，不触发无谓重连
  → TypeError: assistantApi.subscribeEvents is not a function
```

#### 2.1.2 AssistantView 组件流式增强测试失败
- **执行命令**：`cd web && npm test -- src/modules/assistant/AssistantView.spec.ts`
- **预期失败输出**：
```text
FAIL src/modules/assistant/AssistantView.spec.ts (9 tests | 6 failed)
✓ creates one conversation, shows public tool steps and dispatches navigation
✓ renders a confirmation card and only confirms through the dedicated api
✓ shows grounded citations but never renders an unsafe source link
× 组件挂载时建立 SSE 订阅，卸载时关闭连接
  → expected "spy" to be called with arguments: [ 'conversation-1', ... ] (Number of calls: 0)
× 通过 ASSISTANT_DELTA 实时增量渲染打字机流式回复
  → expected text to contain '聚簇索引是'
× 实时流式更新工具调用过程 (TOOL_STARTED, TOOL_SUCCEEDED, TOOL_FAILED)
  → expected text to contain 'learning.context.get'
× 支持流式推送 ACTION_PREVIEW 并呈现高风险确认卡片
  → expected text to contain '需要你的确认'
× 通过 UI_ACTION 事件安全分发页面导航
  → expected push to be called with arguments: [ { name: 'roadmap' } ] (Number of calls: 0)
× 支持在发送中点击取消按钮发起轮次中断，并响应 TURN_CANCELLED 事件
  → expected cancel button to exist (false to be true)
```

---

### 2.2 成功测试证据 (GREEN Phase)

#### 2.2.1 局部单元测试
- **执行命令**：`cd web && npm test -- tests/assistant-sse.spec.ts src/modules/assistant/AssistantView.spec.ts`
- **成功输出**：
```text
✓ tests/assistant-sse.spec.ts (6 tests) 506ms
✓ src/modules/assistant/AssistantView.spec.ts (9 tests) 65ms

Test Files  2 passed (2)
     Tests  15 passed (15)
```

#### 2.2.2 全量前端测试套件回归
- **执行命令**：`cd web && npm test -- --run`
- **成功输出**：
```text
Test Files  23 passed (23)
     Tests  136 passed (136)
  Duration  1.98s
```

#### 2.2.3 静态类型检查与生产构建
- **执行命令**：`cd web && npm run typecheck && npm run build`
- **成功输出**：
```text
> vue-tsc --noEmit
> vue-tsc --noEmit && vite build
vite v7.3.6 building client environment for production...
transforming...
✓ 264 modules transformed.
rendering chunks...
computing gzip size...
✓ built in 826ms
```

#### 2.2.4 代码格式与空白字符校验
- **执行命令**：`git diff --check`
- **成功输出**：无任何警告或输出，退出码为 0。

---

## 3. 核心契约与设计实现

1. **带 Authorization 的 fetch ReadableStream SSE 客户端**：
   - 不依赖无法稳定附加 Bearer 头的原生 EventSource；
   - 严格解析 SSE 标准帧，支持跨 chunk 边界拆分与拼接；
   - 自动维护递增序号，通过 `Last-Event-ID` 支持断线续传重连；
   - 遇到 401 响应时立即清除认证 Token，中断流并不再进行无意义重连。
2. **AssistantView 视图状态机与流式聚合**：
   - 会话挂载即建立 SSE 流，卸载即安全关闭；
   - `ASSISTANT_DELTA`：实时拼接打字机增量；
   - `TOOL_STARTED` / `TOOL_SUCCEEDED` / `TOOL_FAILED`：展示实时执行过程与运行中（⋯）/ 成功（✓）/ 失败（✗）状态；
   - `ACTION_PREVIEW`：流式推送高风险动作卡，展示确认/取消操作入口；
   - `UI_ACTION`：接入 `dispatchUiAction` 进行页面导航，严守白名单路由与安全参数，禁止执行未校验脚本或外链；
   - `TURN_STARTED` / `TURN_COMPLETED` / `TURN_CANCELLED`：完整管理轮次生命周期；发送中支持主动触发“取消”按钮调用 `cancelTurn` 接口。

---

## 4. 变更文件清单

- `新增`: `web/tests/assistant-sse.spec.ts`（SSE 解析与断线重连完整测试）
- `修改`: `web/src/types/assistant.ts`（补充 AssistantEvent、AssistantEventType、流式选项类型）
- `修改`: `web/src/services/current/assistant.ts`（实现 `subscribeAssistantEvents`、`cancelTurn`、`subscribeEvents`）
- `修改`: `web/src/modules/assistant/AssistantView.vue`（聚合 SSE 事件流、打字机渲染、工具步骤状态、中断取消）
- `修改`: `web/src/modules/assistant/AssistantView.spec.ts`（新增 6 项流式与中断交互测试，总计 9 项全过）
- `文档`: `docs/verification/task-29-frontend.md`（本验证报告）

---

## 5. 未覆盖项与已知限制

1. 本测试基于 Vitest Mock 网络流与组件集成，真实 Java (8080) + Python (8000) 联调留待 Task 34 真实端到端全链路验收；
2. 保持严格的前后端契约一致性，前端未修改任何 Java (`backend/**`) 或 Python (`ai-service/**`) 代码。
