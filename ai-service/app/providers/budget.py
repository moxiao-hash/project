"""Provider 调用前的预算预占与失败关闭策略。

预算判定由 Java 统一持有（每日调用次数、每日估算费用、单轮输出上限），并且必须
在每次真实 provider 调用之前以数据库行锁原子预占一个许可。这里只负责调用预占
接口、把回执转成许可对象，并在调用失败时释放许可。

判定接口不可达或回执字段非法时**失败关闭**：返回
:data:`BUDGET_UNAVAILABLE_REASON` 并拒绝模型调用。预算与模型凭据共用同一个 Java
后端不能作为放行理由——凭据仍有缓存、预算服务仍可能单独故障，静默放行会真实计费。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any
from uuid import uuid4

from app.core.request_context import current_request_id
from app.observability.usage import current_usage_scope

logger = logging.getLogger(__name__)

# 预算不可用（不可达或回执非法）时使用的可恢复原因。
BUDGET_UNAVAILABLE_REASON = "BUDGET_CHECK_UNAVAILABLE"


class ModelBudgetExceededError(RuntimeError):
    """预算拒绝新的模型调用；调用方应降级为纯 Java 查询/导航或失败可恢复的任务。"""

    def __init__(self, reason: str, *, max_output_tokens_per_turn: int | None = None) -> None:
        super().__init__(reason)
        self.reason = reason
        self.max_output_tokens_per_turn = max_output_tokens_per_turn


@dataclass(frozen=True)
class BudgetPermit:
    """一次预占许可；``allowed=false`` 时不得发起 provider 调用。"""

    reservation_id: str | None
    allowed: bool
    reason: str
    max_output_tokens_per_turn: int | None = None
    timezone: str = "Asia/Shanghai"


class ModelBudgetGuard:
    """向 Java 预占模型调用许可；预占失败或回执非法一律失败关闭。"""

    def __init__(self, java: Any) -> None:
        self._java = java

    async def reserve(
        self,
        *,
        owner_id: str,
        provider: str,
        model_name: str,
        purpose: str,
        input_tokens_upper_bound: int,
        conversation_id: str | None = None,
        turn_id: str | None = None,
    ) -> BudgetPermit:
        """为一次 provider 调用预占许可。

        ``inputTokensUpperBound`` 是本次请求输入的保守 token 上界，用于把输入成本计入
        预占；缺了它，日费用上限就无法保守执行，Java 侧会失败关闭。

        ``usageId`` 在预占时生成；HTTP 重试复用同一 payload 保证 Java 侧幂等。回执必须
        明确给出 ``allowed``、``reason``，放行时还必须给出 ``reservationId``；任何缺字段、
        类型错误或连接失败都返回失败关闭的许可。
        """

        usage_id = str(uuid4())
        scope = current_usage_scope()
        payload = {
            "usageId": usage_id,
            "ownerId": owner_id,
            "conversationId": (
                conversation_id
                or (scope.conversation_id if scope is not None else None)
                or "background"
            ),
            "turnId": (
                turn_id
                or (scope.turn_id if scope is not None else None)
                or current_request_id()
                or usage_id
            ),
            "purpose": purpose,
            "provider": provider,
            "modelName": model_name,
            "inputTokensUpperBound": _non_negative_int(input_tokens_upper_bound),
        }
        try:
            response = await self._java.reserve_assistant_usage(payload)
        except Exception as exc:  # noqa: BLE001 - 预算不可达必须失败关闭并暴露日志
            logger.warning(
                "assistant.budget.unavailable ownerId=%s error=%s",
                owner_id,
                type(exc).__name__,
            )
            return BudgetPermit(None, False, BUDGET_UNAVAILABLE_REASON)
        if not isinstance(response, dict):
            logger.warning("assistant.budget.unavailable ownerId=%s error=malformed", owner_id)
            return BudgetPermit(None, False, BUDGET_UNAVAILABLE_REASON)
        allowed = response.get("allowed")
        reason = response.get("reason")
        reservation_id = response.get("reservationId")
        if not isinstance(allowed, bool) or not isinstance(reason, str):
            logger.warning("assistant.budget.unavailable ownerId=%s error=malformed", owner_id)
            return BudgetPermit(None, False, BUDGET_UNAVAILABLE_REASON)
        if allowed and not (isinstance(reservation_id, str) and reservation_id):
            logger.warning(
                "assistant.budget.unavailable ownerId=%s error=missing_permit", owner_id
            )
            return BudgetPermit(None, False, BUDGET_UNAVAILABLE_REASON)
        timezone = response.get("timezone")
        return BudgetPermit(
            reservation_id=reservation_id if isinstance(reservation_id, str) else None,
            allowed=allowed,
            reason=reason,
            max_output_tokens_per_turn=_optional_int(
                response.get("maxOutputTokensPerTurn")
            ),
            timezone=timezone if isinstance(timezone, str) and timezone else "Asia/Shanghai",
        )

    async def release(self, reservation_id: str | None) -> None:
        """释放未被用量回调终结的预占；失败只记日志，不影响调用结果。"""

        if not reservation_id:
            return
        try:
            await self._java.release_assistant_usage_reservation(reservation_id)
        except Exception as exc:  # noqa: BLE001 - 释放失败由 TTL 兜底
            logger.warning(
                "assistant.budget.release_failed reservationId=%s error=%s",
                reservation_id,
                type(exc).__name__,
            )


def _non_negative_int(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return 0
    return value


def _optional_int(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value if value > 0 else None
