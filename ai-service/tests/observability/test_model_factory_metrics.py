from pydantic import BaseModel

from app.core.settings import Settings
from app.observability.usage import (
    AssistantUsageReporter,
    ModelPurpose,
    ModelUsageCallback,
)
from app.providers.model_factory import create_chat_model


class StructuredAnswer(BaseModel):
    value: str


def test_chat_model_installs_observability_callback() -> None:
    model = create_chat_model(
        Settings(deepseek_api_key="test-key", model_name="deepseek-test")
    )

    callbacks = model.callbacks
    assert callbacks
    assert any(type(callback).__name__ == "ModelMetricsCallback" for callback in callbacks)

    structured = model.with_structured_output(StructuredAnswer)
    structured_callbacks = structured.first.bound.callbacks
    assert structured_callbacks
    assert any(
        type(callback).__name__ == "ModelMetricsCallback"
        for callback in structured_callbacks
    )


def test_chat_model_installs_usage_callback_with_owner_and_purpose() -> None:
    model = create_chat_model(
        Settings(deepseek_api_key="test-key", model_name="deepseek-test"),
        owner_id="owner-1",
        purpose=ModelPurpose.QUIZ_GENERATION,
        reporter=AssistantUsageReporter(object(), max_attempts=1),
    )
    usage_callbacks = [
        callback for callback in model.callbacks
        if isinstance(callback, ModelUsageCallback)
    ]
    assert len(usage_callbacks) == 1
    assert usage_callbacks[0].owner_id == "owner-1"
    assert usage_callbacks[0].purpose == "QUIZ_GENERATION"
