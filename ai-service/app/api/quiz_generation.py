"""由任务触发的内部自适应测验生成 API。"""

from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.assessment.generator import DeepSeekQuizGenerator
from app.assessment.models import GenerateQuizRequest
from app.assessment.service import (
    AssessmentTaskNotFoundError,
    InvalidGeneratedQuizError,
    PrivateAssessmentSourceError,
    QuizGenerationService,
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
from app.retrieval.async_retriever import AsyncHybridRetriever
from app.retrieval.factory import get_hybrid_index
from app.search.service import WebSearchService
from app.search.tavily import TavilySearchClient

router = APIRouter(
    prefix="/internal/assessment/quizzes",
    tags=["internal-assessment"],
    dependencies=[Depends(require_internal_token)],
)


class OwnerScopedQuizServices:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def for_owner(self, owner_id: str) -> QuizGenerationService:
        java = JavaBackendClient(self._settings)
        resolver = CredentialResolver(java, self._settings)
        deepseek_key = await resolver.resolve(owner_id, CredentialProvider.DEEPSEEK)
        tavily_key = await resolver.resolve(owner_id, CredentialProvider.TAVILY)
        service = QuizGenerationService(
            java,
            AsyncHybridRetriever(
                get_hybrid_index(
                    self._settings.qdrant_path,
                    self._settings.fastembed_cache_path,
                )
            ),
            WebSearchService(
                TavilySearchClient(
                    tavily_key,
                    base_url=self._settings.tavily_base_url,
                ),
                java,
            ),
            DeepSeekQuizGenerator(create_chat_model(self._settings, deepseek_key)),
        )
        return service


def get_quiz_generation_service(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
) -> Any:
    existing = getattr(request.app.state, "quiz_generation_service", None)
    if existing is not None:
        return existing
    service = OwnerScopedQuizServices(settings)
    request.app.state.quiz_generation_service = service
    return service


async def _for_owner(service: Any, owner_id: str) -> QuizGenerationService:
    factory = getattr(service, "for_owner", None)
    try:
        return await factory(owner_id) if factory is not None else service
    except (CredentialServiceUnavailableError, ModelConfigurationError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.post("/generate", status_code=status.HTTP_201_CREATED)
async def generate_quiz(
    body: GenerateQuizRequest,
    service: Annotated[QuizGenerationService, Depends(get_quiz_generation_service)],
) -> dict:
    try:
        scoped = await _for_owner(service, body.owner_id)
        if body.lesson_id is not None:
            return await scoped.generate(
                body.owner_id,
                body.task_id,
                body.web_search,
                lesson_id=body.lesson_id,
            )
        return await scoped.generate(body.owner_id, body.task_id, body.web_search)
    except AssessmentTaskNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except PrivateAssessmentSourceError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except InvalidGeneratedQuizError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    except JavaBackendError as exc:
        if exc.status_code is not None and 400 <= exc.status_code < 500:
            detail = exc.detail or f"HTTP {exc.status_code}"
            raise HTTPException(
                status_code=502,
                detail=f"Java 拒绝保存测验：{detail}",
            ) from exc
        raise HTTPException(status_code=503, detail="Java 后端暂时不可用") from exc
