import asyncio
import gc
import weakref
from collections import deque
from datetime import date
from types import SimpleNamespace

import pytest

from app.agent.grounding import PlanGrounding
from app.agent.models import (
    ConversationStatus,
    PlanDraft,
    PlannerTurn,
    PlanTaskDraft,
)
from app.agent.service import (
    ConversationNotFoundError,
    ConversationService,
    GoalNotFoundError,
)
from app.knowledge.models import KnowledgeCitation
from app.schemas.learning import (
    LearningContext,
    LearningGoal,
)


class FakePlanner:
    def __init__(self, turns: list[PlannerTurn]) -> None:
        self.turns = deque(turns)
        self.seen_messages: list[list[str]] = []

    async def generate(self, state):
        self.seen_messages.append([str(message.content) for message in state["messages"]])
        return self.turns.popleft()


class FakeJavaBackend:
    def __init__(self) -> None:
        self.created_executions = []
        self.confirmed_executions: list[tuple[str, str]] = []
        self.execution_updates = []
        self.created_plans = []

    async def get_learning_context(self, _owner_id: str) -> LearningContext:
        return LearningContext(
            goals=[
                LearningGoal(
                    id="goal-1",
                    title="年底掌握 Java 智能应用开发",
                    target_date=date(2026, 12, 31),
                    weekly_study_hours=10,
                    status="ACTIVE",
                )
            ],
            plans=[],
            tasks=[],
            materials=[],
            mastery=[],
        )

    async def create_agent_execution(self, request):
        self.created_executions.append(request)
        return SimpleNamespace(id="execution-1", status="WAITING_CONFIRMATION")

    async def confirm_agent_execution(self, execution_id: str, *, owner_id: str):
        self.confirmed_executions.append((execution_id, owner_id))
        return SimpleNamespace(id=execution_id, status="PENDING")

    async def update_agent_execution(self, execution_id: str, request):
        self.execution_updates.append((execution_id, request))
        return SimpleNamespace(id=execution_id, status=request.status)

    async def create_confirmed_learning_plan(self, request):
        self.created_plans.append(request)
        return SimpleNamespace(
            plan=SimpleNamespace(id="plan-1"),
            tasks=[SimpleNamespace(id="task-1")],
        )


def collecting(reply: str) -> PlannerTurn:
    return PlannerTurn(reply=reply, status=ConversationStatus.COLLECTING)


def ready(minutes: int) -> PlannerTurn:
    return PlannerTurn(
        reply="计划已生成，请确认或继续修改。",
        status=ConversationStatus.DRAFT_READY,
        draft=PlanDraft(
            title="Java 学习计划",
            start_date=date(2026, 7, 26),
            end_date=date(2026, 7, 31),
            tasks=[
                PlanTaskDraft(
                    title="学习 Spring MVC",
                    scheduled_date=date(2026, 7, 27),
                    estimated_minutes=minutes,
                )
            ],
        ),
    )


def test_conversation_rejects_a_goal_not_owned_by_user() -> None:
    service = ConversationService(FakePlanner([]), FakeJavaBackend())

    with pytest.raises(GoalNotFoundError):
        asyncio.run(service.create_conversation("user-1", "missing-goal"))


def test_conversation_operations_reject_a_different_owner() -> None:
    service = ConversationService(FakePlanner([collecting("继续")]), FakeJavaBackend())

    async def run_flow() -> None:
        conversation = await service.create_conversation("user-1", "goal-1")
        with pytest.raises(ConversationNotFoundError):
            await service.get_conversation(conversation.conversation_id, "user-2")
        with pytest.raises(ConversationNotFoundError):
            await service.send_message(conversation.conversation_id, "越权消息", "user-2")
        with pytest.raises(ConversationNotFoundError):
            await service.confirm(conversation.conversation_id, "user-2")

        asyncio.run(run_flow())


def test_same_conversation_keeps_context_and_conversations_are_isolated() -> None:
    planner = FakePlanner(
        [
            collecting("你每天可以学习多久？"),
            collecting("还希望在哪一天休息？"),
            collecting("另一个会话的问题。"),
        ]
    )
    java = FakeJavaBackend()
    service = ConversationService(planner, java)

    async def run_flow() -> None:
        first = await service.create_conversation("user-1", "goal-1")
        second = await service.create_conversation("user-1", "goal-1")
        await service.send_message(first.conversation_id, "我想学习 Java", "user-1")
        await service.send_message(first.conversation_id, "每天两小时", "user-1")
        await service.send_message(second.conversation_id, "只讨论 Python", "user-1")

    asyncio.run(run_flow())

    assert "我想学习 Java" in planner.seen_messages[1]
    assert "每天两小时" in planner.seen_messages[1]
    assert "我想学习 Java" not in planner.seen_messages[2]
    assert "只讨论 Python" in planner.seen_messages[2]
    assert java.created_executions == []
    assert java.created_plans == []


def test_runtime_rotation_keeps_graph_history_and_uses_new_planner() -> None:
    first = FakePlanner([collecting("第一轮")])
    second = FakePlanner([collecting("第二轮")])
    service = ConversationService(first, FakeJavaBackend())

    async def run_flow() -> None:
        conversation = await service.create_conversation("user-1", "goal-1")
        await service.send_message(conversation.conversation_id, "问题一", "user-1")
        service.replace_runtime(second)
        snapshot = await service.send_message(
            conversation.conversation_id,
            "问题二",
            "user-1",
        )
        assert snapshot.reply == "第二轮"
        assert len(first.seen_messages) == 1
        assert len(second.seen_messages) == 1
        assert "问题一" in second.seen_messages[0]

    asyncio.run(run_flow())


def test_clearing_runtime_releases_planner_but_keeps_conversation_state() -> None:
    planner = FakePlanner([collecting("不会被调用")])
    planner_ref = weakref.ref(planner)
    service = ConversationService(planner, FakeJavaBackend())

    async def create() -> str:
        snapshot = await service.create_conversation("user-1", "goal-1")
        return snapshot.conversation_id

    conversation_id = asyncio.run(create())
    service.clear_runtime()
    del planner
    gc.collect()

    assert planner_ref() is None
    snapshot = asyncio.run(service.get_conversation(conversation_id, "user-1"))
    assert snapshot.conversation_id == conversation_id


def test_draft_can_be_revised_and_only_explicit_confirmation_persists_it() -> None:
    planner = FakePlanner([ready(30), ready(60)])
    java = FakeJavaBackend()
    service = ConversationService(planner, java)

    async def run_flow() -> None:
        conversation = await service.create_conversation("user-1", "goal-1")

        draft = await service.send_message(
            conversation.conversation_id, "请生成计划", "user-1"
        )
        assert draft.status == ConversationStatus.DRAFT_READY
        assert draft.draft.tasks[0].estimated_minutes == 30
        assert java.created_plans == []

        revised = await service.send_message(
            conversation.conversation_id,
            "每天改成 60 分钟",
            "user-1",
        )
        assert revised.status == ConversationStatus.DRAFT_READY
        assert revised.draft.tasks[0].estimated_minutes == 60
        assert java.created_plans == []

        completed = await service.confirm(conversation.conversation_id, "user-1")
        repeated = await service.confirm(conversation.conversation_id, "user-1")
        assert completed.status == ConversationStatus.COMPLETED
        assert completed.saved_plan_id == "plan-1"
        assert repeated.saved_plan_id == completed.saved_plan_id

    asyncio.run(run_flow())

    assert len(java.created_plans) == 1
    saved = java.created_plans[0]
    assert saved.tasks[0].estimated_minutes == 60
    assert java.confirmed_executions == [("execution-1", "user-1")]
    assert [request.status for _, request in java.execution_updates] == [
        "RUNNING",
        "SUCCEEDED",
    ]


def test_plan_conversation_retrieves_grounding_on_every_user_turn() -> None:
    planner = FakePlanner([collecting("还需要时间约束"), ready(60)])
    java = FakeJavaBackend()

    class FakeGroundingProvider:
        def __init__(self) -> None:
            self.queries = []

        async def retrieve(self, owner_id: str, query: str) -> PlanGrounding:
            self.queries.append((owner_id, query))
            return PlanGrounding(
                context=[
                    {
                        "source_type": "MATERIAL",
                        "category": "SYLLABUS",
                        "title": "课程大纲",
                        "locator": "第 1 章",
                        "text": "先 Java 基础，后 Spring Boot",
                    }
                ],
                citations=[
                    KnowledgeCitation(
                        source_type="MATERIAL",
                        material_id="material-1",
                        title="课程大纲",
                        locator="第 1 章",
                        snippet="先 Java 基础，后 Spring Boot",
                    )
                ],
            )

    grounding = FakeGroundingProvider()
    service = ConversationService(planner, java, grounding)

    async def run_flow():
        conversation = await service.create_conversation("user-1", "goal-1")
        first = await service.send_message(
            conversation.conversation_id, "请按课程路线规划", "user-1"
        )
        second = await service.send_message(
            conversation.conversation_id, "每天 60 分钟", "user-1"
        )
        return first, second

    first, second = asyncio.run(run_flow())

    assert len(grounding.queries) == 2
    assert "年底掌握 Java 智能应用开发" in grounding.queries[0][1]
    assert first.citations[0].title == "课程大纲"
    assert second.citations[0].locator == "第 1 章"
