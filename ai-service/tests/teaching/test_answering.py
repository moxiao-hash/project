import pytest
from langchain_core.messages import SystemMessage

from app.teaching.answering import DeepSeekTeachingAnswerer

pytestmark = pytest.mark.anyio


class FakeStructuredModel:
    def __init__(self) -> None:
        self.messages = []
        self.structured_method = None

    def with_structured_output(self, _schema, *, method=None):
        self.structured_method = method
        return self

    async def ainvoke(self, messages):
        self.messages = messages
        return {
            "answer": "DTO 用来隔离 HTTP 输入和持久化模型。",
            "citedSourceIndexes": [1],
            "suggestedActions": ["CHECK_UNDERSTANDING"],
        }


async def test_tutor_is_limited_to_the_current_lesson_and_never_claims_video_access():
    model = FakeStructuredModel()
    answerer = DeepSeekTeachingAnswerer(
        model,
        model_provider="deepseek",
        model_name="deepseek-v4-pro",
    )

    result = await answerer.answer(
        question="视频里的老师为什么使用 DTO？",
        lesson={
            "id": "lesson-rest-controller",
            "title": "Controller、REST API 与参数校验",
            "content": {"blocks": [{"title": "请求 DTO", "markdown": "DTO 描述输入契约"}]},
            "sources": [{"title": "黑马注册接口", "url": "https://www.bilibili.com/video/x"}],
        },
        history=[],
    )

    system = model.messages[0]
    assert isinstance(system, SystemMessage)
    assert "当前课时的 AI 导师" in str(system.content)
    assert "不得声称已经观看或转录 B 站视频" in str(system.content)
    assert "不要在学生尚未尝试练习前直接给完整答案" in str(system.content)
    assert model.structured_method == "json_mode"
    assert result.answer.startswith("DTO")
    assert result.suggested_actions == ["CHECK_UNDERSTANDING"]
