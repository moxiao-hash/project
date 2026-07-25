# Python Task Tool Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Python Agent 增加类型安全的 Java 今日任务查询和幂等任务状态修改客户端，
并保留 Java 返回的错误分类供后续编排层决策。

**Architecture:** 在现有 `JavaBackendClient` 上增加两个低层工具方法，不新增 FastAPI
公开写入口。Pydantic 契约负责 snake_case/camelCase 转换和基础参数校验，Java 继续
负责用户归属、版本、幂等和状态机规则；HTTP 错误统一转换为带状态码和详情的
`JavaBackendError`。

**Tech Stack:** Python 3.12、Pydantic 2、HTTPX、pytest、Ruff

---

## 文件结构

- Modify: `ai-service/app/schemas/learning.py`
  —— 增加任务状态枚举和内部状态修改请求。
- Modify: `ai-service/app/clients/java_backend.py`
  —— 增加任务查询、状态修改和可分类错误。
- Modify: `ai-service/tests/clients/test_java_backend.py`
  —— 用 MockTransport 锁定 Java/Python HTTP 契约。
- Modify: `项目开发步骤.md`
  —— 记录 4.3 实现内容、验证结果和下一开发位置。

### Task 1: 用失败测试锁定查询与写操作契约

**Files:**
- Modify: `ai-service/tests/clients/test_java_backend.py`

- [x] **Step 1: 写指定日期任务查询测试**

测试期望的客户端 API：

```python
tasks = await client.get_learning_tasks(
    "user-123",
    target_date=date(2026, 7, 26),
)
```

MockTransport 必须断言：

```python
assert request.method == "GET"
assert request.url.path == "/internal/users/user-123/learning-tasks"
assert request.url.params["date"] == "2026-07-26"
assert request.headers["X-Internal-Service-Token"] == "shared-internal-token"
```

返回一条 Java camelCase 任务后，断言：

```python
assert tasks[0].id == "task-1"
assert tasks[0].scheduled_date == date(2026, 7, 26)
assert tasks[0].status == "TODO"
```

- [x] **Step 2: 写幂等状态修改测试**

构造：

```python
ChangeLearningTaskStatusRequest(
    owner_id="user-123",
    idempotency_key="conversation-1:task-1:complete",
    expected_version=1,
    status=LearningTaskStatus.COMPLETED,
    reason="用户明确确认完成",
)
```

MockTransport 必须断言 PATCH 路径和请求体：

```python
assert request.url.path == "/internal/learning-tasks/task-1/status"
assert json.loads(request.content) == {
    "ownerId": "user-123",
    "idempotencyKey": "conversation-1:task-1:complete",
    "expectedVersion": 1,
    "status": "COMPLETED",
    "reason": "用户明确确认完成",
}
```

返回任务后断言状态、版本和完成时间均可解析。

- [x] **Step 3: 写延期序列化测试**

延期请求必须包含：

```python
status=LearningTaskStatus.DEFERRED
scheduled_date=date(2026, 7, 28)
reason="当天时间不足"
```

断言发送给 Java 的字段为 `scheduledDate: "2026-07-28"`。

- [x] **Step 4: 运行测试并确认 RED**

Run:

```bash
cd ai-service
.venv/bin/python -m pytest tests/clients/test_java_backend.py -q
```

Expected: FAIL，原因是任务状态类型和两个客户端方法尚不存在。

### Task 2: 定义任务状态与写请求

**Files:**
- Modify: `ai-service/app/schemas/learning.py`
- Test: `ai-service/tests/clients/test_java_backend.py`

- [x] **Step 1: 增加任务状态枚举**

```python
from enum import StrEnum


class LearningTaskStatus(StrEnum):
    TODO = "TODO"
    COMPLETED = "COMPLETED"
    SKIPPED = "SKIPPED"
    DEFERRED = "DEFERRED"
```

`LearningTask.status` 改为 `LearningTaskStatus`。`StrEnum` 与字符串兼容，避免破坏
现有断言，同时让调用方不能随意拼写状态。

- [x] **Step 2: 增加内部写请求**

```python
class ChangeLearningTaskStatusRequest(JavaContractModel):
    owner_id: str
    idempotency_key: str
    expected_version: int
    status: LearningTaskStatus
    scheduled_date: date | None = None
    reason: str | None = None
```

Pydantic 负责字段类型和 camelCase 序列化。是否允许某个状态转换、日期是否晚于今天
继续由 Java 状态机最终判断，避免 Python 成为第二个业务事实中心。

### Task 3: 实现 Java 任务工具方法

**Files:**
- Modify: `ai-service/app/clients/java_backend.py`
- Test: `ai-service/tests/clients/test_java_backend.py`

- [x] **Step 1: 实现指定日期查询**

```python
async def get_learning_tasks(
    self,
    owner_id: str,
    *,
    target_date: date,
) -> list[LearningTask]:
    response = await self._request(
        "GET",
        f"/internal/users/{owner_id}/learning-tasks",
        params={"date": target_date.isoformat()},
    )
    return TypeAdapter(list[LearningTask]).validate_python(response.json())
```

使用 `TypeAdapter` 验证整个数组，拒绝静默接收字段缺失的 Java 响应。

- [x] **Step 2: 实现幂等状态修改**

```python
async def change_learning_task_status(
    self,
    task_id: str,
    request: ChangeLearningTaskStatusRequest,
) -> LearningTask:
    response = await self._request(
        "PATCH",
        f"/internal/learning-tasks/{task_id}/status",
        json=request.model_dump(by_alias=True, mode="json", exclude_none=True),
    )
    return LearningTask.model_validate(response.json())
```

客户端不生成 `idempotency_key`，因为只有上层 Agent 编排知道一次用户操作的稳定身份。

- [x] **Step 3: 运行客户端测试并确认 GREEN**

Run:

```bash
.venv/bin/python -m pytest tests/clients/test_java_backend.py -q
```

Expected: 查询、完成和延期契约测试全部通过。

### Task 4: 保留 Java 错误分类

**Files:**
- Modify: `ai-service/tests/clients/test_java_backend.py`
- Modify: `ai-service/app/clients/java_backend.py`

- [x] **Step 1: 先写 409 和连接失败测试**

409 响应：

```python
httpx.Response(409, json={"detail": "任务版本已变化"})
```

断言：

```python
with pytest.raises(JavaBackendError) as captured:
    asyncio.run(call_client())
assert captured.value.status_code == 409
assert captured.value.detail == "任务版本已变化"
```

连接失败继续断言 `status_code is None`，从而与 Java 的业务拒绝区分。

- [x] **Step 2: 扩展 JavaBackendError**

```python
class JavaBackendError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        path: str,
        status_code: int | None = None,
        detail: str | None = None,
    ) -> None:
        super().__init__(message)
        self.path = path
        self.status_code = status_code
        self.detail = detail
```

`_request` 从 JSON `detail` 中提取 Java 错误说明；若不是 JSON，则安全地回退到响应
文本。连接错误设置 `status_code=None`。

- [x] **Step 3: 运行测试并确认 GREEN**

Run:

```bash
.venv/bin/python -m pytest tests/clients/test_java_backend.py -q
```

Expected: 409、503 和连接失败均转换为可区分的 `JavaBackendError`。

### Task 5: 全量验证、文档和提交

**Files:**
- Modify: `项目开发步骤.md`
- Modify: `docs/superpowers/plans/2026-07-25-python-task-tool-client.md`

- [x] **Step 1: 运行 Python 全量验证**

Run:

```bash
cd ai-service
.venv/bin/python -m pytest -q
.venv/bin/python -m ruff check app tests
```

Expected: 全部测试通过，Ruff 无错误；不启动 Java、MySQL 或 DeepSeek。

- [x] **Step 2: 更新开发总览**

把 4.3 标为完成，记录两个客户端方法、Pydantic 契约、错误分类、测试数量和关键文件；
把当前开发位置移动到 4.4 对话式任务识别。

- [ ] **Step 3: 安全检查并精确暂存**

Run:

```bash
git diff --check
git status --short
```

不得暂存用户修改的：

```text
backend/src/main/resources/application.properties
docs/agent-api-examples.http
```

- [ ] **Step 4: 提交并推送**

```bash
git commit -m "feat: add python task tool client"
git push origin main
```
