"""Task 29 内部 SSE 端点测试：鉴权、帧格式、Last-Event-ID 续传与心跳。"""

import asyncio
from collections.abc import AsyncIterator, Iterator

import httpx
import pytest
from pydantic import SecretStr

from app.api.unified_assistant import get_unified_agent_service
from app.core.settings import Settings, get_settings
from app.main import app
from app.unified_agent.models import AssistantConversationSnapshot, AssistantEvent
from app.unified_agent.supervisor import AssistantConversationNotFoundError


class FakeStreamingService:
    def __init__(self) -> None:
        self.cursors: list[int] = []

    async def get_conversation(self, conversation_id, owner_id):
        if conversation_id == "unknown":
            raise AssistantConversationNotFoundError("统一 Agent 会话不存在")
        assert (conversation_id, owner_id) == ("assistant-1", "user-1")
        return AssistantConversationSnapshot(
            conversation_id=conversation_id,
            owner_id=owner_id,
            status="RUNNING",
            reply="",
            model_name="deepseek-v4-flash",
        )

    async def stream_events(
        self, conversation_id, owner_id, after_sequence=0, *, heartbeat_seconds=None
    ) -> AsyncIterator[AssistantEvent | None]:
        self.cursors.append(after_sequence)
        yield AssistantEvent(
            sequence=after_sequence + 1,
            type="TURN_STARTED",
            conversation_id=conversation_id,
            payload={"turnId": "turn-1"},
        )
        yield None  # 心跳
        yield AssistantEvent(
            sequence=after_sequence + 2,
            type="TURN_COMPLETED",
            conversation_id=conversation_id,
            payload={"reply": "完成", "turnId": "turn-1"},
        )


@pytest.fixture(autouse=True)
def overrides() -> Iterator[FakeStreamingService]:
    service = FakeStreamingService()
    app.dependency_overrides[get_settings] = lambda: Settings(
        internal_service_token=SecretStr("test-token"),
        deepseek_api_key=SecretStr("unused"),
    )
    app.dependency_overrides[get_unified_agent_service] = lambda: service
    yield service
    app.dependency_overrides.clear()


def read_stream(path: str, *, token: str | None = "test-token", last_event_id: str | None = None):
    async def send():
        headers = {}
        if token:
            headers["X-Internal-Service-Token"] = token
        if last_event_id is not None:
            headers["Last-Event-ID"] = last_event_id
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client, client.stream("GET", path, headers=headers) as response:
            body = "".join([chunk async for chunk in response.aiter_text()])
            return response, body

    return asyncio.run(send())


def test_stream_endpoint_requires_internal_token() -> None:
    response, _ = read_stream(
        "/internal/assistant/conversations/assistant-1/events/stream?ownerId=user-1",
        token=None,
    )
    assert response.status_code == 401


def test_stream_endpoint_emits_persisted_events_and_heartbeats() -> None:
    response, body = read_stream(
        "/internal/assistant/conversations/assistant-1/events/stream?ownerId=user-1"
    )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert "id: 1\nevent: TURN_STARTED\n" in body
    assert ": heartbeat\n\n" in body
    assert "id: 2\nevent: TURN_COMPLETED\n" in body
    assert body.index("id: 1\n") < body.index("id: 2\n")


def test_stream_endpoint_honours_last_event_id_header() -> None:
    response, body = read_stream(
        "/internal/assistant/conversations/assistant-1/events/stream?ownerId=user-1",
        last_event_id="7",
    )

    assert response.status_code == 200
    assert "id: 8\nevent: TURN_STARTED\n" in body
    assert "id: 7\n" not in body


def test_stream_endpoint_uses_query_cursor_when_header_is_absent() -> None:
    response, body = read_stream(
        "/internal/assistant/conversations/assistant-1/events/stream"
        "?ownerId=user-1&afterSequence=4"
    )

    assert response.status_code == 200
    assert "id: 5\nevent: TURN_STARTED\n" in body


def test_stream_endpoint_rejects_unknown_conversation() -> None:
    response, _ = read_stream(
        "/internal/assistant/conversations/unknown/events/stream?ownerId=user-1"
    )
    assert response.status_code == 404
