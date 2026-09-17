"""Task 30 真实模型 route-enum 跟进：Planner 系统提示必须暴露冻结的路由契约。

真实模型只会从系统提示得知允许的 ``routeKey``；如果提示里没有精确枚举，它会
自行猜测别名，最终被 ``PlanPolicyValidator`` 以 ``INVALID_ROUTE_KEY`` 拒绝。
这些测试证明 Planner 把 ``ui_action_schema`` 的冻结注册表原样带给模型，
并明确拒绝别名，而不是在 Planner 里复制第二份白名单。
"""

import asyncio
from typing import Any

from app.unified_agent.models import ToolDescriptor, ToolEffect, ToolRiskLevel
from app.unified_agent.planner import AssistantPlanner
from app.unified_agent.planning_models import (
    AssistantPlan,
    AssistantPlanStep,
    PlanIntent,
)
from app.unified_agent.policy_validator import PlanIssueCode, PlanPolicyValidator
from app.unified_agent.ui_action_schema import (
    ALLOWED_UI_ROUTE_KEYS,
    FOCUS_ACTION_KEYS,
    FORM_ACTION_SPECS,
    MODAL_ACTION_KEYS,
    NAVIGATION_PARAM_KEYS,
    RESOURCE_ACTION_KEYS,
    UI_ACTION_ALIAS_RULE,
    render_ui_action_contract,
)


def _descriptor(name: str, input_schema: dict[str, Any] | None = None) -> ToolDescriptor:
    return ToolDescriptor(
        name=name,
        version=1,
        category="NAVIGATION" if name == "navigation.resolve" else "LEARNING",
        effect=ToolEffect.NAVIGATE if name == "navigation.resolve" else ToolEffect.READ,
        risk_level=ToolRiskLevel.NONE,
        idempotency_required=False,
        input_schema=input_schema or {"type": "object", "properties": {}},
        output_schema={"type": "object", "properties": {}},
    )


CATALOG = {
    "navigation.resolve": _descriptor(
        "navigation.resolve",
        {
            "type": "object",
            "properties": {"routeKey": {"type": "string"}, "params": {"type": "object"}},
            "required": ["routeKey"],
        },
    ),
    "learning.goals.list": _descriptor("learning.goals.list"),
}


class _CapturingModel:
    """捕获系统提示的假模型；不发起任何网络请求。"""

    def __init__(self) -> None:
        self.messages: list[dict[str, str]] | None = None

    def with_structured_output(self, _schema: Any, **_kwargs: Any) -> "_CapturingModel":
        return self

    async def ainvoke(self, messages: list[dict[str, str]]) -> AssistantPlan:
        self.messages = messages
        return AssistantPlan(
            intent=PlanIntent.LEARNING_QUERY,
            confidence=0.9,
            summary="读取学习目标",
            steps=[],
        )


def _planner_system_message() -> str:
    model = _CapturingModel()
    planner = AssistantPlanner(
        model=model,
        catalog=CATALOG,
        validator=PlanPolicyValidator(CATALOG),
    )
    asyncio.run(
        planner.propose(
            message="打开学习目标页面",
            context={},
            client_context={},
        )
    )
    assert model.messages is not None
    assert model.messages[0]["role"] == "system"
    return model.messages[0]["content"]


def test_planner_system_message_registers_every_frozen_navigation_route_key() -> None:
    system_prompt = _planner_system_message()

    assert "navigation.resolve" in system_prompt
    for route_key in ALLOWED_UI_ROUTE_KEYS:
        assert route_key in system_prompt, route_key
    assert "LEARNING_GOALS" in system_prompt
    for route_key, required_keys in NAVIGATION_PARAM_KEYS.items():
        assert route_key in system_prompt
        for required_key in required_keys:
            assert required_key in system_prompt, (route_key, required_key)


def test_planner_system_message_exposes_all_five_action_matrices() -> None:
    system_prompt = _planner_system_message()

    for route_key, modal_key in MODAL_ACTION_KEYS.items():
        assert route_key in system_prompt
        assert modal_key in system_prompt, (route_key, modal_key)
    for route_key, resource_key in RESOURCE_ACTION_KEYS.items():
        assert route_key in system_prompt
        assert resource_key in system_prompt, (route_key, resource_key)
    for route_key, element_key in FOCUS_ACTION_KEYS.items():
        assert route_key in system_prompt
        assert element_key in system_prompt, (route_key, element_key)
    for route_key, spec in FORM_ACTION_SPECS.items():
        assert route_key in system_prompt
        assert str(spec["formKey"]) in system_prompt, route_key
        for field_name in (*spec["required"], *spec["optional"]):  # type: ignore[arg-type]
            assert str(field_name) in system_prompt, (route_key, field_name)


def test_rendered_contract_is_derived_from_frozen_registries() -> None:
    contract = render_ui_action_contract()

    for route_key in ALLOWED_UI_ROUTE_KEYS:
        assert route_key in contract, route_key
    for route_key, required_keys in NAVIGATION_PARAM_KEYS.items():
        for required_key in required_keys:
            assert required_key in contract, (route_key, required_key)
    for route_key, modal_key in MODAL_ACTION_KEYS.items():
        assert modal_key in contract, (route_key, modal_key)
    for route_key, resource_key in RESOURCE_ACTION_KEYS.items():
        assert resource_key in contract, (route_key, resource_key)
    for route_key, element_key in FOCUS_ACTION_KEYS.items():
        assert element_key in contract, (route_key, element_key)
    for spec in FORM_ACTION_SPECS.values():
        assert str(spec["formKey"]) in contract
        assert str(spec["titleMax"]) in contract


def test_planner_system_message_states_guessed_aliases_are_rejected() -> None:
    system_prompt = _planner_system_message()

    assert render_ui_action_contract() in system_prompt
    assert UI_ACTION_ALIAS_RULE in system_prompt
    # 契约把“别名会被拒绝”讲清楚，且 LEARNING_GOAL 这类别名确实不是合法枚举。
    assert "拒绝" in UI_ACTION_ALIAS_RULE
    assert "LEARNING_GOAL" not in ALLOWED_UI_ROUTE_KEYS


def test_planner_system_message_keeps_the_java_tool_catalog() -> None:
    system_prompt = _planner_system_message()

    assert "learning.goals.list" in system_prompt
    assert "可用工具目录" in system_prompt


def test_guessed_route_aliases_are_still_rejected_by_the_validator() -> None:
    validator = PlanPolicyValidator(CATALOG)

    for alias in ("LEARNING_GOAL", "learning-goals", "学习目标"):
        result = validator.validate(
            AssistantPlan(
                intent=PlanIntent.ROADMAP_NAVIGATE,
                confidence=0.9,
                summary="导航到学习目标",
                steps=[
                    AssistantPlanStep(
                        step_id="s1",
                        tool_name="navigation.resolve",
                        arguments={"routeKey": alias},
                        depends_on=[],
                    )
                ],
            )
        )

        assert result.ok is False, alias
        assert PlanIssueCode.INVALID_ROUTE_KEY in result.codes, alias
