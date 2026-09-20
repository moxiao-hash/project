"""用量回调的有界去重与 FastAPI 关停排空。"""

import asyncio
import base64
from pathlib import Path
from uuid import uuid4

import pytest
from pydantic import SecretStr

from app import main as main_module
from app.core.settings import Settings
from app.observability.usage import (
    AssistantUsageReporter,
    ModelUsageCallback,
    drain_pending_usage_reports,
    pending_usage_report_count,
    reservation_scope,
)

pytestmark = pytest.mark.anyio


class _ChatMessage:
    def __init__(self, usage_metadata):
        self.usage_metadata = usage_metadata


class _Generation:
    def __init__(self, usage_metadata):
        self.message = _ChatMessage(usage_metadata)


class _Response:
    def __init__(self, prompt_tokens: int = 1) -> None:
        self.llm_output = {"token_usage": {"prompt_tokens": prompt_tokens, "completion_tokens": 1}}
        self.generations = [[_Generation(None)]]


class GatedJava:
    """记录上报；在 gate 打开前阻塞，用于验证关停排空确实等待了未完成上报。"""

    def __init__(self) -> None:
        self.payloads: list[dict] = []
        self.gate = asyncio.Event()

    async def record_assistant_usage(self, payload: dict) -> dict:
        await self.gate.wait()
        self.payloads.append(payload)
        return {"usageId": payload["usageId"], "duplicate": False}


def _callback(java, *, max_tracked_runs: int = 512) -> ModelUsageCallback:
    return ModelUsageCallback(
        provider="deepseek",
        model="deepseek-flash",
        reporter=AssistantUsageReporter(java, backoff_seconds=0),
        owner_id="owner-1",
        purpose="KNOWLEDGE_QA",
        max_tracked_runs=max_tracked_runs,
    )


async def test_reported_run_tracking_is_bounded_and_keeps_recent_dedup():
    java = GatedJava()
    java.gate.set()
    callback = _callback(java, max_tracked_runs=2)

    run_ids = [uuid4() for _ in range(3)]
    for run_id in run_ids:
        callback.on_llm_start({}, ["prompt"], run_id=run_id)
        callback.on_llm_end(_Response(), run_id=run_id)
    await callback.drain()

    # 三次调用都上报，但去重表只保留最近两个 run，不会随长驻模型无限增长。
    assert len(java.payloads) == 3
    assert callback.tracked_run_count == 2

    # 最近的 run 仍会被去重，不会重复计费。
    callback.on_llm_end(_Response(), run_id=run_ids[-1])
    await callback.drain()
    assert len(java.payloads) == 3
    assert callback.tracked_run_count == 2


async def test_reservation_id_is_used_as_the_usage_id():
    java = GatedJava()
    java.gate.set()
    callback = _callback(java)
    run_id = uuid4()
    callback.on_llm_start({}, ["prompt"], run_id=run_id)
    with reservation_scope("reservation-42"):
        callback.on_llm_end(_Response(), run_id=run_id)
        await callback.drain()

    assert [payload["usageId"] for payload in java.payloads] == ["reservation-42"]


async def test_pending_usage_reports_are_drained():
    java = GatedJava()
    callback = _callback(java)
    run_id = uuid4()
    callback.on_llm_start({}, ["prompt"], run_id=run_id)
    callback.on_llm_end(_Response(prompt_tokens=7), run_id=run_id)

    assert pending_usage_report_count() >= 1
    java.gate.set()
    await drain_pending_usage_reports()

    assert pending_usage_report_count() == 0
    assert [payload["promptTokens"] for payload in java.payloads] == [7]


async def test_lifespan_drains_pending_usage_reports_on_shutdown(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = Settings(
        _env_file=None,
        agent_state_db_path=str(tmp_path / "agent-state.sqlite3"),
        langgraph_aes_key=SecretStr(base64.b64encode(bytes(range(32))).decode()),
        agent_worker_count=1,
    )
    monkeypatch.setattr(main_module, "get_settings", lambda: settings)

    java = GatedJava()
    callback = _callback(java)
    application = main_module.FastAPI()

    async with main_module.lifespan(application):
        run_id = uuid4()
        callback.on_llm_start({}, ["prompt"], run_id=run_id)
        callback.on_llm_end(_Response(prompt_tokens=9), run_id=run_id)
        # 上报任务仍在等待 gate；lifespan 退出时必须先排空它。
        assert pending_usage_report_count() >= 1
        java.gate.set()

    assert pending_usage_report_count() == 0
    assert [payload["promptTokens"] for payload in java.payloads] == [9]
