from datetime import date

import pytest
from pydantic import ValidationError

from app.schemas.learning import LearningTaskStatus


def test_task_conversation_preview_serializes_camel_case_contract() -> None:
    from app.agent.task_conversation_models import (
        CreateTaskConversationRequest,
        TaskConversationSnapshot,
        TaskConversationStatus,
    )
    from app.agent.task_models import TaskActionDraft, TaskCandidate

    request = CreateTaskConversationRequest(
        owner_id="user-1",
        target_date=date(2026, 7, 26),
    )
    snapshot = TaskConversationSnapshot(
        conversation_id="conversation-1",
        owner_id="user-1",
        target_date=date(2026, 7, 26),
        status=TaskConversationStatus.PREVIEW_READY,
        reply="请确认任务操作。",
        candidate_tasks=[
            TaskCandidate(
                id="task-1",
                title="完成 Spring MVC 接口",
                status=LearningTaskStatus.TODO,
                version=3,
            )
        ],
        action_draft=TaskActionDraft(
            target_status=LearningTaskStatus.COMPLETED,
            task_id="task-1",
            task_title="完成 Spring MVC 接口",
            expected_version=3,
        ),
        execution_id="execution-1",
    )

    assert request.model_dump(by_alias=True, mode="json") == {
        "ownerId": "user-1",
        "targetDate": "2026-07-26",
    }
    body = snapshot.model_dump(by_alias=True, mode="json")
    assert body["conversationId"] == "conversation-1"
    assert body["candidateTasks"][0]["id"] == "task-1"
    assert body["actionDraft"]["targetStatus"] == "COMPLETED"
    assert body["actionDraft"]["expectedVersion"] == 3
    assert body["executionId"] == "execution-1"


def test_preview_ready_task_conversation_requires_action_draft() -> None:
    from app.agent.task_conversation_models import (
        TaskConversationSnapshot,
        TaskConversationStatus,
    )

    with pytest.raises(ValidationError):
        TaskConversationSnapshot(
            conversation_id="conversation-1",
            owner_id="user-1",
            target_date=date(2026, 7, 26),
            status=TaskConversationStatus.PREVIEW_READY,
            reply="缺少草稿。",
        )


def test_task_execution_request_uses_high_risk_task_governance() -> None:
    from app.schemas.agent import CreateTaskAgentExecutionRequest

    request = CreateTaskAgentExecutionRequest(
        owner_id="user-1",
        idempotency_key="task-action:conversation-1",
        summary="修改学习任务状态并等待用户确认",
    )

    assert request.model_dump(by_alias=True) == {
        "ownerId": "user-1",
        "idempotencyKey": "task-action:conversation-1",
        "summary": "修改学习任务状态并等待用户确认",
        "executionType": "TASK_STATUS_CHANGE",
        "triggerType": "USER_REQUEST",
        "riskLevel": "HIGH",
        "requiredScope": "TASK_MANAGEMENT",
    }
