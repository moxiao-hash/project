"""统一 Agent 的低基数运行指标；不记录用户文本、实体 ID 或工具参数。"""

from prometheus_client import REGISTRY, CollectorRegistry, Counter, Histogram


class AgentRuntimeMetrics:
    _INTENTS = {
        "NAVIGATION", "WRONG_QUESTION_REVIEW", "KNOWLEDGE", "PLAN",
        "TASK", "TEACHING", "CLARIFY", "UNKNOWN",
    }
    _STATUSES = {"success", "error", "cancelled", "pending"}
    _CATEGORIES = {
        "ROADMAP", "LEARNING", "SCHEDULE", "ASSESSMENT", "MATERIAL",
        "NOTIFICATION", "GOVERNANCE", "SETTINGS", "AUTOMATION",
        "WORKSPACE", "NAVIGATION", "TEST", "OTHER",
    }

    def __init__(self, registry: CollectorRegistry = REGISTRY) -> None:
        self.turns = Counter(
            "studypilot_agent_turns", "Unified Agent turns by result.",
            ("intent", "status"), registry=registry,
        )
        self.turn_duration = Histogram(
            "studypilot_agent_turn_duration_seconds", "Unified Agent turn latency.",
            ("intent",), registry=registry,
        )
        self.tool_calls = Counter(
            "studypilot_agent_tool_calls", "Governed tool calls by result.",
            ("category", "status"), registry=registry,
        )

    def observe_turn(self, *, intent: str, status: str, duration_seconds: float) -> None:
        safe_intent = intent if intent in self._INTENTS else "UNKNOWN"
        safe_status = status if status in self._STATUSES else "error"
        self.turns.labels(safe_intent, safe_status).inc()
        self.turn_duration.labels(safe_intent).observe(max(0.0, duration_seconds))

    def observe_tool(self, *, category: str, status: str) -> None:
        safe_category = category if category in self._CATEGORIES else "OTHER"
        safe_status = status if status in self._STATUSES else "error"
        self.tool_calls.labels(safe_category, safe_status).inc()


AGENT_RUNTIME_METRICS = AgentRuntimeMetrics()
