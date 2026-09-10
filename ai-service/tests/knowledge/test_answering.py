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


class FakeStreamingChatModel:
    """只实现 astream 的假模型，用于验证真实增量路径。"""

    def __init__(self, chunks) -> None:
        self.chunks = chunks
        self.messages = []

    async def astream(self, messages):
        self.messages = messages
        for chunk in self.chunks:
            yield type("Chunk", (), {"content": chunk})()


async def test_astream_yields_model_deltas_in_order_with_grounding_prompt() -> None:
    model = FakeStreamingChatModel(["基于资料，", "建议先学 Java 基础。"])
    answerer = DeepSeekKnowledgeAnswerer(
        model,
        model_provider="deepseek",
        model_name="deepseek-v4-pro",
    )

    chunks = [
        chunk
        async for chunk in answerer.astream(
            question="解释依赖注入",
            history=[],
            materials=[],
            web_results=[],
        )
    ]

    assert chunks == ["基于资料，", "建议先学 Java 基础。"]
    assert isinstance(model.messages[0], SystemMessage)
    assert "不可信数据" in str(model.messages[0].content)


async def test_astream_skips_empty_and_joins_list_content_chunks() -> None:
    model = FakeStreamingChatModel(
        ["", [{"type": "text", "text": "先学 Spring Boot"}], None, "  "]
    )
    answerer = DeepSeekKnowledgeAnswerer(
        model,
        model_provider="deepseek",
        model_name="deepseek-v4-pro",
    )

    chunks = [
        chunk
        async for chunk in answerer.astream(
            question="怎么学",
            history=[],
            materials=[],
            web_results=[],
        )
    ]

    # 只跳过空分片；空白分片是模型真实输出（词间空格），必须原样保留。
    assert chunks == ["先学 Spring Boot", "  "]
