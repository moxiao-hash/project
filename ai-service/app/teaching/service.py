"""课时内多轮导师会话及用户隔离。"""

import asyncio
from dataclasses import dataclass, field
from typing import Any, Protocol
from uuid import uuid4

from app.persistence.agent_state import AgentPersistence
from app.teaching.models import TeachingAnswer, TeachingConversationSnapshot


class TeachingConversationNotFoundError(LookupError):
    pass


class TeachingConversationBusyError(RuntimeError):
    pass


class LessonProvider(Protocol):
    async def get_lesson_context(
        self,
        owner_id: str,
        lesson_id: str,
    ) -> dict[str, Any]: ...


class TeachingAnswerer(Protocol):
    async def answer(
        self,
        *,
        question: str,
        lesson: dict[str, Any],
        history: list[tuple[str, str]],
    ) -> TeachingAnswer: ...


@dataclass
class _Conversation:
    conversation_id: str
    owner_id: str
    lesson_id: str
    lesson: dict[str, Any]
    history: list[tuple[str, str]] = field(default_factory=list)
    snapshot: TeachingConversationSnapshot | None = None


class TeachingConversationService:
    def __init__(
        self,
        lesson_provider: LessonProvider,
        answerer: TeachingAnswerer,
        *,
        model_provider: str,
        model_name: str,
        persistence: AgentPersistence | None = None,
    ) -> None:
        self._lesson_provider = lesson_provider
        self._answerer: TeachingAnswerer | None = answerer
        self._model_provider = model_provider
        self._model_name = model_name
        self._persistence = persistence
        self._conversations: dict[str, _Conversation] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self._load_lock = asyncio.Lock()

    def replace_answerer(self, answerer: TeachingAnswerer) -> None:
        self._answerer = answerer

    def clear_runtime(self) -> None:
        self._answerer = None

    async def create_conversation(
        self,
        owner_id: str,
        lesson_id: str,
    ) -> TeachingConversationSnapshot:
        context = await self._lesson_provider.get_lesson_context(owner_id, lesson_id)
        lesson = context["lesson"]
        conversation_id = str(uuid4())
        snapshot = TeachingConversationSnapshot(
            conversation_id=conversation_id,
            owner_id=owner_id,
            lesson_id=lesson_id,
            model_provider=self._model_provider,
            model_name=self._model_name,
        )
        conversation = _Conversation(
            conversation_id=conversation_id,
            owner_id=owner_id,
            lesson_id=lesson_id,
            lesson=lesson,
            snapshot=snapshot,
        )
        await self._save(conversation)
        self._conversations[conversation_id] = conversation
        self._locks[conversation_id] = asyncio.Lock()
        return snapshot

    async def send_message(
        self,
        conversation_id: str,
        *,
        owner_id: str,
        message: str,
    ) -> TeachingConversationSnapshot:
        conversation = await self._find(conversation_id, owner_id)
        lock = self._locks.setdefault(conversation_id, asyncio.Lock())
        if lock.locked():
            raise TeachingConversationBusyError("课内导师正在处理另一条消息")
        async with lock:
            answerer = self._answerer
            if answerer is None:
                raise RuntimeError("课内导师模型运行时尚未注入")
            answer = await answerer.answer(
                question=message,
                lesson=conversation.lesson,
                history=list(conversation.history),
            )
            snapshot = TeachingConversationSnapshot(
                conversation_id=conversation.conversation_id,
                owner_id=conversation.owner_id,
                lesson_id=conversation.lesson_id,
                answer=answer.answer,
                citations=answer.citations,
                suggested_actions=answer.suggested_actions,
                model_provider=self._model_provider,
                model_name=self._model_name,
            )
            previous_history = list(conversation.history)
            previous_snapshot = conversation.snapshot
            conversation.history.extend(
                [("USER", message), ("ASSISTANT", answer.answer)]
            )
            conversation.snapshot = snapshot
            try:
                await self._save(conversation)
            except Exception:
                conversation.history = previous_history
                conversation.snapshot = previous_snapshot
                raise
            return snapshot

    async def get_conversation(
        self,
        conversation_id: str,
        owner_id: str,
    ) -> TeachingConversationSnapshot:
        conversation = await self._find(conversation_id, owner_id)
        assert conversation.snapshot is not None
        return conversation.snapshot

    async def _find(self, conversation_id: str, owner_id: str) -> _Conversation:
        conversation = self._conversations.get(conversation_id)
        if conversation is None and self._persistence is not None:
            async with self._load_lock:
                payload = await self._persistence.store.load(
                    kind="teaching",
                    conversation_id=conversation_id,
                    owner_id=owner_id,
                )
                if payload is not None:
                    conversation = _Conversation(
                        conversation_id=conversation_id,
                        owner_id=payload["owner_id"],
                        lesson_id=payload["lesson_id"],
                        lesson=payload["lesson"],
                        history=[tuple(item) for item in payload["history"]],
                        snapshot=TeachingConversationSnapshot.model_validate(
                            payload["snapshot"]
                        ),
                    )
                    self._conversations[conversation_id] = conversation
                    self._locks.setdefault(conversation_id, asyncio.Lock())
        if conversation is None or conversation.owner_id != owner_id:
            raise TeachingConversationNotFoundError("课内导师会话不存在")
        return conversation

    async def _save(self, conversation: _Conversation) -> None:
        if self._persistence is None:
            return
        assert conversation.snapshot is not None
        await self._persistence.store.save(
            kind="teaching",
            conversation_id=conversation.conversation_id,
            owner_id=conversation.owner_id,
            payload={
                "owner_id": conversation.owner_id,
                "lesson_id": conversation.lesson_id,
                "lesson": conversation.lesson,
                "history": conversation.history,
                "snapshot": conversation.snapshot.model_dump(mode="json"),
            },
        )
