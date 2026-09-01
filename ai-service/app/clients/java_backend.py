"""对 Spring Boot 内部工具 API 的类型化客户端。"""

from datetime import date
from typing import Any

import httpx
from pydantic import SecretStr, TypeAdapter

from app.core.request_context import outbound_request_headers
from app.core.settings import Settings
from app.material.processing import ProcessingJob
from app.schemas.agent import (
    AgentExecution,
    CreateAgentExecutionRequest,
    CreatePlanAdjustmentAgentExecutionRequest,
    CreateTaskAgentExecutionRequest,
    UpdateAgentExecutionRequest,
)
from app.schemas.learning import (
    AdaptationContext,
    ChangeLearningTaskStatusRequest,
    ConfirmedLearningPlan,
    CreateConfirmedLearningPlanRequest,
    CreatePlanAdjustmentRequest,
    CreatePlanDraftRequest,
    ExecutePlanAdjustmentRequest,
    LearningContext,
    LearningPlan,
    LearningTask,
    NightlyAdjustmentCandidate,
    PlanAdjustment,
)
from app.search.models import WebSearchOutcome


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

    async def get_ai_credential(self, owner_id: str, provider: str) -> SecretStr:
        """读取只用于当前运行时的用户凭据，不记录或持久化明文。"""

        path = f"/internal/ai-credentials/{provider.lower()}"
        response = await self._request(
            "GET",
            path,
            params={"ownerId": owner_id},
        )
        payload = response.json()
        api_key = payload.get("apiKey") if isinstance(payload, dict) else None
        if not isinstance(api_key, str) or not api_key:
            raise JavaBackendError(
                "Java 返回了无效的运行时凭据",
                path=path,
                status_code=502,
            )
        return SecretStr(api_key)

    async def get_lesson_context(
        self,
        owner_id: str,
        lesson_id: str,
    ) -> dict[str, Any]:
        """读取当前课时和用户进度；检查题答案已由 Java 在公共 DTO 中裁剪。"""

        response = await self._request(
            "GET",
            f"/internal/teaching/lessons/{lesson_id}/context",
            params={"ownerId": owner_id},
        )
        return response.json()

    async def create_quiz(self, payload: dict[str, Any]) -> dict[str, Any]:
        """保存 Python 已完成结构校验的测验；Java 仍会执行领域校验。"""

        response = await self._request("POST", "/internal/quizzes", json=payload)
        return response.json()

    async def claim_roadmap_quiz_job(self, worker_id: str) -> dict[str, Any] | None:
        try:
            response = await self._request(
                "POST",
                "/internal/roadmap-quiz-generation-jobs/claim",
                json={"workerId": worker_id, "leaseSeconds": 120},
            )
        except JavaBackendError as exc:
            if exc.status_code == 404:
                return None
            raise
        return response.json()

    async def get_roadmap_quiz_context(
        self, job_id: str, worker_id: str, lease_token: str
    ) -> dict[str, Any]:
        response = await self._request(
            "GET",
            f"/internal/roadmap-quiz-generation-jobs/{job_id}/context",
            params={"workerId": worker_id, "leaseToken": lease_token},
        )
        return response.json()

    async def heartbeat_roadmap_quiz_job(
        self, job_id: str, worker_id: str, lease_token: str
    ) -> None:
        await self._request(
            "POST",
            f"/internal/roadmap-quiz-generation-jobs/{job_id}/heartbeat",
            json={"workerId": worker_id, "leaseToken": lease_token, "leaseSeconds": 120},
        )

    async def complete_roadmap_quiz_job(
        self, job_id: str, worker_id: str, lease_token: str, quiz_payload: dict[str, Any]
    ) -> dict[str, Any]:
        response = await self._request(
            "POST",
            f"/internal/roadmap-quiz-generation-jobs/{job_id}/complete",
            json={"workerId": worker_id, "leaseToken": lease_token, "quiz": quiz_payload},
        )
        return response.json()

    async def fail_roadmap_quiz_job(
        self, job_id: str, worker_id: str, lease_token: str, error: str
    ) -> None:
        await self._request(
            "POST",
            f"/internal/roadmap-quiz-generation-jobs/{job_id}/fail",
            json={"workerId": worker_id, "leaseToken": lease_token, "error": error},
        )

    async def claim_coding_evaluation_job(
        self,
        worker_id: str,
    ) -> dict[str, Any] | None:
        """领取一个代码文本评估任务；404 表示当前队列为空。"""

        try:
            response = await self._request(
                "POST",
                "/internal/coding-evaluation-jobs/claim",
                json={"workerId": worker_id, "leaseSeconds": 120},
            )
        except JavaBackendError as exc:
            if exc.status_code == 404:
                return None
            raise
        return response.json()

    async def complete_coding_evaluation_job(
        self,
        job_id: str,
        payload: dict[str, Any],
    ) -> None:
        await self._request(
            "POST",
            f"/internal/coding-evaluation-jobs/{job_id}/complete",
            json=payload,
        )

    async def heartbeat_coding_evaluation_job(
        self,
        job_id: str,
        worker_id: str,
    ) -> None:
        await self._request(
            "POST",
            f"/internal/coding-evaluation-jobs/{job_id}/heartbeat",
            json={"workerId": worker_id, "leaseSeconds": 120},
        )

    async def fail_coding_evaluation_job(
        self,
        job_id: str,
        payload: dict[str, Any],
    ) -> None:
        await self._request(
            "POST",
            f"/internal/coding-evaluation-jobs/{job_id}/fail",
            json=payload,
        )

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

    async def get_adaptation_context(
        self,
        owner_id: str,
        *,
        analysis_date: date,
        window_days: int = 14,
    ) -> AdaptationContext:
        """读取 Java 根据真实学习记录计算的自适应上下文。"""

        response = await self._request(
            "GET",
            f"/internal/users/{owner_id}/adaptation-context",
            params={
                "analysisDate": analysis_date.isoformat(),
                "windowDays": window_days,
            },
        )
        return AdaptationContext.model_validate(response.json())

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
        request: (
            CreateAgentExecutionRequest
            | CreateTaskAgentExecutionRequest
            | CreatePlanAdjustmentAgentExecutionRequest
        ),
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

    async def create_plan_adjustment(
        self,
        request: CreatePlanAdjustmentRequest,
    ) -> PlanAdjustment:
        response = await self._request(
            "POST",
            "/internal/plan-adjustments",
            json=request.model_dump(by_alias=True, mode="json", exclude_none=True),
        )
        return PlanAdjustment.model_validate(response.json())

    async def get_plan_adjustment(self, adjustment_id: str) -> PlanAdjustment:
        response = await self._request(
            "GET",
            f"/internal/plan-adjustments/{adjustment_id}",
        )
        return PlanAdjustment.model_validate(response.json())

    async def find_plan_adjustment(
        self,
        owner_id: str,
        idempotency_key: str,
    ) -> PlanAdjustment | None:
        try:
            response = await self._request(
                "GET",
                "/internal/plan-adjustments/by-key",
                params={
                    "ownerId": owner_id,
                    "idempotencyKey": idempotency_key,
                },
            )
        except JavaBackendError as exc:
            if exc.status_code == 404:
                return None
            raise
        return PlanAdjustment.model_validate(response.json())

    async def execute_plan_adjustment(
        self,
        adjustment_id: str,
        request: ExecutePlanAdjustmentRequest,
    ) -> PlanAdjustment:
        response = await self._request(
            "POST",
            f"/internal/plan-adjustments/{adjustment_id}/execute",
            json=request.model_dump(by_alias=True, mode="json"),
        )
        return PlanAdjustment.model_validate(response.json())

    async def create_notification(
        self,
        owner_id: str,
        notification_type: str,
        title: str,
        content: str,
    ) -> None:
        await self._request(
            "POST",
            "/internal/notifications",
            json={
                "ownerId": owner_id,
                "type": notification_type,
                "title": title,
                "content": content,
            },
        )

    async def get_nightly_adjustment_candidates(
        self,
        *,
        at: Any,
    ) -> list[NightlyAdjustmentCandidate]:
        response = await self._request(
            "GET",
            "/internal/plan-adjustments/nightly-candidates",
            params={"at": at.isoformat()},
        )
        return TypeAdapter(list[NightlyAdjustmentCandidate]).validate_python(
            response.json()
        )

    async def claim_material_job(
        self,
        worker_id: str,
        lease_seconds: int,
    ) -> ProcessingJob | None:
        try:
            response = await self._request(
                "POST",
                "/internal/material-processing-jobs/claim",
                json={"workerId": worker_id, "leaseSeconds": lease_seconds},
            )
        except JavaBackendError as exc:
            if exc.status_code == 404:
                return None
            raise
        body = response.json()
        return ProcessingJob(
            job_id=body["jobId"],
            material_id=body["materialId"],
            owner_id=body["ownerId"],
            title=body["title"],
            material_type=body["materialType"],
            category=body["category"],
            privacy_level=body["privacyLevel"],
            source_url=body.get("sourceUrl"),
        )

    async def download_material_content(self, material_id: str) -> bytes:
        response = await self._request(
            "GET",
            f"/internal/materials/{material_id}/content",
        )
        return response.content

    async def complete_material_job(self, job_id: str, payload: dict) -> None:
        await self._request(
            "POST",
            f"/internal/material-processing-jobs/{job_id}/complete",
            json=payload,
        )

    async def fail_material_job(
        self,
        job_id: str,
        worker_id: str,
        error: str,
    ) -> None:
        await self._request(
            "POST",
            f"/internal/material-processing-jobs/{job_id}/fail",
            json={"workerId": worker_id, "error": error},
        )

    async def record_web_search(
        self,
        owner_id: str,
        outcome: WebSearchOutcome,
    ) -> WebSearchOutcome:
        response = await self._request(
            "POST",
            "/internal/web-searches",
            json={
                "ownerId": owner_id,
                "query": outcome.query,
                "providerRequestId": outcome.provider_request_id,
                "results": [
                    {
                        "title": result.title,
                        "url": result.url,
                        "snippet": result.snippet,
                        "score": result.score,
                    }
                    for result in outcome.results
                ],
            },
        )
        body = response.json()
        return outcome.with_persisted_ids(
            body["id"],
            tuple(result["id"] for result in body["results"]),
        )

    async def _request(
        self,
        method: str,
        path: str,
        **kwargs: Any,
    ) -> httpx.Response:
        headers = {
            "X-Internal-Service-Token": self._internal_token,
            **outbound_request_headers(),
        }
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
                if isinstance(payload, dict):
                    candidate = payload.get("detail") or payload.get("message")
                    if isinstance(candidate, str):
                        detail = candidate
                    field_errors = payload.get("fieldErrors")
                    if isinstance(field_errors, dict) and field_errors:
                        field, error = next(iter(field_errors.items()))
                        detail = f"{detail or '字段校验失败'}（{field}: {error}）"
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
