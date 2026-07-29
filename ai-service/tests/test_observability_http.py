import asyncio

import httpx

from app.main import app


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
