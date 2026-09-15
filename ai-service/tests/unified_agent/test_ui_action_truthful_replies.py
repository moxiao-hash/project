"""Task 30 复查：受控界面动作的预回执回复不得宣称界面已生效。

服务端在浏览器返回终态 ``SUCCEEDED`` 回执之前只能确认“请求已下发”，因此
``OPEN_MODAL`` / ``PREFILL_FORM`` / ``REFRESH_RESOURCE`` / ``FOCUS_ELEMENT``
四类受控动作的预回执回复必须使用进行或将来措辞（“正在请求…”/“将…”），
并且动作 ``reason`` 也不能断言界面已经打开、聚焦或刷新。

本模块是先行失败测试：提交时四类动作中的弹窗、预填与聚焦仍带“已为你打开/已为你
聚焦”等完成断言，会在下列断言处失败；修正后才通过。
"""

import pytest

from app.unified_agent.ui_action_schema import UiActionType, resolve_ui_action_request

#: 冻结契约中的 16 个受控用户场景，覆盖全部四类非导航受控动作。
CONTROLLED_MESSAGES: tuple[str, ...] = (
    "打开新建目标弹窗",
    "打开新建计划弹窗",
    "打开资料导入面板",
    "预填目标：学习 Java 基础",
    "预填计划：第一阶段基础",
    "预填资料：Java 学习笔记",
    "刷新错题集",
    "刷新学习路线",
    "刷新今日任务",
    "刷新学习目标",
    "刷新学习计划",
    "刷新通知",
    "刷新掌握度",
    "刷新执行记录",
    "聚焦消息输入框",
    "聚焦计划标题",
)

#: 在真实终态回执之前，任何断言界面已经生效的措辞都不得出现。
PREMATURE_SUCCESS_MARKERS: tuple[str, ...] = (
    "已为你",
    "已经为你",
    "已打开",
    "已聚焦",
    "已刷新",
    "已经打开",
    "已经聚焦",
    "已成功",
    "已完成",
    "已生效",
)

#: 预回执回复必须带有进行或将来标记。
PENDING_MARKERS: tuple[str, ...] = ("正在", "将")


def _resolve(message: str):
    request = resolve_ui_action_request(message)
    assert request is not None, f"{message!r} 必须解析为受控界面动作"
    return request


@pytest.mark.parametrize("message", CONTROLLED_MESSAGES)
def test_pre_receipt_reply_uses_pending_wording(message: str) -> None:
    request = _resolve(message)
    reply = request.reply
    for marker in PREMATURE_SUCCESS_MARKERS:
        assert marker not in reply, (
            f"{message!r} 的预回执回复不得宣称界面已生效: {reply!r}"
        )
    assert any(marker in reply for marker in PENDING_MARKERS), (
        f"{message!r} 的预回执回复必须使用进行/将来措辞: {reply!r}"
    )


@pytest.mark.parametrize("message", CONTROLLED_MESSAGES)
def test_pre_receipt_reason_never_claims_success(message: str) -> None:
    request = _resolve(message)
    reason = request.reason
    for marker in PREMATURE_SUCCESS_MARKERS:
        assert marker not in reason, (
            f"{message!r} 的动作原因不得宣称界面已生效: {reason!r}"
        )


def test_controlled_messages_cover_every_non_navigation_family() -> None:
    """先行测试必须真正覆盖四类受控动作，而不是只测其中一族。"""

    covered = {_resolve(message).type for message in CONTROLLED_MESSAGES}
    assert covered == {
        UiActionType.OPEN_MODAL,
        UiActionType.PREFILL_FORM,
        UiActionType.REFRESH_RESOURCE,
        UiActionType.FOCUS_ELEMENT,
    }
    # 导航不在本次“预回执回复”修正范围内，必须仍与受控家族区分开。
    assert UiActionType.NAVIGATE not in covered


@pytest.mark.parametrize(
    ("message", "action_type", "route_key", "params"),
    [
        ("打开新建目标弹窗", "OPEN_MODAL", "LEARNING_GOALS", {"modalKey": "CREATE_GOAL"}),
        (
            "预填目标：学习 Java 基础",
            "PREFILL_FORM",
            "LEARNING_GOALS",
            {"formKey": "GOAL_FORM", "title": "学习 Java 基础"},
        ),
        (
            "刷新错题集",
            "REFRESH_RESOURCE",
            "WRONG_QUESTIONS",
            {"resourceKey": "WRONG_QUESTIONS"},
        ),
        (
            "聚焦消息输入框",
            "FOCUS_ELEMENT",
            "ASSISTANT",
            {"elementKey": "MESSAGE_INPUT"},
        ),
    ],
)
def test_copy_fix_preserves_frozen_wire_fields(
    message: str, action_type: str, route_key: str, params: dict[str, str]
) -> None:
    """文案修正只允许改 ``reply``/``reason``，冻结字段必须逐字保持。"""

    request = _resolve(message)
    assert request.type.value == action_type
    assert request.route_key == route_key
    assert request.params == params
