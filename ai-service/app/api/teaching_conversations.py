"""课时内 AI 导师的内部会话 API。"""

from collections.abc import Awaitable
from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status

from app.clients.java_backend import JavaBackendClient, JavaBackendError
from app.core.security import require_internal_token
from app.core.settings import Settings
from app.persistence.agent_state import AgentPersistence
from app.providers.credentials import (
    CredentialProvider,
    CredentialResolver,
    CredentialServiceUnavailableError,
    credential_fingerprint,
)
from app.providers.model_factory import ModelConfigurationError, create_chat_model
from app.providers.owner_runtime_cache import OwnerRuntimeCache
from app.teaching.answering import DeepSeekTeachingAnswerer
from app.teaching.models import (
    CreateTeachingConversationRequest,
    SendTeachingMessageRequest,
    TeachingConversationSnapshot,
)
from app.teaching.service import (
    TeachingConversationBusyError,
    TeachingConversationNotFoundError,
    TeachingConversationService,
)

router = APIRouter(
    prefix="/internal/teaching/conversations",
    tags=["internal-teaching-conversations"],
    dependencies=[Depends(require_internal_token)],
)


class OwnerScopedTeachingServices:
    def __init__(
        self,
        settings: Settings,
        *,
        persistence: AgentPersistence | None = None,
    ) -> None:
        self._settings = settings
        self._persistence = persistence
        self._services: dict[str, TeachingConversationService] = {}
        self._fingerprints = OwnerRuntimeCache[str](
            max_entries=100,
            idle_ttl_seconds=900,
            on_evict=self._clear_runtime,
        )

    async def for_owner(self, owner_id: str) -> TeachingConversationService:
        java = JavaBackendClient(self._settings)
        key = await CredentialResolver(java, self._settings).resolve(
            owner_id,
            CredentialProvider.DEEPSEEK,
        )
        fingerprint = credential_fingerprint(key)
        service = self._services.get(owner_id)
        if service is not None and self._fingerprints.get(owner_id) == fingerprint:
            return service
        answerer = DeepSeekTeachingAnswerer(
            create_chat_model(self._settings, key),
            model_provider=self._settings.model_provider,
            model_name=self._settings.model_name,
        )
        if service is None:
            service = TeachingConversationService(
                java,
                answerer,
                model_provider=self._settings.model_provider,
                model_name=self._settings.model_name,
                persistence=self._persistence,
            )
            self._services[owner_id] = service
        else:
            service.replace_answerer(answerer)
        self._fingerprints.put(owner_id, fingerprint)
        return service

    def _clear_runtime(self, owner_id: str, _fingerprint: str) -> None:
        service = self._services.get(owner_id)
        if service is not None:
            service.clear_runtime()


def get_teaching_conversation_service(request: Request) -> Any:
    existing = getattr(request.app.state, "teaching_conversation_service", None)
    if existing is None:
        raise HTTPException(status_code=503, detail="课内导师服务尚未完成启动")
    return existing


async def _for_owner(service: Any, owner_id: str) -> TeachingConversationService:
    factory = getattr(service, "for_owner", None)
    try:
        return await factory(owner_id) if factory is not None else service
    except (CredentialServiceUnavailableError, ModelConfigurationError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


async def _translate_errors[T](awaitable: Awaitable[T]) -> T:
    try:
        return await awaitable
    except TeachingConversationNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except TeachingConversationBusyError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except JavaBackendError as exc:
        raise HTTPException(status_code=503, detail="Java 后端暂时不可用") from exc


@router.post(
    "",
    response_model=TeachingConversationSnapshot,
    status_code=status.HTTP_201_CREATED,
)
async def create_conversation(
    body: CreateTeachingConversationRequest,
    service: Annotated[Any, Depends(get_teaching_conversation_service)],
) -> TeachingConversationSnapshot:
    scoped = await _for_owner(service, body.owner_id)
    return await _translate_errors(
        scoped.create_conversation(body.owner_id, body.lesson_id)
    )


@router.post("/{conversation_id}/messages", response_model=TeachingConversationSnapshot)
async def send_message(
    conversation_id: str,
    body: SendTeachingMessageRequest,
    service: Annotated[Any, Depends(get_teaching_conversation_service)],
) -> TeachingConversationSnapshot:
    scoped = await _for_owner(service, body.owner_id)
    return await _translate_errors(
        scoped.send_message(
            conversation_id,
            owner_id=body.owner_id,
            message=body.message,
        )
    )


@router.get("/{conversation_id}", response_model=TeachingConversationSnapshot)
async def get_conversation(
    conversation_id: str,
    owner_id: Annotated[str, Query(alias="ownerId", min_length=1)],
    service: Annotated[Any, Depends(get_teaching_conversation_service)],
) -> TeachingConversationSnapshot:
    scoped = await _for_owner(service, owner_id)
    return await _translate_errors(scoped.get_conversation(conversation_id, owner_id))
