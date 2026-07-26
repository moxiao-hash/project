import pytest

from app.knowledge.models import KnowledgeMode, WebSearchPolicy
from app.knowledge.service import KnowledgeConversationService
from app.retrieval.models import RetrievedEvidence
from app.search.models import WebSearchOutcome, WebSearchResult

pytestmark = pytest.mark.anyio


class FakeRetriever:
    def __init__(self, evidence: list[RetrievedEvidence]) -> None:
        self.evidence = evidence
        self.queries: list[tuple[str, str]] = []

    async def search(self, owner_id: str, query: str) -> list[RetrievedEvidence]:
        self.queries.append((owner_id, query))
        return self.evidence


class FakeWebSearcher:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []

    async def search(self, owner_id: str, query: str) -> WebSearchOutcome:
        self.calls.append((owner_id, query))
        return WebSearchOutcome(
            query=query,
            search_id="search-1",
            results=(
                WebSearchResult(
                    title="Spring Boot System Requirements",
                    url="https://spring.io/projects/spring-boot",
                    snippet="Current Spring Boot requires at least Java 17.",
                    score=0.95,
                    result_id="web-result-1",
                ),
            ),
        )


class FakeAnswerer:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    async def answer(self, *, question, history, materials, web_results):
        self.calls.append(
            {
                "question": question,
                "history": history,
                "materials": materials,
                "web_results": web_results,
            }
        )
        return "基于资料与官网，当前建议至少使用 Java 17。"


def evidence(*, privacy_level: str = "NORMAL") -> RetrievedEvidence:
    return RetrievedEvidence(
        material_id="material-1",
        title="Java 学习大纲",
        text="先学习 Java 基础，再学习 Spring Boot。",
        locator="第 3 页 / chunk 7",
        category="SYLLABUS",
        privacy_level=privacy_level,
        score=0.9,
    )


async def test_private_material_never_calls_web_or_cloud_model() -> None:
    retriever = FakeRetriever([evidence(privacy_level="LOCAL_ONLY")])
    web = FakeWebSearcher()
    answerer = FakeAnswerer()
    service = KnowledgeConversationService(retriever, web, answerer)
    created = await service.create_conversation("user-1", KnowledgeMode.AUTO)

    snapshot = await service.send_message(
        created.conversation_id,
        "根据我的私人笔记，我应该先学什么？",
        WebSearchPolicy.ENABLED,
    )

    assert web.calls == []
    assert answerer.calls == []
    assert "先学习 Java 基础" in snapshot.answer
    assert snapshot.retrieval_mode == "LOCAL_ONLY"
    assert snapshot.citations[0].source_type == "MATERIAL"
    assert "未发送给 DeepSeek 或 Tavily" in snapshot.warnings[0]


async def test_current_version_question_combines_material_and_web_citations() -> None:
    retriever = FakeRetriever([evidence()])
    web = FakeWebSearcher()
    answerer = FakeAnswerer()
    service = KnowledgeConversationService(retriever, web, answerer)
    created = await service.create_conversation("user-1", KnowledgeMode.AUTO)

    snapshot = await service.send_message(
        created.conversation_id,
        "Spring Boot 当前推荐使用哪个 Java 版本？",
        WebSearchPolicy.AUTO,
    )

    assert web.calls == [
        ("user-1", "Spring Boot 当前推荐使用哪个 Java 版本？")
    ]
    assert len(answerer.calls) == 1
    assert {citation.source_type for citation in snapshot.citations} == {
        "MATERIAL",
        "WEB",
    }
    assert snapshot.retrieval_mode == "HYBRID"


async def test_each_turn_retrieves_again_and_passes_visible_history() -> None:
    retriever = FakeRetriever([evidence()])
    web = FakeWebSearcher()
    answerer = FakeAnswerer()
    service = KnowledgeConversationService(retriever, web, answerer)
    created = await service.create_conversation("user-1", KnowledgeMode.AUTO)

    await service.send_message(
        created.conversation_id,
        "我的路线从哪里开始？",
        WebSearchPolicy.DISABLED,
    )
    await service.send_message(
        created.conversation_id,
        "那第二步呢？",
        WebSearchPolicy.DISABLED,
    )

    assert len(retriever.queries) == 2
    assert answerer.calls[1]["history"] == [
        ("USER", "我的路线从哪里开始？"),
        ("ASSISTANT", "基于资料与官网，当前建议至少使用 Java 17。"),
    ]
    assert web.calls == []
