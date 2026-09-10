"""StudyPilot 单入口 LangGraph Supervisor。"""

import asyncio
import re
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass, field
from datetime import datetime
from time import monotonic
from typing import Any, TypedDict
from uuid import uuid4
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from langgraph.graph import END, START, StateGraph

from app.clients.java_backend import JavaBackendClient
from app.knowledge.models import KnowledgeMode, WebSearchPolicy
from app.observability.agent_metrics import AGENT_RUNTIME_METRICS, AgentRuntimeMetrics
from app.persistence.agent_state import AgentPersistence
from app.unified_agent.event_stream import (
    DEFAULT_HEARTBEAT_SECONDS,
    DEFAULT_QUEUE_SIZE,
    AssistantEventBus,
    EventStreamOverflow,
    chunk_reply_deltas,
)
from app.unified_agent.models import (
    ALLOWED_UI_ROUTE_KEYS,
    AssistantActiveTurn,
    AssistantConversationSnapshot,
    AssistantConversationStatus,
    AssistantEvent,
    AssistantIntent,
    AssistantMessage,
    PublicToolStep,
    UiAction,
)
from app.unified_agent.planner import PlannerStatus
from app.unified_agent.planning_models import AssistantPlan, PlanIntent
from app.unified_agent.policy_validator import PLAN_REFERENCE_PATTERN
from app.unified_agent.tool_gateway import (
    DuplicateToolCallError,
    ToolBudget,
    ToolBudgetExceededError,
    ToolTurnCancelledError,
    UnifiedToolGateway,
)


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
    # Task 29：实时事件回调，由 send_message 注入；测试可省略。
    emit_event: Any
    is_cancelled: Any
    intent: str
    reply: str
    tool_steps: list[dict[str, str]]
    pending_action: dict[str, Any] | None
    ui_actions: list[dict[str, Any]]
    warnings: list[str]
    citations: list[Any]
    knowledge_conversation_id: str | None
    plan_resume: dict[str, Any] | None
    # Task 29：知识问答已实时推送真实模型增量时置真，收尾不再重复分片。
    reply_streamed: bool


@dataclass
class _Conversation:
    snapshot: AssistantConversationSnapshot
    lock: asyncio.Lock
    turn_results: dict[str, AssistantConversationSnapshot]
    events: list[AssistantEvent]
    action_results: dict[str, AssistantConversationSnapshot] = field(default_factory=dict)
    knowledge_conversation_id: str | None = None
    active_turn_id: str | None = None
    cancel_requested_turn_id: str | None = None
    # Task 28：写步骤需要确认时保存剩余计划，确认成功后继续执行。
    plan_resume: dict[str, Any] | None = None


class UnifiedAgentSupervisor:
    """协调专用能力；不暴露思维链，也不把普通聊天当作确认。"""

    def __init__(
        self,
        java_backend: JavaBackendClient,
        *,
        model_name: str,
        persistence: AgentPersistence | None = None,
        knowledge_services: Any | None = None,
        metrics: AgentRuntimeMetrics = AGENT_RUNTIME_METRICS,
        planner: Any | None = None,
        planner_provider: Any | None = None,
        event_queue_size: int = DEFAULT_QUEUE_SIZE,
        event_heartbeat_seconds: float = DEFAULT_HEARTBEAT_SECONDS,
        reply_delta_source: Callable[[str], AsyncIterator[str]] | None = None,
    ) -> None:
        self._java = java_backend
        self._model_name = model_name
        self._persistence = persistence
        self._knowledge_services = knowledge_services
        self._metrics = metrics
        self._planner = planner
        self._planner_provider = planner_provider
        self._conversations: dict[str, _Conversation] = {}
        # Task 29：每会话在线事件总线。事件先落库再发布，慢消费者只丢连接。
        self._events = AssistantEventBus(queue_size=event_queue_size)
        self._event_heartbeat_seconds = event_heartbeat_seconds
        self._reply_delta_source = reply_delta_source
        self._graph = self._build_graph()

    async def _emit(
        self,
        conversation: _Conversation,
        event_type: str,
        payload: dict[str, Any],
    ) -> AssistantEvent:
        """先持久化、再发布；顺序保证断线重连不会丢事件。"""

        event = AssistantEvent(
            sequence=len(conversation.events) + 1,
            type=event_type,
            conversation_id=conversation.snapshot.conversation_id,
            payload=payload,
        )
        conversation.events.append(event)
        self._advance_active_turn(conversation, event_type, payload)
        self._sync_stream_state(conversation)
        await self._save(conversation)
        self._events.publish(event)
        return event

    @staticmethod
    def _advance_active_turn(
        conversation: _Conversation,
        event_type: str,
        payload: dict[str, Any],
    ) -> None:
        """按增量推进 ``activeTurn.assistantText``；必须在持久化之前调用。

        ``lastEventSequence`` 与 ``assistantText`` 因此始终描述同一状态：
        快照里的文本包含该序号及之前的全部增量，客户端从该序号之后续传即可。
        """

        if event_type != "ASSISTANT_DELTA":
            return
        active = conversation.snapshot.active_turn
        if active is None:
            return
        delta = payload.get("delta")
        index = payload.get("index")
        conversation.snapshot.active_turn = active.model_copy(
            update={
                "assistant_text": active.assistant_text + (
                    delta if isinstance(delta, str) else ""
                ),
                "last_delta_index": (
                    index if isinstance(index, int) else active.last_delta_index
                ),
            }
        )

    @staticmethod
    def _clear_active_turn(conversation: _Conversation) -> None:
        """终态：清空进行中轮次状态，且必须先于终态事件持久化。"""

        conversation.active_turn_id = None
        conversation.snapshot.active_turn = None

    @staticmethod
    def _snapshot_copy(
        snapshot: AssistantConversationSnapshot,
    ) -> AssistantConversationSnapshot:
        """返回深拷贝：调用方改写结果不得污染服务端状态。"""

        return snapshot.model_copy(deep=True)

    @staticmethod
    def _sync_stream_state(conversation: _Conversation) -> None:
        """把事件游标与轮次状态同步进快照，供前端刷新后直接续传。

        原地更新而不是 ``model_copy``：调用方可能已经持有同一快照对象
        （例如 ``send_message`` 的返回值），换成新对象会让返回结果落后一个游标。
        """

        conversation.snapshot.last_event_sequence = (
            conversation.events[-1].sequence if conversation.events else 0
        )
        conversation.snapshot.active_turn_id = conversation.active_turn_id

    def _emit_callback(
        self, conversation: _Conversation
    ) -> Callable[[str, dict[str, Any]], Awaitable[None]]:
        """给工具网关/图节点使用的事件回调（闭包捕获当前会话）。"""

        async def emit(event_type: str, payload: dict[str, Any]) -> None:
            await self._emit(conversation, event_type, payload)

        return emit

    async def _emit_reply_deltas(
        self,
        conversation: _Conversation,
        turn_id: str,
        reply: str,
    ) -> None:
        """把本轮回复作为 ``ASSISTANT_DELTA`` 增量推送。

        默认来源是确定性分片；注入 ``reply_delta_source`` 后由真实模型增量替换。
        无论哪种来源，最终完整消息与增量共享同一 ``turnId``，且刷新只重放事件。
        """

        index = 0
        if self._reply_delta_source is not None:
            async for chunk in self._reply_delta_source(reply):
                if not chunk:
                    continue
                await self._emit(
                    conversation,
                    "ASSISTANT_DELTA",
                    {"turnId": turn_id, "index": index, "delta": chunk},
                )
                index += 1
            return
        for chunk in chunk_reply_deltas(reply):
            await self._emit(
                conversation,
                "ASSISTANT_DELTA",
                {"turnId": turn_id, "index": index, "delta": chunk},
            )
            index += 1

    async def stream_events(
        self,
        conversation_id: str,
        owner_id: str,
        after_sequence: int = 0,
        *,
        heartbeat_seconds: float | None = None,
    ) -> AsyncIterator[AssistantEvent | None]:
        """回放持久事件后持续推送在线事件。

        - 产出 ``AssistantEvent``：真实事件。
        - 产出 ``None``：心跳到期，调用方发送 SSE 注释帧。
        - 慢消费者队列溢出时结束流，客户端用 ``Last-Event-ID`` 重连续传。
        """

        conversation = await self._require(conversation_id, owner_id)
        heartbeat = (
            self._event_heartbeat_seconds
            if heartbeat_seconds is None
            else heartbeat_seconds
        )
        # 先订阅再读历史：两者之间发布的事件会同时出现在队列和持久列表中，
        # 用 sequence 去重即可，既不会丢事件也不会重复。
        subscription = self._events.subscribe(conversation_id)
        last_sequence = after_sequence
        try:
            for event in tuple(conversation.events):
                if event.sequence <= last_sequence:
                    continue
                last_sequence = event.sequence
                yield event
            while True:
                try:
                    event = await subscription.receive(heartbeat)
                except EventStreamOverflow:
                    return
                if event is None:
                    yield None
                    continue
                if event.sequence <= last_sequence:
                    continue
                last_sequence = event.sequence
                yield event
        finally:
            self._events.unsubscribe(subscription)

    async def _planner_for_turn(self, owner_id: str) -> Any | None:
        """优先使用固定 Planner（测试）；生产按 owner 解析凭据。

        凭据服务或模型不可用时返回 ``None``，由确定性降级层继续服务，
        不会让整轮会话失败。
        """

        if self._planner is not None:
            return self._planner
        if self._planner_provider is None:
            return None
        try:
            return await self._planner_provider(owner_id)
        except Exception:  # noqa: BLE001 - 降级优先于中断会话
            return None

    async def create_conversation(self, owner_id: str) -> AssistantConversationSnapshot:
        conversation_id = str(uuid4())
        snapshot = AssistantConversationSnapshot(
            conversation_id=conversation_id,
            owner_id=owner_id,
            status=AssistantConversationStatus.READY,
            reply="我已经准备好，可以帮你操作 StudyPilot 或继续学习。",
            model_name=self._model_name,
            # 创建即产生序号 1 的 TURN_COMPLETED(CONVERSATION_CREATED)。
            last_event_sequence=1,
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
        return self._snapshot_copy(snapshot)

    async def get_conversation(
        self, conversation_id: str, owner_id: str
    ) -> AssistantConversationSnapshot:
        return self._snapshot_copy(
            (await self._require(conversation_id, owner_id)).snapshot
        )

    async def list_events(
        self,
        conversation_id: str,
        owner_id: str,
        after_sequence: int = 0,
    ) -> list[AssistantEvent]:
        conversation = await self._require(conversation_id, owner_id)
        return [
            event.model_copy(deep=True)
            for event in conversation.events
            if event.sequence > after_sequence
        ]

    async def confirm_action(
        self, conversation_id: str, action_id: str, owner_id: str
    ) -> AssistantConversationSnapshot:
        conversation = await self._require(conversation_id, owner_id)
        if conversation.lock.locked():
            raise AssistantConversationBusyError("会话正在处理其他操作")
        async with conversation.lock:
            if action_id in conversation.action_results:
                return self._snapshot_copy(conversation.action_results[action_id])
            result = await self._confirm_action(conversation_id, action_id, owner_id)
            if result.pending_action is None:
                conversation.action_results[action_id] = self._snapshot_copy(result)
                await self._save(conversation)
            return self._snapshot_copy(result)

    async def _confirm_action(
        self, conversation_id: str, action_id: str, owner_id: str
    ) -> AssistantConversationSnapshot:
        conversation = await self._require(conversation_id, owner_id)
        pending = conversation.snapshot.pending_action
        if pending is None or pending.action_id != action_id:
            raise AssistantConversationNotFoundError("待确认操作不存在")
        response = await self._java.confirm_agent_tool_action(action_id, owner_id)
        confirmed = type(pending).model_validate(response)
        # 确认请求成功不等于业务执行成功；只相信 Java 的执行终态。
        succeeded = confirmed.status == "SUCCEEDED"
        failed = confirmed.status in {"FAILED", "REJECTED", "EXPIRED", "CANCELLED"}
        if succeeded and conversation.plan_resume is not None:
            return await self._resume_plan(
                conversation, conversation_id, owner_id, action_id, confirmed
            )
        status = (
            AssistantConversationStatus.COMPLETED if succeeded
            else AssistantConversationStatus.FAILED if failed
            else AssistantConversationStatus.RUNNING if confirmed.status in {"RUNNING", "PENDING"}
            else AssistantConversationStatus.WAITING_CONFIRMATION
        )
        reply = (
            "操作已确认并执行。" if succeeded
            else "操作未执行成功，请查看执行记录后重试。" if failed
            else "操作尚未执行完成，请查看执行状态或处理待确认事项。"
        )
        ui_actions = list(conversation.snapshot.ui_actions)
        if succeeded and confirmed.tool_name == "assessment.wrong_question_review.create":
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
                "status": status,
                "reply": reply,
                "pending_action": None if succeeded or failed else confirmed,
                "ui_actions": ui_actions,
                "messages": [
                    *conversation.snapshot.messages,
                    AssistantMessage(role="assistant", content=reply),
                ],
            }
        )
        conversation.snapshot = snapshot
        await self._emit(
            conversation,
            (
                "TURN_COMPLETED" if succeeded else "TURN_FAILED" if failed else "ACTION_PREVIEW"
            ),
            {"actionId": action_id, "actionStatus": confirmed.status},
        )
        return self._snapshot_copy(conversation.snapshot)

    async def _resume_plan(
        self,
        conversation: _Conversation,
        conversation_id: str,
        owner_id: str,
        action_id: str,
        confirmed: Any,
    ) -> AssistantConversationSnapshot:
        """确认成功后继续执行计划剩余步骤。

        剩余计划只来自服务端持久化的已验证计划，不接受客户端或模型补充的步骤；
        若剩余步骤里还有写操作，会再次生成待确认动作并保留新的续跑状态。
        """

        resume = conversation.plan_resume or {}
        conversation.plan_resume = None
        try:
            plan = AssistantPlan.model_validate(resume["plan"])
            public_steps = [
                PublicToolStep.model_validate(item) for item in resume["publicSteps"]
            ]
            outputs = dict(resume["outputs"])
            ui_actions = [
                UiAction.model_validate(item) for item in resume["uiActions"]
            ]
            start_index = int(resume["nextIndex"])
        except (KeyError, TypeError, ValueError):
            conversation.plan_resume = None
            reply = "操作已确认并执行；剩余计划状态已失效，请重新描述目标。"
            snapshot = conversation.snapshot.model_copy(
                update={
                    "status": AssistantConversationStatus.COMPLETED,
                    "reply": reply,
                    "pending_action": None,
                    "messages": [
                        *conversation.snapshot.messages,
                        AssistantMessage(role="assistant", content=reply),
                    ],
                }
            )
            conversation.snapshot = snapshot
            await self._emit(
                conversation,
                "TURN_COMPLETED",
                {
                    "actionId": action_id,
                    "actionStatus": confirmed.status,
                    "resumeStatus": "INVALID",
                },
            )
            return self._snapshot_copy(conversation.snapshot)

        if start_index > 0 and confirmed.result is not None:
            confirmed_step = plan.steps[start_index - 1]
            outputs[confirmed_step.step_id] = confirmed.result
        for public_index in range(len(public_steps) - 1, -1, -1):
            item = public_steps[public_index]
            if item.tool_name == confirmed.tool_name:
                public_steps[public_index] = item.model_copy(update={"status": "SUCCEEDED"})
                break

        gateway = UnifiedToolGateway(self._java, owner_id, ToolBudget())
        fields, next_resume = await self._run_plan(
            gateway=gateway,
            idempotency_key=f"assistant-resume:{action_id}",
            plan=plan,
            public_steps=public_steps,
            outputs=outputs,
            ui_actions=ui_actions,
            start_index=start_index,
        )
        conversation.plan_resume = next_resume
        pending_action = fields["pending_action"]
        status = (
            AssistantConversationStatus.WAITING_CONFIRMATION
            if pending_action is not None
            else AssistantConversationStatus.COMPLETED
        )
        reply = "操作已确认并执行。" + str(fields["reply"])
        snapshot = conversation.snapshot.model_copy(
            update={
                "status": status,
                "reply": reply,
                "pending_action": pending_action,
                "ui_actions": fields["ui_actions"],
                "tool_steps": fields["tool_steps"],
                "messages": [
                    *conversation.snapshot.messages,
                    AssistantMessage(role="assistant", content=reply),
                ],
            }
        )
        conversation.snapshot = snapshot
        await self._emit(
            conversation,
            (
                "TURN_COMPLETED"
                if status == AssistantConversationStatus.COMPLETED
                else "ACTION_PREVIEW"
            ),
            {"actionId": action_id, "actionStatus": confirmed.status},
        )
        return self._snapshot_copy(conversation.snapshot)

    async def reject_action(
        self, conversation_id: str, action_id: str, owner_id: str
    ) -> AssistantConversationSnapshot:
        conversation = await self._require(conversation_id, owner_id)
        if conversation.lock.locked():
            raise AssistantConversationBusyError("会话正在处理其他操作")
        async with conversation.lock:
            if action_id in conversation.action_results:
                return self._snapshot_copy(conversation.action_results[action_id])
            result = await self._reject_action(conversation_id, action_id, owner_id)
            conversation.action_results[action_id] = self._snapshot_copy(result)
            await self._save(conversation)
            return self._snapshot_copy(result)

    async def _reject_action(
        self, conversation_id: str, action_id: str, owner_id: str
    ) -> AssistantConversationSnapshot:
        conversation = await self._require(conversation_id, owner_id)
        pending = conversation.snapshot.pending_action
        if pending is None or pending.action_id != action_id:
            raise AssistantConversationNotFoundError("待确认操作不存在")
        await self._java.reject_agent_tool_action(action_id, owner_id)
        # 用户拒绝后不再执行计划剩余步骤。
        conversation.plan_resume = None
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
        await self._emit(
            conversation,
            "TURN_COMPLETED",
            {"actionId": action_id, "actionStatus": "REJECTED"},
        )
        return self._snapshot_copy(conversation.snapshot)

    async def cancel_turn(
        self, conversation_id: str, turn_id: str, owner_id: str
    ) -> AssistantConversationSnapshot:
        conversation = await self._require(conversation_id, owner_id)
        if turn_id in conversation.turn_results:
            return self._snapshot_copy(conversation.turn_results[turn_id])
        if conversation.active_turn_id != turn_id:
            raise AssistantConversationNotFoundError("正在执行的轮次不存在")
        conversation.cancel_requested_turn_id = turn_id
        await self._emit(
            conversation,
            "TURN_CANCELLED",
            {"turnId": turn_id, "reason": "CANCEL_REQUESTED"},
        )
        return self._snapshot_copy(conversation.snapshot).model_copy(
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
            return self._snapshot_copy(existing)
        if conversation.lock.locked():
            raise AssistantConversationBusyError("统一 Agent 正在处理上一条消息")
        async with conversation.lock:
            # Task 29：轮次开始立即推送，用户不必等整轮结束才看到反馈。
            # activeTurn 必须先于 TURN_STARTED 持久化，刷新才能还原用户消息。
            conversation.active_turn_id = idempotency_key
            conversation.snapshot.active_turn = AssistantActiveTurn(
                turn_id=idempotency_key,
                user_message=message,
            )
            await self._emit(
                conversation, "TURN_STARTED", {"turnId": idempotency_key}
            )
            # 等待确认时只能走 Java 专用确认接口；“确认”等普通文本不会执行动作。
            reply_streamed = False
            if conversation.snapshot.pending_action is not None:
                self._clear_active_turn(conversation)
                result = conversation.snapshot.model_copy(
                    update={
                        "status": AssistantConversationStatus.WAITING_CONFIRMATION,
                        "reply": "该操作仍在等待专用确认。请使用操作卡片确认或取消。",
                        "messages": [
                            *conversation.snapshot.messages,
                            AssistantMessage(
                                role="user", content=message,
                                turn_id=idempotency_key,
                            ),
                            AssistantMessage(
                                role="assistant",
                                content="该操作仍在等待专用确认。请使用操作卡片确认或取消。",
                                turn_id=idempotency_key,
                                status="completed",
                            ),
                        ],
                    }
                )
            else:
                gateway = UnifiedToolGateway(
                    self._java, owner_id, ToolBudget(),
                    is_cancelled=lambda: conversation.cancel_requested_turn_id == idempotency_key,
                    on_tool_event=self._emit_callback(conversation),
                )
                turn_started = monotonic()
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
                            "emit_event": self._emit_callback(conversation),
                            "is_cancelled": lambda: (
                                conversation.cancel_requested_turn_id
                                == idempotency_key
                            ),
                            "knowledge_conversation_id": (
                                conversation.knowledge_conversation_id
                            ),
                        }
                    )
                except ToolTurnCancelledError:
                    values = {"intent": "UNKNOWN"}
                except BaseException as exc:
                    self._metrics.observe_turn(
                        intent="UNKNOWN", status="error",
                        duration_seconds=monotonic() - turn_started,
                    )
                    self._clear_active_turn(conversation)
                    await self._emit(
                        conversation,
                        "TURN_FAILED",
                        {
                            "turnId": idempotency_key,
                            "errorType": type(exc).__name__,
                        },
                    )
                    raise
                conversation.active_turn_id = None
                self._metrics.observe_turn(
                    intent=str(values.get("intent", "UNKNOWN")),
                    status=(
                        "cancelled" if conversation.cancel_requested_turn_id == idempotency_key
                        else "success"
                    ),
                    duration_seconds=monotonic() - turn_started,
                )
                if conversation.cancel_requested_turn_id == idempotency_key:
                    conversation.cancel_requested_turn_id = None
                    result = conversation.snapshot.model_copy(
                        update={
                            "status": AssistantConversationStatus.FAILED,
                            "reply": "已取消本轮后续操作；已发送的请求请以执行记录为准。",
                            "messages": [
                                *conversation.snapshot.messages,
                                AssistantMessage(
                                    role="user",
                                    content=message,
                                    turn_id=idempotency_key,
                                ),
                                AssistantMessage(
                                    role="assistant",
                                    content=(
                                        conversation.snapshot.active_turn.assistant_text
                                        if conversation.snapshot.active_turn is not None
                                        else ""
                                    ) or "当前轮次已取消。",
                                    turn_id=idempotency_key,
                                    status="cancelled",
                                ),
                            ],
                        }
                    )
                    conversation.snapshot = result
                    conversation.turn_results[idempotency_key] = self._snapshot_copy(
                        result
                    )
                    # 取消请求先给出即时反馈；此处补发终态，保证流以取消事件收尾。
                    self._clear_active_turn(conversation)
                    await self._emit(
                        conversation,
                        "TURN_CANCELLED",
                        {"turnId": idempotency_key, "reason": "TURN_ABORTED"},
                    )
                    await self._save(conversation)
                    return self._snapshot_copy(conversation.snapshot)
                pending_action = values.get("pending_action")
                # 知识问答分支可能已实时推送真实模型增量，收尾时不得再切一次。
                reply_streamed = bool(values.get("reply_streamed"))
                if "plan_resume" in values:
                    # 只有模型计划轮次会写入该键；关键词降级层保持原有状态。
                    conversation.plan_resume = values["plan_resume"]
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
                        AssistantMessage(
                            role="user", content=message, turn_id=idempotency_key
                        ),
                        AssistantMessage(
                            role="assistant",
                            content=values["reply"],
                            turn_id=idempotency_key,
                            status="completed",
                        ),
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
            conversation.turn_results[idempotency_key] = self._snapshot_copy(result)
            await self._emit_turn_tail(
                conversation,
                idempotency_key,
                result,
                reply_streamed=reply_streamed,
            )
            return self._snapshot_copy(conversation.snapshot)

    async def _emit_turn_tail(
        self,
        conversation: _Conversation,
        turn_id: str,
        snapshot: AssistantConversationSnapshot,
        *,
        reply_streamed: bool = False,
    ) -> None:
        """轮次收尾事件：动作预览、界面动作、回复增量、终态。

        ``reply_streamed=True`` 表示增量已在生成过程中实时推送过（真实模型流），
        此处不得再次分片，否则客户端会看到重复内容。终态之前必须清空
        ``activeTurn``，这样刷新后的客户端不会再看到一个已经结束的"进行中"轮次。
        """

        if snapshot.pending_action is not None:
            await self._emit(
                conversation,
                "ACTION_PREVIEW",
                {
                    "turnId": turn_id,
                    "actionId": snapshot.pending_action.action_id,
                    "summary": snapshot.pending_action.summary,
                    "riskLevel": snapshot.pending_action.risk_level.value,
                },
            )
        for action in snapshot.ui_actions:
            payload = action.model_dump(mode="json", by_alias=True)
            payload["turnId"] = turn_id
            await self._emit(conversation, "UI_ACTION", payload)
        if not reply_streamed:
            await self._emit_reply_deltas(conversation, turn_id, snapshot.reply)
        self._clear_active_turn(conversation)
        await self._emit(
            conversation,
            "TURN_COMPLETED",
            {"turnId": turn_id, "reply": snapshot.reply},
        )

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
                interrupted = snapshot.active_turn
                interrupted_turn_id = (
                    interrupted.turn_id if interrupted is not None else snapshot.active_turn_id
                )
                conversation = _Conversation(
                    snapshot=snapshot.model_copy(
                        update={"active_turn_id": None, "active_turn": None}
                    ),
                    lock=asyncio.Lock(),
                    turn_results={
                        key: AssistantConversationSnapshot.model_validate(value)
                        for key, value in payload.get("turnResults", {}).items()
                    },
                    action_results={
                        key: AssistantConversationSnapshot.model_validate(value)
                        for key, value in payload.get("actionResults", {}).items()
                    },
                    events=[
                        AssistantEvent.model_validate(value)
                        for value in payload.get("events", [])
                    ],
                    knowledge_conversation_id=payload.get("knowledgeConversationId"),
                    active_turn_id=None,
                    cancel_requested_turn_id=None,
                    plan_resume=payload.get("planResume"),
                )
                self._conversations[conversation_id] = conversation
                if interrupted_turn_id is not None:
                    # 被进程重启打断的轮次必须留下终态事件，不能只清空 ID：
                    # 客户端需要知道这一轮没有完成，且不会再等它。
                    await self._emit(
                        conversation,
                        "TURN_FAILED",
                        {
                            "turnId": interrupted_turn_id,
                            "reason": "SERVICE_RESTARTED",
                            "partialAssistantText": (
                                interrupted.assistant_text if interrupted else ""
                            ),
                        },
                    )
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
                "actionResults": {
                    key: value.model_dump(mode="json", by_alias=True)
                    for key, value in conversation.action_results.items()
                },
                "events": [
                    event.model_dump(mode="json", by_alias=True)
                    for event in conversation.events
                ],
                "knowledgeConversationId": conversation.knowledge_conversation_id,
                "planResume": conversation.plan_resume,
            },
        )

    def _build_graph(self):
        async def dispatch(state: SupervisorState) -> dict[str, Any]:
            gateway = state["gateway"]
            emit = state.get("emit_event")
            steps: list[PublicToolStep] = []
            context_result = await gateway.invoke("learning.context.get", {})
            if emit is not None:
                # 上下文读取完成即可推送，后续规划/工具步骤继续增量输出。
                await emit("CONTEXT_LOADED", {"toolName": "learning.context.get"})
            steps.append(
                PublicToolStep(
                    tool_name="learning.context.get",
                    status="SUCCEEDED",
                    summary="已读取最新学习上下文",
                )
            )
            message = state["message"].strip()

            # Task 28：先尝试模型多步规划。计划必须已通过确定性策略校验；
            # 模型不可用时保持原有已验证的关键词降级层。
            planner = await self._planner_for_turn(state["owner_id"])
            if planner is not None:
                planner_outcome = await planner.propose(
                    message=message,
                    context=context_result.data,
                    client_context=state.get("client_context", {}),
                )
                if (
                    planner_outcome.status == PlannerStatus.PLAN
                    and planner_outcome.plan is not None
                ):
                    if emit is not None:
                        # 只公开计划意图、摘要与工具名，不泄露模型思维链。
                        await emit(
                            "PLAN_GENERATED",
                            {
                                "planId": str(planner_outcome.plan.plan_id),
                                "intent": planner_outcome.plan.intent.value,
                                "confidence": planner_outcome.plan.confidence,
                                "summary": planner_outcome.plan.summary,
                                "steps": [
                                    step.tool_name
                                    for step in planner_outcome.plan.steps
                                ],
                            },
                        )
                    return await self._execute_plan(
                        state,
                        planner_outcome.plan,
                        steps,
                        context_data=context_result.data,
                    )
                if planner_outcome.status == PlannerStatus.CLARIFY:
                    return {
                        "intent": AssistantIntent.CLARIFY,
                        "reply": planner_outcome.reason
                        or "请把目标拆成更具体的单个操作。",
                        "tool_steps": steps,
                        "pending_action": None,
                        "ui_actions": [],
                        "warnings": [
                            f"PLANNER_{issue.code}" for issue in planner_outcome.issues
                        ],
                    }

            if "推送" in message and any(word in message for word in ("提交", "代码", "分支")):
                workspaces_result = await gateway.invoke("workspaces.list", {})
                steps.append(
                    PublicToolStep(
                        tool_name="workspaces.list",
                        status="SUCCEEDED",
                        summary="已读取登记的代码工作区",
                    )
                )
                workspace_id = UnifiedAgentSupervisor._single_workspace_id(
                    workspaces_result.data
                )
                if workspace_id is None:
                    return UnifiedAgentSupervisor._workspace_clarification(steps)
                preview_result = await gateway.invoke(
                    "developer.git.push.preview", {"workspaceId": workspace_id}
                )
                steps.append(
                    PublicToolStep(
                        tool_name="developer.git.push.preview",
                        status="SUCCEEDED",
                        summary="已核对 origin、当前分支和待推送提交",
                    )
                )
                preview = preview_result.data if isinstance(preview_result.data, dict) else {}
                invocation = await gateway.invoke(
                    "developer.git.push",
                    {
                        "workspaceId": workspace_id,
                        "remoteName": preview.get("remoteName"),
                        "branch": preview.get("branch"),
                        "expectedHead": preview.get("expectedHead"),
                    },
                    idempotency_key=state["idempotency_key"],
                )
                steps.append(
                    PublicToolStep(
                        tool_name="developer.git.push",
                        status=invocation.action.status if invocation.action else "SUCCEEDED",
                        summary="已生成独立的 Git push 高风险确认",
                    )
                )
                return {
                    "intent": AssistantIntent.DEVELOPER,
                    "reply": "推送不会随 commit 自动执行。请单独确认本次 push。",
                    "tool_steps": steps,
                    "pending_action": invocation.action,
                    "ui_actions": [],
                }

            if "提交" in message and any(
                word in message for word in ("修改", "代码", "commit", "提交信息")
            ):
                workspaces_result = await gateway.invoke("workspaces.list", {})
                steps.append(
                    PublicToolStep(
                        tool_name="workspaces.list",
                        status="SUCCEEDED",
                        summary="已读取登记的代码工作区",
                    )
                )
                workspace_id = UnifiedAgentSupervisor._single_workspace_id(
                    workspaces_result.data
                )
                if workspace_id is None:
                    return UnifiedAgentSupervisor._workspace_clarification(steps)
                status_result = await gateway.invoke(
                    "developer.git.status", {"workspaceId": workspace_id}
                )
                steps.append(
                    PublicToolStep(
                        tool_name="developer.git.status",
                        status="SUCCEEDED",
                        summary="已读取真实 Git 改动范围",
                    )
                )
                paths = UnifiedAgentSupervisor._changed_files(status_result.data)
                commit_message = UnifiedAgentSupervisor._commit_message(message)
                if len(paths) != 1 or commit_message is None:
                    return {
                        "intent": AssistantIntent.CLARIFY,
                        "reply": "为避免误提交，请明确一个改动文件和单行提交信息。",
                        "tool_steps": steps,
                        "pending_action": None,
                        "ui_actions": [],
                    }
                preview_result = await gateway.invoke(
                    "developer.git.commit.preview",
                    {"workspaceId": workspace_id, "paths": paths, "message": commit_message},
                )
                steps.append(
                    PublicToolStep(
                        tool_name="developer.git.commit.preview",
                        status="SUCCEEDED",
                        summary="已创建仅包含指定文件的提交预览",
                    )
                )
                preview = preview_result.data if isinstance(preview_result.data, dict) else {}
                invocation = await gateway.invoke(
                    "developer.git.commit",
                    {
                        "workspaceId": workspace_id,
                        "paths": preview.get("paths", paths),
                        "message": preview.get("message", commit_message),
                        "expectedHead": preview.get("expectedHead"),
                        "changeFingerprint": preview.get("changeFingerprint"),
                    },
                    idempotency_key=state["idempotency_key"],
                )
                steps.append(
                    PublicToolStep(
                        tool_name="developer.git.commit",
                        status=invocation.action.status if invocation.action else "SUCCEEDED",
                        summary="已生成独立的 Git commit 高风险确认",
                    )
                )
                return {
                    "intent": AssistantIntent.DEVELOPER,
                    "reply": "已准备仅提交预览中的文件；本次确认不会执行 push。",
                    "tool_steps": steps,
                    "pending_action": invocation.action,
                    "ui_actions": [],
                }

            if "测试" in message and any(
                word in message for word in ("运行", "执行", "检查", "修改后", "改动")
            ):
                workspaces_result = await gateway.invoke("workspaces.list", {})
                steps.append(
                    PublicToolStep(
                        tool_name="workspaces.list",
                        status="SUCCEEDED",
                        summary="已读取登记的代码工作区",
                    )
                )
                workspace_id = UnifiedAgentSupervisor._single_workspace_id(
                    workspaces_result.data
                )
                if workspace_id is None:
                    return {
                        "intent": AssistantIntent.CLARIFY,
                        "reply": "请先登记且只选择一个要测试的代码工作区。",
                        "tool_steps": steps,
                        "pending_action": None,
                        "ui_actions": [
                            UiAction(
                                route_key="WORKSPACE_ARTIFACTS",
                                reason="选择代码工作区",
                            )
                        ],
                    }
                git_status = await gateway.invoke(
                    "developer.git.status", {"workspaceId": workspace_id}
                )
                steps.append(
                    PublicToolStep(
                        tool_name="developer.git.status",
                        status="SUCCEEDED",
                        summary="已读取真实 Git 改动范围",
                    )
                )
                changed_files = UnifiedAgentSupervisor._changed_files(git_status.data)
                recommendation = await gateway.invoke(
                    "developer.tests.recommend",
                    {"workspaceId": workspace_id, "changedFiles": changed_files},
                )
                steps.append(
                    PublicToolStep(
                        tool_name="developer.tests.recommend",
                        status="SUCCEEDED",
                        summary="已按技术栈选择白名单测试模板",
                    )
                )
                template = UnifiedAgentSupervisor._first_test_template(
                    recommendation.data
                )
                if template is None:
                    return {
                        "intent": AssistantIntent.CLARIFY,
                        "reply": "没有找到与当前工作区匹配的白名单测试模板。",
                        "tool_steps": steps,
                        "pending_action": None,
                        "ui_actions": [],
                    }
                invocation = await gateway.invoke(
                    "runner.check.run",
                    {"workspaceId": workspace_id, "templateType": template},
                    idempotency_key=state["idempotency_key"],
                )
                steps.append(
                    PublicToolStep(
                        tool_name="runner.check.run",
                        status=(
                            invocation.action.status
                            if invocation.action is not None
                            else "SUCCEEDED"
                        ),
                        summary=f"已提交固定模板 {template} 到隔离 Runner",
                    )
                )
                return {
                    "intent": AssistantIntent.DEVELOPER,
                    "reply": (
                        "白名单测试已提交执行。"
                        if invocation.action is None
                        else "白名单测试已准备好，请在操作卡片中完成授权或确认。"
                    ),
                    "tool_steps": steps,
                    "pending_action": invocation.action,
                    "ui_actions": [],
                }

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
                # Task 29：知识问答走真实模型增量，增量与最终消息共享同一 turnId。
                delta_index = 0

                async def on_delta(chunk: str) -> None:
                    nonlocal delta_index
                    # 模型流不会经过 UnifiedToolGateway，因此也必须在每个真实
                    # 增量前检查取消标记；否则取消只能阻止后续工具，却阻止不了
                    # 已开始的 DeepSeek 回答继续输出并被提交为完成。
                    if state.get("is_cancelled", lambda: False)():
                        raise ToolTurnCancelledError("统一 Agent 轮次已取消")
                    if emit is not None:
                        await emit(
                            "ASSISTANT_DELTA",
                            {
                                "turnId": state["idempotency_key"],
                                "index": delta_index,
                                "delta": chunk,
                            },
                        )
                    delta_index += 1

                reply_streamed = emit is not None and hasattr(
                    knowledge_service, "stream_message"
                )
                if reply_streamed:
                    answer = await knowledge_service.stream_message(
                        knowledge_conversation_id,
                        message,
                        WebSearchPolicy.AUTO,
                        state["owner_id"],
                        on_delta=on_delta,
                    )
                else:
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
                    "reply_streamed": reply_streamed,
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

    async def _execute_plan(
        self,
        state: SupervisorState,
        plan: AssistantPlan,
        steps: list[PublicToolStep],
        *,
        context_data: Any = None,
    ) -> dict[str, Any]:
        """按声明顺序执行已通过策略校验的计划。

        只有 Planner 返回 ``PLAN`` 时才会进入这里。写工具只会生成待确认动作并立即停止
        后续步骤，绝不自动执行；取消会在每个步骤前被网关拦截。
        """

        fields, resume = await self._run_plan(
            gateway=state["gateway"],
            idempotency_key=state["idempotency_key"],
            plan=plan,
            public_steps=steps,
            outputs={},
            ui_actions=[],
            context_data=context_data,
        )
        fields["plan_resume"] = resume
        return fields

    async def _run_plan(
        self,
        *,
        gateway: UnifiedToolGateway,
        idempotency_key: str,
        plan: AssistantPlan,
        public_steps: list[PublicToolStep],
        outputs: dict[str, Any],
        ui_actions: list[UiAction],
        start_index: int = 0,
        context_data: Any = None,
    ) -> tuple[dict[str, Any], dict[str, Any] | None]:
        """执行计划并从 ``start_index`` 开始；返回结果字段与待恢复状态。"""

        pending_action = None
        executed = 0
        index = start_index
        while index < len(plan.steps):
            step = plan.steps[index]
            try:
                arguments = self._resolve_plan_arguments(step.arguments, outputs)
            except ValueError:
                return (
                    {
                        "intent": AssistantIntent.CLARIFY,
                        "reply": "计划参数无法安全解析，请重新描述目标。",
                        "tool_steps": public_steps,
                        "pending_action": None,
                        "ui_actions": ui_actions,
                    },
                    None,
                )
            # 本轮开始已经调用过 learning.context.get，网关禁止相同参数重复调用；
            # 直接复用已加载的上下文，避免模型重复规划第一步导致整轮失败。
            if (
                step.tool_name == "learning.context.get"
                and not arguments
                and context_data is not None
            ):
                outputs[step.step_id] = context_data
                executed += 1
                index += 1
                public_steps.append(
                    PublicToolStep(
                        tool_name=step.tool_name,
                        status="SUCCEEDED",
                        summary=f"复用已加载上下文（计划步骤 {step.step_id}）",
                    )
                )
                continue
            try:
                invocation = await gateway.invoke(
                    step.tool_name,
                    arguments,
                    idempotency_key=idempotency_key,
                )
            except (DuplicateToolCallError, ToolBudgetExceededError):
                return (
                    {
                        "intent": AssistantIntent.CLARIFY,
                        "reply": "计划包含重复调用或超出本轮预算的步骤，请拆成更具体的单个操作。",
                        "tool_steps": public_steps,
                        "pending_action": None,
                        "ui_actions": ui_actions,
                    },
                    None,
                )
            outputs[step.step_id] = invocation.data
            executed += 1
            index += 1
            public_steps.append(
                PublicToolStep(
                    tool_name=step.tool_name,
                    status=(
                        invocation.action.status
                        if invocation.action is not None
                        else "SUCCEEDED"
                    ),
                    summary=f"已执行计划步骤 {step.step_id}",
                )
            )
            if step.tool_name == "navigation.resolve":
                action = self._plan_navigation_action(arguments)
                if action is not None:
                    ui_actions.append(action)
            if invocation.action is not None:
                pending_action = invocation.action
                break

        resume = None
        remaining = len(plan.steps) - index
        if pending_action is not None and remaining > 0:
            resume = {
                "plan": plan.model_dump(mode="json", by_alias=True),
                "publicSteps": [
                    item.model_dump(mode="json", by_alias=True) for item in public_steps
                ],
                "outputs": outputs,
                "uiActions": [
                    item.model_dump(mode="json", by_alias=True) for item in ui_actions
                ],
                "nextIndex": index,
            }
        if pending_action is None:
            reply = f"已按计划完成 {executed} 个步骤。"
        elif resume is not None:
            reply = (
                f"已完成 {executed} 步；请确认这一步后，我继续执行剩余 {remaining} 步。"
            )
        else:
            reply = f"已完成 {executed} 步；其中一步需要你在操作卡片中确认后才会执行。"
        return (
            {
                "intent": self._plan_intent(plan),
                "reply": reply,
                "tool_steps": public_steps,
                "pending_action": pending_action,
                "ui_actions": ui_actions,
            },
            resume,
        )

    @staticmethod
    def _plan_navigation_action(arguments: dict[str, Any]) -> UiAction | None:
        """只把白名单 routeKey 和字符串参数转成界面动作。"""

        route_key = arguments.get("routeKey")
        if not isinstance(route_key, str) or route_key not in ALLOWED_UI_ROUTE_KEYS:
            return None
        raw_params = arguments.get("params")
        params = (
            {str(key): value for key, value in raw_params.items() if isinstance(value, str)}
            if isinstance(raw_params, dict)
            else {}
        )
        try:
            return UiAction(
                route_key=route_key,
                params=params,
                reason="按计划打开目标页面",
            )
        except ValueError:
            return None

    @staticmethod
    def _resolve_plan_arguments(arguments: Any, outputs: dict[str, Any]) -> Any:
        """把 ``$sN.field`` 引用替换为前序步骤的真实输出值。"""

        if isinstance(arguments, dict):
            return {
                key: UnifiedAgentSupervisor._resolve_plan_arguments(value, outputs)
                for key, value in arguments.items()
            }
        if isinstance(arguments, list):
            return [
                UnifiedAgentSupervisor._resolve_plan_arguments(value, outputs)
                for value in arguments
            ]
        if isinstance(arguments, str) and arguments.startswith("$s"):
            match = PLAN_REFERENCE_PATTERN.match(arguments)
            if match is None:
                raise ValueError("计划参数包含非法引用")
            source_id = f"s{int(match.group('step'))}"
            if source_id not in outputs:
                raise ValueError("计划参数引用了尚未执行的步骤")
            resolved: Any = outputs[source_id]
            for part in match.group("field").split("."):
                if not isinstance(resolved, dict) or part not in resolved:
                    raise ValueError("计划参数引用了未声明的输出字段")
                resolved = resolved[part]
            return resolved
        return arguments

    @staticmethod
    def _plan_intent(plan: AssistantPlan) -> AssistantIntent:
        return {
            PlanIntent.LEARNING_QUERY: AssistantIntent.NAVIGATION,
            PlanIntent.ROADMAP_NAVIGATE: AssistantIntent.NAVIGATION,
            PlanIntent.PLAN_ADJUSTMENT: AssistantIntent.PLAN,
            PlanIntent.QUIZ_PRACTICE: AssistantIntent.TEACHING,
            PlanIntent.CODE_DEVELOPMENT: AssistantIntent.DEVELOPER,
            PlanIntent.GENERAL_CHAT: AssistantIntent.KNOWLEDGE,
            PlanIntent.CLARIFY: AssistantIntent.CLARIFY,
        }[plan.intent]

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
    def _single_workspace_id(data: Any) -> str | None:
        if not isinstance(data, list) or len(data) != 1 or not isinstance(data[0], dict):
            return None
        value = data[0].get("id")
        return value if isinstance(value, str) and value else None

    @staticmethod
    def _workspace_clarification(steps: list[PublicToolStep]) -> dict[str, Any]:
        return {
            "intent": AssistantIntent.CLARIFY,
            "reply": "请先登记且只选择一个代码工作区。",
            "tool_steps": steps,
            "pending_action": None,
            "ui_actions": [
                UiAction(route_key="WORKSPACE_ARTIFACTS", reason="选择代码工作区")
            ],
        }

    @staticmethod
    def _changed_files(data: Any) -> list[str]:
        if not isinstance(data, dict):
            return []
        result: list[str] = []
        for key in ("modifiedFiles", "untrackedFiles"):
            values = data.get(key)
            if isinstance(values, list):
                result.extend(value for value in values if isinstance(value, str))
        return list(dict.fromkeys(result))

    @staticmethod
    def _first_test_template(data: Any) -> str | None:
        if not isinstance(data, dict) or not isinstance(data.get("templates"), list):
            return None
        allowed = {"MAVEN_TEST", "MAVEN_COMPILE", "NPM_TEST", "PYTEST"}
        for value in data["templates"]:
            if isinstance(value, str) and value in allowed:
                return value
        return None

    @staticmethod
    def _commit_message(message: str) -> str | None:
        matched = re.search(r"提交信息\s*[:：]?\s*(.+)$", message, re.IGNORECASE)
        if matched is None:
            return None
        value = matched.group(1).strip()
        return value if 1 <= len(value) <= 200 and "\n" not in value else None

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
