import asyncio
from collections.abc import Iterator

import httpx
import pytest
from pydantic import SecretStr

from app.api.unified_assistant import get_unified_agent_service
from app.core.settings import Settings, get_settings
from app.main import app
from app.unified_agent.models import AssistantConversationSnapshot


class FakeUnifiedAgentService:
    async def create_conversation(self, owner_id):
        return snapshot(owner_id=owner_id)

    async def get_conversation(self, conversation_id, owner_id):
        return snapshot(conversation_id=conversation_id, owner_id=owner_id)

    async def send_message(
        self, conversation_id, message, idempotency_key, owner_id, client_context
    ):
        assert message == "打开错题集"
        assert idempotency_key == "assistant-turn:1"
        assert client_context["routeName"] == "dashboard"
        return snapshot(conversation_id=conversation_id, owner_id=owner_id, reply="已打开。")


def snapshot(
    *, conversation_id: str = "assistant-1", owner_id: str = "user-1", reply: str = "会话已创建。"
) -> AssistantConversationSnapshot:
    return AssistantConversationSnapshot(
        conversation_id=conversation_id,
        owner_id=owner_id,
        status="COMPLETED",
        reply=reply,
        model_name="deepseek-v4-flash",
    )


@pytest.fixture(autouse=True)
def overrides() -> Iterator[None]:
    app.dependency_overrides[get_settings] = lambda: Settings(
        internal_service_token=SecretStr("test-token"),
        deepseek_api_key=SecretStr("unused"),
    )
    app.dependency_overrides[get_unified_agent_service] = lambda: FakeUnifiedAgentService()
    yield
    app.dependency_overrides.clear()


def request(method: str, path: str, *, json=None, token="test-token"):
    async def send():
        headers = {"X-Internal-Service-Token": token} if token else {}
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            return await client.request(method, path, json=json, headers=headers)

    return asyncio.run(send())


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("POST", "/internal/assistant/conversations"),
        ("GET", "/internal/assistant/conversations/assistant-1?ownerId=user-1"),
        ("POST", "/internal/assistant/conversations/assistant-1/messages"),
    ],
)
def test_unified_endpoints_require_internal_token(method: str, path: str) -> None:
    assert request(method, path, token=None).status_code == 401


def test_unified_conversation_http_workflow() -> None:
    created = request(
        "POST", "/internal/assistant/conversations", json={"ownerId": "user-1"}
    )
    sent = request(
        "POST",
        "/internal/assistant/conversations/assistant-1/messages",
        json={
            "ownerId": "user-1",
            "message": "打开错题集",
            "idempotencyKey": "assistant-turn:1",
            "clientContext": {"routeName": "dashboard", "routeParams": {}},
        },
    )
    fetched = request(
        "GET", "/internal/assistant/conversations/assistant-1?ownerId=user-1"
    )

    assert created.status_code == 201
    assert sent.status_code == 200
    assert sent.json()["reply"] == "已打开。"
    assert fetched.status_code == 200
