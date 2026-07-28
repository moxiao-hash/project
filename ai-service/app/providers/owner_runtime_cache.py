"""有界、按空闲时间回收的 owner 运行时对象缓存。"""

from collections import OrderedDict
from collections.abc import Callable
from dataclasses import dataclass
from time import monotonic


@dataclass
class _Entry[T]:
    value: T
    last_access: float


class OwnerRuntimeCache[T]:
    def __init__(
        self,
        *,
        max_entries: int = 100,
        idle_ttl_seconds: float = 900,
        clock: Callable[[], float] = monotonic,
    ) -> None:
        self._max_entries = max_entries
        self._idle_ttl = idle_ttl_seconds
        self._clock = clock
        self._entries: OrderedDict[str, _Entry[T]] = OrderedDict()

    def get(self, owner_id: str) -> T | None:
        now = self._clock()
        self._evict_expired(now)
        entry = self._entries.get(owner_id)
        if entry is None:
            return None
        entry.last_access = now
        self._entries.move_to_end(owner_id)
        return entry.value

    def put(self, owner_id: str, value: T) -> None:
        now = self._clock()
        self._evict_expired(now)
        self._entries[owner_id] = _Entry(value=value, last_access=now)
        self._entries.move_to_end(owner_id)
        while len(self._entries) > self._max_entries:
            self._entries.popitem(last=False)

    def _evict_expired(self, now: float) -> None:
        expired = [
            owner_id
            for owner_id, entry in self._entries.items()
            if now - entry.last_access > self._idle_ttl
        ]
        for owner_id in expired:
            del self._entries[owner_id]
