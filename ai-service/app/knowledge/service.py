"""多轮知识会话、检索路由和隐私边界。"""

import asyncio
from dataclasses import dataclass, field
from typing import Protocol
from uuid import uuid4

from app.knowledge.models import (
    KnowledgeCitation,
    KnowledgeConversationSnapshot,
    KnowledgeMode,
    WebSearchPolicy,
)
from app.retrieval.models import RetrievedEvidence
from app.search.models import WebSearchOutcome, WebSearchResult


class KnowledgeConversationNotFoundError(LookupError):
    pass


class KnowledgeConversationBusyError(RuntimeError):
    pass


class KnowledgeRetriever(Protocol):
    async def search(self, owner_id: str, query: str) -> list[RetrievedEvidence]: ...


class KnowledgeWebSearcher(Protocol):
    async def search(self, owner_id: str, query: str) -> WebSearchOutcome: ...


class KnowledgeAnswerer(Protocol):
    async def answer(
        self,
        *,
        question: str,
        history: list[tuple[str, str]],
        materials: list[RetrievedEvidence],
        web_results: list[WebSearchResult],
    ) -> str: ...


@dataclass
class _Conversation:
    conversation_id: str
    owner_id: str
    mode: KnowledgeMode
    history: list[tuple[str, str]] = field(default_factory=list)
    snapshot: KnowledgeConversationSnapshot | None = None


class KnowledgeConversationService:
    def __init__(
        self,
        retriever: KnowledgeRetriever,
        web_searcher: KnowledgeWebSearcher,
        answerer: KnowledgeAnswerer,
    ) -> None:
        self._retriever = retriever
        self._web_searcher = web_searcher
        self._answerer = answerer
        self._conversations: dict[str, _Conversation] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    async def create_conversation(
        self,
        owner_id: str,
        mode: KnowledgeMode,
    ) -> KnowledgeConversationSnapshot:
        conversation_id = str(uuid4())
        snapshot = KnowledgeConversationSnapshot(
            conversation_id=conversation_id,
            owner_id=owner_id,
            mode=mode,
            retrieval_mode="NONE",
        )
        self._conversations[conversation_id] = _Conversation(
            conversation_id=conversation_id,
            owner_id=owner_id,
            mode=mode,
            snapshot=snapshot,
        )
        self._locks[conversation_id] = asyncio.Lock()
        return snapshot

    async def get_conversation(
        self,
        conversation_id: str,
    ) -> KnowledgeConversationSnapshot:
        conversation = self._find(conversation_id)
        assert conversation.snapshot is not None
        return conversation.snapshot

    async def send_message(
        self,
        conversation_id: str,
        message: str,
        web_search: WebSearchPolicy,
    ) -> KnowledgeConversationSnapshot:
        conversation = self._find(conversation_id)
        lock = self._locks[conversation_id]
        if lock.locked():
            raise KnowledgeConversationBusyError("知识会话正在处理另一条消息")
        async with lock:
            materials = await self._retriever.search(conversation.owner_id, message)
            private = [
                item
                for item in materials
                if item.privacy_level in {"SENSITIVE", "LOCAL_ONLY"}
            ]
            if private:
                snapshot = self._private_snapshot(conversation, private)
            else:
                outcome = WebSearchOutcome(query=message)
                if self._should_search_web(conversation.mode, web_search, message):
                    outcome = await self._web_searcher.search(conversation.owner_id, message)
                answer = await self._answerer.answer(
                    question=message,
                    history=list(conversation.history),
                    materials=materials,
                    web_results=list(outcome.results),
                )
                snapshot = self._grounded_snapshot(
                    conversation,
                    answer,
                    materials,
                    outcome,
                )
            conversation.history.extend([("USER", message), ("ASSISTANT", snapshot.answer)])
            conversation.snapshot = snapshot
            return snapshot

    def _find(self, conversation_id: str) -> _Conversation:
        try:
            return self._conversations[conversation_id]
        except KeyError as exc:
            raise KnowledgeConversationNotFoundError("知识会话不存在") from exc

    @staticmethod
    def _should_search_web(
        mode: KnowledgeMode,
        policy: WebSearchPolicy,
        query: str,
    ) -> bool:
        if mode == KnowledgeMode.LOCAL_ONLY or policy == WebSearchPolicy.DISABLED:
            return False
        if policy == WebSearchPolicy.ENABLED:
            return True
        recency_markers = (
            "当前",
            "最新",
            "版本",
            "官方",
            "发布",
            "api",
            "today",
            "latest",
            "current",
            "version",
            "release",
        )
        lowered = query.lower()
        return any(marker in lowered for marker in recency_markers)

    @staticmethod
    def _material_citations(
        materials: list[RetrievedEvidence],
    ) -> list[KnowledgeCitation]:
        return [
            KnowledgeCitation(
                source_type="MATERIAL",
                material_id=item.material_id,
                title=item.title,
                locator=item.locator,
                snippet=item.text,
            )
            for item in materials
        ]

    def _private_snapshot(
        self,
        conversation: _Conversation,
        materials: list[RetrievedEvidence],
    ) -> KnowledgeConversationSnapshot:
        excerpts = "\n".join(
            f"- {item.title}（{item.locator}）：{item.text}" for item in materials
        )
        return KnowledgeConversationSnapshot(
            conversation_id=conversation.conversation_id,
            owner_id=conversation.owner_id,
            mode=conversation.mode,
            answer=f"根据本地隐私资料，检索到以下相关内容：\n{excerpts}",
            retrieval_mode="LOCAL_ONLY",
            citations=self._material_citations(materials),
            warnings=["隐私资料正文和本轮问题未发送给 DeepSeek 或 Tavily"],
        )

    def _grounded_snapshot(
        self,
        conversation: _Conversation,
        answer: str,
        materials: list[RetrievedEvidence],
        outcome: WebSearchOutcome,
    ) -> KnowledgeConversationSnapshot:
        citations = self._material_citations(materials)
        citations.extend(
            KnowledgeCitation(
                source_type="WEB",
                result_id=result.result_id,
                title=result.title,
                url=result.url,
                snippet=result.snippet,
            )
            for result in outcome.results
        )
        if materials and outcome.results:
            retrieval_mode = "HYBRID"
        elif outcome.results:
            retrieval_mode = "WEB"
        elif materials:
            retrieval_mode = "MATERIAL"
        else:
            retrieval_mode = "NONE"
        return KnowledgeConversationSnapshot(
            conversation_id=conversation.conversation_id,
            owner_id=conversation.owner_id,
            mode=conversation.mode,
            answer=answer,
            retrieval_mode=retrieval_mode,
            citations=citations,
            warnings=list(outcome.warnings),
        )
