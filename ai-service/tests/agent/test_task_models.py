from datetime import date

import pytest
from pydantic import ValidationError


def test_task_recognition_output_accepts_supported_intents() -> None:
    from app.agent.task_models import TaskIntent, TaskRecognitionOutput

    for intent in TaskIntent:
        output = TaskRecognitionOutput(
            intent=intent,
            candidate_task_ids=["task-1"] if intent != TaskIntent.LIST_TASKS else [],
            reply="已识别用户的任务意图。",
        )
        assert output.intent == intent


def test_preview_ready_result_requires_action_draft() -> None:
    from app.agent.task_models import (
        TaskActionDraft,
        TaskRecognitionResult,
        TaskRecognitionStatus,
    )
    from app.schemas.learning import LearningTaskStatus

    draft = TaskActionDraft(
        target_status=LearningTaskStatus.COMPLETED,
        task_id="task-1",
        task_title="完成 Spring MVC 接口",
        expected_version=1,
    )
    result = TaskRecognitionResult(
        status=TaskRecognitionStatus.PREVIEW_READY,
        reply="请确认是否完成该任务。",
        action_draft=draft,
    )

    assert result.action_draft == draft

    with pytest.raises(ValidationError):
        TaskRecognitionResult(
            status=TaskRecognitionStatus.PREVIEW_READY,
            reply="缺少操作草稿。",
        )

    with pytest.raises(ValidationError):
        TaskRecognitionResult(
            status=TaskRecognitionStatus.CLARIFICATION_REQUIRED,
            reply="请补充信息。",
            action_draft=draft,
        )


def test_task_action_draft_preserves_deferred_target() -> None:
    from app.agent.task_models import TaskActionDraft
    from app.schemas.learning import LearningTaskStatus

    draft = TaskActionDraft(
        target_status=LearningTaskStatus.DEFERRED,
        task_id="task-1",
        task_title="完成 FastAPI 路由练习",
        expected_version=3,
        reason="今天时间不足",
        deferred_to=date(2026, 7, 28),
    )

    assert draft.expected_version == 3
    assert draft.deferred_to == date(2026, 7, 28)
