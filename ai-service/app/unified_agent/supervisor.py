"""StudyPilot 单入口 LangGraph Supervisor。"""

import asyncio
import re
from dataclasses import dataclass
from datetime import datetime
from typing import Any, TypedDict
from uuid import uuid4
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from langgraph.graph import END, START, StateGraph

from app.clients.java_backend import JavaBackendClient
from app.knowledge.models import KnowledgeMode, WebSearchPolicy
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
    owner_id: str
    message: str
    idempotency_key: str
    client_context: dict[str, Any]
    gateway: UnifiedToolGateway
    intent: str
    reply: str
    tool_steps: list[dict[str, str]]
    pending_action: dict[str, Any] | None
    ui_actions: list[dict[str, Any]]
    warnings: list[str]
    citations: list[Any]
    knowledge_conversation_id: str | None


@dataclass
class _Conversation:
    snapshot: AssistantConversationSnapshot
    lock: asyncio.Lock
    turn_results: dict[str, AssistantConversationSnapshot]
    events: list[AssistantEvent]
    knowledge_conversation_id: str | None = None
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
        knowledge_services: Any | None = None,
    ) -> None:
        self._java = java_backend
        self._model_name = model_name
        self._persistence = persistence
        self._knowledge_services = knowledge_services
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
                            "owner_id": owner_id,
                            "message": message,
                            "idempotency_key": idempotency_key,
                            # 客户端上下文只是提示。当前确定性路由不从中读取 ownerId，
                            # 后续使用实体 ID 时仍必须由 Java 工具重新校验归属。
                            "client_context": client_context,
                            "gateway": gateway,
                            "knowledge_conversation_id": (
                                conversation.knowledge_conversation_id
                            ),
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
                conversation.knowledge_conversation_id = values.get(
                    "knowledge_conversation_id",
                    conversation.knowledge_conversation_id,
                )
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
                    warnings=values.get("warnings", []),
                    citations=values.get("citations", []),
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
                    knowledge_conversation_id=payload.get("knowledgeConversationId"),
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
                "knowledgeConversationId": conversation.knowledge_conversation_id,
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

    def _build_graph(self):
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

            limit_minutes = UnifiedAgentSupervisor._study_limit_minutes(message)
            if limit_minutes is not None and any(
                word in message for word in ("调整", "改成", "改为", "只有", "设置")
            ):
                invocation = await gateway.invoke(
                    "settings.learning.update",
                    {"dailyStudyLimitMinutes": limit_minutes},
                    idempotency_key=state["idempotency_key"],
                )
                steps.append(
                    PublicToolStep(
                        tool_name="settings.learning.update",
                        status=(
                            invocation.action.status
                            if invocation.action is not None
                            else "SUCCEEDED"
                        ),
                        summary=f"已生成每日学习时长 {limit_minutes} 分钟的调整预览",
                    )
                )
                if invocation.action is not None:
                    return {
                        "intent": AssistantIntent.PLAN,
                        "reply": (
                            f"已准备把每日学习上限调整为 {limit_minutes} 分钟。"
                            "这会影响后续日程，需要你在操作卡片中确认。"
                        ),
                        "tool_steps": steps,
                        "pending_action": invocation.action,
                        "ui_actions": [],
                    }
                return {
                    "intent": AssistantIntent.PLAN,
                    "reply": f"每日学习上限已调整为 {limit_minutes} 分钟。",
                    "tool_steps": steps,
                    "pending_action": None,
                    "ui_actions": [UiAction(route_key="TODAY", reason="查看调整后的日程")],
                }

            if "测验" in message and any(
                word in message for word in ("开始", "打开", "继续", "进入")
            ):
                node_id = UnifiedAgentSupervisor._current_node_id(
                    state.get("client_context", {}), context_result.data
                )
                if node_id is None:
                    return {
                        "intent": AssistantIntent.TEACHING,
                        "reply": "还没有找到可测验的路线节点，请先进入一个知识节点。",
                        "tool_steps": steps,
                        "pending_action": None,
                        "ui_actions": [UiAction(route_key="ROADMAP", reason="选择学习节点")],
                    }
                quiz_result = await gateway.invoke(
                    "assessment.node_quiz_status.get", {"nodeId": node_id}
                )
                steps.append(
                    PublicToolStep(
                        tool_name="assessment.node_quiz_status.get",
                        status="SUCCEEDED",
                        summary="已检查当前节点测验状态",
                    )
                )
                quiz = quiz_result.data if isinstance(quiz_result.data, dict) else {}
                quiz_id = quiz.get("quizId")
                if isinstance(quiz_id, str) and quiz_id:
                    params = {"quizId": quiz_id}
                    await gateway.invoke(
                        "navigation.resolve", {"routeKey": "QUIZ", "params": params}
                    )
                    steps.append(
                        PublicToolStep(
                            tool_name="navigation.resolve",
                            status="SUCCEEDED",
                            summary="已解析当前节点测验页面",
                        )
                    )
                    return {
                        "intent": AssistantIntent.TEACHING,
                        "reply": "测验已经准备好，现在开始作答。",
                        "tool_steps": steps,
                        "pending_action": None,
                        "ui_actions": [
                            UiAction(route_key="QUIZ", params=params, reason="开始当前节点测验")
                        ],
                    }
                return {
                    "intent": AssistantIntent.TEACHING,
                    "reply": "当前节点的测验尚未生成。请先完成节点打卡，系统会自动生成五道题。",
                    "tool_steps": steps,
                    "pending_action": None,
                    "ui_actions": [
                        UiAction(
                            route_key="ROADMAP_NODE",
                            params={"nodeId": node_id},
                            reason="完成学习总结与打卡",
                        )
                    ],
                }

            if "今天" in message and any(
                word in message for word in ("安排", "任务", "学习", "计划", "查看")
            ):
                date = UnifiedAgentSupervisor._today(state.get("client_context", {}))
                await gateway.invoke("schedule.today.get", {"date": date})
                steps.append(
                    PublicToolStep(
                        tool_name="schedule.today.get",
                        status="SUCCEEDED",
                        summary="已读取今天的路线安排",
                    )
                )
                await gateway.invoke("navigation.resolve", {"routeKey": "TODAY"})
                steps.append(
                    PublicToolStep(
                        tool_name="navigation.resolve",
                        status="SUCCEEDED",
                        summary="已解析今日学习页面",
                    )
                )
                return {
                    "intent": AssistantIntent.TASK,
                    "reply": f"已读取 {date} 的学习安排，并为你打开今日任务。",
                    "tool_steps": steps,
                    "pending_action": None,
                    "ui_actions": [UiAction(route_key="TODAY", reason="查看今日学习安排")],
                }

            if any(word in message for word in ("薄弱点", "掌握度", "掌握情况")):
                mastery_result = await gateway.invoke("assessment.mastery.list", {})
                steps.append(
                    PublicToolStep(
                        tool_name="assessment.mastery.list",
                        status="SUCCEEDED",
                        summary="已读取知识点掌握度",
                    )
                )
                weak = UnifiedAgentSupervisor._weakest_mastery(mastery_result.data)
                reply = "还没有足够的测验证据来判断薄弱点。"
                if weak is not None:
                    reply = f"目前最需要复习的是“{weak}”。我已打开掌握度页面供你查看。"
                return {
                    "intent": AssistantIntent.TEACHING,
                    "reply": reply,
                    "tool_steps": steps,
                    "pending_action": None,
                    "ui_actions": [UiAction(route_key="MASTERY", reason="查看知识掌握度")],
                }

            if "AI" in message.upper() and any(
                word in message for word in ("配置", "凭据", "模型", "设置")
            ):
                settings_result = await gateway.invoke("settings.ai_status.get", {})
                steps.append(
                    PublicToolStep(
                        tool_name="settings.ai_status.get",
                        status="SUCCEEDED",
                        summary="已安全检查 AI 配置状态",
                    )
                )
                configured = UnifiedAgentSupervisor._configured(settings_result.data)
                reply = "AI 凭据已配置。" if configured else "AI 凭据尚未配置或暂时不可用。"
                return {
                    "intent": AssistantIntent.NAVIGATION,
                    "reply": reply + "我已打开 AI 设置页面。",
                    "tool_steps": steps,
                    "pending_action": None,
                    "ui_actions": [UiAction(route_key="AI_SETTINGS", reason="查看 AI 配置")],
                }

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

            if self._knowledge_services is not None and any(
                word in message for word in ("查找", "搜索", "解释", "什么是", "怎么学")
            ):
                knowledge_service = await self._knowledge_services.for_owner(state["owner_id"])
                knowledge_conversation_id = state.get("knowledge_conversation_id")
                if knowledge_conversation_id is None:
                    knowledge_conversation = await knowledge_service.create_conversation(
                        state["owner_id"], KnowledgeMode.AUTO
                    )
                    knowledge_conversation_id = knowledge_conversation.conversation_id
                answer = await knowledge_service.send_message(
                    knowledge_conversation_id,
                    message,
                    WebSearchPolicy.AUTO,
                    state["owner_id"],
                )
                steps.append(
                    PublicToolStep(
                        tool_name="knowledge.search",
                        status="SUCCEEDED",
                        summary=f"已完成 {answer.retrieval_mode} 知识检索与回答",
                    )
                )
                return {
                    "intent": AssistantIntent.KNOWLEDGE,
                    "reply": answer.answer,
                    "tool_steps": steps,
                    "pending_action": None,
                    "ui_actions": [],
                    "warnings": answer.warnings,
                    "citations": answer.citations,
                    "knowledge_conversation_id": knowledge_conversation_id,
                }

            page_request = UnifiedAgentSupervisor._page_request(message)
            if page_request is not None:
                route_key, label, tool_names = page_request
                for tool_name in tool_names:
                    await gateway.invoke(tool_name, {})
                    steps.append(
                        PublicToolStep(
                            tool_name=tool_name,
                            status="SUCCEEDED",
                            summary=f"已读取{label}最新数据",
                        )
                    )
                await gateway.invoke("navigation.resolve", {"routeKey": route_key})
                steps.append(
                    PublicToolStep(
                        tool_name="navigation.resolve",
                        status="SUCCEEDED",
                        summary=f"已解析{label}页面",
                    )
                )
                return {
                    "intent": AssistantIntent.NAVIGATION,
                    "reply": f"已读取最新数据并为你打开{label}。",
                    "tool_steps": steps,
                    "pending_action": None,
                    "ui_actions": [UiAction(route_key=route_key, reason=f"查看{label}")],
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

    @staticmethod
    def _current_node_id(client_context: Any, context: Any) -> str | None:
        """界面参数只用于定位；真正归属校验始终由后续 Java 工具完成。"""

        if isinstance(client_context, dict) and client_context.get("routeName") == "roadmap-node":
            params = client_context.get("routeParams")
            if isinstance(params, dict):
                value = params.get("id") or params.get("nodeId")
                if isinstance(value, str) and value:
                    return value
        node = UnifiedAgentSupervisor._next_roadmap_node(context)
        return str(node["id"]) if node is not None else None

    @staticmethod
    def _today(client_context: Any) -> str:
        timezone = "Asia/Shanghai"
        if isinstance(client_context, dict) and isinstance(client_context.get("timezone"), str):
            timezone = client_context["timezone"]
        try:
            zone = ZoneInfo(timezone)
        except ZoneInfoNotFoundError:
            zone = ZoneInfo("Asia/Shanghai")
        return datetime.now(zone).date().isoformat()

    @staticmethod
    def _weakest_mastery(data: Any) -> str | None:
        if not isinstance(data, list):
            return None
        candidates = [item for item in data if isinstance(item, dict)]
        if not candidates:
            return None

        def score(item: dict[str, Any]) -> float:
            for key in ("score", "compositeScore", "masteryScore"):
                value = item.get(key)
                if isinstance(value, (int, float)):
                    return float(value)
            return 101.0

        weakest = min(candidates, key=score)
        value = weakest.get("knowledgePoint") or weakest.get("knowledge_point")
        return str(value) if value else None

    @staticmethod
    def _configured(data: Any) -> bool:
        if not isinstance(data, dict):
            return False
        for key in ("configured", "apiKeyConfigured", "hasApiKey"):
            value = data.get(key)
            if isinstance(value, bool):
                return value
        return False

    @staticmethod
    def _study_limit_minutes(message: str) -> int | None:
        minute_match = re.search(r"(\d{1,3})\s*(?:分钟|分)", message)
        if minute_match:
            value = int(minute_match.group(1))
            return value if 15 <= value <= 720 else None
        hour_match = re.search(r"(\d{1,2})(?:\.5)?\s*(?:小时|钟头)", message)
        if hour_match:
            hours = float(hour_match.group(0).split("小")[0].split("钟")[0])
            value = round(hours * 60)
            return value if 15 <= value <= 720 else None
        return None

    @staticmethod
    def _page_request(message: str) -> tuple[str, str, tuple[str, ...]] | None:
        rules = (
            (
                ("主动自动化", "自动化规则", "主动规则"),
                "LEARNING_SETTINGS",
                "主动 Agent 设置",
                ("automation.settings.get", "automation.rules.list"),
            ),
            (("学习路线",), "ROADMAP", "学习路线", ("roadmap.current.get",)),
            (("学习目标",), "LEARNING_GOALS", "学习目标", ("learning.goals.list",)),
            (("学习计划",), "LEARNING_PLANS", "学习计划", ("learning.plans.list",)),
            (("学习资料", "资料库"), "MATERIALS", "学习资料", ("materials.list",)),
            (("通知",), "NOTIFICATIONS", "通知", ("notifications.list",)),
            (
                ("执行与审计", "执行记录", "审计"),
                "AGENT_ACTIVITY",
                "执行与审计",
                ("governance.executions.list", "governance.audit.list"),
            ),
            (
                ("工作区", "实践成果"),
                "WORKSPACE_ARTIFACTS",
                "工作区与实践成果",
                ("workspaces.list", "artifacts.list"),
            ),
        )
        for keywords, route_key, label, tools in rules:
            if any(keyword in message for keyword in keywords):
                return route_key, label, tools
        return None
