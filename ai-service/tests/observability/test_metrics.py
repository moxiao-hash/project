import logging
from uuid import uuid4

from prometheus_client import CollectorRegistry

from app.core.request_context import bind_request_id, reset_request_id
from app.observability.model_metrics import ModelMetrics, ModelMetricsCallback


def test_model_metrics_use_only_bounded_labels() -> None:
    registry = CollectorRegistry()
    metrics = ModelMetrics(registry=registry)

    with metrics.observe(provider="deepseek", model="deepseek-v4-pro"):
        pass
    try:
        with metrics.observe(provider="deepseek", model="deepseek-v4-pro"):
            raise RuntimeError("upstream")
    except RuntimeError:
        pass

    samples = {
        sample.name: sample
        for metric in registry.collect()
        for sample in metric.samples
        if sample.name == "studypilot_model_requests_total"
    }
    statuses = {
        sample.labels["status"]: sample.value
        for metric in registry.collect()
        for sample in metric.samples
        if sample.name == "studypilot_model_requests_total"
    }
    assert samples
    assert statuses == {"success": 1.0, "error": 1.0}
    assert all(
        set(sample.labels) == {"provider", "model", "status"}
        for metric in registry.collect()
        for sample in metric.samples
        if sample.name == "studypilot_model_requests_total"
    )


def test_model_callback_logs_only_safe_correlation_fields(caplog) -> None:
    callback = ModelMetricsCallback(
        "deepseek",
        "deepseek-test",
        metrics=ModelMetrics(registry=CollectorRegistry()),
    )
    run_id = uuid4()
    token = bind_request_id("req-model-9")
    try:
        callback.on_llm_start({}, ["do-not-log-this-prompt"], run_id=run_id)
        with caplog.at_level(logging.INFO):
            callback.on_llm_end(object(), run_id=run_id)
    finally:
        reset_request_id(token)

    assert "req-model-9" in caplog.text
    assert "deepseek-test" in caplog.text
    assert "do-not-log-this-prompt" not in caplog.text
