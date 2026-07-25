"""对 Spring Boot 内部工具 API 的类型化客户端。"""

from datetime import date
from typing import Any

import httpx
from pydantic import TypeAdapter

from app.core.settings import Settings
from app.schemas.agent import (
    AgentExecution,
    CreateAgentExecutionRequest,
    UpdateAgentExecutionRequest,
)
from app.schemas.learning import (
    ChangeLearningTaskStatusRequest,
    ConfirmedLearningPlan,
    CreateConfirmedLearningPlanRequest,
    CreatePlanDraftRequest,
    LearningContext,
    LearningPlan,
    LearningTask,
)


class JavaBackendError(RuntimeError):
    """Java 后端不可达或拒绝了内部工具请求。

    status_code 为 ``None`` 表示请求没有收到 HTTP 响应；有状态码时，编排层可以
    区分参数错误、资源不存在、版本冲突和服务端异常。
    """

    def __init__(
        self,
        message: str,
        *,
        path: str,
        status_code: int | None = None,
        detail: str | None = None,
    ) -> None:
        super().__init__(message)
        self.path = path
        self.status_code = status_code
        self.detail = detail


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

    async def get_learning_tasks(
        self,
        owner_id: str,
        *,
        target_date: date,
    ) -> list[LearningTask]:
        """读取某个用户在指定日期的任务，供 Agent 进行确定性匹配。"""

        response = await self._request(
            "GET",
            f"/internal/users/{owner_id}/learning-tasks",
            params={"date": target_date.isoformat()},
        )
        return TypeAdapter(list[LearningTask]).validate_python(response.json())

    async def create_plan_draft(
        self,
        request: CreatePlanDraftRequest,
    ) -> LearningPlan:
        """请求 Java 保存计划草案；Python 不直接写 MySQL。"""

        response = await self._request(
            "POST",
            "/internal/learning-plans",
            json=request.model_dump(by_alias=True, mode="json", exclude_none=True),
        )
        return LearningPlan.model_validate(response.json())

    async def change_learning_task_status(
        self,
        task_id: str,
        request: ChangeLearningTaskStatusRequest,
    ) -> LearningTask:
        """调用 Java 幂等状态工具；幂等键由上层 Agent 编排提供。"""

        response = await self._request(
            "PATCH",
            f"/internal/learning-tasks/{task_id}/status",
            json=request.model_dump(by_alias=True, mode="json", exclude_none=True),
        )
        return LearningTask.model_validate(response.json())

    async def create_agent_execution(
        self,
        request: CreateAgentExecutionRequest,
    ) -> AgentExecution:
        """在真正执行高风险操作前，先创建可审计的执行记录。"""

        response = await self._request(
            "POST",
            "/internal/agent-executions",
            json=request.model_dump(by_alias=True, mode="json", exclude_none=True),
        )
        return AgentExecution.model_validate(response.json())

    async def confirm_agent_execution(
        self,
        execution_id: str,
        *,
        owner_id: str,
    ) -> AgentExecution:
        """把对话中的显式确认同步到 Java 治理状态机。"""

        response = await self._request(
            "POST",
            f"/internal/agent-executions/{execution_id}/confirm",
            json={"ownerId": owner_id},
        )
        return AgentExecution.model_validate(response.json())

    async def update_agent_execution(
        self,
        execution_id: str,
        request: UpdateAgentExecutionRequest,
    ) -> AgentExecution:
        """更新执行结果、失败原因和模型调用指标。"""

        response = await self._request(
            "PATCH",
            f"/internal/agent-executions/{execution_id}",
            json=request.model_dump(by_alias=True, mode="json", exclude_none=True),
        )
        return AgentExecution.model_validate(response.json())

    async def create_confirmed_learning_plan(
        self,
        request: CreateConfirmedLearningPlanRequest,
    ) -> ConfirmedLearningPlan:
        """原子保存用户确认后的计划与任务，避免只写入一半。"""

        response = await self._request(
            "POST",
            "/internal/confirmed-learning-plans",
            json=request.model_dump(by_alias=True, mode="json", exclude_none=True),
        )
        return ConfirmedLearningPlan.model_validate(response.json())

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
            detail: str | None = None
            try:
                payload = exc.response.json()
                if isinstance(payload, dict) and isinstance(payload.get("detail"), str):
                    detail = payload["detail"]
            except ValueError:
                detail = exc.response.text.strip() or None
            raise JavaBackendError(
                f"Java 内部接口返回 HTTP {exc.response.status_code}: {path}",
                path=path,
                status_code=exc.response.status_code,
                detail=detail,
            ) from exc
        except httpx.RequestError as exc:
            raise JavaBackendError(
                f"无法连接 Java 后端: {path}",
                path=path,
            ) from exc
