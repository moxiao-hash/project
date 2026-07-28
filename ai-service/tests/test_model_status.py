import asyncio
from collections.abc import Iterator

import httpx
import pytest
from pydantic import SecretStr

from app.core.settings import Settings, get_settings
from app.main import app


@pytest.fixture
def settings() -> Settings:
    return Settings(
        deepseek_api_key=SecretStr("secret-key"),
        tavily_api_key=SecretStr("tavily-secret"),
        internal_service_token=SecretStr("test-internal-token"),
    )


@pytest.fixture(autouse=True)
def override_settings(settings: Settings) -> Iterator[None]:
    app.dependency_overrides[get_settings] = lambda: settings
    yield
    app.dependency_overrides.clear()


def request(path: str, *, token: str | None = None) -> httpx.Response:
    async def send() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        headers = {"X-Internal-Service-Token": token} if token else {}
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get(path, headers=headers)

    return asyncio.run(send())


def test_model_status_rejects_missing_internal_token() -> None:
    response = request("/internal/model/status")

    assert response.status_code == 401


def test_model_status_rejects_wrong_internal_token() -> None:
    response = request("/internal/model/status", token="wrong-token")

    assert response.status_code == 401


def test_model_status_never_exposes_api_key() -> None:
    response = request("/internal/model/status", token="test-internal-token")

    assert response.status_code == 200
    assert response.json() == {
        "provider": "deepseek",
        "model": "deepseek-v4-pro",
        "configured": True,
    }
    assert "secret-key" not in response.text


def test_default_credential_status_only_returns_masked_metadata() -> None:
    response = request(
        "/internal/model/default-credentials",
        token="test-internal-token",
    )

    assert response.status_code == 200
    assert response.json() == {
        "provider": "deepseek",
        "model": "deepseek-v4-pro",
        "deepseek": {"configured": True, "maskedSuffix": "-key"},
        "tavily": {"configured": True, "maskedSuffix": "cret"},
    }
    assert "secret-key" not in response.text
    assert "tavily-secret" not in response.text
