# Task 29 验证证据：前端持续流式（Vue 侧）接入与 Codex 二轮审查闭环

- **执行 Agent**：ZCode (Gemini 3.8 Flash)
- **测试等级**：`[UNIT_TEST]` / `[STATIC_VALIDATION]`
- **执行时间**：2026-09-09 15:45:00 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-29-sse-frontend`
- **关联基础提交**：`da4adfd` (origin/main)
- **交付状态**：**待 Codex 二轮验收**（已严格针对 Codex 二轮审查的 activeTurn 水合、单调请求代际防御、终态回复兜底与僵尸增量阻断完成 TDD 修正与回归测试）

---

## 1. Codex 二轮审查意见修正清单

| 审查条目 | 原始缺陷 | 修正与防护实现 |
| :--- | :--- | :--- |
| **1. activeTurn 水合恢复** | `onMounted` 完全忽略了 `snapshot.activeTurn` 与 `activeTurnId`，刷新后丢失正在进行中的用户消息和部分助手回答 | 在建立 SSE 订阅前优先水合 `snapshot.activeTurn`（恢复用户消息、未完成助手消息槽位与 `status='streaming'`），同步恢复 `activeTurnId` 与 `sending=true`，确保后续增量无缝拼接。 |
| **2. 单调请求代际防御 (Monotonic Generation)** | `activeTurnId === turnId \|\| !activeTurnId` 导致滞后的 Turn A 在 Turn B 完成后返回时覆盖 Turn B 的状态 | 引入严格单调请求代际计数器 `latestTurnGeneration` 与 `activeGeneration`，并维护终态轮次集合 `terminalTurns`。仅当 `activeGeneration === turnGeneration && activeTurnId === turnId && !terminalTurns.has(turnId)` 时才允许合并 POST 结果，滞后的 Turn A 绝无法篡改 Turn B 的 pendingAction、工具步骤或导航。 |
| **3. 缺少预先槽位时的终态兜底** | 刷新恰逢 `TURN_COMPLETED` 到达时，若消息流缺少对应槽位则最终回答丢失 | `TURN_COMPLETED` 处理中加入兜底：若未找到已有助手槽位但 payload 携带 reply，立即为该 `eventTurnId` 创建完整的 completed 消息，防止回答丢失。 |
| **4. 迟到增量（僵尸增量）彻底阻断** | 轮次失败或取消后，网络迟到的 `ASSISTANT_DELTA` 会再次追加并复活已失败内容 | 将失败/取消的轮次记入 `terminalTurns`；在 `ASSISTANT_DELTA` 接收前先检查 `terminalTurns.has(eventTurnId)` 与助手消息自身的 `failed/cancelled` 状态，彻底拦截迟到增量。 |

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

## 3. TDD RED/GREEN 闭环证据

### 3.1 RED 阶段失败测试证据
执行命令：`cd web && npm test -- src/modules/assistant/AssistantView.spec.ts`
在未实现 activeTurn 水合与代际防御前，针对 Codex 审查提出的 4 个精确用例全部按预期失败：
```text
FAIL src/modules/assistant/AssistantView.spec.ts (15 tests | 4 failed)
× 在建立 SSE 订阅前水合 snapshot.activeTurn，恢复出站消息、半成品槽位与发送中状态
  → expected text to contain '正在进行中的复杂问题'
× 陈旧 POST 竞态防御：滞后的 Turn A 响应在 Turn B 完成后返回，绝不可覆盖 Turn B 的 pendingAction、工具步骤或导航
  → expected text to contain 'Turn B 修改目标'
× 在无预先槽位时收到 TURN_COMPLETED（如刷新恰逢终态），能正确恢复最终回复至消息流
  → expected text to contain '在缺少前序槽位时依然恢复的最终回答。'
× 轮次失败或取消后，迟到的 ASSISTANT_DELTA 绝不能复活或篡改已失败的内容
  → expected text not to contain '迟到的恶意/僵尸增量'
```

### 3.2 GREEN 阶段成功测试证据
执行命令：`cd web && npm test -- src/modules/assistant/AssistantView.spec.ts`
```text
✓ src/modules/assistant/AssistantView.spec.ts (15 tests) 71ms
Test Files  1 passed (1)
     Tests  15 passed (15)
```

### 3.3 全量前端测试套件
执行命令：`cd web && npm test -- --run`
```text
Test Files  23 passed (23)
     Tests  142 passed (142)
  Duration  1.87s
```

### 3.4 静态类型检查与生产构建
执行命令：`cd web && npm run typecheck && npm run build`
```text
> vue-tsc --noEmit
> vue-tsc --noEmit && vite build
vite v7.3.6 building client environment for production...
transforming...
✓ 264 modules transformed.
rendering chunks...
computing gzip size...
✓ built in 811ms
```

### 3.5 代码格式与空白字符校验
执行命令：`git diff --check`
结果：无任何警告或输出，退出码为 0。

---

## 4. 变更文件清单

- `修改`: `web/src/types/assistant.ts`（新增 `AssistantActiveTurn` 契约定义与字段扩展）
- `修改`: `web/src/modules/assistant/AssistantView.vue`（实现 activeTurn 水合、单调请求代际防御、终态回复兜底与僵尸增量拦截）
- `修改`: `web/src/modules/assistant/AssistantView.spec.ts`（补充 4 项精确竞态与恢复边界测试，测试总数增至 15 项）
- `修改`: `docs/verification/task-29-frontend.md`（更新二轮验证报告）
