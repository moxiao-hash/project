import pytest
from langchain_core.messages import SystemMessage

from app.knowledge.answering import DeepSeekKnowledgeAnswerer

pytestmark = pytest.mark.anyio


class FakeChatModel:
    def __init__(self) -> None:
        self.messages = []

    async def ainvoke(self, messages):
        self.messages = messages
        return type("Response", (), {"content": "回答"})()


async def test_system_prompt_contains_studypilot_and_actual_model_identity() -> None:
    model = FakeChatModel()
    answerer = DeepSeekKnowledgeAnswerer(
        model,
        model_provider="deepseek",
        model_name="deepseek-v4-pro",
    )

    await answerer.answer(
        question="解释依赖注入",
        history=[],
        materials=[],
        web_results=[],
    )

    system = model.messages[0]
    assert isinstance(system, SystemMessage)
    assert "StudyPilot" in str(system.content)
    assert "deepseek" in str(system.content)
    assert "deepseek-v4-pro" in str(system.content)
    assert "API Key" not in str(system.content)
