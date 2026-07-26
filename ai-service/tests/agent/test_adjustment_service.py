import asyncio
from datetime import date

from app.agent.adjustment_models import (
    AdjustmentOperation,
    AdjustmentOperationType,
    PlanAdjustmentDraft,
)
from app.agent.adjustment_service import PlanAdjustmentService
from app.schemas.agent import AgentExecution
from app.schemas.learning import (
    AdaptationContext,
    LearningPlan,
    LearningTask,
    PlanAdjustment,
)


def context(*, signals: list[dict]) -> AdaptationContext:
    return AdaptationContext(
        owner_id="user-1",
        analysis_date=date(2026, 7, 27),
        window_days=14,
        daily_study_limit_minutes=120,
        plan=LearningPlan(
            id="plan-1",
            goal_id="goal-1",
            title="Java + AI",
            start_date=date(2026, 7, 20),
            end_date=date(2026, 8, 2),
            status="CONFIRMED",
            version=2,
        ),
        tasks=[
            LearningTask(
                id="task-1",
                plan_id="plan-1",
                title="Spring MVC",
                scheduled_date=date(2026, 7, 25),
                estimated_minutes=60,
                status="TODO",
                version=1,
            )
        ],
        signals=signals,
    )


class FakeGenerator:
    def __init__(self) -> None:
        self.calls = 0

    async def generate(self, adaptation_context: AdaptationContext) -> PlanAdjustmentDraft:
        self.calls += 1
        return PlanAdjustmentDraft(
            summary="把逾期任务顺延一天",
            operations=[
                AdjustmentOperation(
                    type=AdjustmentOperationType.RESCHEDULE_TASK,
                    task_id="task-1",
                    expected_version=1,
                    scheduled_date=date(2026, 7, 28),
                )
            ],
        )


class FakeBackend:
    def __init__(self, adaptation_context: AdaptationContext, execution_status: str) -> None:
        self.context = adaptation_context
        self.execution_status = execution_status
        self.created_adjustment: dict | None = None
        self.executions = 0
        self.notifications: list[str] = []

    async def get_adaptation_context(self, *args, **kwargs):
        return self.context

    async def create_agent_execution(self, request):
        return AgentExecution(
            id="execution-1",
            idempotency_key=request.idempotency_key,
            execution_type="PLAN_ADJUSTMENT",
            trigger_type=request.trigger_type,
            risk_level=request.risk_level,
            required_scope=request.required_scope,
            status=self.execution_status,
            summary=request.summary,
            created_at="2026-07-27T00:00:00Z",
        )

    async def create_plan_adjustment(self, request):
        self.created_adjustment = request.model_dump()
        return PlanAdjustment(
            id="adjustment-1",
            owner_id=request.owner_id,
            plan_id=request.plan_id,
            idempotency_key=request.idempotency_key,
            analysis_date=request.analysis_date,
            trigger_type=request.trigger_type,
            signals=request.signals,
            summary=request.summary,
            operations=request.operations,
            risk_level="LOW",
            status="NO_CHANGE" if not request.operations else "DRAFT_READY",
            execution_id=request.execution_id,
            before_plan_version=2,
            created_at="2026-07-27T00:00:00Z",
            updated_at="2026-07-27T00:00:00Z",
        )

    async def execute_plan_adjustment(self, adjustment_id, request):
        self.executions += 1
        return PlanAdjustment(
            **{
                **self._draft().model_dump(),
                "status": "COMPLETED",
                "after_plan_version": 3,
            }
        )

    async def create_notification(self, owner_id, notification_type, title, content):
        self.notifications.append(notification_type)

    def _draft(self):
        assert self.created_adjustment is not None
        data = self.created_adjustment
        return PlanAdjustment(
            id="adjustment-1",
            risk_level="LOW",
            status="DRAFT_READY",
            before_plan_version=2,
            created_at="2026-07-27T00:00:00Z",
            updated_at="2026-07-27T00:00:00Z",
            **data,
        )


def test_no_signal_persists_no_change_without_calling_model() -> None:
    generator = FakeGenerator()
    backend = FakeBackend(context(signals=[]), "PENDING")
    service = PlanAdjustmentService(generator, backend)

    result = asyncio.run(
        service.analyze(
            owner_id="user-1",
            analysis_date=date(2026, 7, 27),
            trigger_type="USER_REQUEST",
        )
    )

    assert result.status == "NO_CHANGE"
    assert generator.calls == 0
    assert backend.executions == 0


def test_authorized_low_risk_adjustment_executes_automatically() -> None:
    generator = FakeGenerator()
    backend = FakeBackend(
        context(signals=[{"type": "OVERDUE_TASKS", "count": 1}]),
        "PENDING",
    )

    result = asyncio.run(
        PlanAdjustmentService(generator, backend).analyze(
            owner_id="user-1",
            analysis_date=date(2026, 7, 27),
            trigger_type="USER_REQUEST",
        )
    )

    assert result.status == "COMPLETED"
    assert backend.executions == 1
    assert backend.created_adjustment["execution_id"] == "execution-1"


def test_adjustment_waiting_for_authorization_only_returns_preview() -> None:
    backend = FakeBackend(
        context(signals=[{"type": "OVERDUE_TASKS", "count": 1}]),
        "WAITING_AUTHORIZATION",
    )

    result = asyncio.run(
        PlanAdjustmentService(FakeGenerator(), backend).analyze(
            owner_id="user-1",
            analysis_date=date(2026, 7, 27),
            trigger_type="USER_REQUEST",
        )
    )

    assert result.status == "DRAFT_READY"
    assert backend.executions == 0
    assert backend.notifications == ["PLAN_ADJUSTMENT_READY"]
