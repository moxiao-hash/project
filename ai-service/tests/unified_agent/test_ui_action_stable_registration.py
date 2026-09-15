"""Task 30 复查：``_register_ui_actions`` 的稳定 actionId 与元数据稳定性。

复核结论（可达流程）：注册只在 ``_emit_turn_tail`` 与计划续跑两处发生，两处传入的
``UiAction`` 都还没有 ``actionId``（新构造或从续跑快照反序列化），因此
``action.action_id or str(uuid4())`` 在可达流程里总是走“新建注册”分支；业务重试的
``attempts`` 元数据由 ``_build_retry_action`` 注册**新** ID，二者键不相同。

本模块把这个可达不变量固化成回归测试：同一动作内容在不同轮次必须拿到不同的注册
ID、重放同一轮次必须复用同一 ID、且任何后续注册都不得清零既有动作（含重试动作）
的 ``attempts``。若将来有人把注册改成“按内容派生 ID”或先清空元数据表，这些断言会失败。
"""

import asyncio

from app.unified_agent.models import (
    ActionReceiptDecision,
    AssistantActionReceipt,
    ToolDescriptor,
    ToolEffect,
    ToolRiskLevel,
    UiActionReceiptStatus,
)
from app.unified_agent.supervisor import UnifiedAgentSupervisor


def tool(name: str, effect: ToolEffect) -> ToolDescriptor:
    return ToolDescriptor(
        name=name,
        version=1,
        category="TEST",
        effect=effect,
        risk_level=ToolRiskLevel.NONE,
        input_schema={"type": "object", "properties": {}},
        output_schema={"type": "object", "properties": {}},
    )


class FakeJavaBackend:
    async def get_agent_tool_catalog(self):
        return [
            tool("learning.context.get", ToolEffect.READ),
            tool("navigation.resolve", ToolEffect.READ),
        ]

    async def invoke_agent_tool(self, name, owner_id, arguments, idempotency_key=None):
        if name == "learning.context.get":
            return {"toolName": name, "data": {"roadmap": {"stages": []}}, "action": None}
        if name == "navigation.resolve":
            return {"toolName": name, "data": {}, "action": None}
        raise AssertionError(name)


def _failed_receipt(action_id: str) -> AssistantActionReceipt:
    return AssistantActionReceipt(
        action_id=action_id,
        status=UiActionReceiptStatus.FAILED,
        current_route="wrong-questions",
        error=None,
    )


def test_registration_reuses_replay_id_and_never_resets_retry_metadata() -> None:
    async def scenario() -> None:
        service = UnifiedAgentSupervisor(FakeJavaBackend(), model_name="test-model")
        conversation = await service.create_conversation("user-1")
        conversation_id = conversation.conversation_id

        first = await service.send_message(
            conversation_id, "刷新错题集", "turn:1", "user-1", {}
        )
        first_id = first.ui_actions[0].action_id
        assert first_id, "新动作必须获得服务端稳定 actionId"

        # SSE 重放/REST 快照：同一轮次必须复用同一 actionId，且不被再次登记清零。
        replay = await service.send_message(
            conversation_id, "刷新错题集", "turn:1", "user-1", {}
        )
        assert replay.ui_actions[0].action_id == first_id

        # 相同动作内容的下一轮次必须拿到新的注册 ID，而不是复用旧 ID。
        second = await service.send_message(
            conversation_id, "刷新错题集", "turn:2", "user-1", {}
        )
        second_id = second.ui_actions[0].action_id
        assert second_id != first_id

        state = service._conversations[conversation_id]
        assert state.ui_actions_by_id[first_id]["attempts"] == 0
        assert state.ui_actions_by_id[second_id]["attempts"] == 0

        # 业务重试注册新 ID 并把 attempts 记到新条目上。
        result = await service.record_action_receipt(
            conversation_id, "user-1", _failed_receipt(first_id)
        )
        assert result.decision == ActionReceiptDecision.RETRY_SCHEDULED
        retry_events = [
            event
            for event in state.events
            if event.type == "UI_ACTION" and event.payload.get("retry") is True
        ]
        assert len(retry_events) == 1
        retry_id = retry_events[0].payload["actionId"]
        assert retry_id not in {first_id, second_id}
        # 既有语义：原动作的 attempts 也 +1（同一 ID 不得再次自动重试），
        # 新重试动作以 attempts=1 登记；这是 `_decide_action_receipt` 的刻意行为。
        assert state.ui_actions_by_id[first_id]["attempts"] == 1
        assert state.ui_actions_by_id[retry_id]["attempts"] == 1

        # 之后的注册（新轮次）不得清零既有动作或重试动作的 attempts。
        third = await service.send_message(
            conversation_id, "刷新错题集", "turn:3", "user-1", {}
        )
        third_id = third.ui_actions[0].action_id
        assert third_id not in {first_id, second_id, retry_id}
        assert state.ui_actions_by_id[first_id]["attempts"] == 1
        assert state.ui_actions_by_id[retry_id]["attempts"] == 1
        assert state.ui_actions_by_id[third_id]["attempts"] == 0

    asyncio.run(scenario())
