"""学习计划 Agent 的内部会话 API。"""

from collections.abc import Awaitable
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.agent.models import (
    ConversationSnapshot,
    CreateConversationRequest,
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
from app.providers.model_factory import ModelConfigurationError, create_chat_model

router = APIRouter(
    prefix="/internal/agent/conversations",
    tags=["internal-agent-conversations"],
    dependencies=[Depends(require_internal_token)],
)


def get_conversation_service(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
) -> ConversationService:
    """惰性创建应用级单例；创建客户端本身不会调用模型或消耗 Token。"""

    existing = getattr(request.app.state, "conversation_service", None)
    if existing is not None:
        return existing
    try:
        model = create_chat_model(settings)
    except ModelConfigurationError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=str(exc),
        ) from exc
    service = ConversationService(
        DeepSeekPlanner(model),
        JavaBackendClient(settings),
    )
    request.app.state.conversation_service = service
    return service


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
    return await _translate_errors(service.create_conversation(body.owner_id, body.goal_id))


@router.post("/{conversation_id}/messages", response_model=ConversationSnapshot)
async def send_message(
    conversation_id: str,
    body: SendMessageRequest,
    service: Annotated[ConversationService, Depends(get_conversation_service)],
) -> ConversationSnapshot:
    return await _translate_errors(service.send_message(conversation_id, body.message))


@router.get("/{conversation_id}", response_model=ConversationSnapshot)
async def get_conversation(
    conversation_id: str,
    service: Annotated[ConversationService, Depends(get_conversation_service)],
) -> ConversationSnapshot:
    return await _translate_errors(service.get_conversation(conversation_id))


@router.post("/{conversation_id}/confirm", response_model=ConversationSnapshot)
async def confirm_conversation(
    conversation_id: str,
    service: Annotated[ConversationService, Depends(get_conversation_service)],
) -> ConversationSnapshot:
    return await _translate_errors(service.confirm(conversation_id))
