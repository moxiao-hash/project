import asyncio

import httpx

from app.clients.java_backend import JavaBackendClient
from app.core.request_context import (
    bind_request_id,
    current_request_id,
    outbound_request_headers,
    reset_request_id,
)
from app.core.settings import Settings


def test_rejects_unsafe_request_id_and_clears_context() -> None:
    token = bind_request_id("unsafe\r\nforged")
    try:
        request_id = current_request_id()
        assert request_id is not None
        assert "\r" not in request_id
        assert "\n" not in request_id
        assert len(request_id) <= 64
    finally:
        reset_request_id(token)

    assert current_request_id() is None


def test_java_outbound_request_carries_correlation_id() -> None:
    captured: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured.update(request.headers)
        return httpx.Response(200, json={})

    token = bind_request_id("req-python-123")
    try:
        client = JavaBackendClient(
            Settings(internal_service_token="secret"),
            transport=httpx.MockTransport(handler),
        )
        asyncio.run(client._request("GET", "/internal/example"))
    finally:
        reset_request_id(token)

    assert captured["x-request-id"] == "req-python-123"
    assert outbound_request_headers() == {}
