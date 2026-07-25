import asyncio
from collections.abc import Iterator

import httpx
import pytest
from pydantic import SecretStr

from app.agent.models import ConversationSnapshot, ConversationStatus
from app.agent.service import InvalidConversationStateError
from app.api.conversations import get_conversation_service
from app.core.settings import Settings, get_settings
from app.main import app


class FakeConversationService:
    def __init__(self) -> None:
        self.snapshot = ConversationSnapshot(
            conversation_id="conversation-1",
            owner_id="user-1",
            goal_id="goal-1",
            status=ConversationStatus.COLLECTING,
            reply="会话已创建。",
        )
        self.confirm_error = False

    async def create_conversation(self, owner_id: str, goal_id: str):
        assert (owner_id, goal_id) == ("user-1", "goal-1")
        return self.snapshot

    async def send_message(self, conversation_id: str, message: str):
        assert (conversation_id, message) == ("conversation-1", "每天两小时")
        return self.snapshot.model_copy(update={"reply": "还需要确认休息日。"})

    async def get_conversation(self, conversation_id: str):
        assert conversation_id == "conversation-1"
        return self.snapshot

    async def confirm(self, conversation_id: str):
        assert conversation_id == "conversation-1"
        if self.confirm_error:
            raise InvalidConversationStateError("只有草稿就绪的会话可以确认")
        return self.snapshot.model_copy(update={"status": ConversationStatus.COMPLETED})


@pytest.fixture
def fake_service() -> FakeConversationService:
    return FakeConversationService()


@pytest.fixture(autouse=True)
def override_dependencies(fake_service: FakeConversationService) -> Iterator[None]:
    settings = Settings(
        internal_service_token=SecretStr("test-internal-token"),
        deepseek_api_key=SecretStr("not-used"),
    )
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_conversation_service] = lambda: fake_service
    yield
    app.dependency_overrides.clear()


def request(
    method: str,
    path: str,
    *,
    json: dict | None = None,
    token: str | None = "test-internal-token",
) -> httpx.Response:
    async def send() -> httpx.Response:
        headers = {"X-Internal-Service-Token": token} if token else {}
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.request(method, path, json=json, headers=headers)

    return asyncio.run(send())


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("POST", "/internal/agent/conversations"),
        ("POST", "/internal/agent/conversations/conversation-1/messages"),
        ("GET", "/internal/agent/conversations/conversation-1"),
        ("POST", "/internal/agent/conversations/conversation-1/confirm"),
    ],
)
def test_conversation_endpoints_require_internal_token(method: str, path: str) -> None:
    response = request(method, path, token=None)

    assert response.status_code == 401


def test_conversation_http_workflow() -> None:
    created = request(
        "POST",
        "/internal/agent/conversations",
        json={"ownerId": "user-1", "goalId": "goal-1"},
    )
    messaged = request(
        "POST",
        "/internal/agent/conversations/conversation-1/messages",
        json={"message": "每天两小时"},
    )
    fetched = request("GET", "/internal/agent/conversations/conversation-1")
    confirmed = request(
        "POST",
        "/internal/agent/conversations/conversation-1/confirm",
    )

    assert created.status_code == 201
    assert created.json()["conversationId"] == "conversation-1"
    assert messaged.json()["reply"] == "还需要确认休息日。"
    assert fetched.json()["status"] == "COLLECTING"
    assert confirmed.json()["status"] == "COMPLETED"


def test_invalid_confirmation_becomes_conflict(
    fake_service: FakeConversationService,
) -> None:
    fake_service.confirm_error = True

    response = request(
        "POST",
        "/internal/agent/conversations/conversation-1/confirm",
    )

    assert response.status_code == 409
    assert response.json() == {"detail": "只有草稿就绪的会话可以确认"}
