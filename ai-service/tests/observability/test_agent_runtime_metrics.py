from prometheus_client import CollectorRegistry

from app.observability.agent_metrics import AgentRuntimeMetrics


def test_agent_runtime_metrics_use_bounded_labels_and_track_results() -> None:
    registry = CollectorRegistry()
    metrics = AgentRuntimeMetrics(registry=registry)

    metrics.observe_turn(intent="NAVIGATION", status="success", duration_seconds=0.25)
    metrics.observe_tool(category="ROADMAP", status="success")
    metrics.observe_tool(category="ROADMAP", status="error")

    samples = [sample for metric in registry.collect() for sample in metric.samples]
    turn = next(sample for sample in samples if sample.name == "studypilot_agent_turns_total")
    tools = [sample for sample in samples if sample.name == "studypilot_agent_tool_calls_total"]
    assert turn.labels == {"intent": "NAVIGATION", "status": "success"}
    assert {sample.labels["status"] for sample in tools} == {"success", "error"}
    assert all(set(sample.labels) == {"category", "status"} for sample in tools)
