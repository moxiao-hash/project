"""不包含用户、提示词或密钥标签的模型调用指标。"""

import logging
from collections.abc import Iterator
from contextlib import contextmanager
from threading import Lock
from time import monotonic
from typing import Any
from uuid import UUID

from langchain_core.callbacks import BaseCallbackHandler
from prometheus_client import REGISTRY, CollectorRegistry, Counter, Histogram

from app.core.request_context import current_request_id

logger = logging.getLogger(__name__)

class ModelMetrics:
    """只使用 provider/model/status 三个有界标签，避免指标基数爆炸。"""

    def __init__(self, registry: CollectorRegistry = REGISTRY) -> None:
        self.requests = Counter(
            "studypilot_model_requests",
            "Model calls completed by status.",
            ("provider", "model", "status"),
            registry=registry,
        )
        self.duration = Histogram(
            "studypilot_model_request_duration_seconds",
            "Model call duration in seconds.",
            ("provider", "model"),
            registry=registry,
        )

    @contextmanager
    def observe(self, *, provider: str, model: str) -> Iterator[None]:
        started = monotonic()
        try:
            yield
        except BaseException:
            self.requests.labels(provider, model, "error").inc()
            raise
        else:
            self.requests.labels(provider, model, "success").inc()
        finally:
            self.duration.labels(provider, model).observe(monotonic() - started)


MODEL_METRICS = ModelMetrics()


class ModelMetricsCallback(BaseCallbackHandler):
    """适用于普通及 ``with_structured_output`` 的 LangChain 回调。"""

    def __init__(self, provider: str, model: str, metrics: ModelMetrics = MODEL_METRICS) -> None:
        self._provider = provider
        self._model = model
        self._metrics = metrics
        self._started: dict[UUID, float] = {}
        self._lock = Lock()

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
        self._finish(run_id, "success")

    def on_llm_error(self, error: BaseException, *, run_id: UUID, **kwargs: Any) -> None:
        self._finish(run_id, "error")

    def _finish(self, run_id: UUID, status: str) -> None:
        with self._lock:
            started = self._started.pop(run_id, monotonic())
        self._metrics.requests.labels(self._provider, self._model, status).inc()
        self._metrics.duration.labels(self._provider, self._model).observe(
            monotonic() - started
        )
        logger.info(
            "model.request.completed requestId=%s provider=%s model=%s status=%s",
            current_request_id() or "background",
            self._provider,
            self._model,
            status,
        )
