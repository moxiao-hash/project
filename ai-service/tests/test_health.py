import asyncio

import httpx

from app.main import app


def test_health_reports_service_is_up() -> None:
    async def request_health() -> httpx.Response:
        # ASGITransport 在内存中调用 FastAPI，不需要真的占用一个本机端口。
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get("/health")

    response = asyncio.run(request_health())

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": "studypilot-ai"}
