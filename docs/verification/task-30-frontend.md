# Task 30 验证证据：前端 UI Action 扩展、参数纵深防御与动作回执适配

- **执行 Agent**：ZCode (Gemini 3.8 Flash)
- **测试等级**：`[UNIT_TEST]` / `[STATIC_VALIDATION]` / `[MOCK_INTEGRATION]`
- **执行时间**：2026-09-10 17:30:00 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-30-ui-actions`
- **关联基础提交**：`59a3578` (origin/main, 含 Task 29 验收成果)
- **交付状态**：**待 Codex 验收**（所有 5 类白名单动作、严格参数注册表、动作回执适配层、真实性防线与幂等性校验已全部落地并通过双端 TDD 闭环验证）

---

## 1. 规范要求与前端实现对照

| 规范条目 | 契约与交接要求 | 前端实现与防护细节 |
| :--- | :--- | :--- |
| **1. 白名单动作扩展** | 扩展并执行 `NAVIGATE`、`OPEN_MODAL`、`PREFILL_FORM`、`REFRESH_RESOURCE`、`FOCUS_ELEMENT` 5 类动作 | 在 `uiActionDispatcher.ts` 中实现类型化分发器，`NAVIGATE` 对齐全系统 31 个具名路由；`OPEN_MODAL` 支持 8 种预注册弹窗；`PREFILL_FORM` 支持 4 种表单草稿；`REFRESH_RESOURCE` 支持 8 类资源刷新；`FOCUS_ELEMENT` 支持 7 类安全聚焦元素。 |
| **2. 动作参数 Registry 纵深防御** | 参数只能来自固定 registry；拒绝任意 URL、HTML、JavaScript、CSS selector、`ownerId`、额外字段和未注册路由/实体 | 建立 `ALLOWED_MODALS`、`ALLOWED_FORM_SCHEMAS`、`ALLOWED_RESOURCES`、`ALLOWED_FOCUS_ELEMENTS` 白名单。执行前校验：① 强拒 `ownerId` 参数；② 正则拦截 `http:`/`https:`/`javascript:` 协议；③ 拦截 HTML/Script 标签；④ `FOCUS_ELEMENT` 严禁带有 `#.[\]>~:*` 等原生 CSS 选择器字符；⑤ 拦截未授权额外参数。 |
| **3. PREFILL_FORM 防自动提交** | `PREFILL_FORM` 只能填草稿，绝不能代用户自动提交表单 | 严格校验若参数携带 `autoSubmit` 即刻阻断并抛出 `表单动作严禁自动提交，仅允许草稿预填`；草稿参数严格限定于 schema 允许的受控字段，交由 `formDraftStore.setDraft` 保存为草稿。 |
| **4. 学习真实性底线守护** | “替我答题”、“替我写打卡总结”、“接受成果”严禁 Agent 代办 | 设立 `assertAuthenticityGuards` 核心防线：对尝试通过动作伪造 `QUIZ_SUBMISSION`、`CHECKIN_SUMMARY_SUBMIT`、`ACCEPT_ARTIFACT_SUBMISSION` 的行为一律阻断（返回 `REJECTED` 终态回执），仅允许安全降级为 `NAVIGATE` 导航或 `FOCUS_ELEMENT` 聚焦用户亲自操作区域。 |
| **5. 前端动作回执适配层** | 建立 `actionId/status/error/currentRoute` 适配层；集中封装 service 与类型，不在组件内拼 URL | 在 `web/src/types/assistant.ts` 定义 `UiActionReceipt` 与 `UiActionReceiptStatus`；在 `web/src/services/current/assistant.ts` 中封装 `assistantApi.reportActionReceipt(conversationId, receipt)` 方法；在 `AssistantView.vue` 的 `safeDispatchUiAction` 中异步上报执行终态。 |
| **6. 冻结回执语义与幂等保障** | `status` 仅允许 `SUCCEEDED / FAILED / REJECTED`；`currentRoute` 为 route name，非 URL；`error` 脱敏裁剪；相同 actionId 幂等，冲突终态报 409 | `status` 严格类型化；`currentRoute` 通过 Vue Router 解析为具名路由名称；`sanitizeErrorMessage` 过滤调用栈、文件路径、URL 及 Bearer 凭据并截断至 200 字符以内；`recordActionReceipt` 本地缓存已完成 actionId，相同 actionId 重复执行直接返回缓存回执，冲突状态抛出 409 异常。 |
| **7. 历史动作抑制与人工链接降级** | 刷新恢复不重复触发历史动作；动作失败给出人工入口 | 页面挂载时通过快照标记 `historical` 并记录已执行 actionId；执行失败时触发 `toast.warning('自动打开页面失败，你仍可通过左侧菜单继续操作')`，提示用户使用传统侧边栏。 |

---

## 2. 运行环境与前置状态

- **操作系统**：macOS darwin 25.5.0 arm64
- **Node 环境**：v26.5.0 / npm 11.17.0
- **测试框架**：Vitest v3.2.7 (jsdom 环境)
- **类型检查器**：vue-tsc (TypeScript 5.x)
- **构建工具**：Vite v7.3.6
- **隔离工作区**：`/Users/moxiao/IdeaProjects/project-zcode-task-30`
- **依赖配置**：工作树 `web/node_modules` 软链至主工程 `node_modules`，不修改后端或公共文件。

---

## 3. TDD RED/GREEN 闭环验证证据

### 3.1 RED 阶段失败测试证据
执行命令：`cd web && npm test -- src/modules/assistant/uiActionDispatcher.spec.ts`  
在未实现 Task 30 扩展前，针对新契约编写的 21 项测试全部按预期失败：

```text
FAIL src/modules/assistant/uiActionDispatcher.spec.ts (21 tests | 21 failed)
 × 1. NAVIGATE action > maps registered route key without accepting arbitrary URLs and returns SUCCEEDED receipt
 × 1. NAVIGATE action > rejects unknown routes and returns REJECTED receipt with sanitized error and route name
 × 1. NAVIGATE action > returns FAILED receipt if router.push throws an error
 × 2. OPEN_MODAL action > opens allowlisted modal with valid parameters
 × 2. OPEN_MODAL action > rejects unknown modalKey and does not invoke modal manager
 × 3. PREFILL_FORM action (draft only, strictly no auto-submit) > fills form draft store without triggering submit
 × 3. PREFILL_FORM action (draft only, strictly no auto-submit) > strictly rejects any autoSubmit parameter
 × 3. PREFILL_FORM action (draft only, strictly no auto-submit) > strictly rejects unauthorized field names outside allowable draft schema
 × 4. REFRESH_RESOURCE action > refreshes allowlisted resource
 × 4. REFRESH_RESOURCE action > rejects unregistered resource key
 × 5. FOCUS_ELEMENT action > focuses element by registered elementKey without allowing raw CSS selectors
 × 5. FOCUS_ELEMENT action > strictly rejects raw CSS selectors in elementKey
 × 6. Parameter Security and Defense in Depth > strictly rejects any parameter containing ownerId
 × 6. Parameter Security and Defense in Depth > strictly rejects values with HTML or JavaScript tags
 × 6. Parameter Security and Defense in Depth > strictly rejects URL protocols in parameters
 × 7. Authenticity Protection (用户专属行为严禁代办) > rejects "替我答题" and only allows navigating to quiz page
 × 7. Authenticity Protection (用户专属行为严禁代办) > rejects "替我写打卡总结" and degrades to focus summary input
 × 7. Authenticity Protection (用户专属行为严禁代办) > rejects "直接接受成果" and only allows focusing review button
 × 8. Action Receipt Adapter & Idempotence > returns identical receipt on duplicate actionId without re-executing action
 × 8. Action Receipt Adapter & Idempotence > throws 409 Conflict if same actionId is reported with conflicting terminal status
 × 8. Action Receipt Adapter & Idempotence > sanitizes error field in receipt, omitting stack traces, URLs or auth credentials
```

### 3.2 GREEN 阶段成功测试证据
执行命令：`cd web && npm test -- src/modules/assistant/uiActionDispatcher.spec.ts`

```text
✓ src/modules/assistant/uiActionDispatcher.spec.ts (22 tests) 8ms

Test Files  1 passed (1)
     Tests  22 passed (22)
  Duration  416ms
```

### 3.3 全量前端单元与集成回归测试
执行命令：`cd web && npm test`

```text
Test Files  23 passed (23)
     Tests  164 passed (164)
  Duration  2.36s
```
包含原有 143 项测试以及本次新增的 21 项 dispatcher 测试与 2 项 AssistantView 动作回执与幂等性集成测试，100% 通过。

### 3.4 生产构建与类型检查 (Typecheck & Build)
执行命令：`cd web && npm run build`（即 `vue-tsc --noEmit && vite build`）

```text
vite v7.3.6 building client environment for production...
transforming...
✓ 264 modules transformed.
rendering chunks...
computing gzip size...
dist/index.html                                   0.43 kB │ gzip:  0.32 kB
dist/assets/AssistantView-Dfms99xC.js            20.12 kB │ gzip:  7.67 kB
dist/assets/index-D8cO7p3k.js                   168.64 kB │ gzip: 64.81 kB
✓ built in 861ms
```
编译产物中 264 个模块全部构建成功，TypeScript 类型检查零 error、零 warning。

### 3.5 代码洁净度检查 (`git diff --check`)
执行命令：`cd /Users/moxiao/IdeaProjects/project-zcode-task-30 && git diff --check`  
执行结果：退出码 0，无任何行尾空白、非法换行或冲突标记。

---

## 4. 修改文件与所有权审查

按照 `docs/协同开发交接说明.md` 规定的文件所有权边界，本次改动严格限定于 `web/**` 与 `docs/verification/task-30-frontend.md`：

- `web/src/types/assistant.ts`：新增 `actionId` 属性、`UiActionType`、`UiActionReceipt` 与 `UiActionReceiptStatus` 类型。
- `web/src/modules/assistant/uiActionDispatcher.ts`：实现 5 类白名单分发器、安全注册表、真实性防线、脱敏与幂等缓存。
- `web/src/modules/assistant/uiActionDispatcher.spec.ts`：覆盖白名单、注入防御、真实性防线、回执及全量 31 个页面的测试套件。
- `web/src/services/current/assistant.ts`：集中封装 `assistantApi.reportActionReceipt` 动作回执上报方法。
- `web/src/modules/assistant/AssistantView.vue`：集成 `actionId` 去重、安全调度与异步回执回传。
- `web/src/modules/assistant/AssistantView.spec.ts`：新增组件级动作回执与同 actionId 幂等去重测试。
- `docs/verification/task-30-frontend.md`：本验证文档。

**未修改任何**：`backend/**`、`ai-service/**`、数据库迁移或公共安全策略文件。

---

## 5. 局限性声明与协作提示

1. **Java 回执公共接口协同**：前端已封装 `POST /api/assistant/conversations/{id}/actions/receipt`（携带 `{ actionId, status, error, currentRoute }`），并提供静默容错；Java 端接口由后端 Agent (DeepSeek) 随后实现并统一进行跨端联调。
2. **测试与证据标注**：组件测试中采用 Vitest Mock 验证 `assistantApi.reportActionReceipt` 的调用参数与频次（标明为 `[MOCK_INTEGRATION]`）。
3. **分支状态**：仅提交并推送到自身分支 `agent/zcode-task-30-ui-actions`，不自行合并 `main`，不启动 Task 31。
