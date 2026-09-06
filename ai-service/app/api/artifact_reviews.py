"""Internal endpoint for user-confirmed roadmap artifact reviews."""

from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Request

from app.artifact_review.evaluator import DeepSeekArtifactEvaluator
from app.artifact_review.models import ArtifactReviewRequest
from app.clients.java_backend import JavaBackendClient
from app.core.security import require_internal_token
from app.core.settings import Settings, get_settings
from app.providers.credentials import (
    CredentialProvider,
    CredentialResolver,
    CredentialServiceUnavailableError,
)
from app.providers.model_factory import ModelConfigurationError, create_chat_model

router = APIRouter(
    prefix="/internal/artifacts",
    tags=["internal-artifact-review"],
    dependencies=[Depends(require_internal_token)],
)


class OwnerScopedArtifactReviewServices:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def for_owner(self, owner_id: str) -> DeepSeekArtifactEvaluator:
        java = JavaBackendClient(self._settings)
        key = await CredentialResolver(java, self._settings).resolve(
            owner_id,
            CredentialProvider.DEEPSEEK,
        )
        return DeepSeekArtifactEvaluator(create_chat_model(self._settings, key))


def get_artifact_review_service(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
) -> Any:
    existing = getattr(request.app.state, "artifact_review_service", None)
    if existing is not None:
        return existing
    service = OwnerScopedArtifactReviewServices(settings)
    request.app.state.artifact_review_service = service
    return service


async def _for_owner(service: Any, owner_id: str) -> DeepSeekArtifactEvaluator:
    factory = getattr(service, "for_owner", None)
    try:
        return await factory(owner_id) if factory is not None else service
    except (CredentialServiceUnavailableError, ModelConfigurationError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.post("/evaluate")
async def evaluate_artifact(
    body: ArtifactReviewRequest,
    service: Annotated[Any, Depends(get_artifact_review_service)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict[str, Any]:
    evaluator = await _for_owner(service, body.owner_id)
    result = await evaluator.evaluate(
        body.model_dump(by_alias=True, exclude={"owner_id"}, mode="json")
    )
    return {
        **result.model_dump(by_alias=True, mode="json"),
        "modelName": settings.model_name,
    }
