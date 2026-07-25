# StudyPilot AI Service

FastAPI AI 服务负责模型调用、Agent 工作流和检索编排。用户、授权、学习计划和审计
等业务事实仍由 Spring Boot 管理；Python 不直接写入 MySQL。

## 当前能力

- 独立的 FastAPI 健康检查。
- 从环境变量或 `.env` 安全加载模型配置。
- 使用内部服务令牌保护 `/internal/**` 接口。
- 创建 DeepSeek OpenAI 兼容的 LangChain 对话模型。
- 通过类型化 HTTPX 客户端读取 Java 学习上下文、创建计划草案。
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

## 4. 测试与代码检查

```bash
.venv/bin/python -m pytest -q
.venv/bin/python -m ruff check app tests
```

## 代码阅读顺序

1. `app/main.py`：FastAPI 如何组装。
2. `app/core/settings.py`：环境变量如何变成类型安全的配置。
3. `app/core/security.py`：Java/Python 内部认证。
4. `app/providers/model_factory.py`：为什么 DeepSeek 可以使用 OpenAI 兼容客户端。
5. `app/schemas/learning.py`：Java camelCase 与 Python snake_case 的数据契约。
6. `app/clients/java_backend.py`：Python 如何通过受控接口操作 Java 业务能力。
