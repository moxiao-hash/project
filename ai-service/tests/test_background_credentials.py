import httpx
import pytest
from pydantic import SecretStr

from app.clients.java_backend import JavaBackendClient
from app.core.settings import Settings
from app.main import (
    build_owner_adjustment_service,
    build_owner_coding_evaluator,
    build_owner_material_analyzer,
)


@pytest.mark.anyio
async def test_background_builders_use_each_users_key_with_empty_environment(
    monkeypatch,
) -> None:
    captured: list[str] = []

    class FakeChat:
        def with_structured_output(self, *_args, **_kwargs):
            return object()

    def fake_model(_settings, key):
        captured.append(key.get_secret_value())
        return FakeChat()

    def handler(request: httpx.Request) -> httpx.Response:
        owner_id = request.url.params["ownerId"]
        return httpx.Response(200, json={"apiKey": f"personal-key-{owner_id}"})

    settings = Settings(
        _env_file=None,
        deepseek_api_key=SecretStr(""),
        internal_service_token=SecretStr("internal-test"),
    )
    java = JavaBackendClient(settings, transport=httpx.MockTransport(handler))
    monkeypatch.setattr("app.main.create_chat_model", fake_model)
    monkeypatch.setattr("app.api.plan_adjustments.create_chat_model", fake_model)

    await build_owner_material_analyzer("user-a", settings, java)
    await build_owner_coding_evaluator("user-b", settings, java)
    await build_owner_adjustment_service("user-a", settings, java)

    assert captured == [
        "personal-key-user-a",
        "personal-key-user-b",
        "personal-key-user-a",
    ]
