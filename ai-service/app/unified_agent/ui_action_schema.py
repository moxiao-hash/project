"""Task 30 整改：五类界面动作的严格逐动作 Schema 与受控请求解析。

界面动作只能由服务端生成、浏览器只能执行，因此每一类动作都在这里被闭集建模：

* ``NAVIGATE`` 保留既有 routeKey + route-specific 标识参数；
* ``OPEN_MODAL`` / ``PREFILL_FORM`` / ``REFRESH_RESOURCE`` / ``FOCUS_ELEMENT``
  使用逐动作的注册表约束 ``routeKey`` 与动作专属键的对齐关系。

所有参数值都保持字符串；未知类型、未知参数键、未知注册表键、以及
URL/HTML/脚本/原生选择器一律拒绝。这里的函数不导入 Pydantic 模型，
避免与 ``models.UiAction`` 形成循环依赖。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date
from enum import StrEnum

#: ``docs/agent-native-contract.md`` v2 冻结的界面动作白名单。
ALLOWED_UI_ROUTE_KEYS = frozenset(
    {
        "DASHBOARD",
        "ASSISTANT",
        "ASSISTANT_HEALTH",
        "ROADMAP",
        "ROADMAP_STAGE",
        "ROADMAP_MODULE",
        "ROADMAP_NODE",
        "LEARNING_GOALS",
        "LEARNING_PLANS",
        "LEARNING_PLAN",
        "TODAY",
        "MATERIALS",
        "MATERIAL_DETAIL",
        "QUIZ",
        "QUIZ_ATTEMPT",
        "WRONG_QUESTIONS",
        "MASTERY",
        "KNOWLEDGE",
        "PLAN_ASSISTANT",
        "TASK_ASSISTANT",
        "NOTIFICATIONS",
        "AGENT_ACTIVITY",
        "LEARNING_SETTINGS",
        "AI_SETTINGS",
        "WORKSPACE_ARTIFACTS",
        "COURSES",
        "COURSE_DETAIL",
        "LESSON",
    }
)

#: 导航参数只允许安全业务标识符，拒绝任意路径、选择器和脚本。
UI_PARAM_VALUE_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

#: 草案文本可以包含中文和空格，但不得包含标记、协议或原生选择器字符。
_DRAFT_FORBIDDEN_PATTERN = re.compile(
    r"[<>\\{}\[\]();]|https?://|javascript:|data:|vbscript:",
    re.IGNORECASE,
)
_ISO_DATE_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_WEEKLY_HOURS_PATTERN = re.compile(r"^(?:[1-9]|[1-3][0-9]|40)$")

#: 弹窗注册表：routeKey -> 唯一允许的 modalKey。
MODAL_ACTION_KEYS: dict[str, str] = {
    "LEARNING_GOALS": "CREATE_GOAL",
    "LEARNING_PLANS": "CREATE_PLAN",
    "MATERIALS": "IMPORT_MATERIAL",
}

#: 刷新注册表：routeKey -> 唯一允许的 resourceKey（八个受控资源）。
RESOURCE_ACTION_KEYS: dict[str, str] = {
    "ROADMAP": "ROADMAP",
    "TODAY": "TODAY_TASKS",
    "LEARNING_GOALS": "LEARNING_GOALS",
    "LEARNING_PLANS": "LEARNING_PLANS",
    "NOTIFICATIONS": "NOTIFICATIONS",
    "WRONG_QUESTIONS": "WRONG_QUESTIONS",
    "MASTERY": "MASTERY",
    "AGENT_ACTIVITY": "ACTIVITY",
}

#: 聚焦注册表：routeKey -> 唯一允许的 elementKey。
FOCUS_ACTION_KEYS: dict[str, str] = {
    "ASSISTANT": "MESSAGE_INPUT",
    "LEARNING_PLANS": "PLAN_TITLE_INPUT",
}

#: 导航 routeKey -> 允许且必须存在的标识参数键集合。
NAVIGATION_PARAM_KEYS: dict[str, frozenset[str]] = {
    "ROADMAP_STAGE": frozenset({"stageId"}),
    "ROADMAP_MODULE": frozenset({"moduleId"}),
    "ROADMAP_NODE": frozenset({"nodeId"}),
    "LEARNING_PLAN": frozenset({"planId"}),
    "MATERIAL_DETAIL": frozenset({"materialId"}),
    "QUIZ": frozenset({"quizId"}),
    "QUIZ_ATTEMPT": frozenset({"attemptId"}),
    "COURSE_DETAIL": frozenset({"courseSlug"}),
    "LESSON": frozenset({"lessonId"}),
}

#: 逐表单注册表：routeKey -> formKey、标题上限、必填/可选字段。
FORM_ACTION_SPECS: dict[str, dict[str, object]] = {
    "LEARNING_GOALS": {
        "formKey": "GOAL_FORM",
        "titleMax": 100,
        "required": ("title",),
        "optional": ("targetDate", "weeklyStudyHours"),
    },
    "LEARNING_PLANS": {
        "formKey": "PLAN_FORM",
        "titleMax": 120,
        "required": ("title",),
        "optional": ("goalId", "startDate", "endDate"),
    },
    "MATERIALS": {
        "formKey": "MATERIAL_FORM",
        "titleMax": 180,
        "required": ("title",),
        "optional": ("content",),
    },
}

#: 告诉 Planner 只能逐字使用枚举，别名与翻译会被服务端直接拒绝。
UI_ACTION_ALIAS_RULE = (
    "routeKey 只能逐字使用上面的枚举值；任何别名、翻译或未列出的名称"
    "（例如 LEARNING_GOAL、learning-goals、学习目标）都会被服务端直接拒绝。"
)


def render_ui_action_contract() -> str:
    """渲染 Planner 可见的冻结界面动作契约。

    契约完全由本模块的注册表派生，因此 Planner 提示与 ``validate_ui_action``
    始终使用同一份白名单；调整注册表时两边同时生效，不会出现第二份副本。
    """

    lines = [
        "界面动作契约（冻结，routeKey 必须逐字匹配）：",
        (
            f"- 允许的 routeKey（{len(ALLOWED_UI_ROUTE_KEYS)} 个）："
            + "、".join(sorted(ALLOWED_UI_ROUTE_KEYS))
        ),
        UI_ACTION_ALIAS_RULE,
        (
            "- NAVIGATE（工具 navigation.resolve）：params 必须与 routeKey 精确对齐，"
            "缺少或多出参数都会被服务端拒绝。"
        ),
    ]
    for route_key, required_keys in sorted(NAVIGATION_PARAM_KEYS.items()):
        lines.append(
            f"  * {route_key}：必须且只能携带 " + "、".join(sorted(required_keys))
        )
    plain_routes = sorted(ALLOWED_UI_ROUTE_KEYS - set(NAVIGATION_PARAM_KEYS))
    lines.append("  * 无路径参数：" + "、".join(plain_routes) + "（不得携带 params）")

    lines.append("- OPEN_MODAL：params 只能包含 modalKey，且必须与 routeKey 配对：")
    for route_key, modal_key in sorted(MODAL_ACTION_KEYS.items()):
        lines.append(f"  * {route_key} → modalKey={modal_key}")

    lines.append("- PREFILL_FORM：params 只能包含 formKey、title 与列出的可选字段：")
    for route_key, spec in sorted(FORM_ACTION_SPECS.items()):
        required = "、".join(str(item) for item in spec["required"])  # type: ignore[arg-type]
        optional = "、".join(str(item) for item in spec["optional"])  # type: ignore[arg-type]
        lines.append(
            f"  * {route_key} → formKey={spec['formKey']}；必填 {required}；"
            f"可选 {optional}；title 最长 {spec['titleMax']} 字符"
        )

    lines.append("- REFRESH_RESOURCE：params 只能包含 resourceKey，且必须与 routeKey 配对：")
    for route_key, resource_key in sorted(RESOURCE_ACTION_KEYS.items()):
        lines.append(f"  * {route_key} → resourceKey={resource_key}")

    lines.append("- FOCUS_ELEMENT：params 只能包含 elementKey，且必须与 routeKey 配对：")
    for route_key, element_key in sorted(FOCUS_ACTION_KEYS.items()):
        lines.append(f"  * {route_key} → elementKey={element_key}")

    return "\n".join(lines)


class UiActionType(StrEnum):
    """Task 30 冻结的界面动作闭集；不存在的第六类必须被拒绝。"""

    NAVIGATE = "NAVIGATE"
    OPEN_MODAL = "OPEN_MODAL"
    PREFILL_FORM = "PREFILL_FORM"
    REFRESH_RESOURCE = "REFRESH_RESOURCE"
    FOCUS_ELEMENT = "FOCUS_ELEMENT"


def _require_identifier(value: str, label: str) -> None:
    if not isinstance(value, str) or not UI_PARAM_VALUE_PATTERN.match(value):
        raise ValueError(f"{label} 必须是安全业务标识符")


def _require_draft_text(value: str, label: str, *, max_length: int) -> None:
    if not isinstance(value, str):
        raise ValueError(f"{label} 必须是文本")
    if not 1 <= len(value) <= max_length:
        raise ValueError(f"{label} 长度必须在 1–{max_length} 之间")
    if any(not character.isprintable() for character in value):
        raise ValueError(f"{label} 不得包含控制字符")
    if _DRAFT_FORBIDDEN_PATTERN.search(value):
        raise ValueError(f"{label} 不得包含标记、协议或脚本")


def _require_iso_date(value: str, label: str) -> None:
    if not isinstance(value, str) or not _ISO_DATE_PATTERN.match(value):
        raise ValueError(f"{label} 必须是 ISO 日期")
    try:
        date.fromisoformat(value)
    except ValueError as exc:  # pragma: no cover - 正则已约束格式，保留真实日历校验
        raise ValueError(f"{label} 必须是有效 ISO 日期") from exc


def _require_weekly_hours(value: str, label: str) -> None:
    if not isinstance(value, str) or not _WEEKLY_HOURS_PATTERN.match(value):
        raise ValueError(f"{label} 必须是 1–40 的整数字符串")


def _reject_unknown_params(params: dict[str, str], allowed: frozenset[str]) -> None:
    unknown = set(params) - allowed
    if unknown:
        raise ValueError(f"动作包含未知参数: {','.join(sorted(unknown))}")


def _require_params(params: dict[str, str], required: frozenset[str]) -> None:
    missing = required - set(params)
    if missing:
        raise ValueError(f"动作缺少必需参数: {','.join(sorted(missing))}")


def validate_ui_action(
    action_type: UiActionType | str,
    route_key: str,
    params: dict[str, str],
) -> None:
    """在动作下发或重试登记前校验逐动作 Schema；非法动作绝不进入注册表。"""

    if route_key not in ALLOWED_UI_ROUTE_KEYS:
        raise ValueError(f"未注册的界面路由: {route_key}")
    if not isinstance(params, dict) or any(
        not isinstance(key, str) or not isinstance(value, str)
        for key, value in params.items()
    ):
        raise ValueError("界面动作参数必须是字符串映射")

    try:
        normalized_type = UiActionType(action_type)
    except ValueError as exc:
        raise ValueError(f"未注册的界面动作类型: {action_type}") from exc

    if normalized_type is UiActionType.NAVIGATE:
        # 导航保留既有 route-specific 标识参数：参数出现时必须与 routeKey 对齐，
        # 但既有调用允许省略可选路径参数，因此不强制补齐标识键。
        allowed = NAVIGATION_PARAM_KEYS.get(route_key, frozenset())
        _reject_unknown_params(params, allowed)
        for name, value in params.items():
            _require_identifier(value, name)
        return

    if normalized_type is UiActionType.OPEN_MODAL:
        expected = MODAL_ACTION_KEYS.get(route_key)
        if expected is None:
            raise ValueError(f"未注册的弹窗目标: {route_key}")
        if set(params) != {"modalKey"}:
            raise ValueError("弹窗动作必须且只能携带 modalKey")
        if params["modalKey"] != expected:
            raise ValueError(f"{route_key} 只允许弹窗 {expected}")
        return

    if normalized_type is UiActionType.PREFILL_FORM:
        spec = FORM_ACTION_SPECS.get(route_key)
        if spec is None:
            raise ValueError(f"未注册的表单目标: {route_key}")
        expected_form = str(spec["formKey"])
        required = tuple(str(item) for item in spec["required"])  # type: ignore[arg-type]
        optional = tuple(str(item) for item in spec["optional"])  # type: ignore[arg-type]
        allowed_fields = frozenset({"formKey", *required, *optional})
        _reject_unknown_params(params, allowed_fields)
        _require_params(params, frozenset({"formKey", *required}))
        if params.get("formKey") != expected_form:
            raise ValueError(f"{route_key} 只允许表单 {expected_form}")
        title = params.get("title")
        if not isinstance(title, str):
            raise ValueError("表单动作缺少标题")
        _require_draft_text(title, "title", max_length=int(spec["titleMax"]))  # type: ignore[arg-type]
        if "targetDate" in params:
            _require_iso_date(params["targetDate"], "targetDate")
        if "weeklyStudyHours" in params:
            _require_weekly_hours(params["weeklyStudyHours"], "weeklyStudyHours")
        if "goalId" in params:
            _require_identifier(params["goalId"], "goalId")
        if "startDate" in params:
            _require_iso_date(params["startDate"], "startDate")
        if "endDate" in params:
            _require_iso_date(params["endDate"], "endDate")
        if "content" in params:
            _require_draft_text(params["content"], "content", max_length=2000)
        return

    if normalized_type is UiActionType.REFRESH_RESOURCE:
        expected = RESOURCE_ACTION_KEYS.get(route_key)
        if expected is None:
            raise ValueError(f"未注册的刷新目标: {route_key}")
        if set(params) != {"resourceKey"}:
            raise ValueError("刷新动作必须且只能携带 resourceKey")
        if params["resourceKey"] != expected:
            raise ValueError(f"{route_key} 只允许刷新资源 {expected}")
        return

    expected = FOCUS_ACTION_KEYS.get(route_key)
    if expected is None:
        raise ValueError(f"未注册的聚焦目标: {route_key}")
    if set(params) != {"elementKey"}:
        raise ValueError("聚焦动作必须且只能携带 elementKey")
    if params["elementKey"] != expected:
        raise ValueError(f"{route_key} 只允许聚焦元素 {expected}")


@dataclass(frozen=True)
class UiActionRequest:
    """确定性解析出的受控界面动作请求；由 Supervisor 转成 ``UiAction``。"""

    type: UiActionType
    route_key: str
    params: dict[str, str]
    reason: str
    intent: str
    reply: str


#: 弹窗规则：reason 是动作描述，reply 只说明请求已下发，绝不宣称弹窗已打开。
_MODAL_REQUEST_RULES = (
    (("目标",), ("新建", "创建", "添加"), ("弹窗", "对话框", "窗口"),
     "LEARNING_GOALS", "CREATE_GOAL", "打开新建学习目标弹窗"),
    (("计划",), ("新建", "创建", "添加"), ("弹窗", "对话框", "窗口"),
     "LEARNING_PLANS", "CREATE_PLAN", "打开新建学习计划弹窗"),
    (("资料",), ("导入", "上传"), ("面板", "弹窗", "对话框"),
     "MATERIALS", "IMPORT_MATERIAL", "打开资料导入面板"),
)

_PREFILL_REQUEST_RULES = (
    (("目标",), "LEARNING_GOALS", "GOAL_FORM", "预填学习目标草稿"),
    (("计划",), "LEARNING_PLANS", "PLAN_FORM", "预填学习计划草稿"),
    (("资料",), "MATERIALS", "MATERIAL_FORM", "预填学习资料草稿"),
)

_REFRESH_REQUEST_RULES = (
    (("错题集", "错题"), "WRONG_QUESTIONS", "WRONG_QUESTIONS", "错题集"),
    (("学习路线", "路线"), "ROADMAP", "ROADMAP", "学习路线"),
    (("今日任务", "今日"), "TODAY", "TODAY_TASKS", "今日任务"),
    (("学习目标",), "LEARNING_GOALS", "LEARNING_GOALS", "学习目标"),
    (("学习计划", "计划"), "LEARNING_PLANS", "LEARNING_PLANS", "学习计划"),
    (("通知",), "NOTIFICATIONS", "NOTIFICATIONS", "通知"),
    (("掌握度", "掌握"), "MASTERY", "MASTERY", "掌握度"),
    (("执行记录", "执行与审计", "审计", "活动"), "AGENT_ACTIVITY", "ACTIVITY", "执行与审计"),
)


def _split_draft_title(message: str) -> str | None:
    for separator in ("：", ":"):
        if separator in message:
            title = message.split(separator, 1)[1].strip()
            if title:
                return title
    return None


def resolve_ui_action_request(message: str) -> UiActionRequest | None:
    """把冻结的受控用户场景解析为唯一界面动作；普通创建/保存请求不匹配。

    解析只依赖明确措辞（弹窗/面板、预填、刷新、聚焦），因此不会把
    “新建目标”“保存计划”等真实写入请求悄悄改写成草稿请求。

    所有回复都发生在浏览器终态回执之前，只能陈述“正在请求/将会执行”，
    不得宣称弹窗已打开、表单已预填、资源已刷新或元素已聚焦。
    """

    if not isinstance(message, str):
        return None
    normalized = message.strip()
    if not normalized:
        return None

    for targets, prefixes, containers, route_key, modal_key, reason in _MODAL_REQUEST_RULES:
        if (
            any(target in normalized for target in targets)
            and any(prefix in normalized for prefix in prefixes)
            and any(container in normalized for container in containers)
        ):
            return UiActionRequest(
                type=UiActionType.OPEN_MODAL,
                route_key=route_key,
                params={"modalKey": modal_key},
                reason=reason,
                intent="NAVIGATION",
                reply=f"正在请求{reason}。",
            )

    if "预填" in normalized:
        for targets, route_key, form_key, reason in _PREFILL_REQUEST_RULES:
            if any(target in normalized for target in targets):
                title = _split_draft_title(normalized)
                if title is None:
                    return None
                return UiActionRequest(
                    type=UiActionType.PREFILL_FORM,
                    route_key=route_key,
                    params={"formKey": form_key, "title": title},
                    reason=reason,
                    intent="NAVIGATION",
                    reply=f"将为你{reason}：“{title}”，请确认后自行保存。",
                )

    if "刷新" in normalized:
        for targets, route_key, resource_key, label in _REFRESH_REQUEST_RULES:
            if any(target in normalized for target in targets):
                return UiActionRequest(
                    type=UiActionType.REFRESH_RESOURCE,
                    route_key=route_key,
                    params={"resourceKey": resource_key},
                    reason=f"刷新{label}",
                    intent="NAVIGATION",
                    reply=f"正在刷新{label}，完成后会显示最新数据。",
                )

    if "聚焦" in normalized:
        if "消息输入框" in normalized or ("消息" in normalized and "输入框" in normalized):
            return UiActionRequest(
                type=UiActionType.FOCUS_ELEMENT,
                route_key="ASSISTANT",
                params={"elementKey": "MESSAGE_INPUT"},
                reason="聚焦消息输入框",
                intent="NAVIGATION",
                reply="正在请求聚焦消息输入框。",
            )
        if "计划标题" in normalized:
            return UiActionRequest(
                type=UiActionType.FOCUS_ELEMENT,
                route_key="LEARNING_PLANS",
                params={"elementKey": "PLAN_TITLE_INPUT"},
                reason="聚焦计划标题",
                intent="NAVIGATION",
                reply="正在请求打开新建计划弹窗并聚焦计划标题。",
            )

    return None
