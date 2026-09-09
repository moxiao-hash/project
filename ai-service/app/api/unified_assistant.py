"""Java Agent Facade 调用的统一 Supervisor 内部接口。"""

from collections.abc import AsyncIterator
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from fastapi.responses import StreamingResponse

from app.core.security import require_internal_token
from app.unified_agent.event_stream import SSE_HEARTBEAT_FRAME, encode_sse_event
from app.unified_agent.models import (
    AssistantConversationSnapshot,
    AssistantEvent,
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


def _cursor_from_header(last_event_id: str | None) -> int:
    """``Last-Event-ID`` 是断线续传的权威游标；非法值按从头重放处理。"""

    if last_event_id is None or not last_event_id.strip():
        return 0
    try:
        return max(0, int(last_event_id.strip()))
    except ValueError:
        return 0


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


@router.get("/{conversation_id}/events", response_model=list[AssistantEvent])
async def list_events(
    conversation_id: str,
    owner_id: Annotated[str, Query(alias="ownerId", min_length=1)],
    service: Annotated[UnifiedAgentSupervisor, Depends(get_unified_agent_service)],
    after_sequence: Annotated[int, Query(alias="afterSequence", ge=0)] = 0,
) -> list[AssistantEvent]:
    try:
        return await service.list_events(conversation_id, owner_id, after_sequence)
    except Exception as exc:
        raise _translate(exc) from exc


@router.get("/{conversation_id}/events/stream")
async def stream_events(
    conversation_id: str,
    owner_id: Annotated[str, Query(alias="ownerId", min_length=1)],
    service: Annotated[UnifiedAgentSupervisor, Depends(get_unified_agent_service)],
    after_sequence: Annotated[int, Query(alias="afterSequence", ge=0)] = 0,
    last_event_id: Annotated[str | None, Header(alias="Last-Event-ID")] = None,
) -> StreamingResponse:
    """Task 29：持续推送已持久化事件；``Last-Event-ID`` 优先于查询参数。

    先做一次鉴权/存在性检查，让 404、409 等错误以普通 HTTP 状态返回，
    而不是在已经开始的流里才发现会话不存在。
    """

    try:
        await service.get_conversation(conversation_id, owner_id)
    except Exception as exc:
        raise _translate(exc) from exc
    cursor = max(after_sequence, _cursor_from_header(last_event_id))

    async def frames() -> AsyncIterator[str]:
        async for event in service.stream_events(conversation_id, owner_id, cursor):
            yield SSE_HEARTBEAT_FRAME if event is None else encode_sse_event(event)

    return StreamingResponse(
        frames(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            # 反向代理不得缓冲，否则事件会攒到最后一起发出。
            "X-Accel-Buffering": "no",
        },
    )


@router.post(
    "/{conversation_id}/actions/{action_id}/confirm",
    response_model=AssistantConversationSnapshot,
)
async def confirm_action(
    conversation_id: str,
    action_id: str,
    body: CreateAssistantConversationRequest,
    service: Annotated[UnifiedAgentSupervisor, Depends(get_unified_agent_service)],
) -> AssistantConversationSnapshot:
    try:
        return await service.confirm_action(conversation_id, action_id, body.owner_id)
    except Exception as exc:
        raise _translate(exc) from exc


@router.post(
    "/{conversation_id}/actions/{action_id}/reject",
    response_model=AssistantConversationSnapshot,
)
async def reject_action(
    conversation_id: str,
    action_id: str,
    body: CreateAssistantConversationRequest,
    service: Annotated[UnifiedAgentSupervisor, Depends(get_unified_agent_service)],
) -> AssistantConversationSnapshot:
    try:
        return await service.reject_action(conversation_id, action_id, body.owner_id)
    except Exception as exc:
        raise _translate(exc) from exc


@router.post(
    "/{conversation_id}/turns/{turn_id}/cancel",
    response_model=AssistantConversationSnapshot,
)
async def cancel_turn(
    conversation_id: str,
    turn_id: str,
    body: CreateAssistantConversationRequest,
    service: Annotated[UnifiedAgentSupervisor, Depends(get_unified_agent_service)],
) -> AssistantConversationSnapshot:
    try:
        return await service.cancel_turn(conversation_id, turn_id, body.owner_id)
    except Exception as exc:
        raise _translate(exc) from exc
