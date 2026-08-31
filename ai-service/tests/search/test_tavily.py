import httpx
import pytest
from pydantic import SecretStr

from app.search.tavily import TavilySearchClient


@pytest.mark.anyio
async def test_maps_tavily_results_without_provider_generated_answer() -> None:
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured.update(__import__("json").loads(request.content))
        return httpx.Response(
            200,
            json={
                "request_id": "request-1",
                "results": [
                    {
                        "title": "Spring Boot Reference",
                        "url": "https://docs.spring.io/spring-boot/",
                        "content": "System requirements and reference docs.",
                        "score": 0.92,
                    }
                ],
            },
        )

    client = TavilySearchClient(
        SecretStr("tvly-test"),
        transport=httpx.MockTransport(handler),
    )

    result = await client.search(
        "Spring Boot 当前 Java 版本", include_domains=("docs.spring.io",)
    )

    assert captured["search_depth"] == "basic"
    assert captured["include_answer"] is False
    assert captured["include_raw_content"] is False
    assert captured["include_domains"] == ["docs.spring.io"]
    assert result.provider_request_id == "request-1"
    assert result.results[0].url == "https://docs.spring.io/spring-boot/"


@pytest.mark.anyio
async def test_missing_key_returns_explicit_degradation_without_network() -> None:
    client = TavilySearchClient(SecretStr(""))

    result = await client.search("最新版本")

    assert result.results == ()
    assert result.warnings == ("未配置 TAVILY_API_KEY，本轮未执行联网搜索",)


@pytest.mark.anyio
async def test_provider_failure_returns_degradation_warning() -> None:
    client = TavilySearchClient(
        SecretStr("tvly-test"),
        transport=httpx.MockTransport(lambda _: httpx.Response(429)),
    )

    result = await client.search("最新版本")

    assert result.results == ()
    assert result.warnings == ("联网搜索暂时不可用，已仅使用本地资料",)
