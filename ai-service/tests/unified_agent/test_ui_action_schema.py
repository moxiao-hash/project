"""Task 30 整改：五类界面动作的严格 Schema、受控请求与稳定 actionId 测试。

这些测试只验证 Python 侧的确定性行为，不调用任何模型，也不启动浏览器；
真实三端链路由 ``scripts/task30-remediation-probe.py`` 负责。
"""

import asyncio

import pytest

from app.unified_agent.models import (
    AssistantActionReceipt,
    ToolDescriptor,
    ToolEffect,
    ToolRiskLevel,
    UiAction,
    UiActionReceiptStatus,
)
from app.unified_agent.supervisor import (
    AssistantActionNotFoundError,
    AssistantConversationNotFoundError,
    UnifiedAgentSupervisor,
)
from app.unified_agent.ui_action_schema import (
    UiActionType,
    resolve_ui_action_request,
    validate_ui_action,
)


def tool(name: str, effect: ToolEffect) -> ToolDescriptor:
    return ToolDescriptor(
        name=name,
        version=1,
        category="TEST",
        effect=effect,
        risk_level=ToolRiskLevel.NONE,
        input_schema={"type": "object", "properties": {}},
        output_schema={"type": "object", "properties": {}},
    )


class FakeJavaBackend:
    async def get_agent_tool_catalog(self):
        return [
            tool("learning.context.get", ToolEffect.READ),
            tool("navigation.resolve", ToolEffect.READ),
        ]

    async def invoke_agent_tool(self, name, owner_id, arguments, idempotency_key=None):
        if name == "learning.context.get":
            return {"toolName": name, "data": {"roadmap": {"stages": []}}, "action": None}
        if name == "navigation.resolve":
            return {"toolName": name, "data": {}, "action": None}
        raise AssertionError(name)


def test_unknown_action_type_is_rejected() -> None:
    with pytest.raises(ValueError):
        validate_ui_action("DELETE_EVERYTHING", "ROADMAP", {})


def test_navigation_keeps_route_specific_identifier_schema() -> None:
    validate_ui_action("NAVIGATE", "ROADMAP", {})
    validate_ui_action("NAVIGATE", "ROADMAP_NODE", {"nodeId": "node-1"})
    # 既有调用允许省略可选路径参数，但出现未知键或非法值必须拒绝。
    validate_ui_action("NAVIGATE", "ROADMAP_NODE", {})
    with pytest.raises(ValueError):
        validate_ui_action("NAVIGATE", "ROADMAP_NODE", {"nodeId": "node-1", "url": "x"})
    with pytest.raises(ValueError):
        validate_ui_action("NAVIGATE", "ROADMAP", {"ownerId": "attacker"})
    with pytest.raises(ValueError):
        validate_ui_action("NAVIGATE", "QUIZ", {"quizId": "https://evil.example"})


def test_open_modal_requires_route_key_alignment() -> None:
    validate_ui_action("OPEN_MODAL", "LEARNING_GOALS", {"modalKey": "CREATE_GOAL"})
    with pytest.raises(ValueError):
        validate_ui_action("OPEN_MODAL", "LEARNING_GOALS", {"modalKey": "CREATE_PLAN"})
    with pytest.raises(ValueError):
        validate_ui_action("OPEN_MODAL", "MATERIALS", {"modalKey": "IMPORT_MATERIAL", "x": "1"})
    with pytest.raises(ValueError):
        validate_ui_action("OPEN_MODAL", "QUIZ", {"modalKey": "CREATE_GOAL"})


def test_prefill_form_allows_chinese_spaces_but_rejects_markup() -> None:
    validate_ui_action(
        "PREFILL_FORM",
        "LEARNING_GOALS",
        {"formKey": "GOAL_FORM", "title": "学习 Java 基础", "weeklyStudyHours": "8"},
    )
    validate_ui_action(
        "PREFILL_FORM",
        "LEARNING_PLANS",
        {
            "formKey": "PLAN_FORM",
            "title": "第一阶段基础",
            "startDate": "2026-09-01",
            "endDate": "2026-10-01",
            "goalId": "goal-1",
        },
    )
    for bad_title in ("<script>alert(1)</script>", "javascript:alert(1)", "a" * 101):
        with pytest.raises(ValueError):
            validate_ui_action(
                "PREFILL_FORM",
                "LEARNING_GOALS",
                {"formKey": "GOAL_FORM", "title": bad_title},
            )
    with pytest.raises(ValueError):
        validate_ui_action(
            "PREFILL_FORM",
            "LEARNING_GOALS",
            {"formKey": "GOAL_FORM", "title": "x", "weeklyStudyHours": "41"},
        )
    with pytest.raises(ValueError):
        validate_ui_action(
            "PREFILL_FORM",
            "MATERIALS",
            {"formKey": "MATERIAL_FORM", "title": "x", "content": "a" * 2001},
        )
    with pytest.raises(ValueError):
        validate_ui_action(
            "PREFILL_FORM",
            "MATERIALS",
            {"formKey": "MATERIAL_FORM", "title": "x", "url": "https://evil.example"},
        )


def test_refresh_and_focus_registries_reject_unknown_keys() -> None:
    validate_ui_action("REFRESH_RESOURCE", "WRONG_QUESTIONS", {"resourceKey": "WRONG_QUESTIONS"})
    validate_ui_action("REFRESH_RESOURCE", "AGENT_ACTIVITY", {"resourceKey": "ACTIVITY"})
    with pytest.raises(ValueError):
        validate_ui_action("REFRESH_RESOURCE", "WRONG_QUESTIONS", {"resourceKey": "MASTERY"})
    with pytest.raises(ValueError):
        validate_ui_action("REFRESH_RESOURCE", "QUIZ", {"resourceKey": "ROADMAP"})
    validate_ui_action("FOCUS_ELEMENT", "ASSISTANT", {"elementKey": "MESSAGE_INPUT"})
    validate_ui_action("FOCUS_ELEMENT", "LEARNING_PLANS", {"elementKey": "PLAN_TITLE_INPUT"})
    with pytest.raises(ValueError):
        validate_ui_action("FOCUS_ELEMENT", "ASSISTANT", {"elementKey": "PLAN_TITLE_INPUT"})
    with pytest.raises(ValueError):
        validate_ui_action("FOCUS_ELEMENT", "DASHBOARD", {"elementKey": "MESSAGE_INPUT"})


def test_ui_action_model_rejects_unknown_fields_and_exposes_type_enum() -> None:
    action = UiAction(
        type="OPEN_MODAL",
        route_key="LEARNING_GOALS",
        params={"modalKey": "CREATE_GOAL"},
        reason="r",
    )
    assert action.type is UiActionType.OPEN_MODAL
    assert action.action_id is None
    with pytest.raises(ValueError):
        UiAction(
            type="OPEN_MODAL",
            route_key="LEARNING_GOALS",
            params={"ownerId": "attacker"},
            reason="r",
        )


@pytest.mark.parametrize(
    ("message", "action_type", "route_key", "params"),
    [
        ("打开新建目标弹窗", "OPEN_MODAL", "LEARNING_GOALS", {"modalKey": "CREATE_GOAL"}),
        ("打开新建计划弹窗", "OPEN_MODAL", "LEARNING_PLANS", {"modalKey": "CREATE_PLAN"}),
        ("打开资料导入面板", "OPEN_MODAL", "MATERIALS", {"modalKey": "IMPORT_MATERIAL"}),
        (
            "预填目标：学习 Java 基础",
            "PREFILL_FORM",
            "LEARNING_GOALS",
            {"formKey": "GOAL_FORM", "title": "学习 Java 基础"},
        ),
        (
            "预填计划：第一阶段基础",
            "PREFILL_FORM",
            "LEARNING_PLANS",
            {"formKey": "PLAN_FORM", "title": "第一阶段基础"},
        ),
        (
            "预填资料：Java 学习笔记",
            "PREFILL_FORM",
            "MATERIALS",
            {"formKey": "MATERIAL_FORM", "title": "Java 学习笔记"},
        ),
        ("刷新错题集", "REFRESH_RESOURCE", "WRONG_QUESTIONS", {"resourceKey": "WRONG_QUESTIONS"}),
        ("刷新学习路线", "REFRESH_RESOURCE", "ROADMAP", {"resourceKey": "ROADMAP"}),
        ("刷新今日任务", "REFRESH_RESOURCE", "TODAY", {"resourceKey": "TODAY_TASKS"}),
        ("刷新学习目标", "REFRESH_RESOURCE", "LEARNING_GOALS", {"resourceKey": "LEARNING_GOALS"}),
        ("刷新学习计划", "REFRESH_RESOURCE", "LEARNING_PLANS", {"resourceKey": "LEARNING_PLANS"}),
        ("刷新通知", "REFRESH_RESOURCE", "NOTIFICATIONS", {"resourceKey": "NOTIFICATIONS"}),
        ("刷新掌握度", "REFRESH_RESOURCE", "MASTERY", {"resourceKey": "MASTERY"}),
        ("刷新执行记录", "REFRESH_RESOURCE", "AGENT_ACTIVITY", {"resourceKey": "ACTIVITY"}),
        ("聚焦消息输入框", "FOCUS_ELEMENT", "ASSISTANT", {"elementKey": "MESSAGE_INPUT"}),
        ("聚焦计划标题", "FOCUS_ELEMENT", "LEARNING_PLANS", {"elementKey": "PLAN_TITLE_INPUT"}),
    ],
)
def test_controlled_requests_resolve_to_frozen_action(
    message: str, action_type: str, route_key: str, params: dict[str, str]
) -> None:
    request = resolve_ui_action_request(message)
    assert request is not None
    assert request.type.value == action_type
    assert request.route_key == route_key
    assert request.params == params


@pytest.mark.parametrize(
    "message",
    [
        "新建学习目标",
        "保存学习计划",
        "创建一条学习资料",
        "把这个目标存下来",
    ],
)
def test_normal_create_requests_are_not_reinterpreted_as_drafts(message: str) -> None:
    assert resolve_ui_action_request(message) is None


async def _scenario(message: str, expected_type: str, expected_params: dict[str, str]):
    service = UnifiedAgentSupervisor(FakeJavaBackend(), model_name="test-model")
    conversation = await service.create_conversation("user-1")
    snapshot = await service.send_message(
        conversation.conversation_id,
        message,
        f"turn:{expected_type}:{message}",
        "user-1",
        {},
    )
    action = snapshot.ui_actions[0]
    assert action.type.value == expected_type
    assert action.params == expected_params
    assert action.action_id, "REST 快照必须携带服务端稳定 actionId"

    events = await service.list_events(conversation.conversation_id, "user-1", 0)
    ui_events = [event for event in events if event.type == "UI_ACTION"]
    assert [event.payload["actionId"] for event in ui_events] == [action.action_id]
    assert ui_events[0].payload["type"] == expected_type
    return service, conversation, action


def test_supervisor_emits_stable_action_id_across_sse_and_rest() -> None:
    async def run() -> None:
        for message, expected_type, params in (
            ("打开新建目标弹窗", "OPEN_MODAL", {"modalKey": "CREATE_GOAL"}),
            (
                "预填目标：学习 Java 基础",
                "PREFILL_FORM",
                {"formKey": "GOAL_FORM", "title": "学习 Java 基础"},
            ),
            ("刷新错题集", "REFRESH_RESOURCE", {"resourceKey": "WRONG_QUESTIONS"}),
            ("聚焦消息输入框", "FOCUS_ELEMENT", {"elementKey": "MESSAGE_INPUT"}),
        ):
            service, conversation, action = await _scenario(message, expected_type, params)
            receipt = AssistantActionReceipt(
                action_id=action.action_id,
                status=UiActionReceiptStatus.SUCCEEDED,
                current_route="assistant",
            )
            result = await service.record_action_receipt(
                conversation.conversation_id, "user-1", receipt
            )
            assert result.action_id == action.action_id
            assert result.status == UiActionReceiptStatus.SUCCEEDED

    asyncio.run(run())


def test_receipt_for_foreign_owner_is_rejected() -> None:
    async def run() -> None:
        service, conversation, action = await _scenario(
            "刷新错题集", "REFRESH_RESOURCE", {"resourceKey": "WRONG_QUESTIONS"}
        )
        receipt = AssistantActionReceipt(
            action_id=action.action_id,
            status=UiActionReceiptStatus.SUCCEEDED,
            current_route="assistant",
        )
        with pytest.raises(
            (AssistantActionNotFoundError, AssistantConversationNotFoundError)
        ):
            await service.record_action_receipt(
                conversation.conversation_id, "user-2", receipt
            )

    asyncio.run(run())
