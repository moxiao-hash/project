"""Tavily Search API 的最小、可降级客户端。"""

import httpx
from pydantic import SecretStr

from app.core.request_context import outbound_request_headers
from app.search.models import WebSearchOutcome, WebSearchResult


class TavilySearchClient:
    def __init__(
        self,
        api_key: SecretStr,
        *,
        base_url: str = "https://api.tavily.com",
        transport: httpx.AsyncBaseTransport | None = None,
        timeout_seconds: float = 15.0,
    ) -> None:
        self._api_key = api_key.get_secret_value()
        self._base_url = base_url.rstrip("/")
        self._transport = transport
        self._timeout = timeout_seconds

    async def search(self, query: str) -> WebSearchOutcome:
        if not self._api_key:
            return WebSearchOutcome(
                query=query,
                warnings=("未配置 TAVILY_API_KEY，本轮未执行联网搜索",),
            )
        try:
            async with httpx.AsyncClient(
                base_url=self._base_url,
                transport=self._transport,
                timeout=self._timeout,
                headers={
                    "Authorization": f"Bearer {self._api_key}",
                    **outbound_request_headers(),
                },
            ) as client:
                response = await client.post(
                    "/search",
                    json={
                        "query": query,
                        "search_depth": "basic",
                        "include_answer": False,
                        "include_raw_content": False,
                        "max_results": 5,
                    },
                )
                response.raise_for_status()
            payload = response.json()
            return WebSearchOutcome(
                query=query,
                provider_request_id=payload.get("request_id"),
                results=tuple(
                    WebSearchResult(
                        title=str(result["title"]),
                        url=str(result["url"]),
                        snippet=str(result.get("content", "")),
                        score=float(result.get("score", 0)),
                    )
                    for result in payload.get("results", [])
                ),
            )
        except (httpx.HTTPError, ValueError, KeyError, TypeError):
            return WebSearchOutcome(
                query=query,
                warnings=("联网搜索暂时不可用，已仅使用本地资料",),
            )
