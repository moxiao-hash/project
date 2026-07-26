import pytest

from app.search.models import WebSearchOutcome, WebSearchResult
from app.search.service import WebSearchService


class Provider:
    async def search(self, query: str) -> WebSearchOutcome:
        return WebSearchOutcome(
            query=query,
            provider_request_id="provider-1",
            results=(
                WebSearchResult(
                    title="Official docs",
                    url="https://example.com/docs",
                    snippet="Current reference",
                    score=0.9,
                ),
            ),
        )


class Recorder:
    async def record_web_search(self, owner_id: str, outcome: WebSearchOutcome):
        return outcome.with_persisted_ids("search-1", ("result-1",))


@pytest.mark.anyio
async def test_persists_successful_provider_results_in_java() -> None:
    result = await WebSearchService(Provider(), Recorder()).search(
        "owner-1",
        "current docs",
    )

    assert result.search_id == "search-1"
    assert result.results[0].result_id == "result-1"
