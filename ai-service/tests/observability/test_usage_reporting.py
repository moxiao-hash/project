"""用量采集与上报：缓存/非缓存输入、缺失 reasoning、重复回调与上报失败。"""

from uuid import uuid4

import pytest

from app.observability.usage import (
    AssistantUsageReporter,
    ModelPurpose,
    ModelUsageCallback,
    UsageScope,
    build_usage_id,
    usage_scope,
)


class _ChatMessage:
    def __init__(self, usage_metadata):
        self.usage_metadata = usage_metadata


class _Generation:
    def __init__(self, usage_metadata):
        self.message = _ChatMessage(usage_metadata)


class _Response:
    def __init__(self, llm_output=None, usage_metadata=None):
        self.llm_output = llm_output
        self.generations = [[_Generation(usage_metadata)]] if usage_metadata else []


class RecordingJava:
    def __init__(self, failures: int = 0) -> None:
        self.payloads: list[dict] = []
        self.failures = failures

    async def record_assistant_usage(self, payload: dict) -> dict:
        if self.failures > 0:
            self.failures -= 1
            raise RuntimeError("java unavailable")
        self.payloads.append(payload)
        return {"usageId": payload["usageId"], "duplicate": False}


def _callback(java: RecordingJava, owner_id: str | None = "owner-1", purpose: str = "KNOWLEDGE_QA"):
    return ModelUsageCallback(
        provider="deepseek",
        model="deepseek-flash",
        reporter=AssistantUsageReporter(java, backoff_seconds=0),
        owner_id=owner_id,
        purpose=purpose,
    )


@pytest.mark.anyio
async def test_cached_and_uncached_input_output_and_missing_reasoning_are_reported():
    java = RecordingJava()
    callback = _callback(java)
    run_id = uuid4()
    callback.on_llm_start({}, ["prompt"], run_id=run_id)
    callback.on_llm_end(
        _Response(llm_output={"token_usage": {
            "prompt_tokens": 1000,
            "completion_tokens": 200,
            "prompt_cache_hit_tokens": 400,
            "prompt_cache_miss_tokens": 600,
        }}),
        run_id=run_id,
    )
    with usage_scope(UsageScope(owner_id="owner-1", conversation_id="c", turn_id="t")):
        await callback.drain()
    assert len(java.payloads) == 1
    payload = java.payloads[0]
    assert payload["ownerId"] == "owner-1"
    assert payload["modelName"] == "deepseek-flash"
    assert payload["purpose"] == "KNOWLEDGE_QA"
    assert payload["status"] == "SUCCEEDED"
    assert payload["promptTokens"] == 1000
    assert payload["cachedPromptTokens"] == 400
    assert payload["completionTokens"] == 200
    assert payload["reasoningTokens"] is None
    assert payload["latencyMs"] >= 0
    assert payload["occurredAt"].endswith("Z")
    assert len(payload["usageId"]) == 36


@pytest.mark.anyio
async def test_reasoning_tokens_are_reported_as_provider_defined_subset():
    java = RecordingJava()
    callback = _callback(java)
    run_id = uuid4()
    callback.on_llm_start({}, ["prompt"], run_id=run_id)
    callback.on_llm_end(
        _Response(llm_output={"token_usage": {
            "prompt_tokens": 100,
            "completion_tokens": 80,
            "completion_tokens_details": {"reasoning_tokens": 30},
        }}),
        run_id=run_id,
    )
    await callback.drain()
    assert java.payloads[0]["reasoningTokens"] == 30
    assert java.payloads[0]["completionTokens"] == 80


@pytest.mark.anyio
async def test_duplicate_callbacks_for_same_run_report_once():
    java = RecordingJava()
    callback = _callback(java)
    run_id = uuid4()
    callback.on_llm_start({}, ["prompt"], run_id=run_id)
    response = _Response(llm_output={"token_usage": {
        "prompt_tokens": 10, "completion_tokens": 5,
    }})
    callback.on_llm_end(response, run_id=run_id)
    callback.on_llm_end(response, run_id=run_id)
    await callback.drain()
    assert len(java.payloads) == 1


@pytest.mark.anyio
async def test_model_failure_is_reported_as_failed_without_falsifying_success():
    java = RecordingJava()
    callback = _callback(java)
    run_id = uuid4()
    callback.on_llm_start({}, ["prompt"], run_id=run_id)
    callback.on_llm_error(RuntimeError("model down"), run_id=run_id)
    await callback.drain()
    assert len(java.payloads) == 1
    assert java.payloads[0]["status"] == "FAILED"
    assert java.payloads[0]["promptTokens"] == 0


@pytest.mark.anyio
async def test_scope_supplies_conversation_turn_and_purpose_override():
    java = RecordingJava()
    callback = _callback(java, owner_id=None)
    run_id = uuid4()
    scope = UsageScope(
        owner_id="owner-9",
        conversation_id="conversation-9",
        turn_id="turn-9",
        execution_id="execution-9",
        purpose=ModelPurpose.AGENT_PLANNING,
    )
    callback.on_llm_start({}, ["prompt"], run_id=run_id)
    with usage_scope(scope):
        callback.on_llm_end(
            _Response(llm_output={"token_usage": {"prompt_tokens": 1}}),
            run_id=run_id,
        )
        await callback.drain()
    payload = java.payloads[0]
    assert payload["ownerId"] == "owner-9"
    assert payload["conversationId"] == "conversation-9"
    assert payload["turnId"] == "turn-9"
    assert payload["executionId"] == "execution-9"
    assert payload["purpose"] == ModelPurpose.AGENT_PLANNING


@pytest.mark.anyio
async def test_reporter_retries_and_never_raises_on_failure():
    java = RecordingJava(failures=2)
    reporter = AssistantUsageReporter(java, max_attempts=3, backoff_seconds=0)
    assert await reporter.report({"usageId": "u1"}) is True
    assert len(java.payloads) == 1

    always_failing = AssistantUsageReporter(
        RecordingJava(failures=10), max_attempts=2, backoff_seconds=0
    )
    assert await always_failing.report({"usageId": "u2"}) is False


def test_usage_id_is_stable_for_the_same_logical_call():
    first = build_usage_id(
        owner_id="owner", conversation_id="c", turn_id="t",
        purpose="KNOWLEDGE_QA", run_id="run-1",
    )
    second = build_usage_id(
        owner_id="owner", conversation_id="c", turn_id="t",
        purpose="KNOWLEDGE_QA", run_id="run-1",
    )
    other = build_usage_id(
        owner_id="owner", conversation_id="c", turn_id="t",
        purpose="KNOWLEDGE_QA", run_id="run-2",
    )
    assert first == second
    assert first != other
    assert len(first) == 36


@pytest.mark.anyio
async def test_unattributed_call_is_not_reported():
    java = RecordingJava()
    callback = _callback(java, owner_id=None)
    run_id = uuid4()
    callback.on_llm_start({}, ["prompt"], run_id=run_id)
    callback.on_llm_end(_Response(llm_output={"token_usage": {"prompt_tokens": 1}}), run_id=run_id)
    await callback.drain()
    assert java.payloads == []


def test_sync_callback_without_event_loop_still_reports():
    """LangChain 会在执行器线程里调用同步回调；没有运行中的循环也必须上报。"""

    java = RecordingJava()
    callback = _callback(java)
    run_id = uuid4()
    callback.on_llm_start({}, ["prompt"], run_id=run_id)
    callback.on_llm_end(
        _Response(llm_output={"token_usage": {
            "prompt_tokens": 7, "completion_tokens": 3,
        }}),
        run_id=run_id,
    )
    assert len(java.payloads) == 1
    assert java.payloads[0]["promptTokens"] == 7
