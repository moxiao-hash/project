# StudyPilot 阶段 8：前端联调、持久化与本地发布实施计划

> 本清单用于阶段 8 的逐模块 TDD 实施。每项生产代码都必须由失败测试驱动，完成后执行模块测试、全量验证、精确提交并推送 `origin/main`。

## 目标

让 Vue 只能通过已认证的 Java 公共 API 使用真实 Agent；安全保存每用户模型凭据；让 Agent 会话可在重启后恢复；最后交付可一键启动、可演示、可写入简历的本地完整应用。

## 固定边界

- Vue 只访问 Java `/api/**`，绝不访问 Python `/internal/**`。
- Java 从登录身份注入 `ownerId`，浏览器不能指定或覆盖用户身份。
- Java/MySQL 是核心业务事实源；Python 负责模型、检索和 Agent 编排。
- 普通响应继续使用 JSON，本阶段不引入 SSE。
- `application.properties` 与 `docs/agent-api-examples.http` 的现有用户修改不纳入阶段提交。
- 每个 8.x 模块独立提交，最终推送 `origin/main`。

## 8.1 Java 认证 Agent 公共门面

1. 先编写失败测试：
   - 未登录访问公共 Agent API 返回 401。
   - 请求体中的身份字段不能覆盖当前用户。
   - Java 正确注入当前用户并携带内部令牌调用 Python。
   - Python 会话读取、消息和确认均校验 `ownerId`。
   - Python 的 400/404/409/422/429/502/503/504 被稳定映射。
   - Agent 请求使用独立 120 秒超时，模型写操作不自动重试。
2. 实现公共接口：
   - `/api/agent/plan-conversations/**`
   - `/api/agent/task-conversations/**`
   - `/api/agent/knowledge-conversations/**`
   - `/api/agent/plan-adjustments/**`
   - `/api/agent/quizzes/generate`
3. 仅返回前端需要的会话快照，不暴露内部令牌或跨用户数据。
4. 运行 Java/Python 相关测试和格式检查。
5. 提交：`feat: expose authenticated agent facade`

## 8.2 Vue 真实 Agent 工作流

1. 先为 HTTP gateway、会话恢复、草稿修改、错误展示和模型身份编写失败测试。
2. 默认使用真实 Java gateway；Mock 仅供测试或显式离线模式。
3. 接通学习计划、每日任务、知识问答、计划调整和测验生成。
4. 会话 ID 写入 URL 查询参数，页面刷新后通过 GET 恢复。
5. `DRAFT_READY` 用结构化表单微调；修改内容转成明确的自然语言修订消息。
6. 知识回答展示真实 provider/model、引用和降级警告；没有证据时不得伪造引用。
7. Agent 调用单独使用 120 秒超时。
8. 运行 Vitest、类型检查和生产构建。
9. 提交：`feat: connect Vue to live agent workflows`

## 8.3 每用户加密 AI 凭据

1. 先编写数据库隔离、AES-GCM、脱敏、审计和回退顺序的失败测试。
2. 新增：
   - `GET /api/ai-settings`
   - `PUT/DELETE /api/ai-settings/deepseek-key`
   - `PUT/DELETE /api/ai-settings/tavily-key`
3. 使用 MySQL 保存 `ownerId + provider` 唯一凭据；随机 IV、AES-GCM、AAD 为用户与 provider，主密钥来自 `AI_CREDENTIAL_MASTER_KEY`。
4. 响应只返回配置状态、`USER/SERVER_DEFAULT/NONE` 和脱敏尾号。
5. Python 通过内部认证按用户运行时读取凭据；用户凭据优先，开发环境变量仅作回退。
6. 更新和删除写审计日志，任何日志、响应和测试快照都不得含明文 Key。
7. 提交：`feat: secure per-user AI credentials`

## 8.4 Agent 会话与 Checkpoint 持久化

1. 先编写进程重启恢复、所有者隔离、锁重建和加密存储失败测试。
2. 引入 `langgraph-checkpoint-sqlite` 与 `AsyncSqliteSaver`。
3. 使用 `LANGGRAPH_AES_KEY` 加密 checkpoint；同一 SQLite 保存计划、任务、知识会话元数据、快照和消息历史。
4. 以 `conversationId + ownerId` 恢复会话，进程启动时重建会话锁。
5. 保持 FastAPI 单进程约束，并在启动时显式校验配置。
6. 提交：`feat: persist agent conversations and checkpoints`

## 8.5 本地 Compose 与工程治理

1. 先为限流、关联 ID、脱敏日志和健康检查编写失败测试。
2. 补充 Python、Vue/Nginx Dockerfile，扩展 Compose：
   - MySQL
   - Spring Boot
   - FastAPI
   - Vue/Nginx
   - 可选 Prometheus
3. Nginx 同源转发 `/api` 到 Java；Python 内部端口不暴露给浏览器。
4. 持久化 MySQL、SQLite、Qdrant 和模型缓存。
5. 增加请求关联 ID、模型耗时/成功失败指标、敏感字段脱敏。
6. 限流：Agent 30 次/分钟/用户，模型消息 10 次/分钟/用户；429 返回 `Retry-After`。
7. 提交：`chore: package full local deployment stack`

## 8.6 端到端发布验证与文档

1. 新增独立阶段 8 联调脚本和演示数据脚本。
2. 真实验证：
   - 注册、登录与 AI Key 配置/服务器回退。
   - 模型身份问答。
   - 资料导入、带引用知识问答。
   - 计划生成、表单修订和确认保存。
   - 任务预览、逐次确认和幂等执行。
   - 测验生成与评分闭环。
   - 重启 FastAPI 后恢复会话。
3. 更新 `项目开发步骤.md`、README、架构/部署/演示文档和 5 分钟演示路线。
4. 执行最终验证：
   - Java Maven 全量测试。
   - Python pytest 全量测试与 Ruff。
   - Vue Vitest、类型检查与生产构建。
   - `git diff --check`。
   - Docker Compose 配置检查及真实服务联调。
5. 提交：`docs: complete studypilot stage eight release`

## 完成定义

- 所有阶段 8 公共接口均受 Java 登录认证和用户隔离保护。
- 浏览器真实调用 DeepSeek/Tavily 的路径只能是 Vue → Java → Python。
- Key 不在浏览器、响应、日志或 Git 中出现。
- Agent 会话在 FastAPI 重启后仍可按当前用户恢复。
- 本地完整栈可按文档启动并走通主要学习闭环。
- 所有全量检查在最终提交后重新执行并通过。
