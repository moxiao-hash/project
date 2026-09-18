"""模型用量采集：缓存/非缓存输入、输出与可缺失的 reasoning token。"""

from app.observability.model_metrics import extract_model_usage


class _ChatMessage:
    """对应 LangChain 的 AIMessage。"""

    def __init__(self, usage_metadata):
        self.usage_metadata = usage_metadata


class _Generation:
    """对应 LangChain 的 ChatGeneration：usage 挂在 .message 上。"""

    def __init__(self, usage_metadata):
        self.message = _ChatMessage(usage_metadata)


class _Response:
    """对应 LangChain 的 LLMResult。"""

    def __init__(self, llm_output=None, usage_metadata=None):
        self.llm_output = llm_output
        self.generations = [[_Generation(usage_metadata)]] if usage_metadata is not None else []


def test_reads_deepseek_cache_fields_from_llm_output():
    usage = extract_model_usage(_Response(llm_output={"token_usage": {
        "prompt_tokens": 1000,
        "completion_tokens": 200,
        "total_tokens": 1200,
        "prompt_cache_hit_tokens": 400,
        "prompt_cache_miss_tokens": 600,
    }}))
    assert usage is not None
    assert usage.prompt_tokens == 1000
    assert usage.cached_prompt_tokens == 400
    assert usage.uncached_prompt_tokens == 600
    assert usage.completion_tokens == 200
    assert usage.reasoning_tokens is None


def test_reads_usage_metadata_and_optional_reasoning_tokens():
    usage = extract_model_usage(_Response(usage_metadata={
        "input_tokens": 10,
        "output_tokens": 5,
        "input_token_details": {"cache_read": 2},
        "output_token_details": {"reasoning": 3},
    }))
    assert usage is not None
    assert usage.prompt_tokens == 10
    assert usage.cached_prompt_tokens == 2
    assert usage.completion_tokens == 5
    assert usage.reasoning_tokens == 3


def test_missing_reasoning_details_stay_none():
    usage = extract_model_usage(_Response(usage_metadata={
        "input_tokens": 10, "output_tokens": 5,
    }))
    assert usage is not None
    assert usage.reasoning_tokens is None


def test_response_without_usage_is_not_estimated():
    assert extract_model_usage(_Response(llm_output={})) is None
    assert extract_model_usage(_Response()) is None
