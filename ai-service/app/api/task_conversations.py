"""任务状态操作 Agent 的内部会话 API。"""

from collections.abc import Awaitable
from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status

from app.agent.models import OwnerConversationRequest, SendMessageRequest
from app.agent.task_conversation_models import (
    CreateTaskConversationRequest,
    TaskConversationSnapshot,
)
from app.agent.task_conversation_service import (
    InvalidTaskConversationStateError,
    TaskConversationBusyError,
    TaskConversationNotFoundError,
    TaskConversationService,
    TaskExecutionUnavailableError,
    TaskVersionConflictError,
)
from app.agent.task_recognizer import (
    DeepSeekTaskRecognizer,
    TaskRecognitionOutputError,
)
from app.agent.task_service import TaskRecognitionService
from app.clients.java_backend import JavaBackendClient, JavaBackendError
from app.core.security import require_internal_token
from app.core.settings import Settings, get_settings
from app.providers.credentials import (
    CredentialProvider,
    CredentialResolver,
    CredentialServiceUnavailableError,
    credential_fingerprint,
)
from app.providers.model_factory import ModelConfigurationError, create_chat_model
from app.providers.owner_runtime_cache import OwnerRuntimeCache

router = APIRouter(
    prefix="/internal/agent/task-conversations",
    tags=["internal-agent-task-conversations"],
    dependencies=[Depends(require_internal_token)],
)


class OwnerScopedTaskConversationServices:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._services = OwnerRuntimeCache[tuple[str, TaskConversationService]]()

    async def for_owner(self, owner_id: str) -> TaskConversationService:
        java = JavaBackendClient(self._settings)
        key = await CredentialResolver(java, self._settings).resolve(
            owner_id, CredentialProvider.DEEPSEEK
        )
        fingerprint = credential_fingerprint(key)
        recognition_service = TaskRecognitionService(
            DeepSeekTaskRecognizer(create_chat_model(self._settings, key)),
            java,
        )
        existing = self._services.get(owner_id)
        if existing is not None:
            old_fingerprint, service = existing
            if old_fingerprint != fingerprint:
                service.replace_runtime(recognition_service)
                self._services.put(owner_id, (fingerprint, service))
            return service
        service = TaskConversationService(recognition_service, java)
        self._services.put(owner_id, (fingerprint, service))
        return service


def get_task_conversation_service(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
) -> Any:
    """惰性创建任务会话服务，应用启动时不会调用模型。"""

    existing = getattr(request.app.state, "task_conversation_service", None)
    if existing is not None:
        return existing
    service = OwnerScopedTaskConversationServices(settings)
    request.app.state.task_conversation_service = service
    return service


async def _for_owner(service: Any, owner_id: str) -> TaskConversationService:
    factory = getattr(service, "for_owner", None)
    try:
        return await factory(owner_id) if factory is not None else service
    except (CredentialServiceUnavailableError, ModelConfigurationError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


async def _translate_errors[T](awaitable: Awaitable[T]) -> T:
    try:
        return await awaitable
    except TaskConversationNotFoundError as exc:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(exc),
        ) from exc
    except (
        TaskConversationBusyError,
        InvalidTaskConversationStateError,
        TaskVersionConflictError,
    ) as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=str(exc),
        ) from exc
    except TaskRecognitionOutputError as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="模型未返回合法的任务识别结果",
        ) from exc
    except (TaskExecutionUnavailableError, JavaBackendError) as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="任务操作依赖服务暂时不可用",
        ) from exc


@router.post(
    "",
    response_model=TaskConversationSnapshot,
    status_code=status.HTTP_201_CREATED,
)
async def create_task_conversation(
    body: CreateTaskConversationRequest,
    service: Annotated[
        TaskConversationService,
        Depends(get_task_conversation_service),
    ],
) -> TaskConversationSnapshot:
    scoped = await _for_owner(service, body.owner_id)
    return await _translate_errors(
        scoped.create_conversation(body.owner_id, body.target_date)
    )


@router.post("/{conversation_id}/messages", response_model=TaskConversationSnapshot)
async def send_task_message(
    conversation_id: str,
    body: SendMessageRequest,
    service: Annotated[
        TaskConversationService,
        Depends(get_task_conversation_service),
    ],
) -> TaskConversationSnapshot:
    scoped = await _for_owner(service, body.owner_id)
    return await _translate_errors(
        scoped.send_message(conversation_id, body.message, body.owner_id)
    )


@router.get("/{conversation_id}", response_model=TaskConversationSnapshot)
async def get_task_conversation(
    conversation_id: str,
    owner_id: Annotated[str, Query(alias="ownerId", min_length=1)],
    service: Annotated[
        TaskConversationService,
        Depends(get_task_conversation_service),
    ],
) -> TaskConversationSnapshot:
    scoped = await _for_owner(service, owner_id)
    return await _translate_errors(scoped.get_conversation(conversation_id, owner_id))


@router.post("/{conversation_id}/confirm", response_model=TaskConversationSnapshot)
async def confirm_task_conversation(
    conversation_id: str,
    body: OwnerConversationRequest,
    service: Annotated[
        TaskConversationService,
        Depends(get_task_conversation_service),
    ],
) -> TaskConversationSnapshot:
    scoped = await _for_owner(service, body.owner_id)
    return await _translate_errors(scoped.confirm(conversation_id, body.owner_id))
