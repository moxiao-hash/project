# Task 29 验证证据：前端持续流式（Vue 侧）接入与 Codex 审查闭环

- **执行 Agent**：ZCode (Gemini 3.8 Flash)
- **测试等级**：`[UNIT_TEST]` / `[STATIC_VALIDATION]`
- **执行时间**：2026-09-09 15:30:00 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-29-sse-frontend`
- **关联基础提交**：`da4adfd` (origin/main)
- **交付状态**：**待 Codex 二轮验收**（已针对一轮审查全部 6 项问题完成 TDD 修正与回归测试，未碰触任何后端代码）

---

## 1. Codex 一轮审查意见修正清单

依据 Codex 对提交 `19fafa3` 的审查要求，逐项完成 TDD 改造与回归防护：

| 审查条目 | 原始缺陷 | 修正与防护实现 |
| :--- | :--- | :--- |
| **1. 初始游标与历史动作** | `startSubscription` 从 0 启动重放导致历史 UI 动作重复触发 | 挂载时优先从 `snapshot.lastEventSequence` 或 `sessionStorage` 游标启动；全量历史中的 `uiActions` 标记为 `historical`，防止重载时误触发自动导航。 |
| **2. Turn 隔离与即时出站** | `ASSISTANT_DELTA` 未校验 `turnId`，发送时出站用户消息直到 POST 返回才上屏 | 用户点击发送时，出站消息与助手流式占位槽立即上屏；`ASSISTANT_DELTA` 严格校验并隔离匹配 `activeTurnId`，防止异轮或陈旧流式数据串入；防陈旧 POST 响应覆盖较新轮次。 |
| **3. 半成品回答清理** | 轮次失败或取消时，未完成的残片依然残留充当完成回答 | 接收 `TURN_FAILED` 时抛弃半成品文字并置为明确失败文案；接收 `TURN_CANCELLED` 时置为取消文案；状态分别更新为 `failed` 与 `cancelled`。 |
| **4. UI Action 去重策略** | 原去重按路由键全局锁定，后续轮次无法再次访问同一路由 | 改为按 `turnId + action.type + routeKey + params` 复合键去重；同轮内重复事件拦截，两个不同独立新轮次均可正常导航至同一路由。 |
| **5. 连接状态与退避重连** | 连接/心跳在 UI 上完全不可见，缺少退避上限 | 增加 `EventStreamStatus` (`connecting` / `connected` / `reconnecting` / `disconnected`)，在 UI 胶囊呈现；实现上限 10s、最大尝试 10 次的有界指数退避重连。 |
| **6. 严格帧校验与 401 共享清理** | 未校验畸形 JSON、未知事件类型、类型与会话不符，401 仅清理 Storage | 帧校验严格检查类型白名单、JSON 合法性、`event` 与 `payload.type` 一致性、`conversationId` 匹配以及 `id` 与 `sequence` 一致性，全部通过才推进游标；401 统一调用共享 `handleUnauthorized` 回调；加载会话非 404 错误严禁静默覆盖新建会话。 |

---

## 2. 运行环境与前置状态

- **操作系统**：macOS darwin 25.5.0 arm64
- **Node 环境**：v26.5.0 / npm 11.17.0
- **测试框架**：Vitest v3.2.7 (jsdom 环境)
- **类型检查器**：vue-tsc
- **构建工具**：Vite v7.3.6
- **隔离工作树**：`/Users/moxiao/IdeaProjects/project-zcode-task-29`
- **依赖隔离**：复用主工作区 `node_modules` 软链接至隔离工作树，无外部网络依赖，未修改任何后端代码。

---

## 3. 测试与验证事实 (GREEN Phase)

### 3.1 单元与组件回归测试
- **执行命令**：`cd web && npm test -- tests/assistant-sse.spec.ts src/modules/assistant/AssistantView.spec.ts`
- **成功输出**：
```text
✓ tests/assistant-sse.spec.ts (6 tests) 526ms
✓ src/modules/assistant/AssistantView.spec.ts (11 tests) 65ms

Test Files  2 passed (2)
     Tests  17 passed (17)
```

### 3.2 前端全量测试套件
- **执行命令**：`cd web && npm test -- --run`
- **成功输出**：
```text
Test Files  23 passed (23)
     Tests  138 passed (138)
  Duration  2.38s
```

### 3.3 静态类型检查与生产构建
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
✓ built in 894ms
```

### 3.4 代码格式与空白字符校验
- **执行命令**：`git diff --check`
- **校验结果**：无任何残余空白字符或格式告警，退出码为 0。

---

## 4. 变更文件清单

- `修改`: `web/src/types/assistant.ts`（扩展 `EventStreamStatus`、`AssistantMessage.turnId/status`、`AssistantConversation.lastEventSequence/activeTurnId`）
- `修改`: `web/src/services/http.ts`（导出共享 `handleUnauthorized()` 清理与认证回调通知）
- `修改`: `web/src/services/current/assistant.ts`（严格帧校验白名单、有界退避重连、连接状态流转、调用共享 401 清理）
- `修改`: `web/src/modules/assistant/AssistantView.vue`（即时出站渲染、Turn 作用域增量、半成品清理、复合去重导航、历史重载抑制、连接状态胶囊呈现）
- `修改`: `web/tests/assistant-sse.spec.ts`（补充畸形 JSON 拒绝、未知事件拒绝、不匹配拒绝、状态转换与共享 401 回调测试）
- `修改`: `web/src/modules/assistant/AssistantView.spec.ts`（扩充至 11 项用例，覆盖出站即时显示、跨轮次同路由导航、历史抑制、非 404 异常防御及状态指示）
- `修改`: `docs/verification/task-29-frontend.md`（更新本验证文档）
