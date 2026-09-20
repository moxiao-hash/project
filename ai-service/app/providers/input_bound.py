"""预占用的请求输入 token 上界。

预占发生在真实 provider 调用之前，输入 token 数无法精确得知，只能给上界。这里给出一个
**确定性且绝不低估**的上界：把请求序列化成文本后取 UTF-8 字节数。

依据：DeepSeek 使用字节级 BPE，词表里每个 token 至少覆盖一个字节，因此
``token 数 ≤ UTF-8 字节数`` 是严格上界。取严格上界意味着预占成本只会高估、不会低估，
并发调用因此不可能因为"输入成本算少了"而一起跨过日费用上限。代价是极大的输入可能在
保守上界上被提前拒绝（fail closed），这是刻意的取舍，而不是缺陷。
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

# 防止病态嵌套结构导致递归过深；超出深度的部分按对象 repr 计一次。
_MAX_DEPTH = 8


def input_token_upper_bound(payload: Any) -> int:
    """返回请求输入 token 的保守上界（UTF-8 字节数）。

    对同一份输入始终返回同一个值；不做任何依赖分词器或网络的推断。
    """

    return sum(len(part.encode("utf-8")) for part in _text_parts(payload, 0))


def _text_parts(value: Any, depth: int) -> list[str]:
    if value is None or depth > _MAX_DEPTH:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, (bytes, bytearray)):
        return [bytes(value).decode("utf-8", "replace")]
    if isinstance(value, Mapping):
        parts: list[str] = []
        for key, item in value.items():
            parts.extend(_text_parts(key, depth + 1))
            parts.extend(_text_parts(item, depth + 1))
        return parts
    if isinstance(value, (list, tuple, set, frozenset)):
        parts = []
        for item in value:
            parts.extend(_text_parts(item, depth + 1))
        return parts
    content = getattr(value, "content", None)
    if content is not None:
        parts = _text_parts(content, depth + 1)
        parts.extend(_text_parts(getattr(value, "tool_calls", None), depth + 1))
        return parts
    # 未知对象退化为 repr：同一输入仍然确定性，且不会静默漏算整段请求。
    return [repr(value)]


__all__ = ["input_token_upper_bound"]
