"""模型调用的用量采集、幂等上报与失败可观测性。

设计要点：

- 每个真实模型调用由 LangChain 回调采集，只使用有界标签，不记录提示词、正文或密钥。
- 一次逻辑调用使用稳定的 ``usageId``（由 turn/purpose/run_id 决定），上报重试与重复
  回调都落到同一主键，Java 侧幂等落库，不会重复计费。
- 上报失败只能影响可观测性，绝不能把一次成功的模型调用改判为失败：``report`` 内部
  捕获全部异常并计入 Prometheus 指标与日志。
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from contextvars import ContextVar, Token
from dataclasses import dataclass
from datetime import UTC, datetime
from threading import Lock
from time import monotonic
from typing import Any
from uuid import UUID, uuid4, uuid5

from langchain_core.callbacks import BaseCallbackHandler
from prometheus_client import REGISTRY, CollectorRegistry, Counter

from app.core.request_context import current_request_id
from app.observability.model_metrics import extract_model_usage

logger = logging.getLogger(__name__)

USAGE_ID_NAMESPACE = UUID("6f1d0f9c-3a2e-4b8e-9a6f-6c2f0f3b9d41")


class ModelPurpose:
    """模型调用用途；写进用量记录，便于按功能拆分成本。"""

    AGENT_PLANNING = "AGENT_PLANNING"
    AGENT_TOOL_LOOP = "AGENT_TOOL_LOOP"
    KNOWLEDGE_QA = "KNOWLEDGE_QA"
    QUIZ_GENERATION = "QUIZ_GENERATION"
    QUIZ_GRADING = "QUIZ_GRADING"
    CODE_EVALUATION = "CODE_EVALUATION"
    RUBRIC_SCORING = "RUBRIC_SCORING"
    MATERIAL_ANALYSIS = "MATERIAL_ANALYSIS"
    PLAN_ADJUSTMENT = "PLAN_ADJUSTMENT"
    TASK_RECOGNITION = "TASK_RECOGNITION"
    TEACHING_QA = "TEACHING_QA"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class UsageScope:
    """一次模型调用所属的 owner、会话与轮次；``purpose`` 缺省时由回调静态值补齐。"""

    owner_id: str
    conversation_id: str = "background"
    turn_id: str | None = None
    execution_id: str | None = None
    purpose: str | None = None
    max_output_tokens: int | None = None


_current_scope: ContextVar[UsageScope | None] = ContextVar("model_usage_scope", default=None)


@contextmanager
def usage_scope(scope: UsageScope) -> Iterator[UsageScope]:
    """绑定当前异步上下文的用量归属；退出时恢复，避免串到后续任务。"""

    token: Token[UsageScope | None] = _current_scope.set(scope)
    try:
        yield scope
    finally:
        _current_scope.reset(token)


def current_usage_scope() -> UsageScope | None:
    return _current_scope.get()


def bind_max_output_tokens(runnable: Any) -> Any:
    """把当前轮的输出上限绑定到模型/结构化链；没有上限时原样返回。"""

    scope = current_usage_scope()
    if scope is None or not scope.max_output_tokens:
        return runnable
    return runnable.bind(max_tokens=scope.max_output_tokens)


def _normalized_turn_id(scope: UsageScope) -> str:
    candidate = scope.turn_id or current_request_id() or str(uuid4())
    return candidate[:120]


def build_usage_id(
    *,
    owner_id: str,
    conversation_id: str,
    turn_id: str,
    purpose: str,
    run_id: UUID | str,
) -> str:
    """由归属 + 轮次 + 用途 + LangChain run 生成稳定且不超过 36 字符的幂等键。"""

    seed = f"{owner_id}|{conversation_id}|{turn_id}|{purpose}|{run_id}"
    return str(uuid5(USAGE_ID_NAMESPACE, seed))


class UsageReportMetrics:
    """上报结果的低基数指标：成功、重试、失败。"""

    def __init__(self, registry: CollectorRegistry = REGISTRY) -> None:
        self.reports = Counter(
            "studypilot_usage_reports_total",
            "Assistant usage reports by outcome.",
            ("outcome",),
            registry=registry,
        )


USAGE_REPORT_METRICS = UsageReportMetrics()


class AssistantUsageReporter:
    """把采集到的用量上报 Java；``report`` 永不抛出，重试后仍失败只记指标与日志。"""

    def __init__(
        self,
        java: Any,
        *,
        max_attempts: int = 3,
        backoff_seconds: float = 0.05,
        metrics: UsageReportMetrics = USAGE_REPORT_METRICS,
    ) -> None:
        self._java = java
        self._max_attempts = max(1, max_attempts)
        self._backoff_seconds = backoff_seconds
        self._metrics = metrics

    async def report(self, payload: dict[str, Any]) -> bool:
        usage_id = payload.get("usageId")
        for attempt in range(1, self._max_attempts + 1):
            try:
                await self._java.record_assistant_usage(payload)
                self._metrics.reports.labels("success").inc()
                return True
            except Exception as exc:  # noqa: BLE001 - 上报失败不得影响模型结果
                last_error = exc
                if attempt < self._max_attempts:
                    self._metrics.reports.labels("retry").inc()
                    logger.warning(
                        "assistant.usage.report.retry usageId=%s attempt=%s error=%s",
                        usage_id,
                        attempt,
                        type(exc).__name__,
                    )
                    if self._backoff_seconds:
                        await asyncio.sleep(self._backoff_seconds * attempt)
        self._metrics.reports.labels("failure").inc()
        logger.error(
            "assistant.usage.report.failed usageId=%s attempts=%s error=%s",
            usage_id,
            self._max_attempts,
            type(last_error).__name__,
        )
        return False


class ModelUsageCallback(BaseCallbackHandler):
    """从 LangChain 回调采集用量并异步上报。

    ``owner_id``/``purpose`` 来自模型构造时的 owner 归属；若调用方用
    :func:`usage_scope` 绑定了更具体的会话与轮次，则优先使用它。
    """

    def __init__(
        self,
        *,
        provider: str,
        model: str,
        reporter: AssistantUsageReporter | None,
        owner_id: str | None = None,
        purpose: str = ModelPurpose.UNKNOWN,
        scope_provider: Callable[[], UsageScope | None] = current_usage_scope,
    ) -> None:
        self._provider = provider
        self._model = model
        self._reporter = reporter
        self._owner_id = owner_id
        self._purpose = purpose
        self._scope_provider = scope_provider
        self._started: dict[UUID, float] = {}
        self._reported: set[UUID] = set()
        self._pending: set[asyncio.Task[Any]] = set()
        self._lock = Lock()

    @property
    def owner_id(self) -> str | None:
        return self._owner_id

    @property
    def purpose(self) -> str:
        return self._purpose

    def on_llm_start(
        self,
        serialized: dict[str, Any],
        prompts: list[str],
        *,
        run_id: UUID,
        **kwargs: Any,
    ) -> None:
        # prompts 是不可信正文，绝不写日志或指标标签。
        with self._lock:
            self._started[run_id] = monotonic()

    def on_llm_end(self, response: Any, *, run_id: UUID, **kwargs: Any) -> None:
        self._emit(run_id, extract_model_usage(response), status="SUCCEEDED")

    def on_llm_error(self, error: BaseException, *, run_id: UUID, **kwargs: Any) -> None:
        self._emit(run_id, None, status="FAILED")

    def _emit(self, run_id: UUID, sample: Any, *, status: str) -> None:
        scope = self._scope_provider()
        owner_id = (scope.owner_id if scope is not None else None) or self._owner_id
        if owner_id is None or self._reporter is None:
            # 没有 owner 归属时无法安全落库；记日志暴露未接入的边界。
            logger.warning(
                "assistant.usage.unattributed model=%s purpose=%s", self._model, self._purpose
            )
            return
        purpose = (scope.purpose if scope is not None else None) or self._purpose
        conversation_id = (
            scope.conversation_id if scope is not None else "background"
        ) or "background"
        turn_id = _normalized_turn_id(scope) if scope is not None else (
            current_request_id() or str(uuid4())
        )
        usage_id = build_usage_id(
            owner_id=owner_id,
            conversation_id=conversation_id,
            turn_id=turn_id,
            purpose=purpose,
            run_id=run_id,
        )
        with self._lock:
            started = self._started.pop(run_id, monotonic())
            if run_id in self._reported:
                return
            self._reported.add(run_id)
        latency_ms = max(0, int((monotonic() - started) * 1000))
        payload = {
            "usageId": usage_id,
            "ownerId": owner_id,
            "conversationId": conversation_id,
            "turnId": turn_id,
            "executionId": scope.execution_id if scope is not None else None,
            "provider": self._provider,
            "modelName": self._model,
            "purpose": purpose,
            "status": status,
            "promptTokens": getattr(sample, "prompt_tokens", 0),
            "cachedPromptTokens": getattr(sample, "cached_prompt_tokens", 0),
            "completionTokens": getattr(sample, "completion_tokens", 0),
            "reasoningTokens": getattr(sample, "reasoning_tokens", None),
            "latencyMs": latency_ms,
            "occurredAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        }
        self._dispatch(payload)

    def _dispatch(self, payload: dict[str, Any]) -> None:
        """派发上报。

        LangChain 可能在事件循环线程之外的执行器线程里调用同步回调；此时没有运行中的
        循环，就在当前线程用一次性循环同步上报。上报失败已在 reporter 内部吞掉，
        不会改变模型调用的成败。
        """

        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            asyncio.run(self._reporter.report(payload))
            return
        task = loop.create_task(self._reporter.report(payload))
        self._pending.add(task)
        task.add_done_callback(self._pending.discard)

    async def drain(self) -> None:
        """等待已派发的上报任务完成；供测试与优雅关停使用。"""

        if self._pending:
            await asyncio.gather(*tuple(self._pending), return_exceptions=True)


__all__ = [
    "AssistantUsageReporter",
    "ModelPurpose",
    "ModelUsageCallback",
    "UsageReportMetrics",
    "UsageScope",
    "bind_max_output_tokens",
    "build_usage_id",
    "current_usage_scope",
    "usage_scope",
]
