"""学习领域的 Java/Python 数据契约。

Python 内部采用 ``snake_case``，Java JSON 采用 ``camelCase``。统一的基类负责
转换，避免每次请求都手写字段名映射。
"""

from datetime import date, datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.agent.adjustment_models import AdjustmentOperation


class JavaContractModel(BaseModel):
    """支持 Java camelCase JSON 的 Pydantic 基类。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
    )


class LearningGoal(JavaContractModel):
    id: str
    title: str
    target_date: date
    weekly_study_hours: int
    status: str


class LearningPlan(JavaContractModel):
    id: str
    goal_id: str
    title: str
    start_date: date
    end_date: date
    status: str
    version: int


class LearningTaskStatus(StrEnum):
    """Java 学习任务状态的 Python 类型化表示。"""

    TODO = "TODO"
    COMPLETED = "COMPLETED"
    SKIPPED = "SKIPPED"
    DEFERRED = "DEFERRED"


class LearningTask(JavaContractModel):
    id: str
    plan_id: str
    title: str
    scheduled_date: date
    estimated_minutes: int
    status: LearningTaskStatus
    version: int
    completed_at: datetime | None = None
    actual_minutes: int | None = Field(default=None, ge=1, le=720)


class ChangeLearningTaskStatusRequest(JavaContractModel):
    """Python 调用 Java 幂等任务状态工具时发送的请求。"""

    owner_id: str = Field(min_length=1)
    idempotency_key: str = Field(min_length=1, max_length=180)
    expected_version: int = Field(ge=1)
    status: LearningTaskStatus
    scheduled_date: date | None = None
    reason: str | None = Field(default=None, max_length=255)
    actual_minutes: int | None = Field(default=None, ge=1, le=720)


class Material(JavaContractModel):
    id: str
    title: str
    material_type: str
    category: str
    privacy_level: str
    source_url: str | None = None
    processing_status: str
    summary: str | None = None
    tags: list[str] = []
    knowledge_points: list[str] = []
    content_reference: str | None = None
    failure_reason: str | None = None


class Mastery(JavaContractModel):
    knowledge_point: str
    score: float
    attempt_count: int


class LearningContext(JavaContractModel):
    """Agent 生成学习方案时所需的只读业务上下文。"""

    goals: list[LearningGoal]
    plans: list[LearningPlan]
    tasks: list[LearningTask]
    materials: list[Material]
    mastery: list[Mastery]


class AdaptationSignalType(StrEnum):
    OVERDUE_TASKS = "OVERDUE_TASKS"
    CONSECUTIVE_SKIPS = "CONSECUTIVE_SKIPS"
    TIME_ESTIMATE_BIAS = "TIME_ESTIMATE_BIAS"


class AdaptationSignal(JavaContractModel):
    type: AdaptationSignalType
    count: int = Field(ge=1)
    deviation_ratio: float | None = Field(default=None, ge=0)


class AdaptationContext(JavaContractModel):
    owner_id: str
    analysis_date: date
    window_days: int = Field(ge=1, le=30)
    daily_study_limit_minutes: int = Field(ge=1)
    plan: LearningPlan
    tasks: list[LearningTask]
    signals: list[AdaptationSignal]


class CreatePlanDraftRequest(JavaContractModel):
    """请求 Java 创建待用户确认的计划草案。"""

    owner_id: str
    goal_id: str
    title: str
    start_date: date
    end_date: date


class CreateConfirmedTaskRequest(JavaContractModel):
    """确认计划时一并写入的任务。"""

    title: str
    scheduled_date: date
    estimated_minutes: int


class CreateConfirmedLearningPlanRequest(JavaContractModel):
    """原子创建已确认计划及任务的 Java 内部契约。"""

    owner_id: str
    goal_id: str
    idempotency_key: str
    title: str
    start_date: date
    end_date: date
    tasks: list[CreateConfirmedTaskRequest]


class ConfirmedLearningPlan(JavaContractModel):
    plan: LearningPlan
    tasks: list[LearningTask]


class PlanAdjustmentStatus(StrEnum):
    ANALYZING = "ANALYZING"
    NO_CHANGE = "NO_CHANGE"
    DRAFT_READY = "DRAFT_READY"
    EXECUTING = "EXECUTING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class CreatePlanAdjustmentRequest(JavaContractModel):
    owner_id: str
    plan_id: str
    idempotency_key: str = Field(max_length=180)
    analysis_date: date
    trigger_type: str
    signals: list[AdaptationSignalType]
    summary: str = Field(max_length=500)
    execution_id: str | None = None
    operations: list[AdjustmentOperation]


class ExecutePlanAdjustmentRequest(JavaContractModel):
    owner_id: str
    execution_id: str
    expected_plan_version: int = Field(ge=1)


class PlanAdjustment(JavaContractModel):
    id: str
    owner_id: str
    plan_id: str
    idempotency_key: str
    analysis_date: date
    trigger_type: str
    signals: list[AdaptationSignalType]
    summary: str
    operations: list[AdjustmentOperation]
    risk_level: str
    status: PlanAdjustmentStatus
    execution_id: str | None = None
    before_plan_version: int
    after_plan_version: int | None = None
    error: str | None = None
    created_at: datetime
    updated_at: datetime


class NightlyAdjustmentCandidate(JavaContractModel):
    owner_id: str
    analysis_date: date
