import pytest
from pydantic import SecretStr

from app.core.settings import Settings
from app.providers.model_factory import ModelConfigurationError, create_chat_model


def test_builds_openai_compatible_deepseek_client() -> None:
    settings = Settings(
        model_provider="deepseek",
        model_base_url="https://api.deepseek.com",
        model_name="deepseek-v4-pro",
        deepseek_api_key=SecretStr("secret-key"),
    )

    model = create_chat_model(settings)

    assert model.model_name == "deepseek-v4-pro"
    assert str(model.openai_api_base).rstrip("/") == "https://api.deepseek.com"
    assert model.openai_api_key.get_secret_value() == "secret-key"
    assert model.temperature == 0


def test_rejects_missing_deepseek_api_key() -> None:
    settings = Settings(deepseek_api_key=SecretStr(""))

    with pytest.raises(ModelConfigurationError, match="DEEPSEEK_API_KEY"):
        create_chat_model(settings)


def test_runtime_user_key_overrides_server_default() -> None:
    settings = Settings(deepseek_api_key=SecretStr("server-default-key"))

    model = create_chat_model(settings, SecretStr("user-runtime-key"))

    assert model.openai_api_key.get_secret_value() == "user-runtime-key"
