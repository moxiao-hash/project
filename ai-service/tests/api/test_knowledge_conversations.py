import asyncio
from collections.abc import Iterator

import httpx
import pytest
from pydantic import SecretStr

from app.api.knowledge_conversations import get_knowledge_conversation_service
from app.core.settings import Settings, get_settings
from app.knowledge.models import (
    KnowledgeConversationSnapshot,
    KnowledgeMode,
    WebSearchPolicy,
)
from app.knowledge.service import KnowledgeConversationNotFoundError
from app.main import app


class FakeKnowledgeService:
    async def create_conversation(self, owner_id, mode):
        assert (owner_id, mode) == ("user-1", KnowledgeMode.AUTO)
        return snapshot()

    async def send_message(self, conversation_id, message, web_search, owner_id):
        assert (conversation_id, message, web_search, owner_id) == (
            "knowledge-1",
            "继续讲解",
            WebSearchPolicy.AUTO,
            "user-1",
        )
        return snapshot(answer="这是有来源的回答。")

    async def get_conversation(self, conversation_id, owner_id):
        assert owner_id == "user-1"
        if conversation_id == "missing":
            raise KnowledgeConversationNotFoundError("知识会话不存在")
        return snapshot()


def snapshot(*, answer: str = "") -> KnowledgeConversationSnapshot:
    return KnowledgeConversationSnapshot(
        conversation_id="knowledge-1",
        owner_id="user-1",
        mode=KnowledgeMode.AUTO,
        answer=answer,
        retrieval_mode="NONE",
    )


@pytest.fixture(autouse=True)
def overrides() -> Iterator[None]:
    app.dependency_overrides[get_settings] = lambda: Settings(
        internal_service_token=SecretStr("test-token"),
        deepseek_api_key=SecretStr("unused"),
    )
    app.dependency_overrides[get_knowledge_conversation_service] = (
        lambda: FakeKnowledgeService()
    )
    yield
    app.dependency_overrides.clear()


def request(method: str, path: str, *, json=None, token="test-token"):
    async def send():
        headers = {"X-Internal-Service-Token": token} if token else {}
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://test",
        ) as client:
            return await client.request(method, path, json=json, headers=headers)

    return asyncio.run(send())


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("POST", "/internal/knowledge/conversations"),
        ("POST", "/internal/knowledge/conversations/knowledge-1/messages"),
        ("GET", "/internal/knowledge/conversations/knowledge-1"),
    ],
)
def test_endpoints_require_internal_token(method: str, path: str) -> None:
    assert request(method, path, token=None).status_code == 401


def test_knowledge_conversation_http_workflow() -> None:
    created = request(
        "POST",
        "/internal/knowledge/conversations",
        json={"ownerId": "user-1", "mode": "AUTO"},
    )
    answered = request(
        "POST",
        "/internal/knowledge/conversations/knowledge-1/messages",
        json={"ownerId": "user-1", "message": "继续讲解", "webSearch": "AUTO"},
    )
    fetched = request(
        "GET", "/internal/knowledge/conversations/knowledge-1?ownerId=user-1"
    )

    assert created.status_code == 201
    assert created.json()["conversationId"] == "knowledge-1"
    assert answered.status_code == 200
    assert answered.json()["answer"] == "这是有来源的回答。"
    assert fetched.status_code == 200


def test_missing_conversation_is_not_found() -> None:
    response = request(
        "GET", "/internal/knowledge/conversations/missing?ownerId=user-1"
    )

    assert response.status_code == 404
    assert response.json() == {"detail": "知识会话不存在"}
