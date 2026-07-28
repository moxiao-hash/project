import asyncio
from collections.abc import Iterator
from datetime import date

import httpx
import pytest
from pydantic import SecretStr

from app.agent.task_conversation_models import (
    TaskConversationSnapshot,
    TaskConversationStatus,
)
from app.agent.task_conversation_service import (
    InvalidTaskConversationStateError,
    TaskConversationBusyError,
    TaskExecutionUnavailableError,
    TaskVersionConflictError,
)
from app.agent.task_models import TaskActionDraft
from app.api.task_conversations import get_task_conversation_service
from app.core.settings import Settings, get_settings
from app.main import app
from app.schemas.learning import LearningTask, LearningTaskStatus


class FakeTaskConversationService:
    def __init__(self) -> None:
        self.snapshot = TaskConversationSnapshot(
            conversation_id="conversation-1",
            owner_id="user-1",
            target_date=date(2026, 7, 26),
            status=TaskConversationStatus.COLLECTING,
            reply="任务会话已创建。",
        )
        self.error: Exception | None = None

    async def create_conversation(self, owner_id: str, target_date: date):
        assert (owner_id, target_date) == ("user-1", date(2026, 7, 26))
        return self.snapshot

    async def send_message(self, conversation_id: str, message: str, owner_id: str):
        assert (conversation_id, message, owner_id) == (
            "conversation-1",
            "Spring MVC 接口完成了",
            "user-1",
        )
        if self.error is not None:
            raise self.error
        return self.snapshot.model_copy(
            update={
                "status": TaskConversationStatus.PREVIEW_READY,
                "reply": "请确认完成任务。",
                "action_draft": TaskActionDraft(
                    target_status=LearningTaskStatus.COMPLETED,
                    task_id="task-1",
                    task_title="完成 Spring MVC 接口",
                    expected_version=1,
                ),
                "execution_id": "execution-1",
            }
        )

    async def get_conversation(self, conversation_id: str, owner_id: str):
        assert (conversation_id, owner_id) == ("conversation-1", "user-1")
        return self.snapshot

    async def confirm(self, conversation_id: str, owner_id: str):
        assert (conversation_id, owner_id) == ("conversation-1", "user-1")
        if self.error is not None:
            raise self.error
        return self.snapshot.model_copy(
            update={
                "status": TaskConversationStatus.COMPLETED,
                "reply": "任务已完成。",
                "updated_task": LearningTask(
                    id="task-1",
                    plan_id="plan-1",
                    title="完成 Spring MVC 接口",
                    scheduled_date=date(2026, 7, 26),
                    estimated_minutes=60,
                    status=LearningTaskStatus.COMPLETED,
                    version=2,
                    completed_at="2026-07-26T01:00:00Z",
                ),
            }
        )


@pytest.fixture
def fake_service() -> FakeTaskConversationService:
    return FakeTaskConversationService()


@pytest.fixture(autouse=True)
def override_dependencies(
    fake_service: FakeTaskConversationService,
) -> Iterator[None]:
    settings = Settings(
        internal_service_token=SecretStr("test-internal-token"),
        deepseek_api_key=SecretStr("not-used"),
    )
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_task_conversation_service] = lambda: fake_service
    yield
    app.dependency_overrides.clear()


def request(
    method: str,
    path: str,
    *,
    json: dict | None = None,
    token: str | None = None,
) -> httpx.Response:
    async def send() -> httpx.Response:
        headers = {"X-Internal-Service-Token": token} if token else {}
        transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.request(method, path, json=json, headers=headers)

    return asyncio.run(send())


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("POST", "/internal/agent/task-conversations"),
        ("POST", "/internal/agent/task-conversations/conversation-1/messages"),
        ("GET", "/internal/agent/task-conversations/conversation-1"),
        ("POST", "/internal/agent/task-conversations/conversation-1/confirm"),
    ],
)
def test_task_conversation_endpoints_require_internal_token(
    method: str,
    path: str,
) -> None:
    response = request(method, path)

    assert response.status_code == 401


def test_task_conversation_http_workflow() -> None:
    created = request(
        "POST",
        "/internal/agent/task-conversations",
        json={"ownerId": "user-1", "targetDate": "2026-07-26"},
        token="test-internal-token",
    )
    preview = request(
        "POST",
        "/internal/agent/task-conversations/conversation-1/messages",
        json={"ownerId": "user-1", "message": "Spring MVC 接口完成了"},
        token="test-internal-token",
    )
    fetched = request(
        "GET",
        "/internal/agent/task-conversations/conversation-1?ownerId=user-1",
        token="test-internal-token",
    )
    confirmed = request(
        "POST",
        "/internal/agent/task-conversations/conversation-1/confirm",
        json={"ownerId": "user-1"},
        token="test-internal-token",
    )

    assert created.status_code == 201
    assert created.json()["targetDate"] == "2026-07-26"
    assert preview.json()["status"] == "PREVIEW_READY"
    assert preview.json()["actionDraft"]["taskId"] == "task-1"
    assert fetched.json()["status"] == "COLLECTING"
    assert confirmed.json()["status"] == "COMPLETED"
    assert confirmed.json()["updatedTask"]["version"] == 2


@pytest.mark.parametrize(
    ("error", "expected_status"),
    [
        (
            InvalidTaskConversationStateError("只有操作预览就绪的任务会话可以确认"),
            409,
        ),
        (TaskConversationBusyError("该任务会话正在处理另一条请求"), 409),
        (TaskVersionConflictError("任务版本已变化"), 409),
        (TaskExecutionUnavailableError("Java 后端不可用"), 503),
    ],
)
def test_task_conversation_errors_have_stable_http_status(
    fake_service: FakeTaskConversationService,
    error: Exception,
    expected_status: int,
) -> None:
    fake_service.error = error

    response = request(
        "POST",
        "/internal/agent/task-conversations/conversation-1/confirm",
        json={"ownerId": "user-1"},
        token="test-internal-token",
    )

    assert response.status_code == expected_status
