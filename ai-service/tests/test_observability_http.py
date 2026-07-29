import asyncio
import logging
from io import StringIO

import httpx
from fastapi import FastAPI
from fastapi.responses import JSONResponse

from app.main import app, request_correlation
from app.observability.safe_logging import install_secret_redaction


def test_response_echoes_safe_request_id_and_metrics_is_available() -> None:
    async def request() -> tuple[httpx.Response, httpx.Response]:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return (
                await client.get("/health", headers={"X-Request-ID": "browser-42"}),
                await client.get("/metrics"),
            )

    response, metrics = asyncio.run(request())
    assert response.headers["X-Request-ID"] == "browser-42"
    assert metrics.status_code == 200
    assert "studypilot_model_requests" in metrics.text


def test_exception_response_keeps_request_id_and_logs_safely() -> None:
    failing_app = FastAPI()
    failing_app.middleware("http")(request_correlation)

    @failing_app.get("/boom")
    async def boom() -> JSONResponse:
        raise RuntimeError("Authorization: Bearer middleware-secret")

    stream = StringIO()
    handler = logging.StreamHandler(stream)
    root = logging.getLogger()
    root.addHandler(handler)
    install_secret_redaction()

    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=failing_app, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get("/boom", headers={"X-Request-ID": "failure-42"})

    try:
        response = asyncio.run(request())
    finally:
        root.removeHandler(handler)

    assert response.status_code == 500
    assert response.headers["X-Request-ID"] == "failure-42"
    assert response.json() == {
        "code": "INTERNAL_ERROR",
        "message": "服务内部错误",
    }
    assert "failure-42" in stream.getvalue()
    assert "middleware-secret" not in stream.getvalue()
