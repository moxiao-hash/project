from pydantic import BaseModel

from app.core.settings import Settings
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
