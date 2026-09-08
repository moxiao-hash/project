"""Task 28 确定性策略验证器。

模型可以提出计划，但不能决定计划是否合法。本模块在**不调用模型**的前提下，
逐项校验 `AssistantPlan` 是否符合 v2 冻结契约、Java 工具目录、预算和真实性边界：

1. 工具必须来自 Java 已发布目录。
2. 参数不得包含 ``ownerId`` / ``url`` / ``sql`` / ``shell`` / 选择器 / 反射类名。
3. 必填参数齐全、未声明参数被拒绝、基础类型匹配。
4. ``dependsOn`` 只能引用前序步骤，禁止自引用和前向引用。
5. ``$sN.field`` 占位符只能引用前序步骤已声明的输出字段。
6. 单轮最多 8 步、1 个写操作、1 次联网、1 个高风险动作，禁止相同参数重复调用。
7. 置信度低于阈值、或 ``CLARIFY`` 意图却携带步骤时，必须转澄清。

校验失败一律返回问题列表，由 Planner 转成澄清，绝不“修复后直接执行”。
"""

import json
import re
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from typing import Any

from app.unified_agent.models import (
    ALLOWED_UI_ROUTE_KEYS,
    ToolDescriptor,
    ToolEffect,
    ToolRiskLevel,
)
from app.unified_agent.planning_models import (
    AssistantPlan,
    AssistantPlanStep,
    PlanIntent,
)
from app.unified_agent.tool_gateway import ToolBudget

#: 模型与客户端都不得生成的身份、链接、数据库、Shell、选择器和反射字段。
FORBIDDEN_ARGUMENT_KEYS = frozenset(
    {
        "ownerid",
        "owner_id",
        "url",
        "weburl",
        "web_url",
        "sql",
        "shell",
        "command",
        "cssselector",
        "css_selector",
        "xpath",
        "script",
        "beanname",
        "bean_name",
        "classname",
        "class_name",
    }
)

PLAN_REFERENCE_PATTERN = re.compile(
    r"^\$s(?P<step>[1-8])\.(?P<field>[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)$"
)

_JSON_TYPE_CHECKS: dict[str, tuple[type, ...]] = {
    "string": (str,),
    "integer": (int,),
    "number": (int, float),
    "boolean": (bool,),
    "array": (list,),
    "object": (dict,),
}


class PlanIssueCode(StrEnum):
    """稳定的机器可读问题码，供日志、测试与前端降级提示使用。"""

    TOO_MANY_STEPS = "TOO_MANY_STEPS"
    DUPLICATE_STEP_ID = "DUPLICATE_STEP_ID"
    UNKNOWN_TOOL = "UNKNOWN_TOOL"
    FORBIDDEN_ARGUMENT = "FORBIDDEN_ARGUMENT"
    MISSING_REQUIRED_ARGUMENT = "MISSING_REQUIRED_ARGUMENT"
    UNKNOWN_ARGUMENT = "UNKNOWN_ARGUMENT"
    INVALID_ARGUMENT_TYPE = "INVALID_ARGUMENT_TYPE"
    INVALID_DEPENDENCY = "INVALID_DEPENDENCY"
    DEPENDENCY_CYCLE = "DEPENDENCY_CYCLE"
    INVALID_REFERENCE = "INVALID_REFERENCE"
    INVALID_ROUTE_KEY = "INVALID_ROUTE_KEY"
    DUPLICATE_TOOL_CALL = "DUPLICATE_TOOL_CALL"
    WRITE_BUDGET_EXCEEDED = "WRITE_BUDGET_EXCEEDED"
    WEB_BUDGET_EXCEEDED = "WEB_BUDGET_EXCEEDED"
    HIGH_RISK_BUDGET_EXCEEDED = "HIGH_RISK_BUDGET_EXCEEDED"
    LOW_CONFIDENCE = "LOW_CONFIDENCE"
    CLARIFY_WITH_STEPS = "CLARIFY_WITH_STEPS"


@dataclass(frozen=True)
class PlanIssue:
    code: PlanIssueCode
    message: str
    step_id: str | None = None


@dataclass(frozen=True)
class PlanValidation:
    ok: bool
    issues: tuple[PlanIssue, ...] = ()

    @property
    def codes(self) -> tuple[PlanIssueCode, ...]:
        return tuple(issue.code for issue in self.issues)


class PlanPolicyValidator:
    """对模型计划做确定性校验；不访问网络、不执行工具。"""

    def __init__(
        self,
        catalog: Mapping[str, ToolDescriptor],
        *,
        budget: ToolBudget | None = None,
        min_confidence: float = 0.7,
        max_steps: int = 8,
    ) -> None:
        self._catalog = dict(catalog)
        self._budget = budget or ToolBudget()
        self._min_confidence = min_confidence
        self._max_steps = max_steps

    def validate(self, plan: AssistantPlan) -> PlanValidation:
        issues: list[PlanIssue] = []
        steps = plan.steps

        if len(steps) > self._max_steps:
            issues.append(
                PlanIssue(
                    PlanIssueCode.TOO_MANY_STEPS,
                    f"计划步骤数超过上限 {self._max_steps}",
                )
            )
        if plan.confidence < self._min_confidence:
            issues.append(
                PlanIssue(
                    PlanIssueCode.LOW_CONFIDENCE,
                    f"置信度低于 {self._min_confidence}，必须先向用户澄清",
                )
            )
        if plan.intent == PlanIntent.CLARIFY and steps:
            issues.append(
                PlanIssue(
                    PlanIssueCode.CLARIFY_WITH_STEPS,
                    "CLARIFY 意图不允许携带执行步骤",
                )
            )

        step_ids = [step.step_id for step in steps]
        if len(set(step_ids)) != len(step_ids):
            issues.append(PlanIssue(PlanIssueCode.DUPLICATE_STEP_ID, "计划包含重复的 stepId"))

        write_count = 0
        web_count = 0
        high_risk_count = 0
        signatures: set[str] = set()

        for index, step in enumerate(steps):
            descriptor = self._catalog.get(step.tool_name)
            if descriptor is None:
                issues.append(
                    PlanIssue(
                        PlanIssueCode.UNKNOWN_TOOL,
                        f"Java 未发布工具: {step.tool_name}",
                        step.step_id,
                    )
                )
                continue

            issues.extend(self._validate_arguments(step, descriptor, steps, index))
            issues.extend(self._validate_dependencies(step, step_ids, index))

            signature = json.dumps(
                {"tool": step.tool_name, "arguments": step.arguments},
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
                default=str,
            )
            if signature in signatures:
                issues.append(
                    PlanIssue(
                        PlanIssueCode.DUPLICATE_TOOL_CALL,
                        "计划包含相同工具和参数的重复调用",
                        step.step_id,
                    )
                )
            signatures.add(signature)

            if descriptor.effect == ToolEffect.WRITE:
                write_count += 1
            if descriptor.is_web_search:
                web_count += 1
            if descriptor.risk_level == ToolRiskLevel.HIGH:
                high_risk_count += 1

        if write_count > self._budget.max_writes:
            issues.append(
                PlanIssue(PlanIssueCode.WRITE_BUDGET_EXCEEDED, "计划包含多个写操作步骤")
            )
        if web_count > self._budget.max_web_searches:
            issues.append(
                PlanIssue(PlanIssueCode.WEB_BUDGET_EXCEEDED, "计划包含多次联网搜索步骤")
            )
        if high_risk_count > self._budget.max_high_risk_actions:
            issues.append(
                PlanIssue(PlanIssueCode.HIGH_RISK_BUDGET_EXCEEDED, "计划包含多个高风险步骤")
            )

        return PlanValidation(ok=not issues, issues=tuple(issues))

    def _validate_arguments(
        self,
        step: AssistantPlanStep,
        descriptor: ToolDescriptor,
        steps: list[AssistantPlanStep],
        index: int,
    ) -> list[PlanIssue]:
        issues: list[PlanIssue] = []
        arguments = step.arguments

        for key in self._iter_keys(arguments):
            if key.lower() in FORBIDDEN_ARGUMENT_KEYS:
                issues.append(
                    PlanIssue(
                        PlanIssueCode.FORBIDDEN_ARGUMENT,
                        f"参数包含禁止字段: {key}",
                        step.step_id,
                    )
                )

        schema = descriptor.input_schema if isinstance(descriptor.input_schema, dict) else {}
        properties = schema.get("properties")
        if isinstance(properties, dict):
            required = schema.get("required")
            if isinstance(required, list):
                for name in required:
                    if isinstance(name, str) and name not in arguments:
                        issues.append(
                            PlanIssue(
                                PlanIssueCode.MISSING_REQUIRED_ARGUMENT,
                                f"缺少必填参数: {name}",
                                step.step_id,
                            )
                        )
            for name, value in arguments.items():
                declared = properties.get(name)
                if not isinstance(declared, dict):
                    issues.append(
                        PlanIssue(
                            PlanIssueCode.UNKNOWN_ARGUMENT,
                            f"工具未声明参数: {name}",
                            step.step_id,
                        )
                    )
                    continue
                expected = declared.get("type")
                check = _JSON_TYPE_CHECKS.get(expected) if isinstance(expected, str) else None
                if check is None or _is_placeholder(value):
                    continue
                bool_mismatch = isinstance(value, bool) and expected in {"integer", "number"}
                if bool_mismatch or not isinstance(value, check):
                    issues.append(
                        PlanIssue(
                            PlanIssueCode.INVALID_ARGUMENT_TYPE,
                            f"参数类型不匹配: {name}",
                            step.step_id,
                        )
                    )

        issues.extend(self._validate_references(step, descriptor, steps, index))

        if step.tool_name == "navigation.resolve":
            route_key = arguments.get("routeKey")
            if not isinstance(route_key, str) or route_key not in ALLOWED_UI_ROUTE_KEYS:
                issues.append(
                    PlanIssue(
                        PlanIssueCode.INVALID_ROUTE_KEY,
                        "导航步骤使用了未注册的 routeKey",
                        step.step_id,
                    )
                )
        return issues

    def _validate_references(
        self,
        step: AssistantPlanStep,
        descriptor: ToolDescriptor,
        steps: list[AssistantPlanStep],
        index: int,
    ) -> list[PlanIssue]:
        issues: list[PlanIssue] = []
        for value in self._iter_values(step.arguments):
            if not _is_placeholder(value):
                continue
            match = PLAN_REFERENCE_PATTERN.match(value)
            if match is None:
                issues.append(
                    PlanIssue(
                        PlanIssueCode.INVALID_REFERENCE,
                        f"非法输出引用: {value}",
                        step.step_id,
                    )
                )
                continue
            source_index = int(match.group("step")) - 1
            if source_index >= index:
                issues.append(
                    PlanIssue(
                        PlanIssueCode.INVALID_REFERENCE,
                        f"只能引用前序步骤输出: {value}",
                        step.step_id,
                    )
                )
                continue
            source = steps[source_index]
            status = self._output_field_status(
                source.tool_name, match.group("field")
            )
            if status == "absent":
                issues.append(
                    PlanIssue(
                        PlanIssueCode.INVALID_REFERENCE,
                        f"前序步骤未声明该输出字段: {value}",
                        step.step_id,
                    )
                )
            # status == "unknown"：Java 目录当前只发布 {"type": "object"}，
            # 无法静态确认字段。允许通过校验，但执行时若字段不存在会转澄清，
            # 不会用错误参数调用工具。Task 30 补齐输出 schema 后自动变严格。
        return issues

    def _output_field_status(self, tool_name: str, field_path: str) -> str:
        """返回 ``present`` / ``absent`` / ``unknown``。"""

        descriptor = self._catalog.get(tool_name)
        if descriptor is None:
            return "absent"
        schema: Any = descriptor.output_schema
        for part in field_path.split("."):
            if not isinstance(schema, dict):
                return "unknown"
            properties = schema.get("properties")
            if not isinstance(properties, dict):
                return "unknown"
            if part not in properties:
                return "absent"
            schema = properties[part]
        return "present"

    @staticmethod
    def _validate_dependencies(
        step: AssistantPlanStep,
        step_ids: list[str],
        index: int,
    ) -> list[PlanIssue]:
        issues: list[PlanIssue] = []
        for dependency in step.depends_on:
            if dependency not in step_ids:
                issues.append(
                    PlanIssue(
                        PlanIssueCode.INVALID_DEPENDENCY,
                        f"依赖了不存在的步骤: {dependency}",
                        step.step_id,
                    )
                )
                continue
            if step_ids.index(dependency) >= index:
                issues.append(
                    PlanIssue(
                        PlanIssueCode.DEPENDENCY_CYCLE,
                        f"依赖必须指向更早的步骤: {dependency}",
                        step.step_id,
                    )
                )
        return issues

    @staticmethod
    def _iter_keys(value: Any):
        if isinstance(value, dict):
            for key, item in value.items():
                if isinstance(key, str):
                    yield key
                yield from PlanPolicyValidator._iter_keys(item)
        elif isinstance(value, list):
            for item in value:
                yield from PlanPolicyValidator._iter_keys(item)

    @staticmethod
    def _iter_values(value: Any):
        if isinstance(value, dict):
            for item in value.values():
                yield from PlanPolicyValidator._iter_values(item)
        elif isinstance(value, list):
            for item in value:
                yield from PlanPolicyValidator._iter_values(item)
        else:
            yield value


def _is_placeholder(value: Any) -> bool:
    return isinstance(value, str) and value.startswith("$s")
