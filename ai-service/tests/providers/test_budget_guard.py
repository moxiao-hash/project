"""预算预占：放行必须带许可，拒绝与预算不可用一律失败关闭。"""

import pytest

from app.providers.budget import (
    BUDGET_UNAVAILABLE_REASON,
    BudgetPermit,
    ModelBudgetGuard,
)


class FakeJava:
    def __init__(self, payload=None, error=None) -> None:
        self.payload = payload
        self.error = error
        self.reserved: list[dict] = []
        self.released: list[str] = []

    async def reserve_assistant_usage(self, payload: dict):
        self.reserved.append(payload)
        if self.error is not None:
            raise self.error
        return self.payload

    async def release_assistant_usage_reservation(self, reservation_id: str):
        self.released.append(reservation_id)
        return {"reservationId": reservation_id, "released": True}


def _guard(payload=None, error=None) -> tuple[ModelBudgetGuard, FakeJava]:
    java = FakeJava(payload=payload, error=error)
    return ModelBudgetGuard(java), java


@pytest.mark.anyio
async def test_explicit_denial_returns_denied_permit_with_reason_and_output_cap():
    guard, java = _guard({
        "allowed": False,
        "reason": "DAILY_MODEL_CALLS_EXHAUSTED",
        "maxOutputTokensPerTurn": 2048,
        "timezone": "Asia/Shanghai",
    })
    permit = await guard.reserve(
        owner_id="owner-1", provider="deepseek", model_name="deepseek-flash",
        purpose="KNOWLEDGE_QA",
    )
    assert permit.allowed is False
    assert permit.reason == "DAILY_MODEL_CALLS_EXHAUSTED"
    assert permit.max_output_tokens_per_turn == 2048
    assert java.reserved[0]["ownerId"] == "owner-1"
    assert java.reserved[0]["usageId"]


@pytest.mark.anyio
async def test_allowed_permit_carries_reservation_output_cap_and_timezone():
    guard, _ = _guard({
        "reservationId": "reservation-1",
        "allowed": True,
        "reason": "WITHIN_BUDGET",
        "maxOutputTokensPerTurn": 4096,
        "timezone": "UTC",
    })
    permit = await guard.reserve(
        owner_id="owner-1", provider="deepseek", model_name="deepseek-flash",
        purpose="KNOWLEDGE_QA",
    )
    assert permit == BudgetPermit("reservation-1", True, "WITHIN_BUDGET", 4096, "UTC")


@pytest.mark.anyio
async def test_budget_endpoint_failure_fails_closed():
    guard, _ = _guard(error=RuntimeError("connection refused"))
    permit = await guard.reserve(
        owner_id="owner-1", provider="deepseek", model_name="deepseek-flash",
        purpose="KNOWLEDGE_QA",
    )
    assert permit.allowed is False
    assert permit.reservation_id is None
    assert permit.reason == BUDGET_UNAVAILABLE_REASON


@pytest.mark.parametrize(
    "payload",
    [
        None,
        [],
        "not-a-dict",
        {},
        {"allowed": "yes", "reason": "WITHIN_BUDGET", "reservationId": "r"},
        {"allowed": True, "reservationId": "r"},
        {"allowed": True, "reason": "WITHIN_BUDGET"},
        {"allowed": True, "reason": "WITHIN_BUDGET", "reservationId": ""},
    ],
)
@pytest.mark.anyio
async def test_malformed_budget_response_fails_closed(payload):
    guard, _ = _guard(payload=payload)
    permit = await guard.reserve(
        owner_id="owner-1", provider="deepseek", model_name="deepseek-flash",
        purpose="KNOWLEDGE_QA",
    )
    assert permit.allowed is False
    assert permit.reason == BUDGET_UNAVAILABLE_REASON


@pytest.mark.anyio
async def test_release_forwards_reservation_and_swallows_failures():
    guard, java = _guard()
    await guard.release("reservation-1")
    assert java.released == ["reservation-1"]
    # 没有许可时不发请求。
    await guard.release(None)
    assert java.released == ["reservation-1"]

    failing_java = FakeJava()

    async def broken(_reservation_id: str):
        raise RuntimeError("java down")

    failing_java.release_assistant_usage_reservation = broken
    # 释放失败必须被吞掉：TTL 会兜底，不影响调用结果。
    await ModelBudgetGuard(failing_java).release("reservation-2")
