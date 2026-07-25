# AI Service Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可运行、可测试且不会泄露模型密钥的 FastAPI AI 服务基础。

**Architecture:** FastAPI 暴露健康检查和受内部令牌保护的状态接口；Pydantic Settings 统一加载环境配置；LangChain OpenAI 兼容客户端连接 DeepSeek；HTTPX 客户端只通过 Java 内部 API 访问业务数据。

**Tech Stack:** Python 3.12、FastAPI、Pydantic Settings、HTTPX、LangChain OpenAI、Pytest

---

### Task 1: Python 工程与健康检查

**Files:**
- Create: `ai-service/pyproject.toml`
- Create: `ai-service/app/__init__.py`
- Create: `ai-service/app/main.py`
- Create: `ai-service/tests/test_health.py`

- [ ] **Step 1: 写失败测试**

```python
from fastapi.testclient import TestClient

from app.main import app


def test_health_reports_service_is_up() -> None:
    response = TestClient(app).get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": "studypilot-ai"}
```

- [ ] **Step 2: 验证测试因 `app.main` 不存在而失败**

Run: `python -m pytest tests/test_health.py -q`
Expected: FAIL，提示 `ModuleNotFoundError`

- [ ] **Step 3: 创建最小 FastAPI 应用**

```python
from fastapi import FastAPI

app = FastAPI(title="StudyPilot AI Service")


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "UP", "service": "studypilot-ai"}
```

- [ ] **Step 4: 验证测试通过**

Run: `python -m pytest tests/test_health.py -q`
Expected: `1 passed`

### Task 2: 安全配置与模型状态

**Files:**
- Create: `ai-service/app/core/__init__.py`
- Create: `ai-service/app/core/settings.py`
- Create: `ai-service/app/core/security.py`
- Create: `ai-service/app/api/__init__.py`
- Create: `ai-service/app/api/model_status.py`
- Create: `ai-service/tests/test_model_status.py`
- Create: `ai-service/.env.example`
- Modify: `.gitignore`

- [ ] **Step 1: 写内部状态接口失败测试**

```python
def test_model_status_rejects_missing_internal_token(client) -> None:
    assert client.get("/internal/model/status").status_code == 401


def test_model_status_never_exposes_api_key(client) -> None:
    response = client.get(
        "/internal/model/status",
        headers={"X-Internal-Service-Token": "test-internal-token"},
    )
    assert response.status_code == 200
    assert response.json()["configured"] is True
    assert "secret-key" not in response.text
```

- [ ] **Step 2: 验证接口不存在或未鉴权导致测试失败**

Run: `python -m pytest tests/test_model_status.py -q`
Expected: FAIL

- [ ] **Step 3: 实现 Settings、常量时间令牌校验与状态响应**

配置类使用 `SecretStr` 保存 Key；鉴权依赖使用 `secrets.compare_digest`；状态响应只返回 Provider、模型名与布尔值。

- [ ] **Step 4: 验证状态测试通过**

Run: `python -m pytest tests/test_model_status.py -q`
Expected: all passed

### Task 3: DeepSeek 模型工厂

**Files:**
- Create: `ai-service/app/providers/__init__.py`
- Create: `ai-service/app/providers/model_factory.py`
- Create: `ai-service/tests/providers/test_model_factory.py`

- [ ] **Step 1: 写模型工厂失败测试**

```python
def test_builds_openai_compatible_deepseek_client(settings) -> None:
    model = create_chat_model(settings)
    assert model.model_name == "deepseek-v4-pro"
    assert str(model.openai_api_base).rstrip("/") == "https://api.deepseek.com"
    assert model.openai_api_key.get_secret_value() == "secret-key"
```

- [ ] **Step 2: 验证 `create_chat_model` 不存在导致失败**

Run: `python -m pytest tests/providers/test_model_factory.py -q`
Expected: FAIL

- [ ] **Step 3: 实现模型工厂**

工厂仅支持 `deepseek`，使用 `ChatOpenAI` 的 `api_key`、`base_url`、`model` 和 `temperature=0`；缺少 Key 或未知 Provider 时抛出 `ModelConfigurationError`。

- [ ] **Step 4: 验证模型工厂测试通过**

Run: `python -m pytest tests/providers/test_model_factory.py -q`
Expected: all passed

### Task 4: Java 内部 API 客户端

**Files:**
- Create: `ai-service/app/clients/__init__.py`
- Create: `ai-service/app/clients/java_backend.py`
- Create: `ai-service/app/schemas/__init__.py`
- Create: `ai-service/app/schemas/learning.py`
- Create: `ai-service/tests/clients/test_java_backend.py`

- [ ] **Step 1: 写请求契约失败测试**

测试使用 `httpx.MockTransport`，断言学习上下文路径、内部令牌请求头、计划草案 JSON 和非 2xx 异常。

- [ ] **Step 2: 验证客户端不存在导致失败**

Run: `python -m pytest tests/clients/test_java_backend.py -q`
Expected: FAIL

- [ ] **Step 3: 实现带类型模型的异步客户端**

`JavaBackendClient` 提供 `get_learning_context(owner_id)` 和 `create_plan_draft(request)`，并统一抛出 `JavaBackendError`。

- [ ] **Step 4: 验证 Java 客户端测试通过**

Run: `python -m pytest tests/clients/test_java_backend.py -q`
Expected: all passed

### Task 5: 文档与完整验证

**Files:**
- Modify: `ai-service/README.md`
- Modify: `docs/development-roadmap.md`

- [ ] **Step 1: 写明 Python 版本、虚拟环境、`.env`、启动和测试命令**
- [ ] **Step 2: 确认文档不包含真实 Key 或不完整占位说明**
- [ ] **Step 3: 执行完整验证**

Run: `python -m pytest -q`
Expected: all passed

Run: `python -m ruff check app tests`
Expected: `All checks passed!`

Run: `python -m uvicorn app.main:app --host 127.0.0.1 --port 8000`
Expected: `/health` 返回 HTTP 200，`/internal/model/status` 的鉴权行为符合测试

