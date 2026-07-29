# StudyPilot 阶段 8 发布验收结果

> 执行日期：2026-07-29
> 环境：macOS，本机 MySQL，Spring Boot `18080`，FastAPI `18000`

## 1. 真实端到端结果

本次使用随机临时账户和临时服务端密钥，通过 Java 公共 `/api/**` 完成验收。浏览器
侧请求未携带 `ownerId` 或内部服务令牌。

| 验收项 | 结果 |
|---|---|
| Java、FastAPI 健康检查 | `UP` |
| 注册/登录、目标创建 | 通过 |
| 文本大纲导入 | 3 次轮询后进入 `READY` |
| AI 设置状态 | `SERVER_DEFAULT`，`configured=true`，不返回明文 Key |
| 模型身份 | `provider=deepseek`，`model=deepseek-v4-pro` |
| 资料知识问答 | 返回有效回答和 2 条资料引用 |
| 计划多轮对话 | 明确日期后生成 `DRAFT_READY`，共 9 项任务 |
| FastAPI 重启恢复 | 同一计划会话恢复为 `DRAFT_READY`，9 项草稿保持不变 |
| 计划确认幂等 | 首次保存成功；重复确认返回同一 `savedPlanId`，任务仍为 9 条 |
| 任务 Agent | `TODO/version 1` → 预览不写入 → 确认后 `COMPLETED/version 2` |
| 任务重复确认 | version 不再增长，任务变更历史只有 1 条 |
| 自适应测验 | 经公共门面生成 5 题；作答前响应不含正确答案或参考实现 |

计划模型在信息不足时连续追问具体开始日期；收到 `2026-07-30` 后才生成草稿，验证了
多轮上下文而非无状态单次调用。重启时保持相同 `AGENT_STATE_DB_PATH` 和
`LANGGRAPH_AES_KEY`，证明加密 SQLite 会话持久化有效。

## 2. 联调中发现并修复的问题

1. JDK 26 `HttpClient` 默认尝试明文 HTTP/2 升级，Uvicorn 拒绝后 Java 收到非 JSON
   响应。Agent 门面客户端现固定使用 HTTP/1.1，并增加回归测试。
2. 日志脱敏过滤器曾把 Uvicorn access record 的五元组展平，导致访问日志格式化异常。
   过滤器现保留结构并逐项脱敏，真实 `/health` 请求的访问日志恢复正常。
3. 首次启动下载的 FastEmbed 模型缓存此前会显示为未跟踪文件；现已加入 `.gitignore`。
4. README 中 Vue 类型检查命令已从不存在的 `type-check` 修正为 `typecheck`。

## 3. 自动化验证

```text
Java:   99 tests passed
Python: 215 tests passed, Ruff passed
Vue:    52 tests passed, vue-tsc passed, production build passed
Shell:  demo-data static contract passed
```

Python 测试仍有一条第三方 Starlette 生命周期弃用警告；Vue 测试仍有 Node
`localStorage` 实验警告和一条测试路由提示，均不影响通过结果。

## 4. 环境限制

- 本机没有 Docker CLI，因此 Compose 只完成静态契约验证，没有声称容器真实启动成功。
- 本地 Qdrant 与加密 SQLite 采用单 FastAPI 进程；横向扩容需要迁移到共享服务。
- 当前不包含 SSE、OCR、Ollama、多人协作和不可信代码执行。
- 本次隔离 Java/FastAPI 服务验收后均已关闭，未占用日常开发端口。
