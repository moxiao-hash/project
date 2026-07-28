"""学习计划 Agent 的内部会话 API。"""

from collections.abc import Awaitable, Callable
from time import monotonic
from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status

from app.agent.grounding import PlanGroundingService
from app.agent.models import (
    ConversationSnapshot,
    CreateConversationRequest,
    OwnerConversationRequest,
    SendMessageRequest,
)
from app.agent.planner import DeepSeekPlanner, PlannerOutputError
from app.agent.service import (
    ConversationBusyError,
    ConversationNotFoundError,
    ConversationService,
    GoalNotFoundError,
    InvalidConversationStateError,
)
from app.clients.java_backend import JavaBackendClient, JavaBackendError
from app.core.security import require_internal_token
from app.core.settings import Settings, get_settings
from app.persistence.agent_state import AgentPersistence
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
    prefix="/internal/agent/conversations",
    tags=["internal-agent-conversations"],
    dependencies=[Depends(require_internal_token)],
)


class OwnerScopedConversationServices:
    """按 owner 构造服务，防止把某用户的模型客户端复用于另一用户。"""

    def __init__(
        self,
        settings: Settings,
        *,
        max_runtime_entries: int = 100,
        runtime_idle_ttl_seconds: float = 900,
        clock: Callable[[], float] = monotonic,
        persistence: AgentPersistence | None = None,
    ) -> None:
        self._settings = settings
        self._persistence = persistence
        self._services: dict[str, ConversationService] = {}
        self._runtime_fingerprints = OwnerRuntimeCache[str](
            max_entries=max_runtime_entries,
            idle_ttl_seconds=runtime_idle_ttl_seconds,
            clock=clock,
            on_evict=self._clear_runtime,
        )

    async def for_owner(self, owner_id: str) -> ConversationService:
        java = JavaBackendClient(self._settings)
        resolver = CredentialResolver(java, self._settings)
        deepseek_key = await resolver.resolve(owner_id, CredentialProvider.DEEPSEEK)
        tavily_key = await resolver.resolve(owner_id, CredentialProvider.TAVILY)
        fingerprint = credential_fingerprint(deepseek_key, tavily_key)
        service = self._services.get(owner_id)
        cached_fingerprint = self._runtime_fingerprints.get(owner_id)
        if service is not None and cached_fingerprint == fingerprint:
            return service

        model = create_chat_model(self._settings, deepseek_key)
        grounding = PlanGroundingService(
            AsyncHybridRetriever(get_hybrid_index(self._settings.qdrant_path)),
            WebSearchService(
                TavilySearchClient(
                    tavily_key,
                    base_url=self._settings.tavily_base_url,
                ),
                java,
            ),
        )
        if service is None:
            service = ConversationService(
                DeepSeekPlanner(model),
                java,
                grounding,
                persistence=self._persistence,
            )
            self._services[owner_id] = service
        else:
            service.replace_runtime(DeepSeekPlanner(model), grounding)
        self._runtime_fingerprints.put(owner_id, fingerprint)
        return service

    def _clear_runtime(self, owner_id: str, _fingerprint: str) -> None:
        service = self._services.get(owner_id)
        if service is not None:
            service.clear_runtime()


def get_conversation_service(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
) -> Any:
    """惰性创建应用级单例；创建客户端本身不会调用模型或消耗 Token。"""

    existing = getattr(request.app.state, "conversation_service", None)
    if existing is not None:
        return existing
    service = OwnerScopedConversationServices(
        settings,
        persistence=getattr(request.app.state, "agent_persistence", None),
    )
    request.app.state.conversation_service = service
    return service


async def _for_owner(service: Any, owner_id: str) -> ConversationService:
    factory = getattr(service, "for_owner", None)
    try:
        return await factory(owner_id) if factory is not None else service
    except CredentialServiceUnavailableError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except ModelConfigurationError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


async def _translate_errors[T](awaitable: Awaitable[T]) -> T:
    """把领域异常映射为稳定 HTTP 状态，避免向调用方泄露堆栈或密钥。"""

    try:
        return await awaitable
    except (ConversationNotFoundError, GoalNotFoundError) as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
    except (ConversationBusyError, InvalidConversationStateError) as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    except PlannerOutputError as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="模型未返回合法的学习计划",
        ) from exc
    except JavaBackendError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Java 后端暂时不可用",
        ) from exc


@router.post("", response_model=ConversationSnapshot, status_code=status.HTTP_201_CREATED)
async def create_conversation(
    body: CreateConversationRequest,
    service: Annotated[ConversationService, Depends(get_conversation_service)],
) -> ConversationSnapshot:
    scoped = await _for_owner(service, body.owner_id)
    return await _translate_errors(scoped.create_conversation(body.owner_id, body.goal_id))


@router.post("/{conversation_id}/messages", response_model=ConversationSnapshot)
async def send_message(
    conversation_id: str,
    body: SendMessageRequest,
    service: Annotated[ConversationService, Depends(get_conversation_service)],
) -> ConversationSnapshot:
    scoped = await _for_owner(service, body.owner_id)
    return await _translate_errors(
        scoped.send_message(conversation_id, body.message, body.owner_id)
    )


@router.get("/{conversation_id}", response_model=ConversationSnapshot)
async def get_conversation(
    conversation_id: str,
    owner_id: Annotated[str, Query(alias="ownerId", min_length=1)],
    service: Annotated[ConversationService, Depends(get_conversation_service)],
) -> ConversationSnapshot:
    scoped = await _for_owner(service, owner_id)
    return await _translate_errors(scoped.get_conversation(conversation_id, owner_id))


@router.post("/{conversation_id}/confirm", response_model=ConversationSnapshot)
async def confirm_conversation(
    conversation_id: str,
    body: OwnerConversationRequest,
    service: Annotated[ConversationService, Depends(get_conversation_service)],
) -> ConversationSnapshot:
    scoped = await _for_owner(service, body.owner_id)
    return await _translate_errors(scoped.confirm(conversation_id, body.owner_id))
