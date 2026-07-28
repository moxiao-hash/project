import asyncio

import httpx
import pytest
from pydantic import SecretStr

from app.clients.java_backend import JavaBackendClient
from app.core.settings import Settings
from app.providers.credentials import (
    CredentialProvider,
    CredentialResolver,
    CredentialServiceUnavailableError,
)


def test_user_key_takes_priority_over_environment_default() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["X-Internal-Service-Token"] == "internal-test"
        assert request.url.params["ownerId"] == "owner-1"
        return httpx.Response(200, json={"apiKey": "user-deepseek-key"})

    settings = Settings(
        internal_service_token=SecretStr("internal-test"),
        deepseek_api_key=SecretStr("server-deepseek-key"),
    )
    java = JavaBackendClient(settings, transport=httpx.MockTransport(handler))
    resolved = asyncio.run(
        CredentialResolver(java, settings).resolve(
            "owner-1", CredentialProvider.DEEPSEEK
        )
    )

    assert resolved.get_secret_value() == "user-deepseek-key"


def test_404_falls_back_to_environment_but_unavailable_does_not() -> None:
    settings = Settings(
        internal_service_token=SecretStr("internal-test"),
        tavily_api_key=SecretStr("server-tavily-key"),
    )

    not_found = JavaBackendClient(
        settings,
        transport=httpx.MockTransport(
            lambda _: httpx.Response(404, json={"message": "not configured"})
        ),
    )
    resolved = asyncio.run(
        CredentialResolver(not_found, settings).resolve(
            "owner-1", CredentialProvider.TAVILY
        )
    )
    assert resolved.get_secret_value() == "server-tavily-key"

    unavailable = JavaBackendClient(
        settings,
        transport=httpx.MockTransport(lambda _: httpx.Response(503)),
    )
    with pytest.raises(CredentialServiceUnavailableError):
        asyncio.run(
            CredentialResolver(unavailable, settings).resolve(
                "owner-1", CredentialProvider.TAVILY
            )
        )


def test_missing_user_and_default_returns_empty_secret() -> None:
    settings = Settings(
        _env_file=None,
        internal_service_token=SecretStr("internal-test"),
        deepseek_api_key=SecretStr(""),
    )
    java = JavaBackendClient(
        settings,
        transport=httpx.MockTransport(lambda _: httpx.Response(404)),
    )
    resolved = asyncio.run(
        CredentialResolver(java, settings).resolve(
            "owner-1", CredentialProvider.DEEPSEEK
        )
    )
    assert resolved.get_secret_value() == ""
