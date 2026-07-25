"""面向 FastAPI 的学习计划会话应用服务。"""

import asyncio
from dataclasses import dataclass
from typing import Any
from uuid import uuid4

from langchain_core.messages import HumanMessage
from langgraph.types import Command

from app.agent.graph import build_learning_plan_graph
from app.agent.models import ConversationSnapshot, ConversationStatus, PlanDraft
from app.agent.planner import PlanTurnGenerator
from app.clients.java_backend import JavaBackendClient
from app.schemas.learning import LearningContext


class ConversationNotFoundError(LookupError):
    pass


class GoalNotFoundError(LookupError):
    pass


class ConversationBusyError(RuntimeError):
    pass


class InvalidConversationStateError(RuntimeError):
    pass


@dataclass
class _Conversation:
    context: LearningContext
    snapshot: ConversationSnapshot
    lock: asyncio.Lock
    started: bool = False


class ConversationService:
    """管理会话身份、并发控制，并把 conversationId 映射为 LangGraph thread。"""

    def __init__(
        self,
        planner: PlanTurnGenerator,
        java_backend: JavaBackendClient,
    ) -> None:
        self._java_backend = java_backend
        self._graph = build_learning_plan_graph(planner, java_backend)
        self._conversations: dict[str, _Conversation] = {}

    async def create_conversation(
        self,
        owner_id: str,
        goal_id: str,
    ) -> ConversationSnapshot:
        context = await self._java_backend.get_learning_context(owner_id)
        if not any(goal.id == goal_id for goal in context.goals):
            raise GoalNotFoundError("学习目标不存在或不属于当前用户")

        conversation_id = str(uuid4())
        snapshot = ConversationSnapshot(
            conversation_id=conversation_id,
            owner_id=owner_id,
            goal_id=goal_id,
            status=ConversationStatus.COLLECTING,
            reply="会话已创建，请告诉我你的学习安排和要求。",
        )
        self._conversations[conversation_id] = _Conversation(
            context=context,
            snapshot=snapshot,
            lock=asyncio.Lock(),
        )
        return snapshot

    async def send_message(
        self,
        conversation_id: str,
        message: str,
    ) -> ConversationSnapshot:
        conversation = self._require(conversation_id)
        if conversation.snapshot.status == ConversationStatus.COMPLETED:
            raise InvalidConversationStateError("已完成的会话不能继续发送消息")

        if conversation.snapshot.status == ConversationStatus.DRAFT_READY:
            graph_input: dict | Command = Command(resume={"action": "revise", "feedback": message})
        elif conversation.started:
            graph_input = {"messages": [HumanMessage(content=message)]}
        else:
            graph_input = {
                "conversation_id": conversation_id,
                "owner_id": conversation.snapshot.owner_id,
                "goal_id": conversation.snapshot.goal_id,
                "messages": [HumanMessage(content=message)],
                "learning_context": conversation.context.model_dump(mode="json"),
                "status": ConversationStatus.COLLECTING.value,
            }

        result = await self._invoke(conversation_id, conversation, graph_input)
        conversation.started = True
        return result

    async def get_conversation(self, conversation_id: str) -> ConversationSnapshot:
        return self._require(conversation_id).snapshot

    async def confirm(self, conversation_id: str) -> ConversationSnapshot:
        conversation = self._require(conversation_id)
        if conversation.snapshot.status == ConversationStatus.COMPLETED:
            return conversation.snapshot
        if conversation.snapshot.status != ConversationStatus.DRAFT_READY:
            raise InvalidConversationStateError("只有草稿就绪的会话可以确认")
        return await self._invoke(
            conversation_id,
            conversation,
            Command(resume={"action": "approve"}),
        )

    async def _invoke(
        self,
        conversation_id: str,
        conversation: _Conversation,
        graph_input: dict | Command,
    ) -> ConversationSnapshot:
        if conversation.lock.locked():
            raise ConversationBusyError("该会话正在处理另一条请求")
        async with conversation.lock:
            values = await self._graph.ainvoke(
                graph_input,
                config={"configurable": {"thread_id": conversation_id}},
            )
            snapshot = self._to_snapshot(conversation.snapshot, values)
            conversation.snapshot = snapshot
            return snapshot

    @staticmethod
    def _to_snapshot(
        previous: ConversationSnapshot,
        values: dict[str, Any],
    ) -> ConversationSnapshot:
        draft_data = values.get("draft")
        return ConversationSnapshot(
            conversation_id=previous.conversation_id,
            owner_id=previous.owner_id,
            goal_id=previous.goal_id,
            status=ConversationStatus(values.get("status", previous.status)),
            reply=values.get("reply", previous.reply),
            draft=PlanDraft.model_validate(draft_data) if draft_data else None,
            saved_plan_id=values.get("saved_plan_id"),
            error=values.get("error"),
        )

    def _require(self, conversation_id: str) -> _Conversation:
        try:
            return self._conversations[conversation_id]
        except KeyError as exc:
            raise ConversationNotFoundError("会话不存在") from exc
