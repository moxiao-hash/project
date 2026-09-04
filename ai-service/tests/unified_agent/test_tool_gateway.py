import asyncio

import pytest

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
