import asyncio
import base64
from pathlib import Path

from app.persistence.agent_state import AgentPersistence
from app.unified_agent.models import (
    ToolDescriptor,
    ToolEffect,
    ToolRiskLevel,
)
from app.unified_agent.supervisor import UnifiedAgentSupervisor

TEST_KEY = base64.b64encode(bytes(range(32))).decode()


class FakeJavaBackend:
    async def get_agent_tool_catalog(self):
        return [
            ToolDescriptor(
                name="learning.context.get",
                version=1,
                category="CONTEXT",
                effect=ToolEffect.READ,
                risk_level=ToolRiskLevel.NONE,
                input_schema={"type": "object"},
                output_schema={"type": "object"},
            )
        ]

    async def invoke_agent_tool(self, name, owner_id, arguments, idempotency_key=None):
        return {"toolName": name, "data": {"roadmap": None}, "action": None}


def test_conversation_and_events_survive_service_recreation(tmp_path: Path) -> None:
    asyncio.run(_conversation_and_events_survive_service_recreation(tmp_path))


async def _conversation_and_events_survive_service_recreation(tmp_path: Path) -> None:
    persistence = await AgentPersistence.open(tmp_path / "agent.sqlite3", TEST_KEY)
    first = UnifiedAgentSupervisor(
        FakeJavaBackend(), model_name="deepseek-v4-flash", persistence=persistence
    )
    created = await first.create_conversation("user-1")
    completed = await first.send_message(
        created.conversation_id,
        "帮我调整一下",
        "assistant-turn:durable-1",
        "user-1",
        {},
    )
    events = await first.list_events(created.conversation_id, "user-1", after_sequence=0)
    assert [event.sequence for event in events] == list(range(1, len(events) + 1))
    assert events[-1].type == "TURN_COMPLETED"

    second = UnifiedAgentSupervisor(
        FakeJavaBackend(), model_name="deepseek-v4-flash", persistence=persistence
    )
    restored = await second.get_conversation(created.conversation_id, "user-1")
    replayed = await second.list_events(
        created.conversation_id,
        "user-1",
        after_sequence=events[-2].sequence,
    )

    assert restored.reply == completed.reply
    assert [event.sequence for event in replayed] == [events[-1].sequence]
    duplicate = await second.send_message(
        created.conversation_id,
        "这次文本不同也不能重复执行",
        "assistant-turn:durable-1",
        "user-1",
        {},
    )
    assert duplicate.reply == completed.reply
    assert len(await second.list_events(created.conversation_id, "user-1", 0)) == len(events)
    await persistence.close()


class FakePlanJavaBackend:
    """覆盖多步计划所需的三类工具与确认接口。"""

    def __init__(self) -> None:
        self.calls: list[str] = []

    async def get_agent_tool_catalog(self):
        return [
            ToolDescriptor(
                name="learning.context.get",
                version=1,
                category="CONTEXT",
                effect=ToolEffect.READ,
                risk_level=ToolRiskLevel.NONE,
                input_schema={"type": "object"},
                output_schema={"type": "object"},
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
                output_schema={"type": "object"},
            ),
            ToolDescriptor(
                name="assessment.mastery.list",
                version=1,
                category="ASSESSMENT",
                effect=ToolEffect.READ,
                risk_level=ToolRiskLevel.NONE,
                input_schema={"type": "object"},
                output_schema={"type": "object"},
            ),
        ]

    async def invoke_agent_tool(self, name, owner_id, arguments, idempotency_key=None):
        self.calls.append(name)
        if name == "settings.learning.update":
            return {
                "toolName": name,
                "data": None,
                "action": {
                    "actionId": "action-durable-settings",
                    "executionId": "execution-durable-settings",
                    "toolName": name,
                    "toolVersion": 1,
                    "riskLevel": "HIGH",
                    "status": "WAITING_CONFIRMATION",
                    "summary": "调整每日学习时长",
                    "arguments": arguments,
                    "result": None,
                    "error": None,
                    "expiresAt": "2026-09-04T12:00:00Z",
                },
            }
        return {"toolName": name, "data": {"ok": True}, "action": None}

    async def confirm_agent_tool_action(self, action_id, owner_id):
        return {
            "actionId": action_id,
            "executionId": "execution-durable-settings",
            "toolName": "settings.learning.update",
            "toolVersion": 1,
            "riskLevel": "HIGH",
            "status": "SUCCEEDED",
            "summary": "调整每日学习时长",
            "arguments": {"dailyStudyLimitMinutes": 30},
            "result": {"dailyStudyLimitMinutes": 30},
            "error": None,
            "expiresAt": "2026-09-04T12:00:00Z",
        }


class _FixedPlanner:
    def __init__(self, outcome) -> None:
        self.outcome = outcome

    async def propose(self, *, message, context, client_context):
        return self.outcome


def test_plan_resume_survives_service_recreation(tmp_path: Path) -> None:
    asyncio.run(_plan_resume_survives_service_recreation(tmp_path))


async def _plan_resume_survives_service_recreation(tmp_path: Path) -> None:
    from app.unified_agent.planner import PlannerOutcome, PlannerStatus
    from app.unified_agent.planning_models import (
        AssistantPlan,
        AssistantPlanStep,
        PlanIntent,
    )

    persistence = await AgentPersistence.open(tmp_path / "agent.sqlite3", TEST_KEY)
    try:
        java = FakePlanJavaBackend()
        plan = AssistantPlan(
            intent=PlanIntent.PLAN_ADJUSTMENT,
            confidence=0.95,
            summary="先调整时长再查看掌握度",
            steps=[
                AssistantPlanStep(
                    step_id="s1",
                    tool_name="settings.learning.update",
                    arguments={"dailyStudyLimitMinutes": 30},
                ),
                AssistantPlanStep(
                    step_id="s2",
                    tool_name="assessment.mastery.list",
                    depends_on=["s1"],
                ),
            ],
        )
        first = UnifiedAgentSupervisor(
            java,
            model_name="deepseek-v4-flash",
            persistence=persistence,
            planner=_FixedPlanner(PlannerOutcome(status=PlannerStatus.PLAN, plan=plan)),
        )
        created = await first.create_conversation("user-1")
        preview = await first.send_message(
            created.conversation_id,
            "把每日时长改成 30 分钟并告诉我薄弱点",
            "assistant-turn:durable-resume-1",
            "user-1",
            {},
        )
        assert preview.pending_action is not None
        assert java.calls == ["learning.context.get", "settings.learning.update"]

        # 模拟服务重启：新的 Supervisor 实例，同一个加密持久化存储。
        second_java = FakePlanJavaBackend()
        second = UnifiedAgentSupervisor(
            second_java, model_name="deepseek-v4-flash", persistence=persistence
        )
        result = await second.confirm_action(
            created.conversation_id, "action-durable-settings", "user-1"
        )

        assert second_java.calls == ["assessment.mastery.list"]
        assert result.status.value == "COMPLETED"
        assert result.pending_action is None
        assert [step.tool_name for step in result.tool_steps] == [
            "learning.context.get",
            "settings.learning.update",
            "assessment.mastery.list",
        ]
    finally:
        await persistence.close()
