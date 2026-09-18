"""不包含用户、提示词或密钥标签的模型调用指标。"""

import logging
from collections.abc import Iterator
from contextlib import contextmanager
from threading import Lock
from time import monotonic
from dataclasses import dataclass
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


@dataclass(frozen=True)
class ModelUsageSample:
    """一次模型调用的原始用量；不包含提示词、用户数据或密钥。"""

    prompt_tokens: int
    cached_prompt_tokens: int
    completion_tokens: int
    reasoning_tokens: int | None

    @property
    def uncached_prompt_tokens(self) -> int:
        return max(0, self.prompt_tokens - self.cached_prompt_tokens)


def _as_int(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return 0
    return int(value)


def _as_optional_int(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return int(value)


def _llm_output_token_usage(response: Any) -> dict[str, Any]:
    llm_output = getattr(response, "llm_output", None) or {}
    if not isinstance(llm_output, dict):
        return {}
    for key in ("token_usage", "usage"):
        candidate = llm_output.get(key)
        if isinstance(candidate, dict) and candidate:
            return candidate
    return {}


def _usage_metadata(response: Any) -> dict[str, Any]:
    generations = getattr(response, "generations", None) or []
    for batch in generations:
        for generation in batch or []:
            metadata = getattr(getattr(generation, "message", None), "usage_metadata", None)
            if isinstance(metadata, dict) and metadata:
                return metadata
    return {}


def extract_model_usage(response: Any) -> ModelUsageSample | None:
    """从 LangChain 响应中提取缓存/非缓存输入、输出与可缺失的 reasoning token。

    优先使用 OpenAI 兼容的 ``token_usage``（DeepSeek 在此返回
    ``prompt_cache_hit_tokens`` / ``prompt_cache_miss_tokens``），其次使用新版
    ``usage_metadata``。两者都没有时返回 ``None``，由调用方按"不可估算"处理。
    """

    token_usage = _llm_output_token_usage(response)
    if token_usage:
        prompt_tokens = _as_int(token_usage.get("prompt_tokens"))
        cached = _as_int(token_usage.get("prompt_cache_hit_tokens"))
        if cached == 0 and token_usage.get("prompt_cache_miss_tokens") is not None:
            cached = max(0, prompt_tokens - _as_int(token_usage.get("prompt_cache_miss_tokens")))
        return ModelUsageSample(
            prompt_tokens=prompt_tokens,
            cached_prompt_tokens=cached,
            completion_tokens=_as_int(token_usage.get("completion_tokens")),
            reasoning_tokens=None,
        )
    metadata = _usage_metadata(response)
    if not metadata:
        return None
    input_details = metadata.get("input_token_details") or {}
    output_details = metadata.get("output_token_details") or {}
    return ModelUsageSample(
        prompt_tokens=_as_int(metadata.get("input_tokens")),
        cached_prompt_tokens=_as_int(input_details.get("cache_read")),
        completion_tokens=_as_int(metadata.get("output_tokens")),
        reasoning_tokens=_as_optional_int(output_details.get("reasoning")),
    )
