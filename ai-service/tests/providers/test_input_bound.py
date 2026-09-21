"""预占输入上界：确定性、保守，且绝不因深度或类型而静默丢内容。"""

from collections import OrderedDict

import pytest
from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage

from app.providers.input_bound import UnboundedInputError, input_token_upper_bound


def _bytes(text: str) -> int:
    return len(text.encode("utf-8"))


def test_plain_text_is_counted_as_utf8_bytes():
    assert input_token_upper_bound("abc") == 3
    assert input_token_upper_bound("学习计划") == _bytes("学习计划")


def test_deeply_nested_content_is_never_dropped():
    marker = "深层内容不可丢弃"
    payload: dict = {"level": {}}
    cursor = payload["level"]
    for _ in range(20):
        cursor["next"] = {}
        cursor = cursor["next"]
    cursor["leaf"] = marker

    assert input_token_upper_bound(payload) >= _bytes(marker)


def test_bound_grows_with_additional_nested_content():
    plain = [HumanMessage(content="问题")]
    nested = [HumanMessage(content="问题", additional_kwargs={"a": {"b": "额外内容"}})]

    assert input_token_upper_bound(nested) > input_token_upper_bound(plain)


def test_empty_system_and_tool_messages_still_contribute_framing():
    assert input_token_upper_bound([HumanMessage(content="")]) > 0
    assert input_token_upper_bound([SystemMessage(content="")]) > 0
    assert input_token_upper_bound([ToolMessage(content="", tool_call_id="call-1")]) > 0


def test_message_roles_are_part_of_the_bound():
    system = input_token_upper_bound([SystemMessage(content="x")])
    human = input_token_upper_bound([HumanMessage(content="x")])

    assert system != human


def test_mappings_and_nested_tool_calls_are_counted():
    with_tool_call = HumanMessage(content="问题", tool_calls=[
        {"name": "learning.context.get", "args": {"nested": {"deep": "深层参数"}}, "id": "call-1"},
    ])

    assert input_token_upper_bound([with_tool_call]) > input_token_upper_bound(
        [HumanMessage(content="问题")]
    )
    assert input_token_upper_bound([with_tool_call]) >= _bytes("深层参数")


def test_bytes_are_counted_without_decoding_failures():
    raw = b"\xff\xfe\x00binary"

    assert input_token_upper_bound(raw) >= len(raw)
    assert input_token_upper_bound([HumanMessage(content="x"), raw]) >= len(raw)


def test_unknown_objects_fail_closed_instead_of_using_repr():
    class Opaque:
        __slots__ = ()

    with pytest.raises(UnboundedInputError):
        input_token_upper_bound([Opaque()])


def test_bound_is_deterministic_across_repeated_calls():
    payload = [SystemMessage(content="你是学习助手"), HumanMessage(content="继续昨天的章节")]
    first = input_token_upper_bound(payload)

    assert all(input_token_upper_bound(payload) == first for _ in range(5))
    same_shape = [SystemMessage(content="你是学习助手"), HumanMessage(content="继续昨天的章节")]
    assert input_token_upper_bound(same_shape) == first


def test_cycles_do_not_hang_and_stay_bounded():
    cyclic: list = ["guard"]
    cyclic.append(cyclic)

    assert input_token_upper_bound(cyclic) >= _bytes("guard")


def test_nested_containers_are_counted_and_never_dropped():
    payload = OrderedDict([("a", ["x", "y", {"b": ["z"]}])])

    assert input_token_upper_bound(payload) >= _bytes("xyz")


def test_bound_never_undercounts_the_sum_of_leaf_bytes():
    payload = [
        SystemMessage(content="系统提示"),
        HumanMessage(content="用户问题"),
        {"role": "user", "content": "字典消息"},
    ]
    leaves = (
        _bytes("系统提示")
        + _bytes("用户问题")
        + _bytes("字典消息")
        + _bytes("role")
        + _bytes("user")
        + _bytes("content")
    )

    assert input_token_upper_bound(payload) >= leaves
