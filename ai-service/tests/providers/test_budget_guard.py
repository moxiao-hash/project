"""模型预算前置校验：显式拒绝才拦截，判定不可达时放行且可观测。"""

import pytest

from app.providers.budget import ModelBudgetExceededError, ModelBudgetGuard


class FakeJava:
    def __init__(self, payload=None, error=None) -> None:
        self.payload = payload
        self.error = error

    async def get_assistant_budget(self, owner_id: str):
        if self.error is not None:
            raise self.error
        return self.payload


@pytest.mark.anyio
async def test_explicit_denial_raises_with_reason_and_output_cap():
    guard = ModelBudgetGuard(FakeJava({
        "allowed": False,
        "reason": "DAILY_MODEL_CALLS_EXHAUSTED",
        "maxOutputTokensPerTurn": 2048,
        "timezone": "Asia/Shanghai",
    }))
    with pytest.raises(ModelBudgetExceededError) as raised:
        await guard.require("owner-1")
    assert raised.value.reason == "DAILY_MODEL_CALLS_EXHAUSTED"
    assert raised.value.max_output_tokens_per_turn == 2048


@pytest.mark.anyio
async def test_allowed_decision_carries_turn_output_cap_and_timezone():
    guard = ModelBudgetGuard(FakeJava({
        "allowed": True,
        "reason": "WITHIN_BUDGET",
        "maxOutputTokensPerTurn": 4096,
        "timezone": "UTC",
    }))
    budget = await guard.check("owner-1")
    assert budget.allowed is True
    assert budget.max_output_tokens_per_turn == 4096
    assert budget.timezone == "UTC"


@pytest.mark.anyio
async def test_budget_endpoint_failure_fails_open_and_is_observable():
    guard = ModelBudgetGuard(FakeJava(error=RuntimeError("connection refused")))
    budget = await guard.check("owner-1")
    assert budget.allowed is True
    assert budget.reason == "BUDGET_CHECK_UNAVAILABLE"
