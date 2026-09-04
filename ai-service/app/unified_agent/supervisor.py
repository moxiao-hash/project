"""StudyPilot 单入口 LangGraph Supervisor。"""

import asyncio
from dataclasses import dataclass
from typing import Any, TypedDict
from uuid import uuid4

from langgraph.graph import END, START, StateGraph

from app.clients.java_backend import JavaBackendClient
from app.persistence.agent_state import AgentPersistence
from app.unified_agent.models import (
    AssistantConversationSnapshot,
    AssistantConversationStatus,
    AssistantEvent,
    AssistantIntent,
    AssistantMessage,
    PublicToolStep,
    UiAction,
)
from app.unified_agent.tool_gateway import ToolBudget, UnifiedToolGateway


class AssistantConversationNotFoundError(LookupError):
    pass


class AssistantConversationBusyError(RuntimeError):
    pass


class SupervisorState(TypedDict, total=False):
    message: str
    idempotency_key: str
    client_context: dict[str, Any]
    gateway: UnifiedToolGateway
    intent: str
    reply: str
    tool_steps: list[dict[str, str]]
    pending_action: dict[str, Any] | None
    ui_actions: list[dict[str, Any]]


@dataclass
class _Conversation:
    snapshot: AssistantConversationSnapshot
    lock: asyncio.Lock
    turn_results: dict[str, AssistantConversationSnapshot]
    events: list[AssistantEvent]
    active_turn_id: str | None = None
    cancel_requested_turn_id: str | None = None


class UnifiedAgentSupervisor:
    """协调专用能力；不暴露思维链，也不把普通聊天当作确认。"""

    def __init__(
        self,
        java_backend: JavaBackendClient,
        *,
        model_name: str,
        persistence: AgentPersistence | None = None,
    ) -> None:
        self._java = java_backend
        self._model_name = model_name
        self._persistence = persistence
        self._conversations: dict[str, _Conversation] = {}
        self._graph = self._build_graph()

    async def create_conversation(self, owner_id: str) -> AssistantConversationSnapshot:
        conversation_id = str(uuid4())
        snapshot = AssistantConversationSnapshot(
            conversation_id=conversation_id,
            owner_id=owner_id,
            status=AssistantConversationStatus.READY,
            reply="我已经准备好，可以帮你操作 StudyPilot 或继续学习。",
            model_name=self._model_name,
        )
        self._conversations[conversation_id] = _Conversation(
            snapshot=snapshot,
            lock=asyncio.Lock(),
            turn_results={},
            events=[
                AssistantEvent(
                    sequence=1,
                    type="TURN_COMPLETED",
                    conversation_id=conversation_id,
                    payload={"phase": "CONVERSATION_CREATED"},
                )
            ],
        )
        await self._save(self._conversations[conversation_id])
        return snapshot

    async def get_conversation(
        self, conversation_id: str, owner_id: str
    ) -> AssistantConversationSnapshot:
        return (await self._require(conversation_id, owner_id)).snapshot

    async def list_events(
        self,
        conversation_id: str,
        owner_id: str,
        after_sequence: int = 0,
    ) -> list[AssistantEvent]:
        conversation = await self._require(conversation_id, owner_id)
        return [event for event in conversation.events if event.sequence > after_sequence]

    async def confirm_action(
        self, conversation_id: str, action_id: str, owner_id: str
    ) -> AssistantConversationSnapshot:
        conversation = await self._require(conversation_id, owner_id)
        pending = conversation.snapshot.pending_action
        if pending is None or pending.action_id != action_id:
            raise AssistantConversationNotFoundError("待确认操作不存在")
        response = await self._java.confirm_agent_tool_action(action_id, owner_id)
        confirmed = type(pending).model_validate(response)
        ui_actions = list(conversation.snapshot.ui_actions)
        if confirmed.tool_name == "assessment.wrong_question_review.create":
            result = confirmed.result if isinstance(confirmed.result, dict) else {}
            quiz_id = result.get("quizId")
            if isinstance(quiz_id, str) and quiz_id:
                ui_actions.append(
                    UiAction(
                        route_key="QUIZ",
                        params={"quizId": quiz_id},
                        reason="开始已确认的错题重做测验",
                    )
                )
        snapshot = conversation.snapshot.model_copy(
            update={
                "status": AssistantConversationStatus.COMPLETED,
                "reply": "操作已确认并执行。",
                "pending_action": None,
                "ui_actions": ui_actions,
                "messages": [
                    *conversation.snapshot.messages,
                    AssistantMessage(role="assistant", content="操作已确认并执行。"),
                ],
            }
        )
        conversation.snapshot = snapshot
        conversation.events.append(
            AssistantEvent(
                sequence=len(conversation.events) + 1,
                type="TURN_COMPLETED",
                conversation_id=conversation_id,
                payload={"actionId": action_id, "actionStatus": confirmed.status},
            )
        )
        await self._save(conversation)
        return snapshot

    async def reject_action(
        self, conversation_id: str, action_id: str, owner_id: str
    ) -> AssistantConversationSnapshot:
        conversation = await self._require(conversation_id, owner_id)
        pending = conversation.snapshot.pending_action
        if pending is None or pending.action_id != action_id:
            raise AssistantConversationNotFoundError("待确认操作不存在")
        await self._java.reject_agent_tool_action(action_id, owner_id)
        snapshot = conversation.snapshot.model_copy(
            update={
                "status": AssistantConversationStatus.COMPLETED,
                "reply": "操作已取消。",
                "pending_action": None,
                "messages": [
                    *conversation.snapshot.messages,
                    AssistantMessage(role="assistant", content="操作已取消。"),
                ],
            }
        )
        conversation.snapshot = snapshot
        conversation.events.append(
            AssistantEvent(
                sequence=len(conversation.events) + 1,
                type="TURN_COMPLETED",
                conversation_id=conversation_id,
                payload={"actionId": action_id, "actionStatus": "REJECTED"},
            )
        )
        await self._save(conversation)
        return snapshot

    async def cancel_turn(
        self, conversation_id: str, turn_id: str, owner_id: str
    ) -> AssistantConversationSnapshot:
        conversation = await self._require(conversation_id, owner_id)
        if turn_id in conversation.turn_results:
            return conversation.turn_results[turn_id]
        if conversation.active_turn_id != turn_id:
            raise AssistantConversationNotFoundError("正在执行的轮次不存在")
        conversation.cancel_requested_turn_id = turn_id
        conversation.events.append(
            AssistantEvent(
                sequence=len(conversation.events) + 1,
                type="TURN_FAILED",
                conversation_id=conversation_id,
                payload={"turnId": turn_id, "reason": "CANCEL_REQUESTED"},
            )
        )
        await self._save(conversation)
        return conversation.snapshot.model_copy(
            update={"reply": "已请求取消当前轮次，正在停止后续工具调用。"}
        )

    async def send_message(
        self,
        conversation_id: str,
        message: str,
        idempotency_key: str,
        owner_id: str,
        client_context: dict[str, Any],
    ) -> AssistantConversationSnapshot:
        conversation = await self._require(conversation_id, owner_id)
        existing = conversation.turn_results.get(idempotency_key)
        if existing is not None:
            return existing
        if conversation.lock.locked():
            raise AssistantConversationBusyError("统一 Agent 正在处理上一条消息")
        async with conversation.lock:
            # 等待确认时只能走 Java 专用确认接口；“确认”等普通文本不会执行动作。
            if conversation.snapshot.pending_action is not None:
                result = conversation.snapshot.model_copy(
                    update={
                        "status": AssistantConversationStatus.WAITING_CONFIRMATION,
                        "reply": "该操作仍在等待专用确认。请使用操作卡片确认或取消。",
                        "messages": [
                            *conversation.snapshot.messages,
                            AssistantMessage(role="user", content=message),
                            AssistantMessage(
                                role="assistant",
                                content="该操作仍在等待专用确认。请使用操作卡片确认或取消。",
                            ),
                        ],
                    }
                )
            else:
                gateway = UnifiedToolGateway(self._java, owner_id, ToolBudget())
                conversation.active_turn_id = idempotency_key
                try:
                    values = await self._graph.ainvoke(
                        {
                            "message": message,
                            "idempotency_key": idempotency_key,
                            # 客户端上下文只是提示。当前确定性路由不从中读取 ownerId，
                            # 后续使用实体 ID 时仍必须由 Java 工具重新校验归属。
                            "client_context": client_context,
                            "gateway": gateway,
                        }
                    )
                except BaseException as exc:
                    conversation.active_turn_id = None
                    conversation.events.append(
                        AssistantEvent(
                            sequence=len(conversation.events) + 1,
                            type="TURN_FAILED",
                            conversation_id=conversation_id,
                            payload={
                                "turnId": idempotency_key,
                                "errorType": type(exc).__name__,
                            },
                        )
                    )
                    await self._save(conversation)
                    raise
                conversation.active_turn_id = None
                if conversation.cancel_requested_turn_id == idempotency_key:
                    conversation.cancel_requested_turn_id = None
                    result = conversation.snapshot.model_copy(
                        update={
                            "status": AssistantConversationStatus.FAILED,
                            "reply": "本轮操作已取消。",
                        }
                    )
                    conversation.snapshot = result
                    conversation.turn_results[idempotency_key] = result
                    await self._save(conversation)
                    return result
                pending_action = values.get("pending_action")
                status = (
                    AssistantConversationStatus.WAITING_CONFIRMATION
                    if pending_action is not None
                    else AssistantConversationStatus.COMPLETED
                )
                result = AssistantConversationSnapshot(
                    conversation_id=conversation_id,
                    owner_id=owner_id,
                    status=status,
                    reply=values["reply"],
                    messages=[
                        *conversation.snapshot.messages,
                        AssistantMessage(role="user", content=message),
                        AssistantMessage(role="assistant", content=values["reply"]),
                    ],
                    intent=values.get("intent"),
                    tool_steps=values.get("tool_steps", []),
                    pending_action=pending_action,
                    ui_actions=values.get("ui_actions", []),
                    model_name=self._model_name,
                )
            conversation.snapshot = result
            conversation.turn_results[idempotency_key] = result
            self._append_turn_events(conversation, result)
            await self._save(conversation)
            return result

    async def _require(self, conversation_id: str, owner_id: str) -> _Conversation:
        conversation = self._conversations.get(conversation_id)
        if conversation is None and self._persistence is not None:
            payload = await self._persistence.store.load(
                kind="unified-assistant",
                conversation_id=conversation_id,
                owner_id=owner_id,
            )
            if payload is not None:
                snapshot = AssistantConversationSnapshot.model_validate(payload["snapshot"])
                conversation = _Conversation(
                    snapshot=snapshot,
                    lock=asyncio.Lock(),
                    turn_results={
                        key: AssistantConversationSnapshot.model_validate(value)
                        for key, value in payload.get("turnResults", {}).items()
                    },
                    events=[
                        AssistantEvent.model_validate(value)
                        for value in payload.get("events", [])
                    ],
                    active_turn_id=None,
                    cancel_requested_turn_id=None,
                )
                self._conversations[conversation_id] = conversation
        if conversation is None or conversation.snapshot.owner_id != owner_id:
            raise AssistantConversationNotFoundError("统一 Agent 会话不存在")
        return conversation

    async def _save(self, conversation: _Conversation) -> None:
        if self._persistence is None:
            return
        await self._persistence.store.save(
            kind="unified-assistant",
            conversation_id=conversation.snapshot.conversation_id,
            owner_id=conversation.snapshot.owner_id,
            payload={
                "snapshot": conversation.snapshot.model_dump(mode="json", by_alias=True),
                "turnResults": {
                    key: value.model_dump(mode="json", by_alias=True)
                    for key, value in conversation.turn_results.items()
                },
                "events": [
                    event.model_dump(mode="json", by_alias=True)
                    for event in conversation.events
                ],
            },
        )

    @staticmethod
    def _append_turn_events(
        conversation: _Conversation,
        snapshot: AssistantConversationSnapshot,
    ) -> None:
        event_types: list[tuple[str, dict[str, Any]]] = [
            ("TURN_STARTED", {}),
            ("CONTEXT_LOADED", {}),
        ]
        event_types.extend(
            ("TOOL_SUCCEEDED", {"toolName": step.tool_name, "summary": step.summary})
            for step in snapshot.tool_steps
        )
        if snapshot.pending_action is not None:
            event_types.append(
                (
                    "ACTION_PREVIEW",
                    {
                        "actionId": snapshot.pending_action.action_id,
                        "summary": snapshot.pending_action.summary,
                    },
                )
            )
        event_types.extend(
            ("UI_ACTION", action.model_dump(mode="json", by_alias=True))
            for action in snapshot.ui_actions
        )
        event_types.append(("TURN_COMPLETED", {"reply": snapshot.reply}))
        for event_type, payload in event_types:
            conversation.events.append(
                AssistantEvent(
                    sequence=len(conversation.events) + 1,
                    type=event_type,
                    conversation_id=snapshot.conversation_id,
                    payload=payload,
                )
            )

    @staticmethod
    def _build_graph():
        async def dispatch(state: SupervisorState) -> dict[str, Any]:
            gateway = state["gateway"]
            steps: list[PublicToolStep] = []
            context_result = await gateway.invoke("learning.context.get", {})
            steps.append(
                PublicToolStep(
                    tool_name="learning.context.get",
                    status="SUCCEEDED",
                    summary="已读取最新学习上下文",
                )
            )
            message = state["message"].strip()

            if "继续" in message and any(
                word in message for word in ("昨天", "没学完", "未完成", "学习")
            ):
                node = UnifiedAgentSupervisor._next_roadmap_node(context_result.data)
                if node is None:
                    return {
                        "intent": AssistantIntent.NAVIGATION,
                        "reply": "当前没有找到可继续的路线节点，你可以先打开学习路线查看进度。",
                        "tool_steps": steps,
                        "pending_action": None,
                        "ui_actions": [
                            UiAction(route_key="ROADMAP", reason="查看当前学习路线")
                        ],
                    }
                params = {"nodeId": str(node["id"])}
                await gateway.invoke(
                    "navigation.resolve",
                    {"routeKey": "ROADMAP_NODE", "params": params},
                )
                steps.append(
                    PublicToolStep(
                        tool_name="navigation.resolve",
                        status="SUCCEEDED",
                        summary="已解析下一个可学习节点",
                    )
                )
                return {
                    "intent": AssistantIntent.NAVIGATION,
                    "reply": f"继续学习：{node.get('title', '未完成节点')}。",
                    "tool_steps": steps,
                    "pending_action": None,
                    "ui_actions": [
                        UiAction(
                            route_key="ROADMAP_NODE",
                            params=params,
                            reason="继续最近未完成的学习节点",
                        )
                    ],
                }

            if "错题" in message and any(word in message for word in ("重做", "再做", "五题")):
                invocation = await gateway.invoke(
                    "assessment.wrong_question_review.create",
                    {},
                    idempotency_key=state["idempotency_key"],
                )
                steps.append(
                    PublicToolStep(
                        tool_name="assessment.wrong_question_review.create",
                        status=(
                            invocation.action.status
                            if invocation.action is not None
                            else "SUCCEEDED"
                        ),
                        summary="已生成错题重做操作",
                    )
                )
                if invocation.action is not None:
                    return {
                        "intent": AssistantIntent.WRONG_QUESTION_REVIEW,
                        "reply": "已准备错题重做批次，请在操作卡片中确认。",
                        "tool_steps": steps,
                        "pending_action": invocation.action,
                        "ui_actions": [],
                    }
                return {
                    "intent": AssistantIntent.WRONG_QUESTION_REVIEW,
                    "reply": "错题重做批次已经准备好。",
                    "tool_steps": steps,
                    "pending_action": None,
                    "ui_actions": [],
                }

            if "错题集" in message or ("错题" in message and "打开" in message):
                await gateway.invoke("navigation.resolve", {"routeKey": "WRONG_QUESTIONS"})
                steps.append(
                    PublicToolStep(
                        tool_name="navigation.resolve",
                        status="SUCCEEDED",
                        summary="已解析错题集页面",
                    )
                )
                return {
                    "intent": AssistantIntent.NAVIGATION,
                    "reply": "已为你打开错题集。",
                    "tool_steps": steps,
                    "pending_action": None,
                    "ui_actions": [
                        UiAction(
                            route_key="WRONG_QUESTIONS",
                            reason="查看和重做错题",
                        )
                    ],
                }

            return {
                "intent": AssistantIntent.CLARIFY,
                "reply": (
                    "请告诉我更具体的目标，例如要打开哪个页面、学习哪个知识点，"
                    "或调整哪项任务。"
                ),
                "tool_steps": steps,
                "pending_action": None,
                "ui_actions": [],
            }

        graph = StateGraph(SupervisorState)
        graph.add_node("dispatch", dispatch)
        graph.add_edge(START, "dispatch")
        graph.add_edge("dispatch", END)
        return graph.compile()

    @staticmethod
    def _next_roadmap_node(context: Any) -> dict[str, Any] | None:
        """只读取 Java 验证后的结构化字段，不解释其中任何自然语言指令。"""

        if not isinstance(context, dict):
            return None
        roadmap = context.get("roadmap")
        if not isinstance(roadmap, dict):
            return None
        stages = roadmap.get("stages")
        if not isinstance(stages, list):
            return None
        fallback: dict[str, Any] | None = None
        for stage in stages:
            if not isinstance(stage, dict):
                continue
            nodes = stage.get("nodes")
            if not isinstance(nodes, list):
                continue
            for node in nodes:
                if not isinstance(node, dict) or not isinstance(node.get("id"), str):
                    continue
                display_status = node.get("displayStatus")
                if display_status in {"IN_PROGRESS", "STARTED"}:
                    return node
                if fallback is None and display_status in {"AVAILABLE", "READY"}:
                    fallback = node
        return fallback
