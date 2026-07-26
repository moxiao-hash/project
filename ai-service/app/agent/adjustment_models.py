"""计划调整模型输出和确定性风险分类。"""

from datetime import date
from enum import StrEnum
from typing import Self

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class AdjustmentOperationType(StrEnum):
    RESCHEDULE_TASK = "RESCHEDULE_TASK"
    UPDATE_ESTIMATE = "UPDATE_ESTIMATE"
    SPLIT_TASK = "SPLIT_TASK"


class AdjustmentOperation(BaseModel):
    """模型允许提出的最小任务修改操作。"""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    type: AdjustmentOperationType
    task_id: str = Field(min_length=1)
    expected_version: int = Field(ge=1)
    scheduled_date: date | None = None
    estimated_minutes: int | None = Field(default=None, ge=5, le=720)
    first_title: str | None = Field(default=None, min_length=1, max_length=160)
    first_estimated_minutes: int | None = Field(default=None, ge=5, le=720)
    second_title: str | None = Field(default=None, min_length=1, max_length=160)
    second_scheduled_date: date | None = None
    second_estimated_minutes: int | None = Field(default=None, ge=5, le=720)

    @model_validator(mode="after")
    def validate_fields_for_operation(self) -> Self:
        if (
            self.type == AdjustmentOperationType.RESCHEDULE_TASK
            and self.scheduled_date is None
        ):
            raise ValueError("重新安排任务必须提供 scheduled_date")
        if (
            self.type == AdjustmentOperationType.UPDATE_ESTIMATE
            and self.estimated_minutes is None
        ):
            raise ValueError("修改预计时长必须提供 estimated_minutes")
        if self.type == AdjustmentOperationType.SPLIT_TASK and any(
            value is None
            for value in (
                self.first_title,
                self.first_estimated_minutes,
                self.second_title,
                self.second_scheduled_date,
                self.second_estimated_minutes,
            )
        ):
            raise ValueError("拆分任务必须完整提供两个子任务")
        return self


class PlanAdjustmentDraft(BaseModel):
    """DeepSeek 只能生成的非权威计划调整草稿。"""

    summary: str = Field(min_length=1, max_length=500)
    operations: list[AdjustmentOperation] = Field(max_length=14)


def classify_adjustment_risk(
    draft: PlanAdjustmentDraft,
    *,
    plan_end_date: date,
) -> str:
    """根据已确认的产品边界判定 LOW/HIGH，不接受模型自报风险。"""

    split_count = sum(
        operation.type == AdjustmentOperationType.SPLIT_TASK
        for operation in draft.operations
    )
    dates = [
        candidate
        for operation in draft.operations
        for candidate in (operation.scheduled_date, operation.second_scheduled_date)
        if candidate is not None
    ]
    if len(draft.operations) > 3 or split_count > 1:
        return "HIGH"
    if any(candidate > plan_end_date for candidate in dates):
        return "HIGH"
    return "LOW"
