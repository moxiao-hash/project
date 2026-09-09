"""Task 29 Supervisor 持续事件流测试：先持久化再发布、可续传、取消可观察。"""

import asyncio

from app.knowledge.models import KnowledgeConversationSnapshot, KnowledgeMode
from app.unified_agent.models import ToolDescriptor, ToolEffect, ToolRiskLevel
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
