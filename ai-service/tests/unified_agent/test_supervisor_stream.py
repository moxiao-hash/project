"""Task 29 Supervisor 持续事件流测试：先持久化再发布、可续传、取消可观察。"""

import asyncio
from contextlib import suppress

from app.knowledge.models import KnowledgeConversationSnapshot, KnowledgeMode
from app.unified_agent.models import (
    AssistantMessage,
    ToolDescriptor,
    ToolEffect,
    ToolRiskLevel,
)
from app.unified_agent.supervisor import UnifiedAgentSupervisor

TURN_END_TYPES = {"TURN_COMPLETED", "TURN_FAILED", "TURN_CANCELLED"}


def tool(name: str, effect: ToolEffect = ToolEffect.READ) -> ToolDescriptor:
    return ToolDescriptor(
        name=name,
        version=1,
        category="CONTEXT",
        effect=effect,
        risk_level=ToolRiskLevel.NONE,
        input_schema={"type": "object"},
        output_schema={"type": "object"},
    )


class StreamingJavaBackend:
    """只读工具 + 一个写工具，用于观察真实事件顺序。"""

    def __init__(self) -> None:
        self.calls: list[str] = []

    async def get_agent_tool_catalog(self):
        return [
            tool("learning.context.get"),
            tool("navigation.resolve"),
            tool("assessment.wrong_question_review.create", ToolEffect.WRITE),
        ]

    async def invoke_agent_tool(self, name, owner_id, arguments, idempotency_key=None):
        self.calls.append(name)
        if name == "assessment.wrong_question_review.create":
            return {
                "toolName": name,
                "data": None,
                "action": {
                    "actionId": "action-1",
                    "executionId": "execution-1",
                    "toolName": name,
                    "toolVersion": 1,
                    "riskLevel": "LOW",
                    "status": "WAITING_CONFIRMATION",
                    "summary": "创建错题重做批次",
                    "arguments": arguments,
                    "result": None,
                    "error": None,
                    "expiresAt": "2099-01-01T00:00:00Z",
                },
            }
        return {"toolName": name, "data": {"roadmap": None}, "action": None}


class BlockingJavaBackend(StreamingJavaBackend):
    """首个工具调用挂起，用于在轮次进行中请求取消。"""

    def __init__(self) -> None:
        super().__init__()
        self.started = asyncio.Event()
        self.release = asyncio.Event()

    async def invoke_agent_tool(self, name, owner_id, arguments, idempotency_key=None):
        self.started.set()
        await self.release.wait()
        return await super().invoke_agent_tool(
            name, owner_id, arguments, idempotency_key
        )


def build_service(**kwargs) -> tuple[UnifiedAgentSupervisor, StreamingJavaBackend]:
    backend = StreamingJavaBackend()
    return (
        UnifiedAgentSupervisor(backend, model_name="deepseek-v4-flash", **kwargs),
        backend,
    )


async def collect_stream(
    service,
    conversation_id,
    *,
    cursor: int = 0,
    stop: set[str] | None = None,
    deadline: float = 1.0,
) -> list:
    """有界收集事件流；超时即认为流已静默，用于断言"没有新事件"。"""

    events = []
    try:
        async with asyncio.timeout(deadline):
            async for item in service.stream_events(
                conversation_id, "user-1", cursor, heartbeat_seconds=0.02
            ):
                if item is None:
                    continue
                persisted = await service.list_events(conversation_id, "user-1", 0)
                assert persisted[-1].sequence >= item.sequence, "事件必须先落库再推送"
                events.append(item)
                if stop is not None and item.type in stop:
                    break
    except TimeoutError:
        pass
    return events


def test_snapshot_exposes_resume_cursor_and_active_turn_id() -> None:
    async def scenario():
        service, _ = build_service()
        conversation = await service.create_conversation("user-1")
        created = await service.get_conversation(conversation.conversation_id, "user-1")
        created_cursor = created.last_event_sequence
        created_active_turn = created.active_turn_id
        observations: list[tuple[int, int]] = []

        async def consume():
            async for item in service.stream_events(
                conversation.conversation_id, "user-1", 1, heartbeat_seconds=0.02
            ):
                if item is None:
                    continue
                snapshot = await service.get_conversation(
                    conversation.conversation_id, "user-1"
                )
                observations.append((item.sequence, snapshot.last_event_sequence))
                if item.type in TURN_END_TYPES:
                    break

        stream = asyncio.create_task(consume())
        await asyncio.sleep(0.02)
        final = await service.send_message(
            conversation.conversation_id, "打开错题集", "turn-9", "user-1", {}
        )
        await asyncio.wait_for(stream, timeout=5)
        return created_cursor, created_active_turn, observations, final

    created_cursor, created_active_turn, observations, final = asyncio.run(scenario())

    assert created_cursor == 1
    assert created_active_turn is None
    # 事件被消费时，其序号必然已写进快照（先持久化、再发布）；快照游标只能更大。
    assert all(cursor >= sequence for sequence, cursor in observations)
    assert observations[-1][0] == final.last_event_sequence
    assert final.active_turn_id is None


def test_active_turn_id_is_visible_while_the_turn_is_running() -> None:
    async def scenario():
        backend = BlockingJavaBackend()
        service = UnifiedAgentSupervisor(backend, model_name="deepseek-v4-flash")
        conversation = await service.create_conversation("user-1")
        turn = asyncio.create_task(
            service.send_message(
                conversation.conversation_id, "打开错题集", "turn-10", "user-1", {}
            )
        )
        await asyncio.wait_for(backend.started.wait(), timeout=5)
        during = await service.get_conversation(
            conversation.conversation_id, "user-1"
        )
        backend.release.set()
        await asyncio.wait_for(turn, timeout=5)
        after = await service.get_conversation(conversation.conversation_id, "user-1")
        return during, after
    during, after = asyncio.run(scenario())

    assert during.active_turn_id == "turn-10"
    assert during.last_event_sequence >= 2
    assert during.active_turn is not None, "TURN_STARTED 起就必须有 activeTurn 状态"
    assert during.active_turn.turn_id == "turn-10"
    assert during.active_turn.user_message == "打开错题集"
    assert during.active_turn.assistant_text == ""
    assert during.active_turn.last_delta_index == -1
    assert after.active_turn_id is None
    assert after.active_turn is None


def test_restored_conversation_never_reports_a_stale_active_turn(tmp_path) -> None:
    async def scenario():
        import base64

        from app.persistence.agent_state import AgentPersistence

        persistence = await AgentPersistence.open(
            tmp_path / "agent.sqlite3", base64.b64encode(bytes(range(32))).decode()
        )
        first = UnifiedAgentSupervisor(
            StreamingJavaBackend(),
            model_name="deepseek-v4-flash",
            persistence=persistence,
        )
        created = await first.create_conversation("user-1")
        # 模拟"服务在轮次中被杀死"：持久化快照里残留 activeTurnId。
        conversation = first._conversations[created.conversation_id]  # noqa: SLF001
        conversation.snapshot = conversation.snapshot.model_copy(
            update={"active_turn_id": "turn-crashed"}
        )
        await first._save(conversation)  # noqa: SLF001
        second = UnifiedAgentSupervisor(
            StreamingJavaBackend(),
            model_name="deepseek-v4-flash",
            persistence=persistence,
        )
        restored = await second.get_conversation(created.conversation_id, "user-1")
        await persistence.close()
        return restored

    restored = asyncio.run(scenario())

    assert restored.active_turn_id is None
    assert restored.active_turn is None
    # 恢复时会补写 TURN_FAILED 终态事件（见 restart 测试），游标因此推进到 2。
    assert restored.last_event_sequence == 2


def test_turn_emits_continuous_events_in_contract_order() -> None:
    async def scenario():
        service, backend = build_service()
        conversation = await service.create_conversation("user-1")
        stream = asyncio.create_task(
            collect_stream(
                service,
                conversation.conversation_id,
                cursor=1,
                stop=TURN_END_TYPES,
                deadline=5.0,
            )
        )
        await asyncio.sleep(0.02)
        reply = await service.send_message(
            conversation.conversation_id, "打开错题集", "turn-1", "user-1", {}
        )
        return await asyncio.wait_for(stream, timeout=5), reply, backend

    events, reply, _ = asyncio.run(scenario())
    types = [item.type for item in events]

    assert types[0] == "TURN_STARTED"
    assert types[-1] == "TURN_COMPLETED"
    assert "CONTEXT_LOADED" in types
    assert types.index("TOOL_STARTED") < types.index("TOOL_SUCCEEDED")
    assert types.index("UI_ACTION") < types.index("TURN_COMPLETED")
    assert [item.sequence for item in events] == list(range(2, 2 + len(events)))
    assert types.count("TOOL_STARTED") == types.count("TOOL_SUCCEEDED")

    deltas = [item for item in events if item.type == "ASSISTANT_DELTA"]
    assert deltas, "正常轮次必须产生增量事件"
    assert "".join(item.payload["delta"] for item in deltas) == reply.reply
    assert {item.payload["turnId"] for item in deltas} == {"turn-1"}
    assert events[-1].payload["reply"] == reply.reply
    assert events[-1].payload["turnId"] == "turn-1"


def test_reconnect_replays_only_missing_events_without_repeating_work() -> None:
    async def scenario():
        service, backend = build_service()
        conversation = await service.create_conversation("user-1")
        first = await service.send_message(
            conversation.conversation_id, "打开错题集", "turn-1", "user-1", {}
        )
        assert first.reply
        calls_after_turn = list(backend.calls)
        replayed = await collect_stream(
            service, conversation.conversation_id, cursor=1, stop=TURN_END_TYPES
        )
        tail = await collect_stream(
            service,
            conversation.conversation_id,
            cursor=replayed[-1].sequence,
            deadline=0.2,
        )
        return replayed, tail, calls_after_turn, backend.calls

    replayed, tail, calls_after_turn, calls_now = asyncio.run(scenario())

    assert replayed[-1].type == "TURN_COMPLETED"
    assert [item.sequence for item in replayed] == list(range(2, 2 + len(replayed)))
    assert tail == [], "断点之后不得重复推送已消费事件"
    assert calls_now == calls_after_turn, "重放事件不得重复调用工具或模型"


def test_pending_write_turn_emits_action_preview_and_stops() -> None:
    async def scenario():
        service, _ = build_service()
        conversation = await service.create_conversation("user-1")
        stream = asyncio.create_task(
            collect_stream(
                service,
                conversation.conversation_id,
                cursor=1,
                stop=TURN_END_TYPES,
                deadline=5.0,
            )
        )
        await asyncio.sleep(0.02)
        await service.send_message(
            conversation.conversation_id, "重做错题五题", "turn-2", "user-1", {}
        )
        return await asyncio.wait_for(stream, timeout=5)

    events = asyncio.run(scenario())
    types = [item.type for item in events]

    assert "ACTION_PREVIEW" in types
    assert types[-1] == "TURN_COMPLETED"
    preview = events[types.index("ACTION_PREVIEW")]
    assert preview.payload["actionId"] == "action-1"
    assert preview.payload["riskLevel"] == "LOW"


def test_cancel_is_observable_on_the_stream() -> None:
    async def scenario():
        backend = BlockingJavaBackend()
        service = UnifiedAgentSupervisor(backend, model_name="deepseek-v4-flash")
        conversation = await service.create_conversation("user-1")
        stream = asyncio.create_task(
            collect_stream(
                service,
                conversation.conversation_id,
                cursor=1,
                stop=TURN_END_TYPES,
                deadline=5.0,
            )
        )
        turn = asyncio.create_task(
            service.send_message(
                conversation.conversation_id, "打开错题集", "turn-3", "user-1", {}
            )
        )
        await asyncio.wait_for(backend.started.wait(), timeout=5)
        await service.cancel_turn(conversation.conversation_id, "turn-3", "user-1")
        backend.release.set()
        await asyncio.wait_for(turn, timeout=5)
        return await asyncio.wait_for(stream, timeout=5)

    events = asyncio.run(scenario())

    assert events[-1].type == "TURN_CANCELLED"
    assert events[-1].payload["turnId"] == "turn-3"


def test_slow_consumer_is_disconnected_but_history_is_complete() -> None:
    async def scenario():
        backend = StreamingJavaBackend()
        service = UnifiedAgentSupervisor(
            backend, model_name="deepseek-v4-flash", event_queue_size=1
        )
        conversation = await service.create_conversation("user-1")
        subscription = service._events.subscribe(conversation.conversation_id)  # noqa: SLF001
        await service.send_message(
            conversation.conversation_id, "打开错题集", "turn-4", "user-1", {}
        )
        overflowed = subscription.closed
        history = await service.list_events(conversation.conversation_id, "user-1", 1)
        replayed = await collect_stream(
            service, conversation.conversation_id, cursor=1, stop=TURN_END_TYPES
        )
        return overflowed, history, replayed

    overflowed, history, replayed = asyncio.run(scenario())

    assert overflowed is True
    assert history[-1].type == "TURN_COMPLETED"
    assert [item.sequence for item in replayed] == [item.sequence for item in history]


class StreamingKnowledgeService:
    """Task 29：暴露 stream_message 的知识服务，验证真实模型增量。"""

    def __init__(self, chunks: list[str]) -> None:
        self.chunks = chunks
        self.stream_calls = 0
        self.send_calls = 0

    async def create_conversation(self, owner_id, mode):
        return KnowledgeConversationSnapshot(
            conversationId="knowledge-1",
            ownerId=owner_id,
            mode=mode,
            retrievalMode="NONE",
            modelProvider="deepseek",
            modelName="deepseek-v4-flash",
        )

    async def stream_message(
        self, conversation_id, message, web_search, owner_id, *, on_delta
    ):
        self.stream_calls += 1
        for chunk in self.chunks:
            await on_delta(chunk)
        return KnowledgeConversationSnapshot(
            conversationId=conversation_id,
            ownerId=owner_id,
            mode=KnowledgeMode.AUTO,
            answer="".join(self.chunks),
            retrievalMode="HYBRID",
            modelProvider="deepseek",
            modelName="deepseek-v4-flash",
        )

    async def send_message(self, conversation_id, message, web_search, owner_id):
        self.send_calls += 1
        raise AssertionError("存在 stream_message 时不得回落到非流式调用")


class StreamingKnowledgeServices:
    def __init__(self, service: StreamingKnowledgeService) -> None:
        self.service = service

    async def for_owner(self, owner_id):
        return self.service


def test_knowledge_turn_streams_model_deltas_once_with_shared_turn_id() -> None:
    async def scenario():
        knowledge = StreamingKnowledgeService(["先学 Java 基础，", "再学 Spring Boot。"])
        backend = StreamingJavaBackend()
        service = UnifiedAgentSupervisor(
            backend,
            model_name="deepseek-v4-flash",
            knowledge_services=StreamingKnowledgeServices(knowledge),
        )
        conversation = await service.create_conversation("user-1")
        stream = asyncio.create_task(
            collect_stream(
                service,
                conversation.conversation_id,
                cursor=1,
                stop=TURN_END_TYPES,
                deadline=5.0,
            )
        )
        await asyncio.sleep(0.02)
        reply = await service.send_message(
            conversation.conversation_id,
            "解释一下学习顺序",
            "turn-5",
            "user-1",
            {},
        )
        return await asyncio.wait_for(stream, timeout=5), reply, knowledge

    events, reply, knowledge = asyncio.run(scenario())
    deltas = [item for item in events if item.type == "ASSISTANT_DELTA"]

    assert knowledge.stream_calls == 1
    assert knowledge.send_calls == 0
    assert [item.payload["delta"] for item in deltas] == [
        "先学 Java 基础，",
        "再学 Spring Boot。",
    ], "增量必须是真实模型分片，不能在收尾阶段再切一次"
    assert [item.payload["index"] for item in deltas] == [0, 1]
    assert {item.payload["turnId"] for item in deltas} == {"turn-5"}
    assert events[-1].type == "TURN_COMPLETED"
    assert events[-1].payload["turnId"] == "turn-5"
    assert events[-1].payload["reply"] == "".join(
        item.payload["delta"] for item in deltas
    )
    assert reply.reply == events[-1].payload["reply"]


class PausedStreamingKnowledgeService(StreamingKnowledgeService):
    """在指定分片后暂停，用于模拟"用户在两段增量之间刷新页面"。"""

    def __init__(self, chunks: list[str], pause_after: int) -> None:
        super().__init__(chunks)
        self.pause_after = pause_after
        self.delta_sent = asyncio.Event()
        self.release = asyncio.Event()

    async def stream_message(
        self, conversation_id, message, web_search, owner_id, *, on_delta
    ):
        self.stream_calls += 1
        for index, chunk in enumerate(self.chunks):
            await on_delta(chunk)
            if index == self.pause_after:
                self.delta_sent.set()
                await self.release.wait()
        return KnowledgeConversationSnapshot(
            conversationId=conversation_id,
            ownerId=owner_id,
            mode=KnowledgeMode.AUTO,
            answer="".join(self.chunks),
            retrievalMode="HYBRID",
            modelProvider="deepseek",
            modelName="deepseek-v4-flash",
        )


def test_refresh_between_deltas_sees_prefix_and_resumes_suffix() -> None:
    """冻结契约：activeTurn.assistantText 是前缀，lastEventSequence 之后的增量是后缀。"""

    async def scenario():
        knowledge = PausedStreamingKnowledgeService(
            ["PREFIX_ALREADY_STREAMED", "_SUFFIX_FROM_RESUME"], pause_after=0
        )
        service = UnifiedAgentSupervisor(
            StreamingJavaBackend(),
            model_name="deepseek-v4-flash",
            knowledge_services=StreamingKnowledgeServices(knowledge),
        )
        conversation = await service.create_conversation("user-1")
        turn = asyncio.create_task(
            service.send_message(
                conversation.conversation_id,
                "解释一下学习顺序",
                "turn-probe",
                "user-1",
                {},
            )
        )
        await asyncio.wait_for(knowledge.delta_sent.wait(), timeout=5)
        refreshed = await service.get_conversation(
            conversation.conversation_id, "user-1"
        )
        cursor = refreshed.last_event_sequence
        knowledge.release.set()
        suffix = await collect_stream(
            service,
            conversation.conversation_id,
            cursor=cursor,
            stop=TURN_END_TYPES,
            deadline=5.0,
        )
        reply = await asyncio.wait_for(turn, timeout=5)
        return refreshed, cursor, suffix, reply

    refreshed, cursor, suffix, reply = asyncio.run(scenario())

    assert refreshed.active_turn is not None
    assert refreshed.active_turn.turn_id == "turn-probe"
    assert refreshed.active_turn.user_message == "解释一下学习顺序"
    assert refreshed.active_turn.assistant_text == "PREFIX_ALREADY_STREAMED"
    assert refreshed.active_turn.last_delta_index == 0
    assert refreshed.active_turn_id == "turn-probe"

    suffix_deltas = [
        item.payload["delta"] for item in suffix if item.type == "ASSISTANT_DELTA"
    ]
    assert suffix_deltas == ["_SUFFIX_FROM_RESUME"]
    assert suffix[-1].type == "TURN_COMPLETED"

    final_answer = refreshed.active_turn.assistant_text + "".join(suffix_deltas)
    assert final_answer == reply.reply == suffix[-1].payload["reply"]
    assert reply.reply.count("PREFIX_ALREADY_STREAMED") == 1
    assert reply.reply.count("_SUFFIX_FROM_RESUME") == 1


def test_cancel_stops_a_streaming_knowledge_answer_before_later_deltas() -> None:
    """取消必须能中止模型流，不能只阻止后续 Java 工具调用。"""

    async def scenario():
        knowledge = PausedStreamingKnowledgeService(
            ["FIRST_DELTA", "_MUST_NOT_BE_EMITTED"], pause_after=0
        )
        service = UnifiedAgentSupervisor(
            StreamingJavaBackend(),
            model_name="deepseek-v4-flash",
            knowledge_services=StreamingKnowledgeServices(knowledge),
        )
        conversation = await service.create_conversation("user-1")
        turn = asyncio.create_task(
            service.send_message(
                conversation.conversation_id,
                "解释一下学习顺序",
                "turn-cancel-stream",
                "user-1",
                {},
            )
        )
        await asyncio.wait_for(knowledge.delta_sent.wait(), timeout=5)
        await service.cancel_turn(
            conversation.conversation_id, "turn-cancel-stream", "user-1"
        )
        knowledge.release.set()
        result = await asyncio.wait_for(turn, timeout=5)
        events = await service.list_events(
            conversation.conversation_id, "user-1", 0
        )
        return result, events

    result, events = asyncio.run(scenario())
    deltas = [event.payload["delta"] for event in events if event.type == "ASSISTANT_DELTA"]

    assert deltas == ["FIRST_DELTA"]
    assert events[-1].type == "TURN_CANCELLED"
    assert all(
        event.type != "TURN_COMPLETED"
        or event.payload.get("turnId") != "turn-cancel-stream"
        for event in events
    )
    assert result.active_turn is None
    assert result.messages[-2].turn_id == "turn-cancel-stream"
    assert result.messages[-1].turn_id == "turn-cancel-stream"
    assert result.messages[-1].status == "cancelled"


def test_restart_mid_stream_persists_turn_failed_for_interrupted_turn(tmp_path) -> None:
    """重启恢复：必须为被中断的轮次补写 TURN_FAILED，不能只清空 ID。"""

    async def scenario():
        import base64

        from app.persistence.agent_state import AgentPersistence

        persistence = await AgentPersistence.open(
            tmp_path / "agent.sqlite3", base64.b64encode(bytes(range(32))).decode()
        )
        knowledge = PausedStreamingKnowledgeService(["PREFIX_", "SUFFIX"], pause_after=0)
        first = UnifiedAgentSupervisor(
            StreamingJavaBackend(),
            model_name="deepseek-v4-flash",
            persistence=persistence,
            knowledge_services=StreamingKnowledgeServices(knowledge),
        )
        conversation = await first.create_conversation("user-1")
        turn = asyncio.create_task(
            first.send_message(
                conversation.conversation_id,
                "解释一下学习顺序",
                "turn-restart",
                "user-1",
                {},
            )
        )
        await asyncio.wait_for(knowledge.delta_sent.wait(), timeout=5)
        interrupted = await first.get_conversation(
            conversation.conversation_id, "user-1"
        )

        # 模拟进程重启：同一持久化，新的服务实例。
        second = UnifiedAgentSupervisor(
            StreamingJavaBackend(),
            model_name="deepseek-v4-flash",
            persistence=persistence,
        )
        recovered = await second.get_conversation(conversation.conversation_id, "user-1")
        events = await second.list_events(conversation.conversation_id, "user-1", 0)
        turn.cancel()
        with suppress(asyncio.CancelledError):
            await turn
        await persistence.close()
        return interrupted, recovered, events

    interrupted, recovered, events = asyncio.run(scenario())

    assert interrupted.active_turn is not None
    assert interrupted.active_turn.assistant_text == "PREFIX_"
    assert recovered.active_turn is None
    assert recovered.active_turn_id is None
    assert events[-1].type == "TURN_FAILED"
    assert events[-1].payload["turnId"] == "turn-restart"
    assert events[-1].payload["reason"] == "SERVICE_RESTARTED"
    assert recovered.last_event_sequence == events[-1].sequence


def test_get_conversation_returns_isolated_deep_copy() -> None:
    """读取必须返回深拷贝，调用方改写不得污染服务端状态。"""

    async def scenario():
        service, _ = build_service()
        conversation = await service.create_conversation("user-1")
        snapshot = await service.get_conversation(conversation.conversation_id, "user-1")
        snapshot.reply = "tampered"
        snapshot.messages.append(
            AssistantMessage(role="assistant", content="tampered")
        )
        fresh = await service.get_conversation(conversation.conversation_id, "user-1")
        return fresh

    fresh = asyncio.run(scenario())

    assert fresh.reply != "tampered"
    assert all(message.content != "tampered" for message in fresh.messages)
