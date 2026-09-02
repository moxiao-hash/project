import asyncio
import json
from datetime import date

import httpx
import pytest
from pydantic import SecretStr, ValidationError

from app.clients.java_backend import JavaBackendClient, JavaBackendError
from app.core.settings import Settings
from app.schemas.agent import (
    CreateAgentExecutionRequest,
    UpdateAgentExecutionRequest,
)
from app.schemas.learning import (
    CreateConfirmedLearningPlanRequest,
    CreateConfirmedTaskRequest,
    CreatePlanDraftRequest,
)


def build_settings() -> Settings:
    return Settings(
        java_backend_base_url="http://java-backend:8080",
        internal_service_token=SecretStr("shared-internal-token"),
    )


def test_internal_requests_ignore_system_proxy_settings(monkeypatch) -> None:
    captured_options: dict[str, object] = {}
    original_async_client = httpx.AsyncClient

    def create_client(*args, **kwargs):
        captured_options.update(kwargs)
        return original_async_client(*args, **kwargs)

    monkeypatch.setattr(
        "app.clients.java_backend.httpx.AsyncClient",
        create_client,
    )

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"goals": [], "plans": [], "tasks": [], "materials": [], "mastery": []},
        )

    async def call_client():
        client = JavaBackendClient(
            build_settings(),
            transport=httpx.MockTransport(handler),
        )
        return await client.get_learning_context("user-123")

    asyncio.run(call_client())

    assert captured_options.get("trust_env") is False


def test_get_learning_context_uses_internal_contract() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "GET"
        assert request.url.path == "/internal/users/user-123/learning-context"
        assert request.headers["X-Internal-Service-Token"] == "shared-internal-token"
        return httpx.Response(
            200,
            json={"goals": [], "plans": [], "tasks": [], "materials": [], "mastery": []},
        )

    async def call_client():
        client = JavaBackendClient(
            build_settings(),
            transport=httpx.MockTransport(handler),
        )
        return await client.get_learning_context("user-123")

    context = asyncio.run(call_client())

    assert context.goals == []
    assert context.materials == []


def test_get_learning_tasks_uses_date_and_internal_contract() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "GET"
        assert request.url.path == "/internal/users/user-123/learning-tasks"
        assert request.url.params["date"] == "2026-07-26"
        assert request.headers["X-Internal-Service-Token"] == "shared-internal-token"
        return httpx.Response(
            200,
            json=[
                {
                    "id": "task-1",
                    "planId": "plan-789",
                    "title": "学习 Spring MVC",
                    "scheduledDate": "2026-07-26",
                    "estimatedMinutes": 60,
                    "status": "TODO",
                    "version": 1,
                    "completedAt": None,
                }
            ],
        )

    async def call_client():
        client = JavaBackendClient(
            build_settings(),
            transport=httpx.MockTransport(handler),
        )
        return await client.get_learning_tasks(
            "user-123",
            target_date=date(2026, 7, 26),
        )

    tasks = asyncio.run(call_client())

    assert tasks[0].id == "task-1"
    assert tasks[0].scheduled_date == date(2026, 7, 26)
    assert tasks[0].status == "TODO"


def test_get_adaptation_context_uses_balanced_window_contract() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "GET"
        assert request.url.path == "/internal/users/user-123/adaptation-context"
        assert dict(request.url.params) == {
            "analysisDate": "2026-07-27",
            "windowDays": "14",
        }
        return httpx.Response(
            200,
            json={
                "ownerId": "user-123",
                "analysisDate": "2026-07-27",
                "windowDays": 14,
                "dailyStudyLimitMinutes": 120,
                "plan": {
                    "id": "plan-1",
                    "goalId": "goal-1",
                    "title": "学习计划",
                    "startDate": "2026-07-20",
                    "endDate": "2026-08-20",
                    "status": "CONFIRMED",
                    "version": 2,
                },
                "tasks": [],
                "signals": [
                    {
                        "type": "OVERDUE_TASKS",
                        "count": 2,
                        "deviationRatio": None,
                    }
                ],
            },
        )

    async def call_client():
        client = JavaBackendClient(
            build_settings(),
            transport=httpx.MockTransport(handler),
        )
        return await client.get_adaptation_context(
            "user-123",
            analysis_date=date(2026, 7, 27),
            window_days=14,
        )

    context = asyncio.run(call_client())

    assert context.plan.id == "plan-1"
    assert context.signals[0].type == "OVERDUE_TASKS"
    assert context.signals[0].count == 2


def test_change_learning_task_status_sends_camel_case_idempotent_request() -> None:
    from app.schemas.learning import (
        ChangeLearningTaskStatusRequest,
        LearningTaskStatus,
    )

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "PATCH"
        assert request.url.path == "/internal/learning-tasks/task-1/status"
        assert request.headers["X-Internal-Service-Token"] == "shared-internal-token"
        assert json.loads(request.content) == {
            "ownerId": "user-123",
            "idempotencyKey": "conversation-1:task-1:complete",
            "expectedVersion": 1,
            "status": "COMPLETED",
            "reason": "用户明确确认完成",
        }
        return httpx.Response(
            200,
            json={
                "id": "task-1",
                "planId": "plan-789",
                "title": "学习 Spring MVC",
                "scheduledDate": "2026-07-26",
                "estimatedMinutes": 60,
                "status": "COMPLETED",
                "version": 2,
                "completedAt": "2026-07-26T10:00:00Z",
            },
        )

    async def call_client():
        client = JavaBackendClient(
            build_settings(),
            transport=httpx.MockTransport(handler),
        )
        return await client.change_learning_task_status(
            "task-1",
            ChangeLearningTaskStatusRequest(
                owner_id="user-123",
                idempotency_key="conversation-1:task-1:complete",
                expected_version=1,
                status=LearningTaskStatus.COMPLETED,
                reason="用户明确确认完成",
            ),
        )

    task = asyncio.run(call_client())

    assert task.status == LearningTaskStatus.COMPLETED
    assert task.version == 2
    assert task.completed_at is not None


def test_change_learning_task_status_serializes_deferred_date() -> None:
    from app.schemas.learning import (
        ChangeLearningTaskStatusRequest,
        LearningTaskStatus,
    )

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["status"] == "DEFERRED"
        assert body["scheduledDate"] == "2026-07-28"
        assert body["reason"] == "当天时间不足"
        return httpx.Response(
            200,
            json={
                "id": "task-1",
                "planId": "plan-789",
                "title": "学习 Spring MVC",
                "scheduledDate": "2026-07-28",
                "estimatedMinutes": 60,
                "status": "DEFERRED",
                "version": 2,
                "completedAt": None,
            },
        )

    async def call_client():
        client = JavaBackendClient(
            build_settings(),
            transport=httpx.MockTransport(handler),
        )
        return await client.change_learning_task_status(
            "task-1",
            ChangeLearningTaskStatusRequest(
                owner_id="user-123",
                idempotency_key="conversation-1:task-1:defer",
                expected_version=1,
                status=LearningTaskStatus.DEFERRED,
                scheduled_date=date(2026, 7, 28),
                reason="当天时间不足",
            ),
        )

    task = asyncio.run(call_client())

    assert task.status == LearningTaskStatus.DEFERRED
    assert task.scheduled_date == date(2026, 7, 28)


def test_change_task_status_request_rejects_non_positive_version() -> None:
    from app.schemas.learning import (
        ChangeLearningTaskStatusRequest,
        LearningTaskStatus,
    )

    with pytest.raises(ValidationError):
        ChangeLearningTaskStatusRequest(
            owner_id="user-123",
            idempotency_key="conversation-1:task-1:complete",
            expected_version=0,
            status=LearningTaskStatus.COMPLETED,
        )


def test_change_task_status_request_rejects_empty_idempotency_key() -> None:
    from app.schemas.learning import (
        ChangeLearningTaskStatusRequest,
        LearningTaskStatus,
    )

    with pytest.raises(ValidationError):
        ChangeLearningTaskStatusRequest(
            owner_id="user-123",
            idempotency_key="",
            expected_version=1,
            status=LearningTaskStatus.COMPLETED,
        )


@pytest.mark.parametrize(
    ("owner_id", "idempotency_key", "reason"),
    [
        ("", "valid-key", None),
        ("user-123", "x" * 181, None),
        ("user-123", "valid-key", "x" * 256),
    ],
)
def test_change_task_status_request_enforces_java_text_limits(
    owner_id: str,
    idempotency_key: str,
    reason: str | None,
) -> None:
    from app.schemas.learning import (
        ChangeLearningTaskStatusRequest,
        LearningTaskStatus,
    )

    with pytest.raises(ValidationError):
        ChangeLearningTaskStatusRequest(
            owner_id=owner_id,
            idempotency_key=idempotency_key,
            expected_version=1,
            status=LearningTaskStatus.COMPLETED,
            reason=reason,
        )


def test_create_plan_draft_sends_camel_case_json() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        assert request.url.path == "/internal/learning-plans"
        assert json.loads(request.content) == {
            "ownerId": "user-123",
            "goalId": "goal-456",
            "title": "Java 学习计划",
            "startDate": "2026-07-26",
            "endDate": "2026-12-31",
        }
        return httpx.Response(
            201,
            json={
                "id": "plan-789",
                "goalId": "goal-456",
                "title": "Java 学习计划",
                "startDate": "2026-07-26",
                "endDate": "2026-12-31",
                "status": "DRAFT",
                "version": 1,
            },
        )

    request = CreatePlanDraftRequest(
        owner_id="user-123",
        goal_id="goal-456",
        title="Java 学习计划",
        start_date=date(2026, 7, 26),
        end_date=date(2026, 12, 31),
    )

    async def call_client():
        client = JavaBackendClient(
            build_settings(),
            transport=httpx.MockTransport(handler),
        )
        return await client.create_plan_draft(request)

    plan = asyncio.run(call_client())

    assert plan.id == "plan-789"
    assert plan.status == "DRAFT"


def test_non_success_response_becomes_domain_error() -> None:
    transport = httpx.MockTransport(lambda _request: httpx.Response(503))

    async def call_client() -> None:
        client = JavaBackendClient(build_settings(), transport=transport)
        await client.get_learning_context("user-123")

    with pytest.raises(JavaBackendError, match="503"):
        asyncio.run(call_client())


def test_java_backend_error_preserves_conflict_status_and_detail() -> None:
    transport = httpx.MockTransport(
        lambda _request: httpx.Response(
            409,
            json={"detail": "任务版本已变化，请刷新任务后重新确认操作"},
        )
    )

    async def call_client() -> None:
        client = JavaBackendClient(build_settings(), transport=transport)
        await client.get_learning_tasks(
            "user-123",
            target_date=date(2026, 7, 26),
        )

    with pytest.raises(JavaBackendError) as captured:
        asyncio.run(call_client())

    assert captured.value.status_code == 409
    assert captured.value.detail == "任务版本已变化，请刷新任务后重新确认操作"
    assert captured.value.path == "/internal/users/user-123/learning-tasks"


def test_java_backend_error_reads_spring_problem_message() -> None:
    transport = httpx.MockTransport(
        lambda _request: httpx.Response(
            400,
            json={
                "message": "请求参数校验失败",
                "fieldErrors": {
                    "questions[0].options[0]": "size must be between 0 and 500"
                },
            },
        )
    )

    async def call_client() -> None:
        client = JavaBackendClient(build_settings(), transport=transport)
        await client.create_quiz({"ownerId": "user-123"})

    with pytest.raises(JavaBackendError) as captured:
        asyncio.run(call_client())

    assert captured.value.detail == (
        "请求参数校验失败（questions[0].options[0]: size must be between 0 and 500）"
    )


def test_java_backend_connection_error_has_no_http_status() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    async def call_client() -> None:
        client = JavaBackendClient(
            build_settings(),
            transport=httpx.MockTransport(handler),
        )
        await client.get_learning_tasks(
            "user-123",
            target_date=date(2026, 7, 26),
        )

    with pytest.raises(JavaBackendError) as captured:
        asyncio.run(call_client())

    assert captured.value.status_code is None
    assert captured.value.detail is None
    assert captured.value.path == "/internal/users/user-123/learning-tasks"


def test_agent_execution_lifecycle_uses_internal_contracts() -> None:
    observed_requests: list[tuple[str, str, dict[str, object]]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content) if request.content else {}
        observed_requests.append((request.method, request.url.path, body))
        response = {
            "id": "execution-1",
            "idempotencyKey": "conversation-1",
            "executionType": "PLAN_GENERATION",
            "triggerType": "USER_REQUEST",
            "riskLevel": "HIGH",
            "requiredScope": "PLAN_GENERATION",
            "status": "WAITING_CONFIRMATION",
            "summary": "生成学习计划",
            "resultSummary": None,
            "errorMessage": None,
            "modelName": None,
            "promptTokens": None,
            "completionTokens": None,
            "latencyMs": None,
            "estimatedCost": None,
            "createdAt": "2026-07-25T06:00:00Z",
        }
        if request.method == "POST" and request.url.path.endswith("/confirm"):
            response["status"] = "PENDING"
        if request.method == "PATCH":
            response["status"] = body["status"]
            response["resultSummary"] = body.get("resultSummary")
        return httpx.Response(201 if len(observed_requests) == 1 else 200, json=response)

    async def call_client() -> None:
        client = JavaBackendClient(build_settings(), transport=httpx.MockTransport(handler))
        execution = await client.create_agent_execution(
            CreateAgentExecutionRequest(
                owner_id="user-123",
                idempotency_key="conversation-1",
                summary="生成学习计划",
            )
        )
        confirmed = await client.confirm_agent_execution(
            execution.id,
            owner_id="user-123",
        )
        updated = await client.update_agent_execution(
            execution.id,
            UpdateAgentExecutionRequest(
                status="RUNNING",
                result_summary="正在保存用户确认的计划",
            ),
        )
        assert confirmed.status == "PENDING"
        assert updated.status == "RUNNING"

    asyncio.run(call_client())

    assert observed_requests == [
        (
            "POST",
            "/internal/agent-executions",
            {
                "ownerId": "user-123",
                "idempotencyKey": "conversation-1",
                "executionType": "PLAN_GENERATION",
                "triggerType": "USER_REQUEST",
                "riskLevel": "HIGH",
                "requiredScope": "PLAN_GENERATION",
                "summary": "生成学习计划",
            },
        ),
        (
            "POST",
            "/internal/agent-executions/execution-1/confirm",
            {"ownerId": "user-123"},
        ),
        (
            "PATCH",
            "/internal/agent-executions/execution-1",
            {
                "status": "RUNNING",
                "resultSummary": "正在保存用户确认的计划",
            },
        ),
    ]


def test_create_confirmed_plan_sends_plan_and_tasks_atomically() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        assert request.url.path == "/internal/confirmed-learning-plans"
        assert json.loads(request.content) == {
            "ownerId": "user-123",
            "goalId": "goal-456",
            "idempotencyKey": "conversation-1",
            "title": "Java 学习计划",
            "startDate": "2026-07-26",
            "endDate": "2026-07-27",
            "tasks": [
                {
                    "title": "学习 Spring MVC",
                    "scheduledDate": "2026-07-26",
                    "estimatedMinutes": 60,
                }
            ],
        }
        return httpx.Response(
            201,
            json={
                "plan": {
                    "id": "plan-789",
                    "goalId": "goal-456",
                    "title": "Java 学习计划",
                    "startDate": "2026-07-26",
                    "endDate": "2026-07-27",
                    "status": "CONFIRMED",
                    "version": 2,
                },
                "tasks": [
                    {
                        "id": "task-1",
                        "planId": "plan-789",
                        "title": "学习 Spring MVC",
                        "scheduledDate": "2026-07-26",
                        "estimatedMinutes": 60,
                        "status": "TODO",
                        "version": 1,
                        "completedAt": None,
                    }
                ],
            },
        )

    async def call_client():
        client = JavaBackendClient(build_settings(), transport=httpx.MockTransport(handler))
        return await client.create_confirmed_learning_plan(
            CreateConfirmedLearningPlanRequest(
                owner_id="user-123",
                goal_id="goal-456",
                idempotency_key="conversation-1",
                title="Java 学习计划",
                start_date=date(2026, 7, 26),
                end_date=date(2026, 7, 27),
                tasks=[
                    CreateConfirmedTaskRequest(
                        title="学习 Spring MVC",
                        scheduled_date=date(2026, 7, 26),
                        estimated_minutes=60,
                    )
                ],
            )
        )

    result = asyncio.run(call_client())

    assert result.plan.status == "CONFIRMED"
    assert result.tasks[0].plan_id == result.plan.id
