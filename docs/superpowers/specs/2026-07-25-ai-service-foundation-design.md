# AI Service 基础模块设计

## 1. 目标

本迭代建立可独立启动、可测试的 Python AI 服务基础，为下一迭代的学习计划 Agent 工作流提供稳定边界。范围只包含服务骨架、安全配置、模型适配和 Java 内部接口客户端，不包含图片理解、OCR、联网检索、RAG 或正式的计划生成工作流。

## 2. 架构边界

```text
Vue（后续）
  │
  ▼
Spring Boot：登录、授权、业务数据、审计
  │  X-Internal-Service-Token
  ▼
FastAPI：Agent 编排、模型调用、检索
  │
  ├── DeepSeek OpenAI 兼容 API（默认）
  └── Ollama（后续备用）
```

前端不会直接访问模型，也不会直接向 Python 传递长期 API Key。Python 不直连 MySQL，只能通过 Java 暴露的 `/internal/**` 接口读取学习上下文或请求业务操作。

## 3. 本迭代组件

### FastAPI 应用

- `GET /health`：进程级健康检查，不依赖模型 Key。
- `GET /internal/model/status`：返回 Provider、模型名和是否已配置 Key，不返回 Key 内容。
- `/internal/**` 使用 `X-Internal-Service-Token` 校验调用方。

### 配置

配置由 `pydantic-settings` 从环境变量和本地 `.env` 读取：

- `DEEPSEEK_API_KEY`
- `MODEL_PROVIDER`
- `MODEL_BASE_URL`
- `MODEL_NAME`
- `JAVA_BACKEND_BASE_URL`
- `INTERNAL_SERVICE_TOKEN`

`.env` 永远不提交，仓库只保存 `.env.example`。

### 模型适配

使用一个小型工厂创建 LangChain `ChatOpenAI` 客户端。DeepSeek 官方 API 兼容 OpenAI 格式，因此模型名称、Base URL 和 Key 全部配置化。此迭代只验证客户端构造，不在自动化测试中调用付费 API。

### Java 客户端

使用 `httpx.AsyncClient` 调用 Java：

- `GET /internal/users/{ownerId}/learning-context`
- `POST /internal/learning-plans`

客户端统一添加内部服务令牌，并把超时、连接失败和非 2xx 响应转换为清晰的领域异常。

## 4. 错误与安全

- 缺少 DeepSeek Key 不阻止服务启动；健康检查仍可用于排障。
- 真正创建模型客户端时若 Key 缺失，返回明确配置错误。
- 状态接口只能显示 `configured: true/false`，不能返回或记录 Key。
- Java 内部接口令牌使用常量时间比较。
- 生产日志不打印请求头、完整 Prompt、访问令牌或模型 Key。

## 5. 测试策略

- FastAPI `TestClient` 验证健康检查、内部令牌和脱敏状态。
- 单元测试验证配置别名与模型工厂参数。
- `httpx.MockTransport` 验证 Java 客户端的路径、请求体和内部请求头。
- 每个生产行为严格经过一次 RED → GREEN。

## 6. 后续迭代

下一迭代在本基础上实现第一条 LangGraph 纵向闭环：

```text
读取学习上下文 → 生成结构化计划 → 校验日期与工作量
→ 写回 Java 的待确认计划 → 用户确认后生效
```

资料解析、联网搜索、引用合并和 Ollama 回退在该闭环稳定后逐步加入。

