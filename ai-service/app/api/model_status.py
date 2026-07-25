"""模型配置状态接口。"""

from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel

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
