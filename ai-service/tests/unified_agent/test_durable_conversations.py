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
