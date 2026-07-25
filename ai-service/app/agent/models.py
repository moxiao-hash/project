"""学习计划 Agent 内部使用的强类型状态。

大模型返回的是不可信输入。这里先用 Pydantic 校验标题长度、日期范围、任务数量和
学习时长，只有通过校验的草稿才允许进入“等待用户确认”阶段。
"""

from datetime import date
from enum import StrEnum
from typing import Self

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class ConversationStatus(StrEnum):
    """一次学习计划对话在 Python 编排层的生命周期。"""

    COLLECTING = "COLLECTING"
    DRAFT_READY = "DRAFT_READY"
    SAVING = "SAVING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class PlanTaskDraft(BaseModel):
    """尚未写入 Java 后端的单个学习任务。"""

    title: str = Field(min_length=1, max_length=160)
    scheduled_date: date
    estimated_minutes: int = Field(ge=5, le=720)


class PlanDraft(BaseModel):
    """供用户预览、修改和确认的完整学习计划。"""

    title: str = Field(min_length=1, max_length=120)
    start_date: date
    end_date: date
    tasks: list[PlanTaskDraft] = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def validate_date_range(self) -> Self:
        """保证计划自身及其任务都处于同一个有效日期范围内。"""

        if self.end_date < self.start_date:
            raise ValueError("计划结束日期不能早于开始日期")
        if any(
            task.scheduled_date < self.start_date
            or task.scheduled_date > self.end_date
            for task in self.tasks
        ):
            raise ValueError("任务日期必须位于计划日期范围内")
        return self


class PlannerTurn(BaseModel):
    """模型一次回复的结构化结果。

    收集信息时不能夹带半成品草稿；进入 DRAFT_READY 时则必须提供一个已经通过
    领域校验的草稿。这个互斥规则可以让 API 调用方准确判断是否应展示确认表单。
    """

    reply: str = Field(min_length=1)
    status: ConversationStatus
    draft: PlanDraft | None = None

    @model_validator(mode="after")
    def validate_draft_matches_status(self) -> Self:
        if self.status not in {
            ConversationStatus.COLLECTING,
            ConversationStatus.DRAFT_READY,
        }:
            raise ValueError("Planner 只能返回 COLLECTING 或 DRAFT_READY")
        if self.status == ConversationStatus.DRAFT_READY and self.draft is None:
            raise ValueError("草稿就绪时必须返回 draft")
        if self.status == ConversationStatus.COLLECTING and self.draft is not None:
            raise ValueError("收集信息阶段不能返回 draft")
        return self


class ConversationSnapshot(BaseModel):
    """HTTP 层可安全返回的会话快照，不暴露模型内部推理内容。"""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    conversation_id: str
    owner_id: str
    goal_id: str
    status: ConversationStatus
    reply: str = ""
    draft: PlanDraft | None = None
    saved_plan_id: str | None = None
    error: str | None = None


class CreateConversationRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    owner_id: str = Field(min_length=1)
    goal_id: str = Field(min_length=1)


class SendMessageRequest(BaseModel):
    message: str = Field(min_length=1, max_length=10_000)
