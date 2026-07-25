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
- 自动化测试不会调用付费模型 API。

当前只处理文本。普通 TXT、Markdown、网页正文、可复制文字的 PDF 和 Word 会在后续
资料解析迭代接入；扫描 PDF、图片理解和 OCR 暂不实现。

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

当前使用 `InMemorySaver` 保存上下文，因此 Python 进程重启后会话失效。这是第一版
有意保留的限制；后续可以换成数据库 Checkpointer，而不改变会话 API。

## 5. 测试与代码检查

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
