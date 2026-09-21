"""预占用的请求输入 token 上界。

预占发生在真实 provider 调用之前，输入 token 数无法精确得知，只能给上界。这里给出一个
**确定性、保守、且绝不因深度或类型而静默丢内容**的上界：

- 递归遍历整个请求结构（用显式栈，不设深度上限），把所有字符串按 UTF-8 字节数、
  字节串按长度、数字按其十进制/字面表示计入；
- 每个结构节点额外计入固定的框架开销（消息 role/分隔符/特殊 token、容器标点等），
  这些常量同时是"至少这么多 token"的余量；
- 无法确定性枚举字段的对象（既没有已知消息字段，也没有 ``__dict__``）会让
  :func:`input_token_upper_bound` 抛 :class:`UnboundedInputError`，由调用方失败关闭，
  而不是退回可能包含内存地址的 ``repr``。

依据：DeepSeek 使用字节级 BPE，词表里每个 token 至少覆盖一个字节，因此
``token 数 ≤ UTF-8 字节数``。把请求里**所有**会发送给 provider 的文本都数进来，
再加上框架开销，得到的值就是这次请求 token 数的上界；只会高估，不会低估。
代价是极大的输入可能在上界上被提前拒绝（fail closed），这是刻意的取舍。

本模块只产出数字，不保存也不记录提示词、正文或密钥。
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

# 每条消息的 role/分隔符/特殊 token 开销（字节当量，同时也是 token 余量）。
_FRAMING_PER_MESSAGE = 16
# 容器里每个元素/键值对的分隔开销。
_FRAMING_PER_ENTRY = 8
# 每个容器自身的标点开销。
_FRAMING_PER_CONTAINER = 2

# 消息/提示对象上需要计入的已知字段（LangChain 消息与 OpenAI 兼容字典都覆盖）。
_KNOWN_FIELDS = (
    "type",
    "role",
    "name",
    "content",
    "tool_calls",
    "invalid_tool_calls",
    "tool_call_id",
    "function_call",
    "additional_kwargs",
    "messages",
    "text",
)


class UnboundedInputError(RuntimeError):
    """输入包含无法确定性枚举的对象，给不出可信上界；调用方必须失败关闭。

    只暴露类型名，不包含对象内容或 repr，避免把正文带进日志或错误信息。
    """

    def __init__(self, type_name: str) -> None:
        super().__init__(f"输入包含无法确定边界的对象类型：{type_name}")
        self.type_name = type_name


def input_token_upper_bound(payload: Any) -> int:
    """返回请求输入 token 的保守上界（UTF-8 字节当量）。

    对结构相同、内容相同的输入始终返回同一个值；不做任何依赖分词器或网络的推断。
    遇到无法枚举的对象抛 :class:`UnboundedInputError`。
    """

    total = 0
    active: set[int] = set()
    stack: list[tuple[Any, bool]] = [(payload, False)]
    while stack:
        value, leaving = stack.pop()
        if leaving:
            active.discard(id(value))
            continue
        if value is None:
            continue
        if isinstance(value, bool):
            total += len("false")
            continue
        if isinstance(value, str):
            total += len(value.encode("utf-8", "surrogatepass"))
            continue
        if isinstance(value, (bytes, bytearray, memoryview)):
            total += len(bytes(value))
            continue
        if isinstance(value, int):
            total += len(str(value))
            continue
        if isinstance(value, float):
            total += len(repr(value))
            continue
        marker = id(value)
        if isinstance(value, Mapping):
            if marker in active:
                total += _FRAMING_PER_CONTAINER
                continue
            active.add(marker)
            stack.append((value, True))
            total += _FRAMING_PER_CONTAINER
            for key, item in value.items():
                total += _FRAMING_PER_ENTRY
                stack.append((item, False))
                stack.append((key, False))
            continue
        if isinstance(value, (list, tuple, set, frozenset)):
            if marker in active:
                total += _FRAMING_PER_CONTAINER
                continue
            active.add(marker)
            stack.append((value, True))
            total += _FRAMING_PER_CONTAINER
            for item in value:
                total += _FRAMING_PER_ENTRY
                stack.append((item, False))
            continue
        fields = _enumerable_fields(value)
        if fields is None:
            raise UnboundedInputError(type(value).__name__)
        total += _FRAMING_PER_MESSAGE
        if marker in active:
            continue
        active.add(marker)
        stack.append((value, True))
        for field in fields:
            stack.append((field, False))
    return total


def _enumerable_fields(value: Any) -> list[Any] | None:
    """列出对象上需要计入的字段；无法枚举时返回 ``None``（调用方失败关闭）。"""

    fields: list[Any] = []
    for name in _KNOWN_FIELDS:
        try:
            present = hasattr(value, name)
        except Exception:  # noqa: BLE001 - 属性访问抛错说明对象不可确定性枚举
            return None
        if present:
            try:
                fields.append(getattr(value, name))
            except Exception:  # noqa: BLE001 - 同上
                return None
    state = getattr(value, "__dict__", None)
    if isinstance(state, Mapping):
        for key, item in state.items():
            if isinstance(key, str) and key.startswith("_"):
                continue
            fields.append(key)
            fields.append(item)
    return fields or None


__all__ = ["UnboundedInputError", "input_token_upper_bound"]
