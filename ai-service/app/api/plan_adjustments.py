"""计划自适应分析、查询和显式确认 API。"""

from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status

from app.agent.adjustment_service import (
    AdjustmentOutputError,
    DeepSeekAdjustmentGenerator,
    PlanAdjustmentService,
)
from app.clients.java_backend import JavaBackendClient, JavaBackendError
from app.core.security import require_internal_token
from app.core.settings import Settings, get_settings
from app.providers.model_factory import ModelConfigurationError, create_chat_model
from app.schemas.learning import JavaContractModel, PlanAdjustment

router = APIRouter(
    prefix="/internal/agent/plan-adjustments",
    tags=["internal-agent-plan-adjustments"],
    dependencies=[Depends(require_internal_token)],
)


class AnalyzePlanAdjustmentRequest(JavaContractModel):
    owner_id: str
    analysis_date: date


class ConfirmPlanAdjustmentRequest(JavaContractModel):
    owner_id: str


def build_plan_adjustment_service(settings: Settings) -> PlanAdjustmentService:
    model = create_chat_model(settings)
    return PlanAdjustmentService(
        DeepSeekAdjustmentGenerator(model),
        JavaBackendClient(settings, timeout_seconds=45),
    )


def get_plan_adjustment_service(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
) -> PlanAdjustmentService:
    existing = getattr(request.app.state, "plan_adjustment_service", None)
    if existing is not None:
        return existing
    try:
        service = build_plan_adjustment_service(settings)
    except ModelConfigurationError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=str(exc),
        ) from exc
    request.app.state.plan_adjustment_service = service
    return service


def translate_error(exc: Exception) -> HTTPException:
    if isinstance(exc, JavaBackendError):
        if exc.status_code in {404, 409}:
            return HTTPException(status_code=exc.status_code, detail=exc.detail or str(exc))
        return HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="计划调整依赖服务暂时不可用",
        )
    if isinstance(exc, AdjustmentOutputError):
        return HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=str(exc),
        )
    return HTTPException(
        status_code=status.HTTP_502_BAD_GATEWAY,
        detail="模型未返回合法的计划调整结果",
    )


@router.post(
    "/analyze",
    response_model=PlanAdjustment,
    status_code=status.HTTP_201_CREATED,
)
async def analyze_plan_adjustment(
    body: AnalyzePlanAdjustmentRequest,
    service: Annotated[PlanAdjustmentService, Depends(get_plan_adjustment_service)],
) -> PlanAdjustment:
    try:
        return await service.analyze(
            owner_id=body.owner_id,
            analysis_date=body.analysis_date,
            trigger_type="USER_REQUEST",
        )
    except Exception as exc:
        raise translate_error(exc) from exc


@router.get("/{adjustment_id}", response_model=PlanAdjustment)
async def get_plan_adjustment(
    adjustment_id: str,
    owner_id: Annotated[str, Query(alias="ownerId", min_length=1)],
    service: Annotated[PlanAdjustmentService, Depends(get_plan_adjustment_service)],
) -> PlanAdjustment:
    try:
        return await service.get(adjustment_id, owner_id)
    except Exception as exc:
        raise translate_error(exc) from exc


@router.post("/{adjustment_id}/confirm", response_model=PlanAdjustment)
async def confirm_plan_adjustment(
    adjustment_id: str,
    body: ConfirmPlanAdjustmentRequest,
    service: Annotated[PlanAdjustmentService, Depends(get_plan_adjustment_service)],
) -> PlanAdjustment:
    try:
        return await service.confirm(adjustment_id, body.owner_id)
    except Exception as exc:
        raise translate_error(exc) from exc
