# StudyPilot AI Service

FastAPI AI 服务负责模型调用、Agent 工作流和检索编排。用户、授权、学习计划和审计
等业务事实仍由 Spring Boot 管理；Python 不直接写入 MySQL。

## 当前能力

- 独立的 FastAPI 健康检查。
- 从环境变量或 `.env` 安全加载模型配置。
- 使用内部服务令牌保护 `/internal/**` 接口。
- 创建 DeepSeek OpenAI 兼容的 LangChain 对话模型。
- 通过类型化 HTTPX 客户端读取 Java 学习上下文和调用受控写工具。
- 使用 LangGraph 保存同一 `conversationId` 的多轮上下文。
- 使用 Pydantic 约束模型输出，生成可编辑的学习计划与任务草稿。
- 在用户显式确认前暂停工作流；确认后由 Java 原子保存计划和任务并记录审计。
- 异步解析 TXT、Markdown、DOCX、可复制文本的 PDF 和安全公网网页。
- 使用 FastEmbed 与本地 Qdrant 完成 dense + BM25 + RRF 混合检索。
- 使用 Tavily 搜索时效资料，返回可追溯来源；搜索结果需确认后才进入资料库。
- 提供多轮知识问答，并用大纲、资料和网页证据增强学习计划。
- `SENSITIVE`、`LOCAL_ONLY` 正文不会发送给 DeepSeek 或 Tavily。
- 从真实学习任务生成固定五题的自适应测验，并保存逐题来源。
- 异步租约 Worker 只评估代码文本，绝不编译或运行用户代码。
- 自动化测试不会调用付费模型 API。

当前只处理文本；扫描 PDF、图片理解和 OCR 暂不实现。

## 1. 准备 Python

项目固定使用 Python 3.12。macOS Homebrew 安装命令：

```bash
brew install python@3.12
```

在 `ai-service` 目录创建虚拟环境并安装依赖：

```bash
/opt/homebrew/bin/python3.12 -m venv .venv
.venv/bin/python -m pip install -e '.[dev]'
```

IDEA/PyCharm 的 Python Interpreter 可以选择：

```text
项目目录/ai-service/.venv/bin/python
```

## 2. 配置环境变量

复制配置模板：

```bash
cp .env.example .env
```

编辑 `.env`：

```env
MODEL_PROVIDER=deepseek
MODEL_BASE_URL=https://api.deepseek.com
MODEL_NAME=deepseek-v4-pro
DEEPSEEK_API_KEY=填写你自己的Key

JAVA_BACKEND_BASE_URL=http://localhost:8080
INTERNAL_SERVICE_TOKEN=local-dev-internal-token
TAVILY_API_KEY=填写你自己的Key
QDRANT_PATH=./data/qdrant
AGENT_STATE_DB_PATH=./data/agent-state.sqlite3
LANGGRAPH_AES_KEY=使用 openssl rand -base64 32 生成
AGENT_WORKER_COUNT=1
```

Java 启动时的 `INTERNAL_SERVICE_TOKEN` 必须与 Python 相同。`.env` 已被根目录
`.gitignore` 排除，不能使用 `git add -f` 强制提交。

## 3. 启动

先启动 Java 后端（8080），再启动 Python 服务：

```bash
cd ai-service
.venv/bin/python -m uvicorn app.main:app --reload --port 8000
```

健康检查：

```bash
curl http://localhost:8000/health
```

预期：

```json
{"status":"UP","service":"studypilot-ai"}
```

检查模型配置状态：

```bash
curl \
  -H "X-Internal-Service-Token: local-dev-internal-token" \
  http://localhost:8000/internal/model/status
```

状态响应只包含 `configured`，不会返回 API Key。

## 4. 学习计划会话

内部会话 API 共四个：

```text
POST /internal/agent/conversations
POST /internal/agent/conversations/{conversationId}/messages
GET  /internal/agent/conversations/{conversationId}
POST /internal/agent/conversations/{conversationId}/confirm
```

创建会话时传入 Java 用户 ID 和该用户已有的学习目标 ID。发送消息可以多轮补充或
修改要求；返回 `DRAFT_READY` 时，前端应显示 `draft` 表单。只有调用独立的
`confirm` 接口后，Python 才会请求 Java 写入已确认计划和任务。

完整请求顺序见 [`../docs/agent-api-examples.http`](../docs/agent-api-examples.http)，
可以直接在 IntelliJ IDEA HTTP Client 中逐条运行。

学习计划与任务图使用加密的 `AsyncSqliteSaver`，知识会话及三类会话快照也保存在同一
SQLite 文件中，因此进程重启后可以按 `conversationId` 恢复。磁盘上不保存用户消息、
草稿、回答或 owner ID 明文。`LANGGRAPH_AES_KEY` 是 Base64 编码的 32 字节主密钥，
丢失后旧会话不可恢复，必须像数据库密钥一样备份且不得提交 Git。

当前 SQLite 与本地 Qdrant 部署仅支持一个 FastAPI worker。请保持 Uvicorn 默认的单
worker 启动方式；多进程和横向扩容将在改用共享数据库 checkpointer 后支持。

## 5. 资料处理与知识问答

Java 把导入资料写入本地存储和 MySQL 任务表；FastAPI 默认每 10 秒领取一个任务，
完成解析、分段、摘要和本地向量化。首次运行 FastEmbed 会下载约 220 MB 模型。
本地 Qdrant 采用单进程共享客户端，数据目录不会提交 Git。

知识会话 API：

```text
POST /internal/knowledge/conversations
POST /internal/knowledge/conversations/{conversationId}/messages
GET  /internal/knowledge/conversations/{conversationId}
```

`webSearch` 可取 `AUTO`、`ENABLED`、`DISABLED`。Tavily Key 缺失或调用失败时接口
不会伪造联网结果，而是在 `warnings` 中说明降级。完整联调顺序见
[`../docs/material-rag-e2e.http`](../docs/material-rag-e2e.http)。

## 6. 自适应测验与代码文本评估

测验通过 `POST /internal/assessment/quizzes/generate` 由用户主动触发。题型比例由最低
相关掌握度确定。编程题提交后，Java 返回 `EVALUATING`，FastAPI 定时领取持久化
任务，并按 40/25/20/15 固定 Rubric 写回结果。

代码评分标记为 `AI_EVALUATED`，始终附带“未执行代码”的警告；它是学习反馈，不
等同于编译器、测试沙箱或人工代码审查。完整联调顺序见
[`../docs/quiz-mastery-e2e.http`](../docs/quiz-mastery-e2e.http)。

## 7. 测试与代码检查

```bash
.venv/bin/python -m ruff format --check app tests
.venv/bin/python -m pytest -q
.venv/bin/python -m ruff check app tests
```

这些自动化测试注入 Fake Planner，不会访问 DeepSeek。手动发送真实会话消息才会产生
模型 API 调用和费用。

## 代码阅读顺序

1. `app/main.py`：FastAPI 如何组装。
2. `app/core/settings.py`：环境变量如何变成类型安全的配置。
3. `app/core/security.py`：Java/Python 内部认证。
4. `app/providers/model_factory.py`：为什么 DeepSeek 可以使用 OpenAI 兼容客户端。
5. `app/agent/models.py`：如何校验不可信的模型输出。
6. `app/agent/planner.py` 与 `app/prompts/learning_plan.py`：结构化 DeepSeek 调用。
7. `app/agent/graph.py`：多轮规划、暂停、修改、确认和落库节点。
8. `app/agent/service.py`：会话 ID、上下文隔离和并发控制。
9. `app/api/conversations.py`：HTTP 接口和异常映射。
10. `app/clients/java_backend.py`：Python 如何通过受控接口操作 Java 业务能力。
11. `app/material/`：各格式解析、分段、隐私路由与任务处理。
12. `app/retrieval/`：FastEmbed、Qdrant、owner 过滤和 RRF 混合检索。
13. `app/search/`：Tavily、来源持久化和安全网页抓取。
14. `app/knowledge/`：多轮问答、联网策略、引用与隐私降级。
