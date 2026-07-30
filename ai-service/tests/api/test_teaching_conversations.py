import asyncio
from collections.abc import Iterator

import httpx
import pytest
from pydantic import SecretStr

from app.api.teaching_conversations import get_teaching_conversation_service
from app.core.settings import Settings, get_settings
from app.main import app
from app.teaching.models import TeachingConversationSnapshot


class FakeTeachingService:
    async def create_conversation(self, owner_id, lesson_id):
        assert (owner_id, lesson_id) == ("user-1", "lesson-rest-controller")
        return snapshot()

    async def send_message(self, conversation_id, *, owner_id, message):
        assert (conversation_id, owner_id, message) == (
            "teaching-1",
            "user-1",
            "继续讲解",
        )
        return snapshot(answer="换一个项目例子。")

    async def get_conversation(self, conversation_id, owner_id):
        assert (conversation_id, owner_id) == ("teaching-1", "user-1")
        return snapshot()


def snapshot(*, answer="") -> TeachingConversationSnapshot:
    return TeachingConversationSnapshot(
        conversation_id="teaching-1",
        owner_id="user-1",
        lesson_id="lesson-rest-controller",
        answer=answer,
        suggested_actions=["CONTINUE_LESSON"],
        model_provider="deepseek",
        model_name="deepseek-v4-pro",
    )


@pytest.fixture(autouse=True)
def overrides() -> Iterator[None]:
    app.dependency_overrides[get_settings] = lambda: Settings(
        internal_service_token=SecretStr("test-token"),
        deepseek_api_key=SecretStr("unused"),
    )
    app.dependency_overrides[get_teaching_conversation_service] = (
        lambda: FakeTeachingService()
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
        ("POST", "/internal/teaching/conversations"),
        ("POST", "/internal/teaching/conversations/teaching-1/messages"),
        ("GET", "/internal/teaching/conversations/teaching-1"),
    ],
)
def test_endpoints_require_internal_token(method, path):
    assert request(method, path, token=None).status_code == 401


def test_teaching_conversation_http_workflow():
    created = request(
        "POST",
        "/internal/teaching/conversations",
        json={"ownerId": "user-1", "lessonId": "lesson-rest-controller"},
    )
    answered = request(
        "POST",
        "/internal/teaching/conversations/teaching-1/messages",
        json={"ownerId": "user-1", "message": "继续讲解"},
    )
    fetched = request(
        "GET",
        "/internal/teaching/conversations/teaching-1?ownerId=user-1",
    )

    assert created.status_code == 201
    assert answered.status_code == 200
    assert answered.json()["answer"] == "换一个项目例子。"
    assert fetched.status_code == 200
