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

    async def list_events(self, conversation_id, owner_id, after_sequence):
        assert (conversation_id, owner_id, after_sequence) == ("assistant-1", "user-1", 2)
        return [
            {
                "sequence": 3,
                "type": "TURN_COMPLETED",
                "conversationId": conversation_id,
                "payload": {"reply": "已打开。"},
            }
        ]

    async def confirm_action(self, conversation_id, action_id, owner_id):
        assert (conversation_id, action_id, owner_id) == (
            "assistant-1",
            "action-1",
            "user-1",
        )
        return snapshot(conversation_id=conversation_id, owner_id=owner_id, reply="操作已执行。")

    async def reject_action(self, conversation_id, action_id, owner_id):
        return snapshot(conversation_id=conversation_id, owner_id=owner_id, reply="操作已取消。")

    async def cancel_turn(self, conversation_id, turn_id, owner_id):
        assert (conversation_id, turn_id, owner_id) == ("assistant-1", "turn-1", "user-1")
        return snapshot(conversation_id=conversation_id, owner_id=owner_id, reply="已请求取消。")


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
        ("GET", "/internal/assistant/conversations/assistant-1/events?ownerId=user-1"),
        ("POST", "/internal/assistant/conversations/assistant-1/actions/action-1/confirm"),
        ("POST", "/internal/assistant/conversations/assistant-1/actions/action-1/reject"),
        ("POST", "/internal/assistant/conversations/assistant-1/turns/turn-1/cancel"),
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


def test_event_replay_uses_sequence_cursor() -> None:
    response = request(
        "GET",
        "/internal/assistant/conversations/assistant-1/events?ownerId=user-1&afterSequence=2",
    )

    assert response.status_code == 200
    assert response.json()[0]["sequence"] == 3


def test_action_confirmation_is_a_dedicated_endpoint() -> None:
    response = request(
        "POST",
        "/internal/assistant/conversations/assistant-1/actions/action-1/confirm",
        json={"ownerId": "user-1"},
    )

    assert response.status_code == 200
    assert response.json()["reply"] == "操作已执行。"


def test_turn_cancel_has_dedicated_endpoint() -> None:
    response = request(
        "POST",
        "/internal/assistant/conversations/assistant-1/turns/turn-1/cancel",
        json={"ownerId": "user-1"},
    )

    assert response.status_code == 200
    assert response.json()["reply"] == "已请求取消。"
