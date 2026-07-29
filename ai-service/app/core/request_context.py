"""HTTP 请求关联标识。

ContextVar 可在 asyncio 任务之间正确隔离；请求结束必须用返回的 token 恢复，
避免后台任务或复用执行上下文继承错误的标识。
"""

from contextvars import ContextVar, Token
from re import fullmatch
from uuid import uuid4

REQUEST_ID_HEADER = "X-Request-ID"
_request_id: ContextVar[str | None] = ContextVar("request_id", default=None)


def _safe_request_id(candidate: str | None) -> str:
    if candidate and fullmatch(r"[A-Za-z0-9._-]{1,64}", candidate):
        return candidate
    return str(uuid4())


def bind_request_id(candidate: str | None) -> Token[str | None]:
    """绑定安全的请求标识，并返回用于 finally 恢复上下文的 token。"""

    return _request_id.set(_safe_request_id(candidate))


def reset_request_id(token: Token[str | None]) -> None:
    _request_id.reset(token)


def current_request_id() -> str | None:
    return _request_id.get()


def outbound_request_headers() -> dict[str, str]:
    request_id = current_request_id()
    return {REQUEST_ID_HEADER: request_id} if request_id else {}
