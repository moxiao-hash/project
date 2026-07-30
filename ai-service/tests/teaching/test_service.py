import pytest

from app.teaching.models import TeachingAnswer
from app.teaching.service import (
    TeachingConversationNotFoundError,
    TeachingConversationService,
)

pytestmark = pytest.mark.anyio


class FakeLessonProvider:
    def __init__(self) -> None:
        self.calls = []

    async def get_lesson_context(self, owner_id, lesson_id):
        self.calls.append((owner_id, lesson_id))
        return {
            "lesson": {
                "id": lesson_id,
                "title": "Controller、REST API 与参数校验",
                "content": {"blocks": []},
                "sources": [],
            }
        }


class FakeAnswerer:
    def __init__(self) -> None:
        self.calls = []

    async def answer(self, *, question, lesson, history):
        self.calls.append(
            {"question": question, "lesson": lesson, "history": history}
        )
        return TeachingAnswer(
            answer="先区分 HTTP 输入模型和数据库实体。",
            suggested_actions=["CHECK_UNDERSTANDING"],
        )


async def test_tutor_receives_current_lesson_and_visible_history():
    provider = FakeLessonProvider()
    answerer = FakeAnswerer()
    service = TeachingConversationService(
        provider,
        answerer,
        model_provider="deepseek",
        model_name="deepseek-v4-pro",
    )
    created = await service.create_conversation("user-1", "lesson-rest-controller")

    first = await service.send_message(
        created.conversation_id,
        owner_id="user-1",
        message="DTO 是什么？",
    )
    await service.send_message(
        created.conversation_id,
        owner_id="user-1",
        message="再举一个例子",
    )

    assert provider.calls == [("user-1", "lesson-rest-controller")]
    assert answerer.calls[0]["lesson"]["id"] == "lesson-rest-controller"
    assert answerer.calls[1]["history"] == [
        ("USER", "DTO 是什么？"),
        ("ASSISTANT", first.answer),
    ]
    assert first.suggested_actions == ["CHECK_UNDERSTANDING"]


async def test_conversation_is_owner_isolated():
    service = TeachingConversationService(
        FakeLessonProvider(),
        FakeAnswerer(),
        model_provider="deepseek",
        model_name="deepseek-v4-pro",
    )
    created = await service.create_conversation("user-1", "lesson-rest-controller")

    with pytest.raises(TeachingConversationNotFoundError):
        await service.get_conversation(created.conversation_id, "user-2")
