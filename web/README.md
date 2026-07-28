# StudyPilot Web

Vue 3 + TypeScript + Vite + Pinia + Vue Router + Axios 前端。

## 环境要求

- Node.js ≥ 20.19（开发使用 v26 验证）
- npm ≥ 10

## 安装与启动

```bash
cd web
npm install
cp .env.example .env.development   # 或直接使用已提交的 .env.development
npm run dev                        # http://localhost:5173
```

其它命令：

```bash
npm run test        # vitest 单元测试（18 个用例）
npm run typecheck   # vue-tsc 类型检查
npm run build       # 类型检查 + 生产构建
```

## 环境变量

只允许 Java 公共地址等非敏感变量，**禁止**出现 Python 地址、内部令牌或模型 Key：

| 变量 | 取值 | 说明 |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` | Java 公共 API 地址，浏览器仅访问其 `/api/**` |
| `VITE_AGENT_GATEWAY` | `mock`（默认）/ `http` | 阶段 8 Agent 门面实现切换 |

启动顺序：MySQL → Java（8080）→ Python（8000，可选）→ Vue（5173）。
只开发 CRUD 页面时无需启动 Python。

## 目录结构

```text
src/
├── app/            入口、路由（含 401/登录守卫）、App
├── modules/        页面（auth/dashboard/learning/materials/assessment/agent/settings/notifications）
├── services/
│   ├── http.ts     Axios 实例：Bearer 注入、401 统一清理、错误文案
│   ├── current/    ✅ 已公开的 Java /api/** 封装（页面只调用这里）
│   └── planned/    🟨 阶段 8 Agent 门面：AgentGateway 接口 + Mock/Http 双实现
├── stores/         auth（sessionStorage 会话）、toast
├── components/     AppShell、ConfirmDialog、CitationCard、TaskList、EmptyState 等
├── composables/    usePolling（退避、后台降频、卸载清理）
├── types/          api.ts（当前契约）、agent.ts（阶段 8 契约）
└── utils/          枚举→中文映射、日期格式化
```

## 路由表

| 路由 | 页面 | 状态 |
|---|---|---|
| `/login`、`/register` | 登录 / 注册 | ✅ 真实联调 |
| `/` | 工作台 | ✅ |
| `/goals` | 学习目标（增/列表/编辑） | ✅ |
| `/plans`、`/plans/:id` | 计划列表、确认、详情（任务、版本历史、自适应调整） | ✅（调整为 Mock） |
| `/today` | 今日任务打卡（完成/跳过/延期/历史） | ✅ |
| `/materials`、`/materials/:id` | 资料导入（文本/网页/文件）、解析状态轮询 | ✅ |
| `/quizzes/:id` | 测验作答（幂等提交） | ✅（需已知测验 ID） |
| `/attempts/:id` | 异步评分轮询、结果、自评 | ✅ |
| `/mastery` | 掌握度分量展示 | ✅ |
| `/notifications` | 通知与已读 | ✅ |
| `/activity` | 执行记录（确认）、授权、审计 | ✅ |
| `/settings` | 学习设置 | ✅ |
| `/knowledge` | RAG 知识问答（引用、导入网页） | 🟨 Mock |
| `/agent/plan` | 对话生成计划（DRAFT_READY 草案 + 专用保存按钮） | 🟨 Mock |
| `/agent/tasks` | 对话式任务操作（PREVIEW_READY 预览 + 确认） | 🟨 Mock |
| `/settings/ai` | DeepSeek / Tavily Key 管理 | 🟨 Mock |
| 从任务生成测验 | 任务行「🧪 生成测验」按钮 | 🟨 Mock |

## Mock / HTTP Gateway 切换

页面只依赖 `types/agent.ts` 的 `AgentGateway` 接口，不感知实现：

- `VITE_AGENT_GATEWAY=mock`（默认）：`MockAgentGateway`，内存状态机，
  严格遵守真实流程（草稿必须先 `DRAFT_READY`/`PREVIEW_READY`，只有专用
  confirm 方法推进写操作）。Mock 页面顶部有醒目的 Mock 横幅。
- `VITE_AGENT_GATEWAY=http`：`HttpAgentGateway`，调用未来的
  `/api/agent/**` 与 `/api/ai-settings`。Java 门面就绪后仅需改环境变量。

**对说明书 §7.7 接口的扩展**（页面必需，已在接口中声明）：
`getPlanAdjustment`、`confirmPlanAdjustment`（§7.4 的轮询与确认端点）、
`importWebResult`（§5.4 已公开的真实接口，经门面转发）、
`getAiSettings` / Key 的增删（§7.6）。

## 安全基线

- 浏览器仅调用 Java `/api/**`；代码中不存在任何 `/internal/**`、内部令牌、模型 Key。
- Bearer Token 存 `sessionStorage`；401 由响应拦截器统一清理并回登录页。
- 恢复会话必须调 `/api/auth/me`，不信任本地缓存。
- AI Key 仅保留输入框临时值，提交成功即清空，不进 Pinia/LocalStorage。
- 外部链接一律 `rel="noopener noreferrer"` 新窗口打开。

## 交互边界

- 所有写操作（保存计划、任务操作、计划调整、执行确认）使用专用确认按钮 +
  专用接口；聊天中的“确认”只是一条消息。
- 测验提交使用 `quiz-attempt:{quizId}:{uuid}` 幂等键，同一轮重试复用。
- 409 不自动重试：提示后刷新服务端数据。
- 轮询（资料解析 3s、评分 2s、调整分析 2s）带错误退避（上限 5 次）、
  后台降频 3 倍、离开页面自动清理。

## 待联调清单（阶段 8 Java 门面就绪后）

1. `POST/GET /api/agent/plan-conversations/**`（创建、消息、confirm）
2. `POST/GET /api/agent/task-conversations/**`（创建、消息、confirm）
3. `POST/GET /api/agent/knowledge-conversations/**`
4. `POST /api/agent/plan-adjustments/analyze`、`GET/POST /api/agent/plan-adjustments/{id}(/confirm)`
5. `POST /api/agent/quizzes/generate`（真实 quizId 返回后自动跳转作答页）
6. `GET /api/ai-settings` 与 DeepSeek/Tavily Key 的 PUT/DELETE
7. Mock 模式下「生成测验」只提示不落库；http 模式下需验证跳转链路

切换方式：`.env.development` 中 `VITE_AGENT_GATEWAY=http`。
