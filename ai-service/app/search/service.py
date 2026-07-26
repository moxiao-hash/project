"""联网搜索和 Java 来源持久化的编排服务。"""

from typing import Protocol

from app.search.models import WebSearchOutcome


class SearchProvider(Protocol):
    async def search(self, query: str) -> WebSearchOutcome: ...


class SearchRecorder(Protocol):
    async def record_web_search(
        self,
        owner_id: str,
        outcome: WebSearchOutcome,
    ) -> WebSearchOutcome: ...


class WebSearchService:
    def __init__(
        self,
        provider: SearchProvider,
        recorder: SearchRecorder,
    ) -> None:
        self._provider = provider
        self._recorder = recorder

    async def search(self, owner_id: str, query: str) -> WebSearchOutcome:
        outcome = await self._provider.search(query)
        if not outcome.results:
            return outcome
        return await self._recorder.record_web_search(owner_id, outcome)
