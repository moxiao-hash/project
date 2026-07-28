"""资料与联网融合的内部知识会话 API。"""

from collections.abc import Awaitable
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status

from app.clients.java_backend import JavaBackendClient, JavaBackendError
from app.core.security import require_internal_token
from app.core.settings import Settings, get_settings
from app.knowledge.answering import DeepSeekKnowledgeAnswerer
from app.knowledge.models import (
    CreateKnowledgeConversationRequest,
    KnowledgeConversationSnapshot,
    SendKnowledgeMessageRequest,
)
from app.knowledge.service import (
    KnowledgeConversationBusyError,
    KnowledgeConversationNotFoundError,
    KnowledgeConversationService,
)
from app.providers.model_factory import ModelConfigurationError, create_chat_model
from app.retrieval.async_retriever import AsyncHybridRetriever
from app.retrieval.factory import get_hybrid_index
from app.search.service import WebSearchService
from app.search.tavily import TavilySearchClient

router = APIRouter(
    prefix="/internal/knowledge/conversations",
    tags=["internal-knowledge-conversations"],
    dependencies=[Depends(require_internal_token)],
)


def get_knowledge_conversation_service(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
) -> KnowledgeConversationService:
    existing = getattr(request.app.state, "knowledge_conversation_service", None)
    if existing is not None:
        return existing
    try:
        model = create_chat_model(settings)
    except ModelConfigurationError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    service = KnowledgeConversationService(
        AsyncHybridRetriever(get_hybrid_index(settings.qdrant_path)),
        WebSearchService(
            TavilySearchClient(
                settings.tavily_api_key,
                base_url=settings.tavily_base_url,
            ),
            JavaBackendClient(settings),
        ),
        DeepSeekKnowledgeAnswerer(
            model,
            model_provider=settings.model_provider,
            model_name=settings.model_name,
        ),
        model_provider=settings.model_provider,
        model_name=settings.model_name,
    )
    request.app.state.knowledge_conversation_service = service
    return service


async def _translate_errors[T](awaitable: Awaitable[T]) -> T:
    try:
        return await awaitable
    except KnowledgeConversationNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except KnowledgeConversationBusyError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except JavaBackendError as exc:
        raise HTTPException(status_code=503, detail="Java 后端暂时不可用") from exc


@router.post(
    "",
    response_model=KnowledgeConversationSnapshot,
    status_code=status.HTTP_201_CREATED,
)
async def create_conversation(
    body: CreateKnowledgeConversationRequest,
    service: Annotated[
        KnowledgeConversationService,
        Depends(get_knowledge_conversation_service),
    ],
) -> KnowledgeConversationSnapshot:
    return await _translate_errors(service.create_conversation(body.owner_id, body.mode))


@router.post("/{conversation_id}/messages", response_model=KnowledgeConversationSnapshot)
async def send_message(
    conversation_id: str,
    body: SendKnowledgeMessageRequest,
    service: Annotated[
        KnowledgeConversationService,
        Depends(get_knowledge_conversation_service),
    ],
) -> KnowledgeConversationSnapshot:
    return await _translate_errors(
        service.send_message(
            conversation_id,
            body.message,
            body.web_search,
            body.owner_id,
        )
    )


@router.get("/{conversation_id}", response_model=KnowledgeConversationSnapshot)
async def get_conversation(
    conversation_id: str,
    owner_id: Annotated[str, Query(alias="ownerId", min_length=1)],
    service: Annotated[
        KnowledgeConversationService,
        Depends(get_knowledge_conversation_service),
    ],
) -> KnowledgeConversationSnapshot:
    return await _translate_errors(service.get_conversation(conversation_id, owner_id))
