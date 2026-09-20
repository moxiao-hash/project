"""预算耗尽时仍保留纯 Java 导航/查询，并拒绝新的模型调用。"""

import asyncio

from app.unified_agent.models import AssistantConversationStatus, AssistantIntent
from app.unified_agent.planner import PlannerOutcome, PlannerStatus
from app.unified_agent.planning_models import AssistantPlan, AssistantPlanStep, PlanIntent
from app.unified_agent.supervisor import UnifiedAgentSupervisor
from tests.unified_agent.test_supervisor import FakeJavaBackend


class BudgetAwareJava(FakeJavaBackend):
    def __init__(self, allowed: bool, max_output_tokens: int | None = 1024) -> None:
        super().__init__()
        self.allowed = allowed
        self.max_output_tokens = max_output_tokens
        self.budget_checks: list[str] = []

    async def get_assistant_budget(self, owner_id: str):
        self.budget_checks.append(owner_id)
        return {
            "allowed": self.allowed,
            "reason": "WITHIN_BUDGET" if self.allowed else "DAILY_MODEL_CALLS_EXHAUSTED",
            "dailyModelCalls": 1,
            "dailyEstimatedCost": "0.10",
            "maxOutputTokensPerTurn": self.max_output_tokens,
            "timezone": "Asia/Shanghai",
        }


class RecordingPlanner:
    def __init__(self) -> None:
        self.calls = 0

    async def propose(self, *, message, context, client_context):
        self.calls += 1
        return PlannerOutcome(
            status=PlannerStatus.PLAN,
            plan=AssistantPlan(
                intent=PlanIntent.LEARNING_QUERY,
                confidence=0.95,
                summary="模型计划",
                steps=[
                    AssistantPlanStep(
                        step_id="s1", tool_name="roadmap.current.get", arguments={}
                    )
                ],
            ),
        )


def test_budget_exhaustion_skips_planner_and_keeps_java_tools_available() -> None:
    asyncio.run(_budget_exhaustion_skips_planner_and_keeps_java_tools_available())


async def _budget_exhaustion_skips_planner_and_keeps_java_tools_available() -> None:
    java = BudgetAwareJava(allowed=False)
    planner = RecordingPlanner()
    service = UnifiedAgentSupervisor(
        java, model_name="deepseek-v4-flash", planner=planner
    )
    conversation = await service.create_conversation("user-1")

    result = await service.send_message(
        conversation.conversation_id,
        "查找依赖注入的学习资料",
        "assistant-turn:budget-1",
        "user-1",
        {},
    )

    assert java.budget_checks == ["user-1"]
    assert planner.calls == 0
    assert result.intent == AssistantIntent.NAVIGATION
    assert result.ui_actions[0].route_key == "MATERIALS"
    assert result.status == AssistantConversationStatus.COMPLETED
    assert java.calls[0][0] == "learning.context.get"


def test_budget_exhaustion_still_allows_pure_navigation() -> None:
    asyncio.run(_budget_exhaustion_still_allows_pure_navigation())


async def _budget_exhaustion_still_allows_pure_navigation() -> None:
    java = BudgetAwareJava(allowed=False)
    service = UnifiedAgentSupervisor(java, model_name="deepseek-v4-flash")
    conversation = await service.create_conversation("user-1")

    result = await service.send_message(
        conversation.conversation_id,
        "打开我的错题集",
        "assistant-turn:budget-2",
        "user-1",
        {},
    )

    assert result.ui_actions[0].route_key == "WRONG_QUESTIONS"
    assert [call[0] for call in java.calls] == ["learning.context.get", "navigation.resolve"]


def test_budget_available_runs_planner_normally() -> None:
    asyncio.run(_budget_available_runs_planner_normally())


async def _budget_available_runs_planner_normally() -> None:
    java = BudgetAwareJava(allowed=True)
    planner = RecordingPlanner()
    service = UnifiedAgentSupervisor(
        java, model_name="deepseek-v4-flash", planner=planner
    )
    conversation = await service.create_conversation("user-1")

    result = await service.send_message(
        conversation.conversation_id,
        "查看我的学习路线",
        "assistant-turn:budget-3",
        "user-1",
        {},
    )

    assert java.budget_checks == ["user-1"]
    assert planner.calls == 1
    assert result.status == AssistantConversationStatus.COMPLETED


def test_budget_exhaustion_does_not_block_pending_confirmation_flow() -> None:
    asyncio.run(_budget_exhaustion_does_not_block_pending_confirmation_flow())


async def _budget_exhaustion_does_not_block_pending_confirmation_flow() -> None:
    java = BudgetAwareJava(allowed=False)
    planner = RecordingPlanner()
    service = UnifiedAgentSupervisor(
        java, model_name="deepseek-v4-flash", planner=planner
    )
    conversation = await service.create_conversation("user-1")

    preview = await service.send_message(
        conversation.conversation_id,
        "今天只有 30 分钟，把学习时间调整一下",
        "assistant-turn:budget-4",
        "user-1",
        {},
    )
    assert preview.status == AssistantConversationStatus.WAITING_CONFIRMATION
    assert preview.pending_action is not None
    assert java.calls[-1][0] == "settings.learning.update"

    # 等待确认时不再发起模型调用，也不会被预算额外阻断。
    follow_up = await service.send_message(
        conversation.conversation_id,
        "确认",
        "assistant-turn:budget-5",
        "user-1",
        {},
    )
    assert follow_up.status == AssistantConversationStatus.WAITING_CONFIRMATION
    assert planner.calls == 0
