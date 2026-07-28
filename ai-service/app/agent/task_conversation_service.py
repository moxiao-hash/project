"""面向 FastAPI 的任务操作会话服务。"""

import asyncio
from dataclasses import dataclass
from datetime import date
from typing import Any
from uuid import uuid4

from langchain_core.messages import HumanMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from app.agent.task_conversation_graph import build_task_conversation_graph
from app.agent.task_conversation_models import (
    TaskConversationSnapshot,
    TaskConversationStatus,
)
from app.agent.task_models import TaskActionDraft, TaskCandidate
from app.agent.task_service import TaskRecognitionService
from app.clients.java_backend import JavaBackendClient
from app.schemas.learning import LearningTask


class TaskConversationNotFoundError(LookupError):
    pass


class TaskConversationBusyError(RuntimeError):
    pass


class InvalidTaskConversationStateError(RuntimeError):
    pass


class TaskVersionConflictError(RuntimeError):
    pass


class TaskExecutionUnavailableError(RuntimeError):
    pass


@dataclass
class _TaskConversation:
    snapshot: TaskConversationSnapshot
    lock: asyncio.Lock
    started: bool = False


class TaskConversationService:
    """管理任务会话身份、并发锁和 LangGraph thread。"""

    def __init__(
        self,
        recognition_service: TaskRecognitionService,
        java_backend: JavaBackendClient,
    ) -> None:
        self._java_backend = java_backend
        self._checkpointer = InMemorySaver()
        self._graph = build_task_conversation_graph(
            recognition_service,
            java_backend,
            self._checkpointer,
        )
        self._conversations: dict[str, _TaskConversation] = {}

    def replace_runtime(self, recognition_service: TaskRecognitionService) -> None:
        self._graph = build_task_conversation_graph(
            recognition_service,
            self._java_backend,
            self._checkpointer,
        )

    async def create_conversation(
        self,
        owner_id: str,
        target_date: date,
    ) -> TaskConversationSnapshot:
        conversation_id = str(uuid4())
        snapshot = TaskConversationSnapshot(
            conversation_id=conversation_id,
            owner_id=owner_id,
            target_date=target_date,
            status=TaskConversationStatus.COLLECTING,
            reply="任务会话已创建，请查询任务或告诉我你要完成、跳过或延期的任务。",
        )
        self._conversations[conversation_id] = _TaskConversation(
            snapshot=snapshot,
            lock=asyncio.Lock(),
        )
        return snapshot

    async def send_message(
        self,
        conversation_id: str,
        message: str,
        owner_id: str,
    ) -> TaskConversationSnapshot:
        conversation = self._require(conversation_id, owner_id)
        if conversation.snapshot.status in {
            TaskConversationStatus.COMPLETED,
            TaskConversationStatus.FAILED,
        }:
            raise InvalidTaskConversationStateError("已结束的任务会话不能继续发送消息")

        if conversation.snapshot.status == TaskConversationStatus.PREVIEW_READY:
            graph_input: dict | Command = Command(
                resume={"action": "revise", "feedback": message}
            )
        elif conversation.started:
            graph_input = {"messages": [HumanMessage(content=message)]}
        else:
            graph_input = {
                "conversation_id": conversation_id,
                "owner_id": conversation.snapshot.owner_id,
                "target_date": conversation.snapshot.target_date,
                "messages": [HumanMessage(content=message)],
                "status": TaskConversationStatus.COLLECTING.value,
            }

        snapshot, _ = await self._invoke(conversation_id, conversation, graph_input)
        conversation.started = True
        return snapshot

    async def get_conversation(
        self,
        conversation_id: str,
        owner_id: str,
    ) -> TaskConversationSnapshot:
        return self._require(conversation_id, owner_id).snapshot

    async def confirm(
        self,
        conversation_id: str,
        owner_id: str,
    ) -> TaskConversationSnapshot:
        conversation = self._require(conversation_id, owner_id)
        if conversation.snapshot.status == TaskConversationStatus.COMPLETED:
            return conversation.snapshot
        if conversation.snapshot.status != TaskConversationStatus.PREVIEW_READY:
            raise InvalidTaskConversationStateError("只有操作预览就绪的任务会话可以确认")

        snapshot, java_status_code = await self._invoke(
            conversation_id,
            conversation,
            Command(resume={"action": "approve"}),
        )
        if snapshot.status == TaskConversationStatus.FAILED:
            if java_status_code == 409:
                raise TaskVersionConflictError(snapshot.error or "任务版本已变化")
            raise TaskExecutionUnavailableError(snapshot.error or "任务操作执行失败")
        return snapshot

    async def _invoke(
        self,
        conversation_id: str,
        conversation: _TaskConversation,
        graph_input: dict | Command,
    ) -> tuple[TaskConversationSnapshot, int | None]:
        if conversation.lock.locked():
            raise TaskConversationBusyError("该任务会话正在处理另一条请求")
        async with conversation.lock:
            values = await self._graph.ainvoke(
                graph_input,
                config={"configurable": {"thread_id": conversation_id}},
            )
            snapshot = self._to_snapshot(conversation.snapshot, values)
            conversation.snapshot = snapshot
            return snapshot, values.get("java_status_code")

    @staticmethod
    def _to_snapshot(
        previous: TaskConversationSnapshot,
        values: dict[str, Any],
    ) -> TaskConversationSnapshot:
        action_data = values.get("action_draft")
        updated_data = values.get("updated_task")
        return TaskConversationSnapshot(
            conversation_id=previous.conversation_id,
            owner_id=previous.owner_id,
            target_date=previous.target_date,
            status=TaskConversationStatus(values.get("status", previous.status)),
            reply=values.get("reply", previous.reply),
            candidate_tasks=[
                TaskCandidate.model_validate(task)
                for task in values.get("candidate_tasks", [])
            ],
            action_draft=(
                TaskActionDraft.model_validate(action_data)
                if action_data
                else None
            ),
            execution_id=values.get("execution_id"),
            updated_task=(
                LearningTask.model_validate(updated_data)
                if updated_data
                else None
            ),
            error=values.get("error"),
        )

    def _require(self, conversation_id: str, owner_id: str) -> _TaskConversation:
        try:
            conversation = self._conversations[conversation_id]
        except KeyError as exc:
            raise TaskConversationNotFoundError("任务会话不存在") from exc
        if conversation.snapshot.owner_id != owner_id:
            raise TaskConversationNotFoundError("任务会话不存在")
        return conversation
