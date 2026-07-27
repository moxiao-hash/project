"""由任务触发的内部自适应测验生成 API。"""

from typing import Annotated

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


def get_quiz_generation_service(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
) -> QuizGenerationService:
    existing = getattr(request.app.state, "quiz_generation_service", None)
    if existing is not None:
        return existing
    try:
        model = create_chat_model(settings)
    except ModelConfigurationError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    java = JavaBackendClient(settings)
    service = QuizGenerationService(
        java,
        AsyncHybridRetriever(get_hybrid_index(settings.qdrant_path)),
        WebSearchService(
            TavilySearchClient(
                settings.tavily_api_key,
                base_url=settings.tavily_base_url,
            ),
            java,
        ),
        DeepSeekQuizGenerator(model),
    )
    request.app.state.quiz_generation_service = service
    return service


@router.post("/generate", status_code=status.HTTP_201_CREATED)
async def generate_quiz(
    body: GenerateQuizRequest,
    service: Annotated[QuizGenerationService, Depends(get_quiz_generation_service)],
) -> dict:
    try:
        return await service.generate(body.owner_id, body.task_id, body.web_search)
    except AssessmentTaskNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except PrivateAssessmentSourceError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except InvalidGeneratedQuizError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    except JavaBackendError as exc:
        raise HTTPException(status_code=503, detail="Java 后端暂时不可用") from exc
