"""模型配置状态接口。"""

from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from app.core.security import require_internal_token
from app.core.settings import Settings, get_settings

router = APIRouter(
    prefix="/internal/model",
    tags=["internal-model"],
    dependencies=[Depends(require_internal_token)],
)


class ModelStatusResponse(BaseModel):
    """可安全返回给调用方的模型配置摘要。"""

    provider: str
    model: str
    configured: bool


class SafeCredentialStatus(BaseModel):
    configured: bool
    masked_suffix: str | None = Field(serialization_alias="maskedSuffix")


class DefaultCredentialsResponse(BaseModel):
    deepseek: SafeCredentialStatus
    tavily: SafeCredentialStatus


@router.get("/status", response_model=ModelStatusResponse)
async def model_status(
    settings: Annotated[Settings, Depends(get_settings)],
) -> ModelStatusResponse:
    """报告模型是否可用，但永远不返回 API Key。"""

    return ModelStatusResponse(
        provider=settings.model_provider,
        model=settings.model_name,
        configured=settings.model_is_configured,
    )


@router.get("/default-credentials", response_model=DefaultCredentialsResponse)
async def default_credentials(
    settings: Annotated[Settings, Depends(get_settings)],
) -> DefaultCredentialsResponse:
    """只公开环境默认凭据是否存在和尾号，绝不返回明文。"""

    return DefaultCredentialsResponse(
        deepseek=_safe_status(settings.deepseek_api_key.get_secret_value()),
        tavily=_safe_status(settings.tavily_api_key.get_secret_value()),
    )


def _safe_status(value: str) -> SafeCredentialStatus:
    configured = bool(value)
    return SafeCredentialStatus(
        configured=configured,
        masked_suffix=value[-4:] if configured else None,
    )
