"""预占输入上界：确定性，且对字节级 BPE 是严格上界。"""

from langchain_core.messages import HumanMessage, SystemMessage

from app.providers.input_bound import input_token_upper_bound


class _PromptValue:
    def __init__(self, text: str) -> None:
        self.text = text

    def __repr__(self) -> str:
        return f"PromptValue({self.text!r})"


def test_bound_is_the_utf8_byte_length_of_plain_prompt_text():
    assert input_token_upper_bound("abc") == 3
    # 结构化消息还会把 role/content 等键算进去，只会更保守，不会更小。
    assert input_token_upper_bound([{"role": "user", "content": "abc"}]) >= 3


def test_bound_never_underestimates_multibyte_text():
    text = "学习计划" * 10
    assert input_token_upper_bound(text) == len(text.encode("utf-8"))
    # 中文字符 3 字节，字节数严格大于字符数，因此也严格大于任何等量的 token 数。
    assert input_token_upper_bound(text) > len(text)


def test_bound_covers_langchain_messages_including_tool_calls():
    plain = [HumanMessage(content="问题")]
    with_tools = [HumanMessage(content="问题", tool_calls=[
        {"name": "learning.context.get", "args": {"nodeId": "n1"}, "id": "call-1"},
    ])]

    assert input_token_upper_bound(plain) >= len("问题".encode())
    assert input_token_upper_bound(with_tools) > input_token_upper_bound(plain)


def test_bound_is_deterministic_for_the_same_request():
    payload = [
        SystemMessage(content="你是学习助手"),
        HumanMessage(content="继续昨天的章节"),
    ]
    assert input_token_upper_bound(payload) == input_token_upper_bound(payload)


def test_empty_inputs_bound_to_zero_and_unknown_objects_are_not_ignored():
    assert input_token_upper_bound(None) == 0
    assert input_token_upper_bound([]) == 0
    assert input_token_upper_bound(_PromptValue("hello")) >= len("hello")
