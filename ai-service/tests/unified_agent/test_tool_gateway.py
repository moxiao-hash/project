import asyncio

import pytest
from prometheus_client import CollectorRegistry

from app.observability.agent_metrics import AgentRuntimeMetrics
from app.unified_agent.models import ToolDescriptor, ToolEffect, ToolRiskLevel
from app.unified_agent.tool_gateway import (
    DuplicateToolCallError,
    ToolBudget,
    ToolBudgetExceededError,
    UnifiedToolGateway,
)


class FakeJavaBackend:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, dict, str | None]] = []

    async def get_agent_tool_catalog(self):
        return [
            descriptor("learning.context.get", ToolEffect.READ),
            descriptor("navigation.resolve", ToolEffect.READ),
            descriptor("materials.web.search", ToolEffect.READ),
            descriptor("assessment.wrong_question_review.create", ToolEffect.WRITE),
        ]

    async def invoke_agent_tool(self, name, owner_id, arguments, idempotency_key=None):
        self.calls.append((name, owner_id, arguments, idempotency_key))
        return {"toolName": name, "data": {"ok": True}, "action": None}


def test_failed_calls_consume_budget_and_cannot_be_repeated() -> None:
    async def run():
        java = FakeJavaBackend()

        async def fail(*args):
            raise RuntimeError("unavailable")

        java.invoke_agent_tool = fail
        gateway = UnifiedToolGateway(java, "user-1", ToolBudget(max_calls=1))
        with pytest.raises(RuntimeError, match="unavailable"):
            await gateway.invoke("learning.context.get", {})
        assert gateway.usage.total_calls == 1
        with pytest.raises(DuplicateToolCallError):
            await gateway.invoke("learning.context.get", {})
        with pytest.raises(ToolBudgetExceededError):
            await gateway.invoke("navigation.resolve", {"routeKey": "TODAY"})

    asyncio.run(run())


def test_business_failure_is_not_reported_as_tool_success() -> None:
    async def run():
        java = FakeJavaBackend()
        async def business_failure(*args):
            return {"toolName": args[0], "action": {
                "actionId": "a", "executionId": "e", "toolName": args[0],
                "toolVersion": 1, "riskLevel": "LOW", "status": "FAILED",
                "summary": "failed", "arguments": {}, "expiresAt": "2099-01-01",
            }}
        java.invoke_agent_tool = business_failure
        registry = CollectorRegistry()
        gateway = UnifiedToolGateway(java, "user-1", ToolBudget(),
                                     metrics=AgentRuntimeMetrics(registry))
        await gateway.invoke("assessment.wrong_question_review.create", {},
                             idempotency_key="failure")
        assert registry.get_sample_value("studypilot_agent_tool_calls_total",
                                         {"category": "TEST", "status": "error"}) == 1
    asyncio.run(run())


def descriptor(name: str, effect: ToolEffect) -> ToolDescriptor:
    return ToolDescriptor(
        name=name,
        version=1,
        category="TEST",
        effect=effect,
        risk_level=ToolRiskLevel.NONE if effect == ToolEffect.READ else ToolRiskLevel.LOW,
        idempotency_required=effect == ToolEffect.WRITE,
        input_schema={"type": "object"},
        output_schema={"type": "object"},
    )


def test_gateway_injects_owner_and_tracks_write_budget() -> None:
    asyncio.run(_gateway_injects_owner_and_tracks_write_budget())


async def _gateway_injects_owner_and_tracks_write_budget() -> None:
    java = FakeJavaBackend()
    gateway = UnifiedToolGateway(java, "user-1", ToolBudget())

    await gateway.invoke("learning.context.get", {})
    await gateway.invoke(
        "assessment.wrong_question_review.create",
        {"chapterKey": None},
        idempotency_key="assistant-turn:1",
    )

    assert java.calls[0] == ("learning.context.get", "user-1", {}, None)
    assert java.calls[1][1] == "user-1"
    assert gateway.usage.total_calls == 2
    assert gateway.usage.write_calls == 1


def test_gateway_rejects_repeated_call_and_second_write() -> None:
    asyncio.run(_gateway_rejects_repeated_call_and_second_write())


async def _gateway_rejects_repeated_call_and_second_write() -> None:
    java = FakeJavaBackend()
    gateway = UnifiedToolGateway(java, "user-1", ToolBudget())
    await gateway.invoke("navigation.resolve", {"routeKey": "WRONG_QUESTIONS"})

    with pytest.raises(DuplicateToolCallError):
        await gateway.invoke("navigation.resolve", {"routeKey": "WRONG_QUESTIONS"})

    await gateway.invoke(
        "assessment.wrong_question_review.create",
        {},
        idempotency_key="assistant-turn:1",
    )
    with pytest.raises(ToolBudgetExceededError, match="写操作"):
        await gateway.invoke(
            "assessment.wrong_question_review.create",
            {"chapterKey": "other"},
            idempotency_key="assistant-turn:2",
        )


def test_gateway_limits_web_search_and_total_calls() -> None:
    asyncio.run(_gateway_limits_web_search_and_total_calls())


async def _gateway_limits_web_search_and_total_calls() -> None:
    java = FakeJavaBackend()
    gateway = UnifiedToolGateway(java, "user-1", ToolBudget(max_calls=2))
    await gateway.invoke("materials.web.search", {"query": "Redis"})

    with pytest.raises(ToolBudgetExceededError, match="联网搜索"):
        await gateway.invoke("materials.web.search", {"query": "Spring"})

    await gateway.invoke("learning.context.get", {})
    with pytest.raises(ToolBudgetExceededError, match="工具调用"):
        await gateway.invoke("navigation.resolve", {"routeKey": "DASHBOARD"})
