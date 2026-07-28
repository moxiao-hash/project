"""计划自适应分析、查询和显式确认 API。"""

from datetime import date
from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from pydantic import SecretStr

from app.agent.adjustment_service import (
    AdjustmentOutputError,
    DeepSeekAdjustmentGenerator,
    PlanAdjustmentNotFoundError,
    PlanAdjustmentService,
)
from app.clients.java_backend import JavaBackendClient, JavaBackendError
from app.core.security import require_internal_token
from app.core.settings import Settings, get_settings
from app.providers.credentials import (
    CredentialProvider,
    CredentialResolver,
    CredentialServiceUnavailableError,
)
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


def build_plan_adjustment_service(
    settings: Settings,
    api_key: SecretStr | None = None,
) -> PlanAdjustmentService:
    model = create_chat_model(settings, api_key)
    return PlanAdjustmentService(
        DeepSeekAdjustmentGenerator(model),
        JavaBackendClient(settings, timeout_seconds=45),
    )


class OwnerScopedAdjustmentServices:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def for_owner(self, owner_id: str) -> PlanAdjustmentService:
        java = JavaBackendClient(self._settings, timeout_seconds=45)
        key = await CredentialResolver(java, self._settings).resolve(
            owner_id, CredentialProvider.DEEPSEEK
        )
        service = PlanAdjustmentService(
            DeepSeekAdjustmentGenerator(create_chat_model(self._settings, key)),
            java,
        )
        return service


def get_plan_adjustment_service(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
) -> Any:
    existing = getattr(request.app.state, "plan_adjustment_service", None)
    if existing is not None:
        return existing
    service = OwnerScopedAdjustmentServices(settings)
    request.app.state.plan_adjustment_service = service
    return service


async def _for_owner(service: Any, owner_id: str) -> PlanAdjustmentService:
    factory = getattr(service, "for_owner", None)
    try:
        return await factory(owner_id) if factory is not None else service
    except (CredentialServiceUnavailableError, ModelConfigurationError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


def translate_error(exc: Exception) -> HTTPException:
    if isinstance(exc, PlanAdjustmentNotFoundError):
        return HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="计划调整不存在",
        )
    if isinstance(exc, JavaBackendError):
        if exc.status_code == 404:
            return HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="计划调整不存在",
            )
        if exc.status_code == 409:
            return HTTPException(status_code=409, detail=exc.detail or str(exc))
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
    scoped = await _for_owner(service, body.owner_id)
    try:
        return await scoped.analyze(
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
    scoped = await _for_owner(service, owner_id)
    try:
        return await scoped.get(adjustment_id, owner_id)
    except Exception as exc:
        raise translate_error(exc) from exc


@router.post("/{adjustment_id}/confirm", response_model=PlanAdjustment)
async def confirm_plan_adjustment(
    adjustment_id: str,
    body: ConfirmPlanAdjustmentRequest,
    service: Annotated[PlanAdjustmentService, Depends(get_plan_adjustment_service)],
) -> PlanAdjustment:
    scoped = await _for_owner(service, body.owner_id)
    try:
        return await scoped.confirm(adjustment_id, body.owner_id)
    except Exception as exc:
        raise translate_error(exc) from exc
