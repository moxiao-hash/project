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


async def test_system_prompt_limits_answers_to_the_java_ai_project_stack() -> None:
    model = FakeChatModel()
    answerer = DeepSeekKnowledgeAnswerer(
        model,
        model_provider="deepseek",
        model_name="deepseek-v4-pro",
    )

    await answerer.answer(
        question="我应该学习什么？",
        history=[],
        materials=[],
        web_results=[],
    )

    system_content = str(model.messages[0].content)
    assert "Java、Spring Boot、MySQL、Vue/TypeScript、Python/FastAPI" in system_content
    assert (
        "DeepSeek API、LangChain/LangGraph、RAG/Qdrant/Tavily、Git/Docker"
        in system_content
    )
    assert "教程类资料优先黑马程序员" in system_content
    assert "时效事实优先官方文档" in system_content
