"""对 Spring Boot 内部工具 API 的类型化客户端。"""

from typing import Any

import httpx

from app.core.settings import Settings
from app.schemas.learning import (
    CreatePlanDraftRequest,
    LearningContext,
    LearningPlan,
)


class JavaBackendError(RuntimeError):
    """Java 后端不可达或拒绝了内部工具请求。"""


class JavaBackendClient:
    """封装 Agent 被允许调用的 Java 内部接口。

    将内部令牌和错误转换集中在这里，Agent 节点只处理学习领域对象，不需要了解
    HTTP 请求头、超时或 Java 的 URL 结构。
    """

    def __init__(
        self,
        settings: Settings,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout_seconds: float = 10.0,
    ) -> None:
        self._base_url = settings.java_backend_base_url.rstrip("/")
        self._internal_token = settings.internal_service_token.get_secret_value()
        self._transport = transport
        self._timeout = timeout_seconds

    async def get_learning_context(self, owner_id: str) -> LearningContext:
        """读取某个用户生成计划所需的聚合上下文。"""

        response = await self._request(
            "GET",
            f"/internal/users/{owner_id}/learning-context",
        )
        return LearningContext.model_validate(response.json())

    async def create_plan_draft(
        self,
        request: CreatePlanDraftRequest,
    ) -> LearningPlan:
        """请求 Java 保存计划草案；Python 不直接写 MySQL。"""

        response = await self._request(
            "POST",
            "/internal/learning-plans",
            json=request.model_dump(by_alias=True, mode="json"),
        )
        return LearningPlan.model_validate(response.json())

    async def _request(
        self,
        method: str,
        path: str,
        **kwargs: Any,
    ) -> httpx.Response:
        headers = {"X-Internal-Service-Token": self._internal_token}
        try:
            async with httpx.AsyncClient(
                base_url=self._base_url,
                headers=headers,
                timeout=self._timeout,
                transport=self._transport,
            ) as client:
                response = await client.request(method, path, **kwargs)
                response.raise_for_status()
                return response
        except httpx.HTTPStatusError as exc:
            raise JavaBackendError(
                f"Java 内部接口返回 HTTP {exc.response.status_code}: {path}"
            ) from exc
        except httpx.RequestError as exc:
            raise JavaBackendError(f"无法连接 Java 后端: {path}") from exc

