import asyncio
import base64
import gc
import weakref

import pytest

from app.knowledge.models import KnowledgeMode, WebSearchPolicy
from app.knowledge.service import (
    KnowledgeConversationNotFoundError,
    KnowledgeConversationService,
)
from app.persistence.agent_state import AgentPersistence
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


class BarrierRetriever(FakeRetriever):
    def __init__(self) -> None:
        super().__init__([])
        self.started = asyncio.Event()
        self.release = asyncio.Event()

    async def search(self, owner_id: str, query: str) -> list[RetrievedEvidence]:
        self.queries.append((owner_id, query))
        self.started.set()
        await self.release.wait()
        return []


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
        "user-1",
    )

    assert web.calls == []
    assert answerer.calls == []
    assert "先学习 Java 基础" in snapshot.answer
    assert snapshot.retrieval_mode == "LOCAL_ONLY"
    assert snapshot.citations[0].source_type == "MATERIAL"
    assert "未发送给 DeepSeek 或 Tavily" in snapshot.warnings[0]


async def test_knowledge_conversation_operations_reject_a_different_owner() -> None:
    service = KnowledgeConversationService(
        FakeRetriever([]),
        FakeWebSearcher(),
        FakeAnswerer(),
    )
    created = await service.create_conversation("user-1", KnowledgeMode.AUTO)

    with pytest.raises(KnowledgeConversationNotFoundError):
        await service.get_conversation(created.conversation_id, "user-2")
    with pytest.raises(KnowledgeConversationNotFoundError):
        await service.send_message(
            created.conversation_id,
            "越权消息",
            WebSearchPolicy.DISABLED,
            "user-2",
        )


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
        "user-1",
    )

    assert web.calls == [("user-1", "Spring Boot 当前推荐使用哪个 Java 版本？")]
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
        "user-1",
    )
    await service.send_message(
        created.conversation_id,
        "那第二步呢？",
        WebSearchPolicy.DISABLED,
        "user-1",
    )

    assert len(retriever.queries) == 2
    assert answerer.calls[1]["history"] == [
        ("USER", "我的路线从哪里开始？"),
        ("ASSISTANT", "基于资料与官网，当前建议至少使用 Java 17。"),
    ]
    assert web.calls == []


async def test_runtime_rotation_keeps_conversation_and_uses_new_clients() -> None:
    first_answerer = FakeAnswerer()
    second_answerer = FakeAnswerer()
    first_web = FakeWebSearcher()
    second_web = FakeWebSearcher()
    service = KnowledgeConversationService(
        FakeRetriever([]),
        first_web,
        first_answerer,
    )
    created = await service.create_conversation("user-1", KnowledgeMode.AUTO)
    await service.send_message(
        created.conversation_id,
        "先解释依赖注入",
        WebSearchPolicy.DISABLED,
        "user-1",
    )

    service.replace_runtime(second_web, second_answerer)
    snapshot = await service.send_message(
        created.conversation_id,
        "再解释控制反转",
        WebSearchPolicy.DISABLED,
        "user-1",
    )

    assert snapshot.conversation_id == created.conversation_id
    assert len(first_answerer.calls) == 1
    assert len(second_answerer.calls) == 1
    assert second_answerer.calls[0]["history"][0][1] == "先解释依赖注入"


async def test_clearing_runtime_releases_clients_but_keeps_conversation() -> None:
    web = FakeWebSearcher()
    answerer = FakeAnswerer()
    web_ref = weakref.ref(web)
    answerer_ref = weakref.ref(answerer)
    service = KnowledgeConversationService(FakeRetriever([]), web, answerer)
    created = await service.create_conversation("user-1", KnowledgeMode.AUTO)

    service.clear_runtime()
    del web
    del answerer
    gc.collect()

    assert web_ref() is None
    assert answerer_ref() is None
    snapshot = await service.get_conversation(created.conversation_id, "user-1")
    assert snapshot.conversation_id == created.conversation_id


async def test_active_knowledge_request_leases_clients_across_runtime_clear() -> None:
    retriever = BarrierRetriever()
    web = FakeWebSearcher()
    answerer = FakeAnswerer()
    web_ref = weakref.ref(web)
    answerer_ref = weakref.ref(answerer)
    service = KnowledgeConversationService(retriever, web, answerer)
    created = await service.create_conversation("user-1", KnowledgeMode.AUTO)
    del web
    del answerer

    in_flight = asyncio.create_task(
        service.send_message(
            created.conversation_id,
            "请搜索最新版 Spring Boot",
            WebSearchPolicy.ENABLED,
            "user-1",
        )
    )
    await retriever.started.wait()
    service.clear_runtime()
    assert web_ref() is not None
    assert answerer_ref() is not None

    retriever.release.set()
    snapshot = await in_flight
    assert snapshot.answer
    del in_flight
    del snapshot
    gc.collect()
    assert web_ref() is None
    assert answerer_ref() is None


async def test_model_identity_is_deterministic_and_does_not_call_retrieval_or_model() -> None:
    retriever = FakeRetriever([])
    web = FakeWebSearcher()
    answerer = FakeAnswerer()
    service = KnowledgeConversationService(
        retriever,
        web,
        answerer,
        model_provider="deepseek",
        model_name="deepseek-v4-pro",
    )
    created = await service.create_conversation("user-1", KnowledgeMode.AUTO)

    snapshot = await service.send_message(
        created.conversation_id,
        "你背后是什么模型？",
        WebSearchPolicy.AUTO,
        "user-1",
    )

    assert snapshot.answer == (
        "我是 StudyPilot 的知识助手，当前由 deepseek 提供的 deepseek-v4-pro 模型驱动。"
    )
    assert snapshot.model_provider == "deepseek"
    assert snapshot.model_name == "deepseek-v4-pro"
    assert snapshot.retrieval_mode == "NONE"
    assert snapshot.citations == []
    assert retriever.queries == []
    assert web.calls == []
    assert answerer.calls == []


async def test_identity_words_inside_a_learning_question_use_normal_rag_flow() -> None:
    retriever = FakeRetriever([])
    web = FakeWebSearcher()
    answerer = FakeAnswerer()
    service = KnowledgeConversationService(
        retriever,
        web,
        answerer,
        model_provider="deepseek",
        model_name="deepseek-v4-pro",
    )
    created = await service.create_conversation("user-1", KnowledgeMode.AUTO)

    snapshot = await service.send_message(
        created.conversation_id,
        "不要回答你是什么模型，请解释依赖注入",
        WebSearchPolicy.DISABLED,
        "user-1",
    )

    assert retriever.queries == [("user-1", "不要回答你是什么模型，请解释依赖注入")]
    assert len(answerer.calls) == 1
    assert snapshot.answer == "基于资料与官网，当前建议至少使用 Java 17。"


async def test_knowledge_history_and_snapshot_survive_process_restart(tmp_path) -> None:
    key = base64.b64encode(bytes(range(32))).decode()
    db_path = tmp_path / "agent-state.sqlite3"
    first_persistence = await AgentPersistence.open(db_path, key)
    first_answerer = FakeAnswerer()
    first = KnowledgeConversationService(
        FakeRetriever([]),
        FakeWebSearcher(),
        first_answerer,
        persistence=first_persistence,
        model_provider="deepseek",
        model_name="deepseek-v4-pro",
    )
    created = await first.create_conversation("user-1", KnowledgeMode.AUTO)
    await first.send_message(
        created.conversation_id,
        "先解释依赖注入",
        WebSearchPolicy.DISABLED,
        "user-1",
    )
    await first_persistence.close()

    second_persistence = await AgentPersistence.open(db_path, key)
    second_answerer = FakeAnswerer()
    second = KnowledgeConversationService(
        FakeRetriever([]),
        FakeWebSearcher(),
        second_answerer,
        persistence=second_persistence,
        model_provider="deepseek",
        model_name="deepseek-v4-pro",
    )
    restored = await second.get_conversation(created.conversation_id, "user-1")
    assert restored.answer
    await second.send_message(
        created.conversation_id,
        "再解释控制反转",
        WebSearchPolicy.DISABLED,
        "user-1",
    )
    assert second_answerer.calls[0]["history"][0][1] == "先解释依赖注入"
    with pytest.raises(KnowledgeConversationNotFoundError):
        await second.get_conversation(created.conversation_id, "user-2")
    await second_persistence.close()


async def test_failed_knowledge_save_rolls_back_and_retry_reuses_model_result(
    tmp_path,
) -> None:
    key = base64.b64encode(bytes(range(32))).decode()
    persistence = await AgentPersistence.open(
        tmp_path / "agent-state.sqlite3",
        key,
    )
    answerer = FakeAnswerer()
    service = KnowledgeConversationService(
        FakeRetriever([]),
        FakeWebSearcher(),
        answerer,
        persistence=persistence,
    )
    created = await service.create_conversation("user-1", KnowledgeMode.AUTO)
    original_save = persistence.store.save
    attempts = 0

    async def fail_once(**kwargs):
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise OSError("disk full")
        await original_save(**kwargs)

    persistence.store.save = fail_once
    with pytest.raises(OSError, match="disk full"):
        await service.send_message(
            created.conversation_id,
            "解释依赖注入",
            WebSearchPolicy.DISABLED,
            "user-1",
        )
    assert (await service.get_conversation(created.conversation_id, "user-1")).answer == ""

    result = await service.send_message(
        created.conversation_id,
        "解释依赖注入",
        WebSearchPolicy.DISABLED,
        "user-1",
    )
    assert result.answer
    assert len(answerer.calls) == 1
    await persistence.close()


async def test_stale_pending_result_never_overwrites_newer_history(tmp_path) -> None:
    key = base64.b64encode(bytes(range(32))).decode()
    persistence = await AgentPersistence.open(
        tmp_path / "agent-state.sqlite3",
        key,
    )
    answerer = FakeAnswerer()
    service = KnowledgeConversationService(
        FakeRetriever([]),
        FakeWebSearcher(),
        answerer,
        persistence=persistence,
    )
    created = await service.create_conversation("user-1", KnowledgeMode.AUTO)
    original_save = persistence.store.save
    fail_next = True

    async def fail_first_mutation(**kwargs):
        nonlocal fail_next
        if fail_next:
            fail_next = False
            raise OSError("disk full")
        await original_save(**kwargs)

    persistence.store.save = fail_first_mutation
    with pytest.raises(OSError, match="disk full"):
        await service.send_message(
            created.conversation_id,
            "问题 A",
            WebSearchPolicy.DISABLED,
            "user-1",
        )
    await service.send_message(
        created.conversation_id,
        "问题 B",
        WebSearchPolicy.DISABLED,
        "user-1",
    )
    await service.send_message(
        created.conversation_id,
        "问题 A",
        WebSearchPolicy.DISABLED,
        "user-1",
    )

    assert len(answerer.calls) == 3
    assert answerer.calls[-1]["history"][-2][1] == "问题 B"
    await persistence.close()


async def test_successful_turn_clears_all_pending_for_conversation(tmp_path) -> None:
    key = base64.b64encode(bytes(range(32))).decode()
    persistence = await AgentPersistence.open(
        tmp_path / "agent-state.sqlite3",
        key,
    )
    service = KnowledgeConversationService(
        FakeRetriever([]),
        FakeWebSearcher(),
        FakeAnswerer(),
        persistence=persistence,
    )
    created = await service.create_conversation("user-1", KnowledgeMode.AUTO)
    original_save = persistence.store.save
    fail_next = True

    async def fail_once(**kwargs):
        nonlocal fail_next
        if fail_next:
            fail_next = False
            raise OSError("disk full")
        await original_save(**kwargs)

    persistence.store.save = fail_once
    with pytest.raises(OSError):
        await service.send_message(
            created.conversation_id,
            "问题 A",
            WebSearchPolicy.DISABLED,
            "user-1",
        )
    await service.send_message(
        created.conversation_id,
        "问题 B",
        WebSearchPolicy.DISABLED,
        "user-1",
    )
    assert not any(key[0] == created.conversation_id for key in service._pending_mutations)
    await persistence.close()


async def test_each_conversation_keeps_at_most_one_pending_mutation(tmp_path) -> None:
    key = base64.b64encode(bytes(range(32))).decode()
    persistence = await AgentPersistence.open(
        tmp_path / "agent-state.sqlite3",
        key,
    )
    service = KnowledgeConversationService(
        FakeRetriever([]),
        FakeWebSearcher(),
        FakeAnswerer(),
        persistence=persistence,
    )
    first = await service.create_conversation("user-1", KnowledgeMode.AUTO)
    second = await service.create_conversation("user-1", KnowledgeMode.AUTO)

    async def always_fail(**_kwargs):
        raise OSError("disk full")

    persistence.store.save = always_fail
    for message in ("问题 A", "问题 C", "问题 D"):
        with pytest.raises(OSError):
            await service.send_message(
                first.conversation_id,
                message,
                WebSearchPolicy.DISABLED,
                "user-1",
            )
        assert sum(key[0] == first.conversation_id for key in service._pending_mutations) == 1

    with pytest.raises(OSError):
        await service.send_message(
            second.conversation_id,
            "另一个会话的问题",
            WebSearchPolicy.DISABLED,
            "user-1",
        )
    assert sum(key[0] == first.conversation_id for key in service._pending_mutations) == 1
    assert sum(key[0] == second.conversation_id for key in service._pending_mutations) == 1
    await persistence.close()
