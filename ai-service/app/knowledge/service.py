"""多轮知识会话、检索路由和隐私边界。"""

import asyncio
import re
from dataclasses import dataclass, field
from typing import Protocol
from uuid import uuid4

from app.knowledge.models import (
    KnowledgeCitation,
    KnowledgeConversationSnapshot,
    KnowledgeMode,
    WebSearchPolicy,
)
from app.persistence.agent_state import AgentPersistence
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
        *,
        model_provider: str = "deepseek",
        model_name: str = "unknown",
        persistence: AgentPersistence | None = None,
    ) -> None:
        self._retriever = retriever
        self._web_searcher: KnowledgeWebSearcher | None = web_searcher
        self._answerer: KnowledgeAnswerer | None = answerer
        self._model_provider = model_provider
        self._model_name = model_name
        self._persistence = persistence
        self._conversations: dict[str, _Conversation] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self._load_lock = asyncio.Lock()
        self._pending_mutations: dict[
            tuple[str, str, WebSearchPolicy],
            tuple[KnowledgeConversationSnapshot, list[tuple[str, str]]],
        ] = {}

    def replace_runtime(
        self,
        web_searcher: KnowledgeWebSearcher,
        answerer: KnowledgeAnswerer,
    ) -> None:
        """轮换外部客户端但保留会话历史和并发锁。"""

        self._web_searcher = web_searcher
        self._answerer = answerer

    def clear_runtime(self) -> None:
        """释放外部模型/搜索客户端，同时保留历史、快照和并发锁。"""

        self._web_searcher = None
        self._answerer = None

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
            model_provider=self._model_provider,
            model_name=self._model_name,
        )
        conversation = _Conversation(
            conversation_id=conversation_id,
            owner_id=owner_id,
            mode=mode,
            snapshot=snapshot,
        )
        await self._save(conversation)
        self._conversations[conversation_id] = conversation
        self._locks[conversation_id] = asyncio.Lock()
        return snapshot

    async def get_conversation(
        self,
        conversation_id: str,
        owner_id: str,
    ) -> KnowledgeConversationSnapshot:
        conversation = await self._find(conversation_id, owner_id)
        assert conversation.snapshot is not None
        return conversation.snapshot

    async def send_message(
        self,
        conversation_id: str,
        message: str,
        web_search: WebSearchPolicy,
        owner_id: str,
    ) -> KnowledgeConversationSnapshot:
        retriever = self._retriever
        web_searcher = self._web_searcher
        answerer = self._answerer
        conversation = await self._find(conversation_id, owner_id)
        lock = self._locks.setdefault(conversation_id, asyncio.Lock())
        if lock.locked():
            raise KnowledgeConversationBusyError("知识会话正在处理另一条消息")
        async with lock:
            # 在第一次 await 前租用完整运行时快照，避免缓存淘汰中断活跃请求。
            pending_key = (conversation_id, message, web_search)
            pending = self._pending_mutations.get(pending_key)
            if pending is not None:
                snapshot, history = pending
                previous_history = list(conversation.history)
                previous_snapshot = conversation.snapshot
                conversation.history = list(history)
                conversation.snapshot = snapshot
                try:
                    await self._save(conversation)
                except Exception:
                    conversation.history = previous_history
                    conversation.snapshot = previous_snapshot
                    raise
                self._pending_mutations.pop(pending_key, None)
                return snapshot
            if self._is_model_identity_question(message):
                snapshot = self._identity_snapshot(conversation)
                return await self._commit_mutation(
                    conversation,
                    message,
                    web_search,
                    snapshot,
                )
            materials = await retriever.search(conversation.owner_id, message)
            private = [
                item for item in materials if item.privacy_level in {"SENSITIVE", "LOCAL_ONLY"}
            ]
            if private:
                snapshot = self._private_snapshot(conversation, private)
            else:
                if web_searcher is None or answerer is None:
                    raise RuntimeError("知识问答模型运行时尚未注入")
                outcome = WebSearchOutcome(query=message)
                if self._should_search_web(conversation.mode, web_search, message):
                    outcome = await web_searcher.search(conversation.owner_id, message)
                answer = await answerer.answer(
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
            return await self._commit_mutation(
                conversation,
                message,
                web_search,
                snapshot,
            )

    async def _commit_mutation(
        self,
        conversation: _Conversation,
        message: str,
        web_search: WebSearchPolicy,
        snapshot: KnowledgeConversationSnapshot,
    ) -> KnowledgeConversationSnapshot:
        previous_history = list(conversation.history)
        previous_snapshot = conversation.snapshot
        next_history = [
            *previous_history,
            ("USER", message),
            ("ASSISTANT", snapshot.answer),
        ]
        conversation.history = next_history
        conversation.snapshot = snapshot
        key = (conversation.conversation_id, message, web_search)
        try:
            await self._save(conversation)
        except Exception:
            conversation.history = previous_history
            conversation.snapshot = previous_snapshot
            self._pending_mutations[key] = (snapshot, next_history)
            raise
        self._pending_mutations.pop(key, None)
        return snapshot

    async def _find(self, conversation_id: str, owner_id: str) -> _Conversation:
        conversation = self._conversations.get(conversation_id)
        if conversation is None and self._persistence is not None:
            async with self._load_lock:
                conversation = self._conversations.get(conversation_id)
                if conversation is None:
                    payload = await self._persistence.store.load(
                        kind="knowledge",
                        conversation_id=conversation_id,
                        owner_id=owner_id,
                    )
                    if payload is not None:
                        conversation = _Conversation(
                            conversation_id=conversation_id,
                            owner_id=payload["owner_id"],
                            mode=KnowledgeMode(payload["mode"]),
                            history=[tuple(item) for item in payload["history"]],
                            snapshot=KnowledgeConversationSnapshot.model_validate(
                                payload["snapshot"]
                            ),
                        )
                        self._conversations[conversation_id] = conversation
                        self._locks.setdefault(conversation_id, asyncio.Lock())
        if conversation is None:
            raise KnowledgeConversationNotFoundError("知识会话不存在")
        if conversation.owner_id != owner_id:
            raise KnowledgeConversationNotFoundError("知识会话不存在")
        return conversation

    async def _save(self, conversation: _Conversation) -> None:
        if self._persistence is None:
            return
        assert conversation.snapshot is not None
        await self._persistence.store.save(
            kind="knowledge",
            conversation_id=conversation.conversation_id,
            owner_id=conversation.owner_id,
            payload={
                "owner_id": conversation.owner_id,
                "mode": conversation.mode.value,
                "history": conversation.history,
                "snapshot": conversation.snapshot.model_dump(mode="json"),
            },
        )

    @staticmethod
    def _is_model_identity_question(question: str) -> bool:
        normalized = re.sub(r"[\s？?。.!！,，]", "", question.lower())
        chinese_identity = re.fullmatch(
            r"(请问|请告诉我)?(你背后|你|底层)"
            r"(使用的|用的|是)?(什么|哪个)模型(呢)?",
            normalized,
        )
        return chinese_identity is not None or normalized in {
            "whatmodelareyou",
            "whichmodelareyou",
        }

    def _identity_snapshot(
        self,
        conversation: _Conversation,
    ) -> KnowledgeConversationSnapshot:
        return KnowledgeConversationSnapshot(
            conversation_id=conversation.conversation_id,
            owner_id=conversation.owner_id,
            mode=conversation.mode,
            answer=(
                "我是 StudyPilot 的知识助手，当前由 "
                f"{self._model_provider} 提供的 {self._model_name} 模型驱动。"
            ),
            retrieval_mode="NONE",
            citations=[],
            warnings=["模型身份来自 StudyPilot 服务端配置，不属于检索来源"],
            model_provider=self._model_provider,
            model_name=self._model_name,
        )

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
        excerpts = "\n".join(f"- {item.title}（{item.locator}）：{item.text}" for item in materials)
        return KnowledgeConversationSnapshot(
            conversation_id=conversation.conversation_id,
            owner_id=conversation.owner_id,
            mode=conversation.mode,
            answer=f"根据本地隐私资料，检索到以下相关内容：\n{excerpts}",
            retrieval_mode="LOCAL_ONLY",
            citations=self._material_citations(materials),
            warnings=["隐私资料正文和本轮问题未发送给 DeepSeek 或 Tavily"],
            model_provider=self._model_provider,
            model_name=self._model_name,
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
            model_provider=self._model_provider,
            model_name=self._model_name,
        )
