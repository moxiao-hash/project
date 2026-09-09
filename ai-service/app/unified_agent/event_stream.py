"""Task 29：每会话的"持久化优先"事件总线与 SSE 编码。

设计约束（对应 ``docs/superpowers/plans/2026-09-08-...`` Task 29）：

1. 事件必须先写入会话持久化，再发布给在线订阅者。总线只负责"在线推送"，
   掉线、慢消费者或进程重启一律由持久化事件 + ``Last-Event-ID`` 重放兜底。
2. 每个订阅者的队列有上限。队列满时只断开该连接，绝不阻塞业务轮次，
   也绝不丢弃持久事件；客户端重连后从游标续传。
3. 断开订阅只影响连接，不取消已经进入治理层的业务动作。
"""

import asyncio
import contextlib
import json
from collections.abc import Iterable

from app.unified_agent.models import AssistantEvent

#: 默认每个连接的待发送队列上限。超过上限说明消费者明显落后，直接断开重连。
DEFAULT_QUEUE_SIZE = 256

#: 默认心跳间隔；SSE 注释帧不进入事件序号，只用于保活。
DEFAULT_HEARTBEAT_SECONDS = 30.0

#: 心跳帧是标准 SSE 注释，客户端必须忽略。
SSE_HEARTBEAT_FRAME = ": heartbeat\n\n"

#: 队列溢出哨兵，仅用于唤醒等待中的消费者。
_OVERFLOW = object()

#: 降级增量分片长度（字符）。真实模型流式接入后由模型 token 决定。
DEFAULT_DELTA_CHARS = 24


def chunk_reply_deltas(reply: str, size: int = DEFAULT_DELTA_CHARS) -> list[str]:
    """把确定性回复切成有序增量片段。

    这是 ``ASSISTANT_DELTA`` 的确定性降级来源：模型流式不可用时，用户仍能
    看到逐步输出；真实模型增量由 ``reply_delta_source`` 注入后替换本函数。
    """

    if not reply:
        return []
    return [reply[index : index + size] for index in range(0, len(reply), size)]


class EventStreamOverflow(RuntimeError):
    """慢消费者已被断开；调用方应让客户端用 Last-Event-ID 重连。"""


def encode_sse_event(event: AssistantEvent) -> str:
    """把一个已持久化的事件编码为 SSE 帧。

    序号同时写入 ``id:``，这样浏览器/代理可以原样回传 ``Last-Event-ID``。
    """

    payload = event.model_dump(mode="json", by_alias=True)
    data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    return f"id: {event.sequence}\nevent: {event.type}\ndata: {data}\n\n"


class EventSubscription:
    """单个在线连接的订阅句柄。"""

    def __init__(self, conversation_id: str, queue_size: int) -> None:
        self.conversation_id = conversation_id
        self.queue: asyncio.Queue = asyncio.Queue(maxsize=queue_size)
        self.closed = False
        self.overflowed = False

    def offer(self, event: AssistantEvent) -> None:
        """非阻塞投递；队列满则标记溢出并唤醒消费者结束连接。"""

        if self.closed:
            return
        try:
            self.queue.put_nowait(event)
        except asyncio.QueueFull:
            self._overflow()

    def _overflow(self) -> None:
        self.overflowed = True
        self.closed = True
        while True:
            try:
                self.queue.get_nowait()
            except asyncio.QueueEmpty:
                break
        with contextlib.suppress(asyncio.QueueFull):
            self.queue.put_nowait(_OVERFLOW)

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        try:
            self.queue.put_nowait(_OVERFLOW)
        except asyncio.QueueFull:  # pragma: no cover - 与 _overflow 竞争时的兜底
            self._overflow()

    async def receive(
        self, timeout: float | None = None
    ) -> AssistantEvent | None:
        """等待下一个事件。

        - 返回事件：正常投递。
        - 返回 ``None``：等待超时，调用方应发送心跳。
        - 抛出 :class:`EventStreamOverflow`：连接已被关闭，调用方应结束流。
        """

        if self.closed and self.queue.empty():
            raise EventStreamOverflow("事件流连接已关闭")
        try:
            item = await asyncio.wait_for(self.queue.get(), timeout)
        except TimeoutError:
            return None
        if item is _OVERFLOW:
            raise EventStreamOverflow("事件流连接已关闭")
        return item


class AssistantEventBus:
    """会话 ID 到在线订阅者的内存总线；不持有任何业务状态。"""

    def __init__(self, *, queue_size: int = DEFAULT_QUEUE_SIZE) -> None:
        self._queue_size = queue_size
        self._subscribers: dict[str, list[EventSubscription]] = {}

    def subscribe(self, conversation_id: str) -> EventSubscription:
        subscription = EventSubscription(conversation_id, self._queue_size)
        self._subscribers.setdefault(conversation_id, []).append(subscription)
        return subscription

    def unsubscribe(self, subscription: EventSubscription) -> None:
        remaining = [
            item
            for item in self._subscribers.get(subscription.conversation_id, [])
            if item is not subscription
        ]
        if remaining:
            self._subscribers[subscription.conversation_id] = remaining
        else:
            self._subscribers.pop(subscription.conversation_id, None)
        subscription.closed = True

    def publish(self, event: AssistantEvent) -> None:
        """同步、非阻塞投递；任何慢消费者都不得影响业务轮次。"""

        for subscription in list(self._subscribers.get(event.conversation_id, [])):
            subscription.offer(event)
        self._prune(event.conversation_id)

    def close_conversation(self, conversation_id: str) -> None:
        for subscription in list(self._subscribers.get(conversation_id, [])):
            subscription.close()
        self._subscribers.pop(conversation_id, None)

    def _prune(self, conversation_id: str) -> None:
        """移除已断开（含溢出）的订阅，避免注册表随连接泄漏。"""

        remaining = [
            item
            for item in self._subscribers.get(conversation_id, [])
            if not item.closed
        ]
        if remaining:
            self._subscribers[conversation_id] = remaining
        else:
            self._subscribers.pop(conversation_id, None)

    def subscriber_count(self, conversation_id: str) -> int:
        return len(self._subscribers.get(conversation_id, ()))

    def subscribers(self, conversation_id: str) -> Iterable[EventSubscription]:
        return tuple(self._subscribers.get(conversation_id, ()))
