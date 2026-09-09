"""Task 28 确定性策略验证器测试。

这些测试只验证 Python 侧的确定性规则，不调用任何模型。规则来源：
`docs/agent-native-contract.md` v2 冻结契约与 Task 28 验收清单。
"""

from typing import Any

import pytest

from app.unified_agent.models import ToolDescriptor, ToolEffect, ToolRiskLevel
from app.unified_agent.planning_models import (
    AssistantPlan,
    AssistantPlanStep,
    PlanIntent,
)
from app.unified_agent.policy_validator import (
    PlanIssueCode,
    PlanPolicyValidator,
)
from app.unified_agent.tool_gateway import ToolBudget


def descriptor(
    name: str,
    *,
    effect: ToolEffect = ToolEffect.READ,
    risk: ToolRiskLevel = ToolRiskLevel.NONE,
    input_schema: dict[str, Any] | None = None,
    output_schema: dict[str, Any] | None = None,
) -> ToolDescriptor:
    return ToolDescriptor(
        name=name,
        version=1,
        category="TEST",
        effect=effect,
        risk_level=risk,
        idempotency_required=effect == ToolEffect.WRITE,
        input_schema=input_schema or {"type": "object", "properties": {}},
        output_schema=output_schema or {"type": "object", "properties": {}},
    )


CONTEXT_TOOL = descriptor(
    "learning.context.get",
    output_schema={
        "type": "object",
        "properties": {"nextNodeId": {"type": "string"}, "roadmap": {"type": "object"}},
    },
)
QUIZ_TOOL = descriptor(
    "assessment.node_quiz_status.get",
    input_schema={
        "type": "object",
        "properties": {"nodeId": {"type": "string"}},
        "required": ["nodeId"],
    },
    output_schema={"type": "object", "properties": {"quizId": {"type": "string"}}},
)
MASTERY_TOOL = descriptor("assessment.mastery.list")
SETTINGS_WRITE_TOOL = descriptor(
    "settings.learning.update",
    effect=ToolEffect.WRITE,
    risk=ToolRiskLevel.HIGH,
    input_schema={
        "type": "object",
        "properties": {"dailyStudyLimitMinutes": {"type": "integer"}},
        "required": ["dailyStudyLimitMinutes"],
    },
)
WEB_TOOL = descriptor("materials.web.search")
HIGH_RISK_READ_TOOL = descriptor(
    "developer.git.commit.preview", risk=ToolRiskLevel.HIGH
)

CATALOG = {
    tool.name: tool
    for tool in (
        CONTEXT_TOOL,
        QUIZ_TOOL,
        MASTERY_TOOL,
        SETTINGS_WRITE_TOOL,
        WEB_TOOL,
        HIGH_RISK_READ_TOOL,
    )
}


def step(
    step_id: str,
    tool_name: str,
    arguments: dict[str, Any] | None = None,
    depends_on: list[str] | None = None,
) -> AssistantPlanStep:
    return AssistantPlanStep(
        step_id=step_id,
        tool_name=tool_name,
        arguments=arguments or {},
        depends_on=depends_on or [],
    )


def plan(steps: list[AssistantPlanStep], **overrides: Any) -> AssistantPlan:
    payload: dict[str, Any] = {
        "intent": PlanIntent.LEARNING_QUERY,
        "confidence": 0.9,
        "summary": "测试计划",
        "steps": steps,
    }
    payload.update(overrides)
    return AssistantPlan(**payload)


def validator(**kwargs: Any) -> PlanPolicyValidator:
    return PlanPolicyValidator(CATALOG, **kwargs)


def test_multi_step_read_plan_with_declared_reference_is_accepted() -> None:
    result = validator().validate(
        plan(
            [
                step("s1", "learning.context.get"),
                step(
                    "s2",
                    "assessment.node_quiz_status.get",
                    {"nodeId": "$s1.nextNodeId"},
                    depends_on=["s1"],
                ),
            ]
        )
    )

    assert result.ok is True
    assert result.issues == ()


def test_unknown_tool_is_rejected() -> None:
    result = validator().validate(plan([step("s1", "evil.tool.call")]))

    assert result.ok is False
    assert PlanIssueCode.UNKNOWN_TOOL in result.codes
    assert result.issues[0].step_id == "s1"


@pytest.mark.parametrize(
    "arguments",
    [
        {"ownerId": "other-user"},
        {"nodeId": "node-1", "url": "https://evil.example.com"},
        {"sql": "DROP TABLE users"},
        {"command": "rm -rf /"},
        {"nested": {"cssSelector": "#root"}},
        {"nested": [{"script": "alert(1)"}]},
        {"beanName": "dataSource"},
        {"className": "java.lang.Runtime"},
        {"xpath": "//input"},
    ],
)
def test_forbidden_arguments_are_rejected_recursively(arguments: dict[str, Any]) -> None:
    result = validator().validate(plan([step("s1", "learning.context.get", arguments)]))

    assert result.ok is False
    assert PlanIssueCode.FORBIDDEN_ARGUMENT in result.codes


def test_missing_required_argument_is_rejected() -> None:
    result = validator().validate(plan([step("s1", "assessment.node_quiz_status.get", {})]))

    assert result.ok is False
    assert PlanIssueCode.MISSING_REQUIRED_ARGUMENT in result.codes


def test_argument_not_declared_by_tool_schema_is_rejected() -> None:
    result = validator().validate(
        plan([step("s1", "assessment.node_quiz_status.get", {"nodeId": "n1", "limit": 5})])
    )

    assert result.ok is False
    assert PlanIssueCode.UNKNOWN_ARGUMENT in result.codes


def test_argument_type_mismatch_is_rejected() -> None:
    result = validator().validate(
        plan(
            [
                step(
                    "s1",
                    "settings.learning.update",
                    {"dailyStudyLimitMinutes": "тридцать"},
                )
            ],
            intent=PlanIntent.PLAN_ADJUSTMENT,
        )
    )

    assert result.ok is False
    assert PlanIssueCode.INVALID_ARGUMENT_TYPE in result.codes


def test_dependency_on_unknown_step_is_rejected() -> None:
    result = validator().validate(
        plan([step("s1", "learning.context.get", depends_on=["s9"])])
    )

    assert result.ok is False
    assert PlanIssueCode.INVALID_DEPENDENCY in result.codes


def test_forward_or_self_dependency_is_rejected_as_cycle() -> None:
    result = validator().validate(
        plan(
            [
                step("s1", "learning.context.get", depends_on=["s2"]),
                step("s2", "assessment.mastery.list", depends_on=["s1"]),
            ]
        )
    )

    assert result.ok is False
    assert PlanIssueCode.DEPENDENCY_CYCLE in result.codes


def test_reference_to_undeclared_output_field_is_rejected() -> None:
    result = validator().validate(
        plan(
            [
                step("s1", "learning.context.get"),
                step(
                    "s2",
                    "assessment.node_quiz_status.get",
                    {"nodeId": "$s1.secretToken"},
                    depends_on=["s1"],
                ),
            ]
        )
    )

    assert result.ok is False
    assert PlanIssueCode.INVALID_REFERENCE in result.codes


def test_reference_to_later_step_is_rejected() -> None:
    result = validator().validate(
        plan(
            [
                step(
                    "s1",
                    "assessment.node_quiz_status.get",
                    {"nodeId": "$s2.quizId"},
                ),
                step("s2", "learning.context.get"),
            ]
        )
    )

    assert result.ok is False
    assert PlanIssueCode.INVALID_REFERENCE in result.codes


def test_reference_uses_declared_step_id_instead_of_array_position() -> None:
    result = validator().validate(
        plan(
            [
                step("s2", "learning.context.get"),
                step(
                    "s1",
                    "assessment.node_quiz_status.get",
                    {"nodeId": "$s1.nextNodeId"},
                ),
            ]
        )
    )

    assert result.ok is False
    assert PlanIssueCode.INVALID_REFERENCE in result.codes


def test_more_steps_than_configured_limit_is_rejected() -> None:
    result = validator(max_steps=2).validate(
        plan(
            [
                step("s1", "learning.context.get"),
                step("s2", "assessment.mastery.list"),
                step("s3", "assessment.mastery.list"),
            ]
        )
    )

    assert result.ok is False
    assert PlanIssueCode.TOO_MANY_STEPS in result.codes


def test_preloaded_learning_context_counts_toward_total_tool_budget() -> None:
    tools = {
        item.name: item
        for item in (
            descriptor("learning.goals.list"),
            descriptor("learning.plans.list"),
            descriptor("assessment.mastery.list"),
        )
    }
    result = PlanPolicyValidator(
        tools,
        budget=ToolBudget(max_calls=3),
    ).validate(
        plan(
            [
                step("s1", "learning.goals.list"),
                step("s2", "learning.plans.list"),
                step("s3", "assessment.mastery.list"),
            ]
        )
    )

    assert result.ok is False
    assert PlanIssueCode.TOOL_CALL_BUDGET_EXCEEDED in result.codes


def test_second_write_step_is_rejected_by_write_budget() -> None:
    result = validator().validate(
        plan(
            [
                step("s1", "settings.learning.update", {"dailyStudyLimitMinutes": 30}),
                step("s2", "settings.learning.update", {"dailyStudyLimitMinutes": 60}),
            ],
            intent=PlanIntent.PLAN_ADJUSTMENT,
        )
    )

    assert result.ok is False
    assert PlanIssueCode.WRITE_BUDGET_EXCEEDED in result.codes


def test_second_web_search_is_rejected_by_web_budget() -> None:
    result = validator().validate(
        plan([step("s1", "materials.web.search"), step("s2", "materials.web.search")])
    )

    assert result.ok is False
    assert PlanIssueCode.WEB_BUDGET_EXCEEDED in result.codes


def test_second_high_risk_step_is_rejected() -> None:
    result = validator().validate(
        plan(
            [
                step("s1", "developer.git.commit.preview"),
                step("s2", "developer.git.commit.preview"),
            ],
            intent=PlanIntent.CODE_DEVELOPMENT,
        )
    )

    assert result.ok is False
    assert PlanIssueCode.HIGH_RISK_BUDGET_EXCEEDED in result.codes


def test_duplicate_tool_call_with_same_arguments_is_rejected() -> None:
    result = validator().validate(
        plan(
            [
                step("s1", "assessment.node_quiz_status.get", {"nodeId": "n1"}),
                step("s2", "assessment.node_quiz_status.get", {"nodeId": "n1"}),
            ]
        )
    )

    assert result.ok is False
    assert PlanIssueCode.DUPLICATE_TOOL_CALL in result.codes


def test_low_confidence_requires_clarification() -> None:
    result = validator().validate(
        plan([step("s1", "learning.context.get")], confidence=0.4)
    )

    assert result.ok is False
    assert PlanIssueCode.LOW_CONFIDENCE in result.codes


def test_clarify_intent_must_not_carry_steps() -> None:
    result = validator().validate(
        plan([step("s1", "learning.context.get")], intent=PlanIntent.CLARIFY)
    )

    assert result.ok is False
    assert PlanIssueCode.CLARIFY_WITH_STEPS in result.codes


def test_non_conversational_action_plan_must_contain_a_tool_step() -> None:
    result = validator().validate(plan([]))

    assert result.ok is False
    assert PlanIssueCode.EMPTY_PLAN in result.codes


def test_custom_budget_is_respected() -> None:
    result = validator(budget=ToolBudget(max_calls=8, max_web_searches=0)).validate(
        plan([step("s1", "materials.web.search")])
    )

    assert result.ok is False
    assert PlanIssueCode.WEB_BUDGET_EXCEEDED in result.codes


def test_step_id_outside_contract_pattern_cannot_be_constructed() -> None:
    with pytest.raises(ValueError):
        AssistantPlanStep(step_id="x1", tool_name="learning.context.get")


def test_plan_cannot_declare_more_than_eight_steps() -> None:
    with pytest.raises(ValueError):
        plan([step(f"s{index}", "assessment.mastery.list") for index in range(1, 10)])


def test_navigation_step_with_unregistered_route_key_is_rejected() -> None:
    catalog = dict(CATALOG)
    catalog["navigation.resolve"] = descriptor(
        "navigation.resolve",
        input_schema={
            "type": "object",
            "properties": {"routeKey": {"type": "string"}},
            "required": ["routeKey"],
        },
    )

    result = PlanPolicyValidator(catalog).validate(
        plan([step("s1", "navigation.resolve", {"routeKey": "EVIL_ROUTE"})])
    )

    assert result.ok is False
    assert PlanIssueCode.INVALID_ROUTE_KEY in result.codes


def test_ui_action_contract_rejects_unregistered_route_key() -> None:
    from app.unified_agent.models import UiAction

    with pytest.raises(ValueError):
        UiAction(route_key="EVIL_ROUTE", reason="任意页面")


def test_ui_action_contract_rejects_unsafe_parameter_value() -> None:
    from app.unified_agent.models import UiAction

    with pytest.raises(ValueError):
        UiAction(
            route_key="ROADMAP_NODE",
            params={"nodeId": "../../etc/passwd"},
            reason="越界路径",
        )


def test_reference_is_allowed_when_java_catalog_has_no_output_schema() -> None:
    """Java 目录当前只发布 {"type": "object"}，此时运行时解析兜底。"""

    catalog = dict(CATALOG)
    catalog["learning.context.get"] = descriptor(
        "learning.context.get", output_schema={"type": "object"}
    )

    result = PlanPolicyValidator(catalog).validate(
        plan(
            [
                step("s1", "learning.context.get"),
                step(
                    "s2",
                    "assessment.node_quiz_status.get",
                    {"nodeId": "$s1.nextNodeId"},
                    depends_on=["s1"],
                ),
            ]
        )
    )

    assert result.ok is True
