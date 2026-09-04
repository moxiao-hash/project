import asyncio

from app.unified_agent.models import (
    AssistantConversationStatus,
    ToolDescriptor,
    ToolEffect,
    ToolRiskLevel,
)
from app.unified_agent.supervisor import UnifiedAgentSupervisor


class FakeJavaBackend:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, dict, str | None]] = []

    async def get_agent_tool_catalog(self):
        return [
            tool("learning.context.get", ToolEffect.READ),
            tool("navigation.resolve", ToolEffect.READ),
            tool("assessment.wrong_question_review.create", ToolEffect.WRITE),
        ]

    async def invoke_agent_tool(self, name, owner_id, arguments, idempotency_key=None):
        self.calls.append((name, owner_id, arguments, idempotency_key))
        if name == "learning.context.get":
            return {
                "toolName": name,
                "data": {
                    "roadmap": {
                        "stages": [
                            {
                                "nodes": [
                                    {
                                        "id": "node-done",
                                        "title": "已经完成",
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
                },
                "action": None,
            }
        if name == "assessment.wrong_question_review.create":
            return {
                "toolName": name,
                "data": None,
                "action": {
                    "actionId": "action-1",
                    "executionId": "execution-1",
                    "toolName": name,
                    "toolVersion": 1,
                    "riskLevel": "LOW",
                    "status": "WAITING_CONFIRMATION",
                    "summary": "创建错题重做批次",
                    "arguments": arguments,
                    "result": None,
                    "error": None,
                    "expiresAt": "2026-09-04T12:00:00Z",
                },
            }
        return {"toolName": name, "data": {"routeKey": arguments.get("routeKey")}, "action": None}


def tool(name: str, effect: ToolEffect) -> ToolDescriptor:
    return ToolDescriptor(
        name=name,
        version=1,
        category="TEST",
        effect=effect,
        risk_level=ToolRiskLevel.NONE if effect == ToolEffect.READ else ToolRiskLevel.LOW,
        idempotency_required=effect == ToolEffect.WRITE,
        input_schema={"type": "object"},
        output_schema={"type": "object"},
    )


def test_navigation_turn_loads_context_and_emits_whitelisted_ui_action() -> None:
    asyncio.run(_navigation_turn_loads_context_and_emits_whitelisted_ui_action())


async def _navigation_turn_loads_context_and_emits_whitelisted_ui_action() -> None:
    java = FakeJavaBackend()
    service = UnifiedAgentSupervisor(java, model_name="deepseek-v4-flash")
    conversation = await service.create_conversation("user-1")

    result = await service.send_message(
        conversation.conversation_id,
        "打开我的错题集",
        "assistant-turn:nav-1",
        "user-1",
        {"routeName": "dashboard", "routeParams": {}},
    )

    assert [call[0] for call in java.calls] == ["learning.context.get", "navigation.resolve"]
    assert result.status == AssistantConversationStatus.COMPLETED
    assert result.ui_actions[0].route_key == "WRONG_QUESTIONS"
    assert result.model_name == "deepseek-v4-flash"


def test_continue_learning_uses_structured_context_instead_of_model_guess() -> None:
    asyncio.run(_continue_learning_uses_structured_context_instead_of_model_guess())


async def _continue_learning_uses_structured_context_instead_of_model_guess() -> None:
    java = FakeJavaBackend()
    service = UnifiedAgentSupervisor(java, model_name="deepseek-v4-flash")
    conversation = await service.create_conversation("user-1")

    result = await service.send_message(
        conversation.conversation_id,
        "继续昨天没学完的章节",
        "assistant-turn:continue-1",
        "user-1",
        {},
    )

    assert result.ui_actions[0].route_key == "ROADMAP_NODE"
    assert result.ui_actions[0].params == {"nodeId": "node-next"}
    assert java.calls[-1][2] == {
        "routeKey": "ROADMAP_NODE",
        "params": {"nodeId": "node-next"},
    }


def test_wrong_question_review_surfaces_preview_without_executing_it() -> None:
    asyncio.run(_wrong_question_review_surfaces_preview_without_executing_it())


async def _wrong_question_review_surfaces_preview_without_executing_it() -> None:
    java = FakeJavaBackend()
    service = UnifiedAgentSupervisor(java, model_name="deepseek-v4-flash")
    conversation = await service.create_conversation("user-1")

    result = await service.send_message(
        conversation.conversation_id,
        "打开错题集并重做五题",
        "assistant-turn:review-1",
        "user-1",
        {},
    )

    assert result.status == AssistantConversationStatus.WAITING_CONFIRMATION
    assert result.pending_action is not None
    assert result.pending_action.action_id == "action-1"
    assert len([call for call in java.calls if call[0].endswith("create")]) == 1

    # 自然语言“确认”只是新一轮聊天，绝不能替代专用确认接口。
    follow_up = await service.send_message(
        conversation.conversation_id,
        "确认",
        "assistant-turn:review-2",
        "user-1",
        {},
    )
    assert follow_up.status == AssistantConversationStatus.WAITING_CONFIRMATION
    assert len([call for call in java.calls if call[0].endswith("create")]) == 1


def test_ambiguous_write_request_is_clarified_without_write_tool() -> None:
    asyncio.run(_ambiguous_write_request_is_clarified_without_write_tool())


async def _ambiguous_write_request_is_clarified_without_write_tool() -> None:
    java = FakeJavaBackend()
    service = UnifiedAgentSupervisor(java, model_name="deepseek-v4-flash")
    conversation = await service.create_conversation("user-1")

    result = await service.send_message(
        conversation.conversation_id,
        "帮我调整一下",
        "assistant-turn:ambiguous-1",
        "user-1",
        {"routeName": "materials", "routeParams": {"ownerId": "attacker"}},
    )

    assert result.status == AssistantConversationStatus.COMPLETED
    assert "具体" in result.reply
    assert [call[0] for call in java.calls] == ["learning.context.get"]
    assert all(call[1] == "user-1" for call in java.calls)
