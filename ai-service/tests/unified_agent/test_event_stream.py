"""Task 29 事件总线单元测试：持久化优先、有界队列、慢消费者只丢连接。"""

import asyncio

from app.unified_agent.event_stream import (
    SSE_HEARTBEAT_FRAME,
    AssistantEventBus,
    EventStreamOverflow,
    encode_sse_event,
)
from app.unified_agent.models import AssistantEvent


def event(sequence: int, event_type: str = "TOOL_STARTED", conversation_id: str = "c-1"):
    return AssistantEvent(
        sequence=sequence,
        type=event_type,
        conversation_id=conversation_id,
        payload={"toolName": "learning.context.get"},
    )


def run(coro):
    return asyncio.run(coro)


def test_publish_delivers_events_in_order_to_every_subscriber() -> None:
    async def scenario():
        bus = AssistantEventBus(queue_size=8)
        first = bus.subscribe("c-1")
        second = bus.subscribe("c-1")
        other = bus.subscribe("other")
        bus.publish(event(1))
        bus.publish(event(2))
        bus.publish(event(3, conversation_id="other"))
        return (
            [await first.receive(1) for _ in range(2)],
            [await second.receive(1) for _ in range(2)],
            [await other.receive(1)],
        )

    first_events, second_events, other_events = run(scenario())

    assert [item.sequence for item in first_events] == [1, 2]
    assert [item.sequence for item in second_events] == [1, 2]
    assert [item.sequence for item in other_events] == [3]


def test_publish_is_non_blocking_and_never_raises_without_subscribers() -> None:
    bus = AssistantEventBus(queue_size=4)
    bus.publish(event(1))
    assert bus.subscriber_count("c-1") == 0


def test_receive_returns_none_on_heartbeat_timeout() -> None:
    async def scenario():
        bus = AssistantEventBus(queue_size=4)
        subscription = bus.subscribe("c-1")
        idle = await subscription.receive(0.01)
        bus.publish(event(7))
        return idle, await subscription.receive(1)

    idle, received = run(scenario())

    assert idle is None
    assert received.sequence == 7


def test_slow_consumer_overflows_and_is_disconnected_without_losing_events() -> None:
    async def scenario():
        bus = AssistantEventBus(queue_size=2)
        subscription = bus.subscribe("c-1")
        for sequence in range(1, 6):
            bus.publish(event(sequence))
        overflowed = subscription.closed
        try:
            await subscription.receive(0.1)
        except EventStreamOverflow:
            raised = True
        else:  # pragma: no cover - 断言分支
            raised = False
        return overflowed, raised, bus.subscriber_count("c-1")

    overflowed, raised, remaining = run(scenario())

    assert overflowed is True
    assert raised is True
    assert remaining == 0


def test_unsubscribe_and_close_conversation_stop_delivery() -> None:
    async def scenario():
        bus = AssistantEventBus(queue_size=4)
        first = bus.subscribe("c-1")
        second = bus.subscribe("c-1")
        bus.unsubscribe(first)
        bus.publish(event(1))
        received = [await second.receive(1)]
        bus.close_conversation("c-1")
        return received, second.closed, bus.subscriber_count("c-1")

    received, closed, remaining = run(scenario())

    assert [item.sequence for item in received] == [1]
    assert closed is True
    assert remaining == 0


def test_sse_frames_use_sequence_id_event_name_and_json_data() -> None:
    frame = encode_sse_event(event(4, "TOOL_SUCCEEDED"))

    assert frame.startswith("id: 4\nevent: TOOL_SUCCEEDED\ndata: {")
    assert frame.endswith("\n\n")
    assert '"conversationId":"c-1"' in frame
    assert SSE_HEARTBEAT_FRAME == ": heartbeat\n\n"
