"""模型调用前的用户预算校验。

预算判定由 Java 统一持有（每日调用次数、每日估算费用、单轮输出上限），AI 服务
只在每次真实模型调用前查询一次。判定接口不可达时按"放行并记录"处理：预算服务
与模型凭据共用同一个 Java 后端，凭据本身已不可用时模型也无法创建，因此这里不会
成为额外故障点；一旦 Java 明确返回 ``allowed=false`` 则严格拦截。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any

logger = logging.getLogger(__name__)


class ModelBudgetExceededError(RuntimeError):
    """预算拒绝新的模型调用；调用方应降级为纯 Java 查询/导航或失败可恢复的任务。"""

    def __init__(self, reason: str, *, max_output_tokens_per_turn: int | None = None) -> None:
        super().__init__(reason)
        self.reason = reason
        self.max_output_tokens_per_turn = max_output_tokens_per_turn


@dataclass(frozen=True)
class TurnBudget:
    allowed: bool
    reason: str
    max_output_tokens_per_turn: int | None = None
    timezone: str = "Asia/Shanghai"


class ModelBudgetGuard:
    """查询 Java 预算判定；失败不抛出，显式拒绝才拦截。"""

    def __init__(self, java: Any) -> None:
        self._java = java

    async def check(self, owner_id: str) -> TurnBudget:
        try:
            payload = await self._java.get_assistant_budget(owner_id)
        except Exception as exc:  # noqa: BLE001 - 预算不可达时放行并暴露日志
            logger.warning(
                "assistant.budget.unavailable ownerId=%s error=%s",
                owner_id,
                type(exc).__name__,
            )
            return TurnBudget(True, "BUDGET_CHECK_UNAVAILABLE")
        if not isinstance(payload, dict):
            return TurnBudget(True, "BUDGET_CHECK_UNAVAILABLE")
        return TurnBudget(
            allowed=bool(payload.get("allowed", True)),
            reason=str(payload.get("reason") or "UNKNOWN"),
            max_output_tokens_per_turn=_optional_int(payload.get("maxOutputTokensPerTurn")),
            timezone=str(payload.get("timezone") or "Asia/Shanghai"),
        )

    async def require(self, owner_id: str) -> TurnBudget:
        budget = await self.check(owner_id)
        if not budget.allowed:
            raise ModelBudgetExceededError(
                budget.reason,
                max_output_tokens_per_turn=budget.max_output_tokens_per_turn,
            )
        return budget


def _optional_int(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value if value > 0 else None
