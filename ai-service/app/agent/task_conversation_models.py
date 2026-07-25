"""任务操作会话对 FastAPI 暴露的安全快照。"""

from datetime import date
from enum import StrEnum
from typing import Self

from pydantic import Field, model_validator

from app.agent.task_models import TaskActionDraft, TaskCandidate
from app.schemas.learning import JavaContractModel, LearningTask


class TaskConversationStatus(StrEnum):
    """任务会话从识别到确认执行的生命周期。"""

    COLLECTING = "COLLECTING"
    PREVIEW_READY = "PREVIEW_READY"
    EXECUTING = "EXECUTING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class CreateTaskConversationRequest(JavaContractModel):
    owner_id: str = Field(min_length=1)
    target_date: date


class TaskConversationSnapshot(JavaContractModel):
    """不会暴露模型推理内容的任务会话响应。"""

    conversation_id: str
    owner_id: str
    target_date: date
    status: TaskConversationStatus
    reply: str = Field(min_length=1, max_length=2000)
    candidate_tasks: list[TaskCandidate] = Field(default_factory=list)
    action_draft: TaskActionDraft | None = None
    execution_id: str | None = None
    updated_task: LearningTask | None = None
    error: str | None = None

    @model_validator(mode="after")
    def validate_preview_has_draft(self) -> Self:
        if (
            self.status == TaskConversationStatus.PREVIEW_READY
            and self.action_draft is None
        ):
            raise ValueError("任务操作预览就绪时必须提供 action_draft")
        return self
