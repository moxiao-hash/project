"""对话式任务识别使用的强类型中间结果。

大模型只能产生 ``TaskRecognitionOutput``。应用服务验证候选任务归属和业务字段后，
才会构造 ``TaskActionDraft``；草稿仍是只读预览，不代表任务已经执行。
"""

from datetime import date
from enum import StrEnum
from typing import Self

from pydantic import BaseModel, Field, model_validator

from app.schemas.learning import JavaContractModel, LearningTaskStatus


class TaskIntent(StrEnum):
    """用户消息中支持的任务意图。"""

    LIST_TASKS = "LIST_TASKS"
    COMPLETE_TASK = "COMPLETE_TASK"
    SKIP_TASK = "SKIP_TASK"
    DEFER_TASK = "DEFER_TASK"
    UNKNOWN = "UNKNOWN"


class TaskRecognitionStatus(StrEnum):
    """确定性代码处理模型输出后的安全状态。"""

    LIST_READY = "LIST_READY"
    PREVIEW_READY = "PREVIEW_READY"
    CLARIFICATION_REQUIRED = "CLARIFICATION_REQUIRED"
    NO_TASKS = "NO_TASKS"
    UNSUPPORTED = "UNSUPPORTED"


class TaskRecognitionOutput(BaseModel):
    """模型返回的非权威结构化识别结果。"""

    intent: TaskIntent
    candidate_task_ids: list[str] = Field(default_factory=list, max_length=20)
    reason: str | None = Field(default=None, max_length=255)
    deferred_to: date | None = None
    actual_minutes: int | None = Field(default=None, ge=1, le=720)
    reply: str = Field(min_length=1, max_length=1000)


class TaskCandidate(JavaContractModel):
    """可以安全展示给用户的真实 Java 任务快照。"""

    id: str
    title: str
    status: LearningTaskStatus
    version: int = Field(ge=1)


class TaskActionDraft(JavaContractModel):
    """等待 4.5 用户确认的任务操作草稿。"""

    target_status: LearningTaskStatus
    task_id: str
    task_title: str
    expected_version: int = Field(ge=1)
    reason: str | None = Field(default=None, max_length=255)
    deferred_to: date | None = None
    actual_minutes: int | None = Field(default=None, ge=1, le=720)


class TaskRecognitionResult(BaseModel):
    """任务识别应用服务的最终只读结果。"""

    status: TaskRecognitionStatus
    reply: str = Field(min_length=1, max_length=2000)
    candidate_tasks: list[TaskCandidate] = Field(default_factory=list)
    action_draft: TaskActionDraft | None = None

    @model_validator(mode="after")
    def validate_action_draft_matches_status(self) -> Self:
        """只有预览就绪状态可以携带可执行候选，避免调用方误判。"""

        if self.status == TaskRecognitionStatus.PREVIEW_READY:
            if self.action_draft is None:
                raise ValueError("预览就绪时必须提供任务操作草稿")
        elif self.action_draft is not None:
            raise ValueError("非预览状态不能携带任务操作草稿")
        return self
