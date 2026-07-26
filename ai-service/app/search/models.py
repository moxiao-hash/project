"""联网搜索的供应商无关结果模型。"""

from dataclasses import dataclass


@dataclass(frozen=True)
class WebSearchResult:
    title: str
    url: str
    snippet: str
    score: float
    result_id: str | None = None


@dataclass(frozen=True)
class WebSearchOutcome:
    query: str
    search_id: str | None = None
    provider_request_id: str | None = None
    results: tuple[WebSearchResult, ...] = ()
    warnings: tuple[str, ...] = ()

    def with_persisted_ids(
        self,
        search_id: str,
        result_ids: tuple[str, ...],
    ) -> "WebSearchOutcome":
        if len(result_ids) != len(self.results):
            raise ValueError("Java 返回的搜索结果 ID 数量不一致")
        return WebSearchOutcome(
            query=self.query,
            search_id=search_id,
            provider_request_id=self.provider_request_id,
            results=tuple(
                WebSearchResult(
                    title=result.title,
                    url=result.url,
                    snippet=result.snippet,
                    score=result.score,
                    result_id=result_id,
                )
                for result, result_id in zip(self.results, result_ids, strict=True)
            ),
            warnings=self.warnings,
        )
