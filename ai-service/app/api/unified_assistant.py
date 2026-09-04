"""Java Agent Facade 调用的统一 Supervisor 内部接口。"""

from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request

from app.core.security import require_internal_token
from app.unified_agent.models import (
    AssistantConversationSnapshot,
    CreateAssistantConversationRequest,
    SendAssistantMessageRequest,
)
from app.unified_agent.supervisor import (
    AssistantConversationBusyError,
    AssistantConversationNotFoundError,
    UnifiedAgentSupervisor,
)

router = APIRouter(
    prefix="/internal/assistant/conversations",
    tags=["internal-unified-assistant"],
    dependencies=[Depends(require_internal_token)],
)


def get_unified_agent_service(request: Request) -> Any:
    service = getattr(request.app.state, "unified_agent_service", None)
    if service is None:
        raise HTTPException(status_code=503, detail="统一 Agent 服务尚未完成启动")
    return service


def _translate(exc: Exception) -> HTTPException:
    if isinstance(exc, AssistantConversationNotFoundError):
        return HTTPException(status_code=404, detail=str(exc))
    if isinstance(exc, AssistantConversationBusyError):
        return HTTPException(status_code=409, detail=str(exc))
    return HTTPException(status_code=503, detail="统一 Agent 暂时不可用")


@router.post("", response_model=AssistantConversationSnapshot, status_code=201)
async def create_conversation(
    body: CreateAssistantConversationRequest,
    service: Annotated[UnifiedAgentSupervisor, Depends(get_unified_agent_service)],
) -> AssistantConversationSnapshot:
    return await service.create_conversation(body.owner_id)


@router.get("/{conversation_id}", response_model=AssistantConversationSnapshot)
async def get_conversation(
    conversation_id: str,
    owner_id: Annotated[str, Query(alias="ownerId", min_length=1)],
    service: Annotated[UnifiedAgentSupervisor, Depends(get_unified_agent_service)],
) -> AssistantConversationSnapshot:
    try:
        return await service.get_conversation(conversation_id, owner_id)
    except Exception as exc:
        raise _translate(exc) from exc


@router.post("/{conversation_id}/messages", response_model=AssistantConversationSnapshot)
async def send_message(
    conversation_id: str,
    body: SendAssistantMessageRequest,
    service: Annotated[UnifiedAgentSupervisor, Depends(get_unified_agent_service)],
) -> AssistantConversationSnapshot:
    try:
        return await service.send_message(
            conversation_id,
            body.message,
            body.idempotency_key,
            body.owner_id,
            body.client_context,
        )
    except Exception as exc:
        raise _translate(exc) from exc
