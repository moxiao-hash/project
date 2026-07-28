"""资料与联网融合的内部知识会话 API。"""

from collections.abc import Awaitable
from typing import Annotated, Any

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
from app.providers.credentials import (
    CredentialProvider,
    CredentialResolver,
    CredentialServiceUnavailableError,
    credential_fingerprint,
)
from app.providers.model_factory import ModelConfigurationError, create_chat_model
from app.providers.owner_runtime_cache import OwnerRuntimeCache
from app.retrieval.async_retriever import AsyncHybridRetriever
from app.retrieval.factory import get_hybrid_index
from app.search.service import WebSearchService
from app.search.tavily import TavilySearchClient

router = APIRouter(
    prefix="/internal/knowledge/conversations",
    tags=["internal-knowledge-conversations"],
    dependencies=[Depends(require_internal_token)],
)


class OwnerScopedKnowledgeServices:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._services = OwnerRuntimeCache[tuple[str, KnowledgeConversationService]]()

    async def for_owner(self, owner_id: str) -> KnowledgeConversationService:
        java = JavaBackendClient(self._settings)
        resolver = CredentialResolver(java, self._settings)
        deepseek_key = await resolver.resolve(owner_id, CredentialProvider.DEEPSEEK)
        tavily_key = await resolver.resolve(owner_id, CredentialProvider.TAVILY)
        fingerprint = credential_fingerprint(deepseek_key, tavily_key)
        web = WebSearchService(
            TavilySearchClient(
                tavily_key,
                base_url=self._settings.tavily_base_url,
            ),
            java,
        )
        answerer = DeepSeekKnowledgeAnswerer(
            create_chat_model(self._settings, deepseek_key),
            model_provider=self._settings.model_provider,
            model_name=self._settings.model_name,
        )
        existing = self._services.get(owner_id)
        if existing is not None:
            old_fingerprint, service = existing
            if old_fingerprint != fingerprint:
                service.replace_runtime(web, answerer)
                self._services.put(owner_id, (fingerprint, service))
            return service
        service = KnowledgeConversationService(
            AsyncHybridRetriever(get_hybrid_index(self._settings.qdrant_path)),
            web,
            answerer,
            model_provider=self._settings.model_provider,
            model_name=self._settings.model_name,
        )
        self._services.put(owner_id, (fingerprint, service))
        return service


def get_knowledge_conversation_service(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
) -> Any:
    existing = getattr(request.app.state, "knowledge_conversation_service", None)
    if existing is not None:
        return existing
    service = OwnerScopedKnowledgeServices(settings)
    request.app.state.knowledge_conversation_service = service
    return service


async def _for_owner(service: Any, owner_id: str) -> KnowledgeConversationService:
    factory = getattr(service, "for_owner", None)
    try:
        return await factory(owner_id) if factory is not None else service
    except (CredentialServiceUnavailableError, ModelConfigurationError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


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
    scoped = await _for_owner(service, body.owner_id)
    return await _translate_errors(scoped.create_conversation(body.owner_id, body.mode))


@router.post("/{conversation_id}/messages", response_model=KnowledgeConversationSnapshot)
async def send_message(
    conversation_id: str,
    body: SendKnowledgeMessageRequest,
    service: Annotated[
        KnowledgeConversationService,
        Depends(get_knowledge_conversation_service),
    ],
) -> KnowledgeConversationSnapshot:
    scoped = await _for_owner(service, body.owner_id)
    return await _translate_errors(
        scoped.send_message(
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
    scoped = await _for_owner(service, owner_id)
    return await _translate_errors(scoped.get_conversation(conversation_id, owner_id))
