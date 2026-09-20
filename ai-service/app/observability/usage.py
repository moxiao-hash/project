"""模型调用的用量采集、幂等上报与失败可观测性。

设计要点：

- 每个真实模型调用由 LangChain 回调采集，只使用有界标签，不记录提示词、正文或密钥。
- 一次逻辑调用使用稳定的 ``usageId``：正常来自预算预占许可（``reservationId``），
  没有预占时退化为 ``turn/purpose/run_id`` 派生的 uuid5；上报重试与重复回调都落到
  同一主键，Java 侧幂等落库，不会重复计费。
- 上报失败只能影响可观测性，绝不能把一次成功的模型调用改判为失败：``report`` 内部
  捕获全部异常并计入 Prometheus 指标与日志。
- 长生命周期模型客户端里的重复回调跟踪必须有界：``ModelUsageCallback`` 只保留最近
  ``max_tracked_runs`` 个 run，淘汰后仍能正常上报新调用。
- 派发出去但尚未完成的上报登记在进程级集合里，FastAPI 关停时统一排空，避免一次
  已经成功的模型调用因为进程退出而丢失。
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

# 默认保留最近 512 个 run 的去重/计时记录；长驻的缓存模型不会无限增长。
DEFAULT_TRACKED_RUNS = 512


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


_current_scope: ContextVar[UsageScope | None] = ContextVar("model_usage_scope", default=None)
# 当前 provider 调用持有的预占许可 id；用量回调据此生成与预占一致的幂等键。
_current_reservation: ContextVar[str | None] = ContextVar(
    "model_usage_reservation", default=None
)


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


@contextmanager
def reservation_scope(reservation_id: str | None) -> Iterator[None]:
    """绑定本次 provider 调用持有的预占许可；退出时恢复。"""

    token: Token[str | None] = _current_reservation.set(reservation_id)
    try:
        yield
    finally:
        _current_reservation.reset(token)


def current_reservation_id() -> str | None:
    return _current_reservation.get()


class _BoundedRunSet:
    """按插入顺序保留最近 ``max_entries`` 个 run_id 的去重集合。"""

    def __init__(self, max_entries: int) -> None:
        if max_entries < 1:
            raise ValueError("max_entries 必须为正整数")
        self._max_entries = max_entries
        self._entries: dict[UUID, None] = {}

    def mark(self, run_id: UUID) -> bool:
        """首次记录返回 ``True``；重复 run 返回 ``False`` 且不改变顺序。"""

        if run_id in self._entries:
            return False
        self._entries[run_id] = None
        while len(self._entries) > self._max_entries:
            self._entries.pop(next(iter(self._entries)))
        return True

    def __len__(self) -> int:
        return len(self._entries)


class _BoundedTiming:
    """按插入顺序保留最近 ``max_entries`` 个 run 的开始时间。"""

    def __init__(self, max_entries: int) -> None:
        self._max_entries = max_entries
        self._entries: dict[UUID, float] = {}

    def start(self, run_id: UUID) -> None:
        self._entries[run_id] = monotonic()
        while len(self._entries) > self._max_entries:
            self._entries.pop(next(iter(self._entries)))

    def pop(self, run_id: UUID, default: float) -> float:
        return self._entries.pop(run_id, default)

    def __len__(self) -> int:
        return len(self._entries)


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


# 进程级待完成上报：FastAPI 关停时排空，避免优雅停机丢掉成功调用的用量。
_PENDING_REPORTS: set[asyncio.Task[Any]] = set()
_PENDING_REPORTS_LOCK = Lock()


def _track_report(task: asyncio.Task[Any]) -> None:
    with _PENDING_REPORTS_LOCK:
        _PENDING_REPORTS.add(task)
    task.add_done_callback(_untrack_report)


def _untrack_report(task: asyncio.Task[Any]) -> None:
    with _PENDING_REPORTS_LOCK:
        _PENDING_REPORTS.discard(task)


def pending_usage_report_count() -> int:
    """当前仍未完成的上报数量；用于关停排空与测试。"""

    with _PENDING_REPORTS_LOCK:
        return sum(1 for task in _PENDING_REPORTS if not task.done())


async def drain_pending_usage_reports() -> None:
    """等待当前事件循环上所有已派发的用量上报完成。"""

    while True:
        loop = asyncio.get_running_loop()
        with _PENDING_REPORTS_LOCK:
            pending = [
                task
                for task in _PENDING_REPORTS
                if not task.done() and task.get_loop() is loop
            ]
        if not pending:
            return
        await asyncio.gather(*pending, return_exceptions=True)


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
    :func:`usage_scope` 绑定了更具体的会话与轮次，则优先使用它。当前 provider 调用
    若持有预占许可（:func:`reservation_scope`），上报使用同一 id，保证预占与用量
    幂等对应。
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
        reservation_provider: Callable[[], str | None] = current_reservation_id,
        max_tracked_runs: int = DEFAULT_TRACKED_RUNS,
    ) -> None:
        self._provider = provider
        self._model = model
        self._reporter = reporter
        self._owner_id = owner_id
        self._purpose = purpose
        self._scope_provider = scope_provider
        self._reservation_provider = reservation_provider
        self._started = _BoundedTiming(max_tracked_runs)
        self._reported = _BoundedRunSet(max_tracked_runs)
        self._pending: set[asyncio.Task[Any]] = set()
        self._lock = Lock()

    @property
    def owner_id(self) -> str | None:
        return self._owner_id

    @property
    def purpose(self) -> str:
        return self._purpose

    @property
    def tracked_run_count(self) -> int:
        """当前去重表里保留的 run 数量；用于验证长驻模型不会无限增长。"""

        with self._lock:
            return len(self._reported)

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
            self._started.start(run_id)

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
        reservation_id = self._reservation_provider()
        usage_id = reservation_id or build_usage_id(
            owner_id=owner_id,
            conversation_id=conversation_id,
            turn_id=turn_id,
            purpose=purpose,
            run_id=run_id,
        )
        with self._lock:
            started = self._started.pop(run_id, monotonic())
            if not self._reported.mark(run_id):
                return
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
        with self._lock:
            self._pending.add(task)
        task.add_done_callback(self._pending.discard)
        _track_report(task)

    async def drain(self) -> None:
        """等待本回调已派发的上报任务完成；供测试与优雅关停使用。"""

        with self._lock:
            pending = tuple(self._pending)
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)


__all__ = [
    "AssistantUsageReporter",
    "DEFAULT_TRACKED_RUNS",
    "ModelPurpose",
    "ModelUsageCallback",
    "UsageReportMetrics",
    "UsageScope",
    "build_usage_id",
    "current_reservation_id",
    "current_usage_scope",
    "drain_pending_usage_reports",
    "pending_usage_report_count",
    "reservation_scope",
    "usage_scope",
]
