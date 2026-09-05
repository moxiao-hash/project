"""带调用次数、联网和写操作预算的 Java 工具网关。"""

import json
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from app.clients.java_backend import JavaBackendClient
from app.observability.agent_metrics import AGENT_RUNTIME_METRICS, AgentRuntimeMetrics
from app.unified_agent.models import ToolDescriptor, ToolEffect, ToolInvocationResult


class UnknownToolError(LookupError):
    pass


class DuplicateToolCallError(RuntimeError):
    pass


class ToolBudgetExceededError(RuntimeError):
    pass


class ToolTurnCancelledError(RuntimeError):
    pass


@dataclass(frozen=True)
class ToolBudget:
    max_calls: int = 8
    max_web_searches: int = 1
    max_writes: int = 1
    max_high_risk_actions: int = 1


@dataclass
class ToolUsage:
    total_calls: int = 0
    web_searches: int = 0
    write_calls: int = 0
    high_risk_actions: int = 0


class UnifiedToolGateway:
    """模型永远不直接构造 Java URL，所有调用必须命中显式目录。"""

    def __init__(
        self,
        java_backend: JavaBackendClient,
        owner_id: str,
        budget: ToolBudget,
        metrics: AgentRuntimeMetrics = AGENT_RUNTIME_METRICS,
        is_cancelled: Callable[[], bool] = lambda: False,
    ) -> None:
        self._java = java_backend
        self._owner_id = owner_id
        self._budget = budget
        self._metrics = metrics
        self._is_cancelled = is_cancelled
        self._catalog: dict[str, ToolDescriptor] | None = None
        self._signatures: set[str] = set()
        self.usage = ToolUsage()

    async def invoke(
        self,
        tool_name: str,
        arguments: dict[str, Any],
        *,
        idempotency_key: str | None = None,
    ) -> ToolInvocationResult:
        self._check_cancelled()
        descriptor = await self._descriptor(tool_name)
        self._check_cancelled()
        signature = json.dumps(
            {"tool": tool_name, "arguments": arguments},
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        if signature in self._signatures:
            raise DuplicateToolCallError("禁止连续重复调用相同工具和参数")
        if self.usage.total_calls >= self._budget.max_calls:
            raise ToolBudgetExceededError("本轮工具调用次数已达到上限")
        is_web = tool_name.endswith("web.search") or tool_name == "materials.web.search"
        if is_web and self.usage.web_searches >= self._budget.max_web_searches:
            raise ToolBudgetExceededError("本轮联网搜索次数已达到上限")
        if descriptor.effect == ToolEffect.WRITE:
            if self.usage.write_calls >= self._budget.max_writes:
                raise ToolBudgetExceededError("本轮写操作次数已达到上限")
            if descriptor.idempotency_required and not idempotency_key:
                raise ValueError("写工具必须提供幂等键")
        if descriptor.risk_level.value == "HIGH" and (
            self.usage.high_risk_actions >= self._budget.max_high_risk_actions
        ):
            raise ToolBudgetExceededError("本轮高风险待确认操作已达到上限")

        # 在网络请求前占用预算；超时也可能已经发生副作用，不能免费无限重试。
        self._signatures.add(signature)
        self.usage.total_calls += 1
        if is_web:
            self.usage.web_searches += 1
        if descriptor.effect == ToolEffect.WRITE:
            self.usage.write_calls += 1
        if descriptor.risk_level.value == "HIGH":
            self.usage.high_risk_actions += 1
        try:
            payload = await self._java.invoke_agent_tool(
                tool_name, self._owner_id, arguments, idempotency_key,
            )
            result = ToolInvocationResult.model_validate(payload)
        except BaseException:
            self._metrics.observe_tool(category=descriptor.category, status="error")
            raise
        status = "success"
        if result.action is not None:
            status = (
                "success" if result.action.status == "SUCCEEDED"
                else "error" if result.action.status == "FAILED" else "pending"
            )
        self._metrics.observe_tool(category=descriptor.category, status=status)
        # 只读请求结束时也检查，避免随后继续调用知识模型等非工具能力。
        if descriptor.effect != ToolEffect.WRITE:
            self._check_cancelled()
        return result

    def _check_cancelled(self) -> None:
        if self._is_cancelled():
            raise ToolTurnCancelledError("本轮已取消，停止后续工具调用")

    async def _descriptor(self, tool_name: str) -> ToolDescriptor:
        if self._catalog is None:
            descriptors = await self._java.get_agent_tool_catalog()
            self._catalog = {item.name: item for item in descriptors}
        try:
            return self._catalog[tool_name]
        except KeyError as exc:
            raise UnknownToolError(f"Java 未公布工具: {tool_name}") from exc
