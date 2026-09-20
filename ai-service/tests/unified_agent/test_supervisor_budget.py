"""预算拒绝/不可用时 Supervisor 只保留纯 Java 路径，并按每次 provider 调用重新预占。"""

import asyncio

from app.providers.budget import ModelBudgetExceededError, ModelBudgetGuard
from app.providers.budgeted_model import BudgetedChatModel
from app.unified_agent.models import AssistantConversationStatus, AssistantIntent
from app.unified_agent.planner import PlannerOutcome, PlannerStatus
from app.unified_agent.planning_models import AssistantPlan, AssistantPlanStep, PlanIntent
from app.unified_agent.supervisor import UnifiedAgentSupervisor
from tests.unified_agent.test_supervisor import FakeJavaBackend


class ReservationJava(FakeJavaBackend):
    """记录预占请求，并可按剩余许可数拒绝后续调用。"""

    def __init__(self, allowed_permits: int | None = None) -> None:
        super().__init__()
        self.allowed_permits = allowed_permits
        self.reservations: list[dict] = []
        self.released: list[str] = []

    async def reserve_assistant_usage(self, payload: dict):
        self.reservations.append(payload)
        if self.allowed_permits is not None and len(self.reservations) > self.allowed_permits:
            return {
                "reservationId": None,
                "allowed": False,
                "reason": "DAILY_MODEL_CALLS_EXHAUSTED",
                "maxOutputTokensPerTurn": 1024,
                "timezone": "Asia/Shanghai",
            }
        return {
            "reservationId": payload["usageId"],
            "allowed": True,
            "reason": "WITHIN_BUDGET",
            "maxOutputTokensPerTurn": 1024,
            "timezone": "Asia/Shanghai",
        }

    async def release_assistant_usage_reservation(self, reservation_id: str):
        self.released.append(reservation_id)
        return {"reservationId": reservation_id, "released": True}


class RecordingStructuredRunnable:
    """底层假 runnable：记录每次真实 provider 调用与绑定的 max_tokens。"""

    def __init__(self, result=None) -> None:
        self.result = result if result is not None else {"plan": "unused"}
        self.calls: list[object] = []
        self.bound: list[dict] = []

    def with_structured_output(self, _schema, **_kwargs):
        return self

    def bind(self, **kwargs):
        self.bound.append(kwargs)
        return self

    async def ainvoke(self, messages, config=None, **kwargs):
        self.calls.append(messages)
        return self.result


class DeniedPlanner:
    """第一次模型调用即被预算拒绝的 Planner（模拟真实边界冒泡）。"""

    def __init__(self) -> None:
        self.calls = 0

    async def propose(self, *, message, context, client_context):
        self.calls += 1
        raise ModelBudgetExceededError(
            "DAILY_MODEL_CALLS_EXHAUSTED", max_output_tokens_per_turn=1024
        )


class BudgetProbePlanner:
    """先走一次真实预算模型调用，再返回 UNAVAILABLE 以落到关键词分支。"""

    def __init__(self, model) -> None:
        self._structured = model.with_structured_output(AssistantPlan, method="json_mode")
        self.calls = 0

    async def propose(self, *, message, context, client_context):
        self.calls += 1
        await self._structured.ainvoke([{"role": "user", "content": "plan"}])
        return PlannerOutcome(status=PlannerStatus.UNAVAILABLE)


class FakeKnowledgeServices:
    def __init__(self) -> None:
        self.for_owner_calls = 0

    async def for_owner(self, owner_id):
        self.for_owner_calls += 1
        raise AssertionError("预算被拒时不得构造知识问答模型")


class BudgetedKnowledgeService:
    """知识问答走真实预算模型边界：许可耗尽时由边界抛出 ModelBudgetExceededError。"""

    def __init__(self, model) -> None:
        self._model = model

    async def create_conversation(self, owner_id, mode):
        return type("Conversation", (), {"conversation_id": "knowledge-1"})()

    async def send_message(self, conversation_id, message, web_search, owner_id):
        await self._model.ainvoke([{"role": "user", "content": message}])


class BudgetedKnowledgeServices:
    def __init__(self, model) -> None:
        self._model = model

    async def for_owner(self, owner_id):
        return BudgetedKnowledgeService(self._model)


def _budgeted_model(java: ReservationJava, runnable: RecordingStructuredRunnable):
    return BudgetedChatModel(
        runnable,
        guard=ModelBudgetGuard(java),
        owner_id="user-1",
        provider="deepseek",
        model_name="deepseek-v4-flash",
        purpose="AGENT_PLANNING",
    )


def test_budget_denial_keeps_pure_java_navigation_and_blocks_model_path() -> None:
    asyncio.run(_budget_denial_keeps_pure_java_navigation_and_blocks_model_path())


async def _budget_denial_keeps_pure_java_navigation_and_blocks_model_path() -> None:
    java = ReservationJava()
    planner = DeniedPlanner()
    knowledge = FakeKnowledgeServices()
    service = UnifiedAgentSupervisor(
        java,
        model_name="deepseek-v4-flash",
        planner=planner,
        knowledge_services=knowledge,
    )
    conversation = await service.create_conversation("user-1")

    result = await service.send_message(
        conversation.conversation_id,
        "查找依赖注入的学习资料",
        "assistant-turn:budget-1",
        "user-1",
        {},
    )

    assert planner.calls == 1
    assert knowledge.for_owner_calls == 0
    assert result.intent == AssistantIntent.NAVIGATION
    assert result.ui_actions[0].route_key == "MATERIALS"
    assert result.status == AssistantConversationStatus.COMPLETED
    assert java.calls[0][0] == "learning.context.get"


def test_budget_denial_still_allows_pure_navigation() -> None:
    asyncio.run(_budget_denial_still_allows_pure_navigation())


async def _budget_denial_still_allows_pure_navigation() -> None:
    java = ReservationJava()
    planner = DeniedPlanner()
    service = UnifiedAgentSupervisor(
        java, model_name="deepseek-v4-flash", planner=planner
    )
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
    class PlanningPlanner:
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

    java = ReservationJava()
    planner = PlanningPlanner()
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

    assert planner.calls == 1
    assert result.status == AssistantConversationStatus.COMPLETED


def test_second_model_call_is_blocked_after_the_first_consumes_the_last_permit() -> None:
    asyncio.run(
        _second_model_call_is_blocked_after_the_first_consumes_the_last_permit()
    )


async def _second_model_call_is_blocked_after_the_first_consumes_the_last_permit() -> None:
    java = ReservationJava(allowed_permits=1)
    planner_runnable = RecordingStructuredRunnable()
    planner = BudgetProbePlanner(_budgeted_model(java, planner_runnable))
    knowledge_runnable = RecordingStructuredRunnable()
    knowledge_model = BudgetedChatModel(
        knowledge_runnable,
        guard=ModelBudgetGuard(java),
        owner_id="user-1",
        provider="deepseek",
        model_name="deepseek-v4-flash",
        purpose="KNOWLEDGE_QA",
    )
    service = UnifiedAgentSupervisor(
        java,
        model_name="deepseek-v4-flash",
        planner=planner,
        knowledge_services=BudgetedKnowledgeServices(knowledge_model),
    )
    conversation = await service.create_conversation("user-1")

    result = await service.send_message(
        conversation.conversation_id,
        "查找依赖注入的学习资料",
        "assistant-turn:budget-4",
        "user-1",
        {},
    )

    # 同一轮两次 provider 调用前各预占一次；第二次被拒绝，因此只发生一次真实调用。
    assert [entry["purpose"] for entry in java.reservations] == [
        "AGENT_PLANNING",
        "KNOWLEDGE_QA",
    ]
    assert planner_runnable.calls and len(planner_runnable.calls) == 1
    assert knowledge_runnable.calls == []
    assert planner_runnable.bound[0]["max_tokens"] == 1024
    assert result.intent == AssistantIntent.NAVIGATION
    assert result.ui_actions[0].route_key == "MATERIALS"


def test_pending_confirmation_makes_zero_model_calls_and_zero_reservations() -> None:
    asyncio.run(_pending_confirmation_makes_zero_model_calls_and_zero_reservations())


async def _pending_confirmation_makes_zero_model_calls_and_zero_reservations() -> None:
    java = ReservationJava()
    planner = BudgetProbePlanner(_budgeted_model(java, RecordingStructuredRunnable()))
    service = UnifiedAgentSupervisor(
        java, model_name="deepseek-v4-flash", planner=planner
    )
    conversation = await service.create_conversation("user-1")

    preview = await service.send_message(
        conversation.conversation_id,
        "今天只有 30 分钟，把学习时间调整一下",
        "assistant-turn:budget-5",
        "user-1",
        {},
    )
    assert preview.status == AssistantConversationStatus.WAITING_CONFIRMATION
    assert preview.pending_action is not None
    assert planner.calls == 1
    assert len(java.reservations) == 1

    # 等待确认时先返回专用确认提示，不查询预算也不调用模型。
    follow_up = await service.send_message(
        conversation.conversation_id,
        "确认",
        "assistant-turn:budget-6",
        "user-1",
        {},
    )
    assert follow_up.status == AssistantConversationStatus.WAITING_CONFIRMATION
    assert planner.calls == 1
    assert len(java.reservations) == 1
