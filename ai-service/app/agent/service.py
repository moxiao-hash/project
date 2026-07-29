"""面向 FastAPI 的学习计划会话应用服务。"""

import asyncio
import logging
from dataclasses import dataclass
from typing import Any
from uuid import uuid4

from langchain_core.messages import HumanMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from app.agent.graph import build_learning_plan_graph
from app.agent.grounding import PlanGrounding, PlanGroundingService
from app.agent.models import ConversationSnapshot, ConversationStatus, PlanDraft
from app.agent.planner import PlanTurnGenerator
from app.clients.java_backend import JavaBackendClient
from app.persistence.agent_state import AgentPersistence
from app.schemas.learning import LearningContext

logger = logging.getLogger(__name__)


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
        grounding: PlanGroundingService | None = None,
        *,
        persistence: AgentPersistence | None = None,
    ) -> None:
        self._java_backend = java_backend
        self._persistence = persistence
        self._checkpointer = (
            persistence.checkpointer if persistence is not None else InMemorySaver()
        )
        self._graph: Any | None = build_learning_plan_graph(
            planner,
            java_backend,
            self._checkpointer,
        )
        self._grounding = grounding
        self._conversations: dict[str, _Conversation] = {}
        self._load_lock = asyncio.Lock()

    def replace_runtime(
        self,
        planner: PlanTurnGenerator,
        grounding: PlanGroundingService | None = None,
    ) -> None:
        """轮换模型客户端，同时保留会话字典和同一 checkpointer 的图状态。"""

        self._graph = build_learning_plan_graph(
            planner,
            self._java_backend,
            self._checkpointer,
        )
        if grounding is not None:
            self._grounding = grounding

    def clear_runtime(self) -> None:
        """释放含用户密钥的模型/搜索客户端，但保留会话与图检查点。"""

        self._graph = None
        self._grounding = None

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
        conversation = _Conversation(
            context=context,
            snapshot=snapshot,
            lock=asyncio.Lock(),
        )
        await self._save(conversation)
        self._conversations[conversation_id] = conversation
        return snapshot

    async def send_message(
        self,
        conversation_id: str,
        message: str,
        owner_id: str,
    ) -> ConversationSnapshot:
        graph = self._require_graph()
        grounding_service = self._grounding
        conversation = await self._require(conversation_id, owner_id)
        if conversation.snapshot.status == ConversationStatus.COMPLETED:
            raise InvalidConversationStateError("已完成的会话不能继续发送消息")
        if conversation.lock.locked():
            raise ConversationBusyError("该会话正在处理另一条请求")
        async with conversation.lock:
            # 在第一次 await 前取得完整租约；clear_runtime 只会清长期引用。
            grounding = await self._retrieve_grounding(
                conversation,
                message,
                grounding_service,
            )
            grounding_update = {
                "knowledge_context": grounding.context,
                "citations": [
                    citation.model_dump(by_alias=True, mode="json")
                    for citation in grounding.citations
                ],
                "warnings": grounding.warnings,
            }
            if conversation.snapshot.status == ConversationStatus.DRAFT_READY:
                graph_input: dict | Command = Command(
                    resume={"action": "revise", "feedback": message},
                    update=grounding_update,
                )
            elif conversation.started:
                graph_input = {
                    "messages": [HumanMessage(content=message)],
                    **grounding_update,
                }
            else:
                graph_input = {
                    "conversation_id": conversation_id,
                    "owner_id": conversation.snapshot.owner_id,
                    "goal_id": conversation.snapshot.goal_id,
                    "messages": [HumanMessage(content=message)],
                    "learning_context": conversation.context.model_dump(mode="json"),
                    "status": ConversationStatus.COLLECTING.value,
                    **grounding_update,
                }

            result = await self._invoke_locked(
                graph,
                conversation_id,
                conversation,
                graph_input,
            )
            conversation.started = True
            try:
                await self._save(conversation)
            except Exception:
                logger.exception("学习计划已进入 checkpoint，但 started 快照补写失败")
            return result

    async def get_conversation(
        self,
        conversation_id: str,
        owner_id: str,
    ) -> ConversationSnapshot:
        return (await self._require(conversation_id, owner_id)).snapshot

    async def confirm(
        self,
        conversation_id: str,
        owner_id: str,
    ) -> ConversationSnapshot:
        graph = self._graph
        conversation = await self._require(conversation_id, owner_id)
        if conversation.snapshot.status == ConversationStatus.COMPLETED:
            return conversation.snapshot
        if conversation.snapshot.status != ConversationStatus.DRAFT_READY:
            raise InvalidConversationStateError("只有草稿就绪的会话可以确认")
        if conversation.lock.locked():
            raise ConversationBusyError("该会话正在处理另一条请求")
        async with conversation.lock:
            if graph is None:
                raise RuntimeError("学习计划模型运行时尚未注入")
            return await self._invoke_locked(
                graph,
                conversation_id,
                conversation,
                Command(resume={"action": "approve"}),
            )

    async def _invoke_locked(
        self,
        graph: Any,
        conversation_id: str,
        conversation: _Conversation,
        graph_input: dict | Command,
    ) -> ConversationSnapshot:
        values = await graph.ainvoke(
            graph_input,
            config={"configurable": {"thread_id": conversation_id}},
        )
        snapshot = self._to_snapshot(conversation.snapshot, values)
        conversation.snapshot = snapshot
        try:
            await self._save(conversation)
        except Exception:
            logger.exception("学习计划 checkpoint 已保存，但会话快照补写失败")
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
            citations=values.get("citations", previous.citations),
            warnings=values.get("warnings", previous.warnings),
        )

    async def _retrieve_grounding(
        self,
        conversation: _Conversation,
        message: str,
        grounding_service: PlanGroundingService | None,
    ) -> PlanGrounding:
        if grounding_service is None:
            return PlanGrounding()
        goal = next(
            goal for goal in conversation.context.goals if goal.id == conversation.snapshot.goal_id
        )
        query = f"{goal.title}\n{message}"
        return await grounding_service.retrieve(conversation.snapshot.owner_id, query)

    def _require_graph(self) -> Any:
        graph = self._graph
        if graph is None:
            raise RuntimeError("学习计划模型运行时尚未注入")
        return graph

    async def _require(self, conversation_id: str, owner_id: str) -> _Conversation:
        conversation = self._conversations.get(conversation_id)
        if conversation is None and self._persistence is not None:
            async with self._load_lock:
                conversation = self._conversations.get(conversation_id)
                if conversation is None:
                    payload = await self._persistence.store.load(
                        kind="plan",
                        conversation_id=conversation_id,
                        owner_id=owner_id,
                    )
                    if payload is not None:
                        conversation = _Conversation(
                            context=LearningContext.model_validate(payload["context"]),
                            snapshot=ConversationSnapshot.model_validate(payload["snapshot"]),
                            lock=asyncio.Lock(),
                            started=bool(payload["started"]),
                        )
                        await self._reconcile_checkpoint(conversation)
                        self._conversations[conversation_id] = conversation
        if conversation is None:
            raise ConversationNotFoundError("会话不存在")
        if conversation.snapshot.owner_id != owner_id:
            # 对越权访问也返回不存在，避免通过 ID 探测其他用户的会话。
            raise ConversationNotFoundError("会话不存在")
        return conversation

    async def _reconcile_checkpoint(self, conversation: _Conversation) -> None:
        if self._persistence is None:
            return
        checkpoint = await self._checkpointer.aget_tuple(
            {
                "configurable": {
                    "thread_id": conversation.snapshot.conversation_id,
                    "checkpoint_ns": "",
                }
            }
        )
        if checkpoint is None:
            return
        values = checkpoint.checkpoint.get("channel_values", {})
        conversation.snapshot = self._to_snapshot(conversation.snapshot, values)
        conversation.started = True
        try:
            await self._save(conversation)
        except Exception:
            logger.exception("从学习计划 checkpoint 重建快照后的补写失败")

    async def _save(self, conversation: _Conversation) -> None:
        if self._persistence is None:
            return
        await self._persistence.store.save(
            kind="plan",
            conversation_id=conversation.snapshot.conversation_id,
            owner_id=conversation.snapshot.owner_id,
            payload={
                "context": conversation.context.model_dump(mode="json"),
                "snapshot": conversation.snapshot.model_dump(mode="json"),
                "started": conversation.started,
            },
        )
