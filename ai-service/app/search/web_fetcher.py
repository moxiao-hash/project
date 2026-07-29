"""带 DNS 与重定向校验的网页正文抓取器。"""

import ipaddress
import socket
from collections.abc import Callable
from html.parser import HTMLParser
from urllib.parse import urljoin, urlparse

import httpx

from app.core.request_context import outbound_request_headers


class PrivateNetworkUrlError(ValueError):
    """URL 解析到非公网地址，禁止服务端访问。"""


class _TextExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.parts: list[str] = []

    def handle_data(self, data: str) -> None:
        cleaned = " ".join(data.split())
        if cleaned:
            self.parts.append(cleaned)


def _resolve(host: str) -> list[str]:
    return list(
        {
            info[4][0]
            for info in socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
        }
    )


class SafeWebFetcher:
    def __init__(
        self,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        resolver: Callable[[str], list[str]] = _resolve,
        timeout_seconds: float = 15.0,
    ) -> None:
        self._transport = transport
        self._resolver = resolver
        self._timeout = timeout_seconds

    async def fetch(self, url: str) -> bytes:
        current = url
        async with httpx.AsyncClient(
            transport=self._transport,
            timeout=self._timeout,
            follow_redirects=False,
            headers=outbound_request_headers(),
        ) as client:
            for _ in range(4):
                self._validate(current)
                response = await client.get(current)
                if response.is_redirect:
                    location = response.headers.get("Location")
                    if not location:
                        raise ValueError("网页重定向缺少目标地址")
                    current = urljoin(current, location)
                    continue
                response.raise_for_status()
                if len(response.content) > 5 * 1024 * 1024:
                    raise ValueError("网页正文不能超过 5 MB")
                media_type = response.headers.get("Content-Type", "").lower()
                if "text/html" in media_type:
                    parser = _TextExtractor()
                    parser.feed(response.text)
                    return " ".join(parser.parts).encode()
                if "text/plain" in media_type:
                    return response.content
                raise ValueError("网页不是可解析的 HTML 或纯文本")
        raise ValueError("网页重定向次数超过限制")

    def _validate(self, url: str) -> None:
        parsed = urlparse(url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError("网页地址必须是有效的 HTTP 或 HTTPS URL")
        for raw_address in self._resolver(parsed.hostname):
            address = ipaddress.ip_address(raw_address)
            if not address.is_global:
                raise PrivateNetworkUrlError("网页地址不能指向本机或内网")
