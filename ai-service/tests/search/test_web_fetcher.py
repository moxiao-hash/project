import httpx
import pytest

from app.search.web_fetcher import PrivateNetworkUrlError, SafeWebFetcher


@pytest.mark.anyio
async def test_fetches_public_html_as_plain_text() -> None:
    fetcher = SafeWebFetcher(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                200,
                headers={"Content-Type": "text/html; charset=utf-8"},
                text="<h1>Spring Boot</h1><p>System requirements</p>",
            )
        ),
        resolver=lambda _: ["93.184.216.34"],
    )

    content = await fetcher.fetch("https://example.com/docs")

    assert content.decode() == "Spring Boot System requirements"


@pytest.mark.anyio
async def test_rejects_private_address_before_request() -> None:
    fetcher = SafeWebFetcher(resolver=lambda _: ["127.0.0.1"])

    with pytest.raises(PrivateNetworkUrlError):
        await fetcher.fetch("https://example.com/private")
