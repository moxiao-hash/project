import asyncio
import json
from datetime import date

import httpx
import pytest
from pydantic import SecretStr

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
                "errorMessage": None,
                "modelName": None,
                "promptTokens": None,
                "completionTokens": None,
                "latencyMs": None,
                "estimatedCost": None,
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
