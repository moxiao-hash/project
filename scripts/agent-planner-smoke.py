#!/usr/bin/env python3
"""Task 28 多步 Planner 真实模型最小冒烟脚本。

用途：在**不启动 Java/FastAPI** 的前提下，用真实 DeepSeek 验证
「裁剪上下文 + Java 工具目录 → 结构化 AssistantPlan → 确定性策略校验」这条链路。

安全边界：
- 只读取 ``ai-service/.env`` 的配置，不打印任何密钥、Token 或提示词正文。
- 只调用一次模型，不做任何业务写操作，不访问数据库。
- 若 Java 未启动，使用脚本内置的最小工具目录；这只用于验证规划与校验，
  不能替代 Task 34 的完整真实端到端验收。

运行方式（在 ai-service 目录执行）：

    cd ai-service
    .venv/bin/python ../scripts/agent-planner-smoke.py
"""

import asyncio
import json
import sys
from pathlib import Path
from time import monotonic

AI_SERVICE_ROOT = Path(__file__).resolve().parent.parent / "ai-service"
sys.path.insert(0, str(AI_SERVICE_ROOT))

from app.clients.java_backend import JavaBackendClient  # noqa: E402
from app.core.settings import get_settings  # noqa: E402
from app.providers.model_factory import ModelConfigurationError, create_chat_model  # noqa: E402
from app.unified_agent.models import ToolDescriptor, ToolEffect, ToolRiskLevel  # noqa: E402
from app.unified_agent.planner import AssistantPlanner, PlannerStatus  # noqa: E402
from app.unified_agent.policy_validator import PlanPolicyValidator  # noqa: E402

MESSAGE = "继续昨天没学完的章节，学完后准备测验，并告诉我薄弱点"

FALLBACK_CATALOG = [
    ToolDescriptor(
        name="learning.context.get",
        version=1,
        category="CONTEXT",
        effect=ToolEffect.READ,
        risk_level=ToolRiskLevel.NONE,
        input_schema={"type": "object", "properties": {}},
        output_schema={
            "type": "object",
            "properties": {"nextNodeId": {"type": "string"}, "roadmap": {"type": "object"}},
        },
    ),
    ToolDescriptor(
        name="assessment.node_quiz_status.get",
        version=1,
        category="ASSESSMENT",
        effect=ToolEffect.READ,
        risk_level=ToolRiskLevel.NONE,
        input_schema={
            "type": "object",
            "properties": {"nodeId": {"type": "string"}},
            "required": ["nodeId"],
        },
        output_schema={
            "type": "object",
            "properties": {"status": {"type": "string"}, "quizId": {"type": "string"}},
        },
    ),
    ToolDescriptor(
        name="assessment.mastery.list",
        version=1,
        category="ASSESSMENT",
        effect=ToolEffect.READ,
        risk_level=ToolRiskLevel.NONE,
        input_schema={"type": "object", "properties": {}},
        output_schema={
            "type": "object",
            "properties": {"knowledgePoints": {"type": "array"}},
        },
    ),
    ToolDescriptor(
        name="settings.learning.update",
        version=1,
        category="SETTINGS",
        effect=ToolEffect.WRITE,
        risk_level=ToolRiskLevel.HIGH,
        idempotency_required=True,
        input_schema={
            "type": "object",
            "properties": {"dailyStudyLimitMinutes": {"type": "integer"}},
            "required": ["dailyStudyLimitMinutes"],
        },
        output_schema={"type": "object", "properties": {}},
    ),
]


async def load_catalog(settings) -> tuple[dict[str, ToolDescriptor], str]:
    try:
        descriptors = await JavaBackendClient(settings).get_agent_tool_catalog()
    except Exception:  # noqa: BLE001 - Java 未启动时用最小目录
        descriptors = []
    if descriptors:
        return {item.name: item for item in descriptors}, "java-catalog"
    return {item.name: item for item in FALLBACK_CATALOG}, "builtin-fallback-catalog"


async def main() -> int:
    settings = get_settings()
    if not settings.model_is_configured:
        print(
            json.dumps(
                {"status": "SKIPPED", "reason": "未配置 DEEPSEEK_API_KEY"},
                ensure_ascii=False,
            )
        )
        return 0
    catalog, catalog_source = await load_catalog(settings)
    try:
        model = create_chat_model(settings)
    except ModelConfigurationError as exc:
        print(json.dumps({"status": "SKIPPED", "reason": str(exc)}, ensure_ascii=False))
        return 0

    planner = AssistantPlanner(
        model=model,
        catalog=catalog,
        validator=PlanPolicyValidator(
            catalog,
            min_confidence=settings.agent_planner_min_confidence,
            max_steps=settings.agent_planner_max_steps,
        ),
        timeout_seconds=settings.agent_planner_timeout_seconds,
        max_context_chars=settings.agent_planner_max_context_chars,
    )
    context = {
        "roadmap": {
            "stages": [
                {
                    "nodes": [
                        {
                            "id": "node-done",
                            "title": "已完成的节点",
                            "displayStatus": "COMPLETED",
                        },
                        {
                            "id": "node-next",
                            "title": "变量与类型转换",
                            "displayStatus": "AVAILABLE",
                        },
                    ]
                }
            ]
        }
    }
    started = monotonic()
    outcome = await planner.propose(
        message=MESSAGE, context=context, client_context={"routeName": "dashboard"}
    )
    latency_ms = int((monotonic() - started) * 1000)
    report = {
        "status": outcome.status.value,
        "modelName": settings.model_name,
        "catalogSource": catalog_source,
        "catalogSize": len(catalog),
        "latencyMs": latency_ms,
        "issueCodes": [issue.code.value for issue in outcome.issues],
        "reason": outcome.reason,
        "plan": None,
    }
    if outcome.plan is not None:
        report["plan"] = {
            "intent": outcome.plan.intent.value,
            "confidence": outcome.plan.confidence,
            "summary": outcome.plan.summary,
            "steps": [
                {
                    "stepId": step.step_id,
                    "toolName": step.tool_name,
                    "dependsOn": step.depends_on,
                    "argumentKeys": sorted(step.arguments),
                }
                for step in outcome.plan.steps
            ],
        }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if outcome.status in {PlannerStatus.PLAN, PlannerStatus.CLARIFY} else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
