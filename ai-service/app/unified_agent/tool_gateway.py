"""带调用次数、联网和写操作预算的 Java 工具网关。"""

import json
from dataclasses import dataclass
from typing import Any

from app.clients.java_backend import JavaBackendClient
from app.unified_agent.models import ToolDescriptor, ToolEffect, ToolInvocationResult


class UnknownToolError(LookupError):
    pass


class DuplicateToolCallError(RuntimeError):
    pass


class ToolBudgetExceededError(RuntimeError):
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
    ) -> None:
        self._java = java_backend
        self._owner_id = owner_id
        self._budget = budget
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
        descriptor = await self._descriptor(tool_name)
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

        payload = await self._java.invoke_agent_tool(
            tool_name,
            self._owner_id,
            arguments,
            idempotency_key,
        )
        result = ToolInvocationResult.model_validate(payload)
        self._signatures.add(signature)
        self.usage.total_calls += 1
        if is_web:
            self.usage.web_searches += 1
        if descriptor.effect == ToolEffect.WRITE:
            self.usage.write_calls += 1
        if result.action is not None and result.action.risk_level.value == "HIGH":
            self.usage.high_risk_actions += 1
        return result

    async def _descriptor(self, tool_name: str) -> ToolDescriptor:
        if self._catalog is None:
            descriptors = await self._java.get_agent_tool_catalog()
            self._catalog = {item.name: item for item in descriptors}
        try:
            return self._catalog[tool_name]
        except KeyError as exc:
            raise UnknownToolError(f"Java 未公布工具: {tool_name}") from exc
