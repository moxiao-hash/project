import asyncio
import json
from datetime import date

import httpx
import pytest
from pydantic import SecretStr

from app.clients.java_backend import JavaBackendClient, JavaBackendError
from app.core.settings import Settings
from app.schemas.learning import CreatePlanDraftRequest


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
