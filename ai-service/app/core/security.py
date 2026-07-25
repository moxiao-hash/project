"""Python 内部接口的服务间认证。"""

import secrets
from typing import Annotated

from fastapi import Depends, Header, HTTPException, status

from app.core.settings import Settings, get_settings


def require_internal_token(
    settings: Annotated[Settings, Depends(get_settings)],
    x_internal_service_token: Annotated[str | None, Header()] = None,
) -> None:
    """校验 Java 与 Python 共享的内部服务令牌。

    ``compare_digest`` 使用近似恒定时间比较，避免普通字符串比较可能产生的时序
    信息泄露。空配置永远不会被视为有效令牌。
    """

    expected = settings.internal_service_token.get_secret_value()
    valid = bool(expected) and x_internal_service_token is not None
    valid = valid and secrets.compare_digest(expected, x_internal_service_token)
    if not valid:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="内部服务令牌无效",
        )
