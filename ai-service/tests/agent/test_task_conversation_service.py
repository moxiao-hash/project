import asyncio
from collections import deque
from datetime import date
from types import SimpleNamespace

import pytest

from app.agent.task_models import (
    TaskActionDraft,
    TaskCandidate,
    TaskRecognitionResult,
    TaskRecognitionStatus,
)
from app.clients.java_backend import JavaBackendError
from app.schemas.learning import LearningTask, LearningTaskStatus


def candidate(
    task_id: str = "task-1",
    title: str = "完成 Spring MVC 接口",
    *,
    version: int = 1,
) -> TaskCandidate:
    return TaskCandidate(
        id=task_id,
        title=title,
        status=LearningTaskStatus.TODO,
        version=version,
    )


def preview(
    *,
    status: LearningTaskStatus = LearningTaskStatus.COMPLETED,
    version: int = 1,
    reason: str | None = None,
    deferred_to: date | None = None,
) -> TaskRecognitionResult:
    task = candidate(version=version)
    return TaskRecognitionResult(
        status=TaskRecognitionStatus.PREVIEW_READY,
        reply="已识别任务操作，请确认。",
        candidate_tasks=[task],
        action_draft=TaskActionDraft(
            target_status=status,
            task_id=task.id,
            task_title=task.title,
            expected_version=version,
            reason=reason,
            deferred_to=deferred_to,
        ),
    )


class FakeRecognitionService:
    def __init__(self, results: list[TaskRecognitionResult]) -> None:
        self.results = deque(results)
        self.calls = []

    async def recognize(self, *, owner_id, message, target_date):
        self.calls.append((owner_id, message, target_date))
        return self.results.popleft()


class FakeJavaBackend:
    def __init__(self) -> None:
        self.created_executions = []
        self.confirmed_executions = []
        self.execution_updates = []
        self.task_changes = []
        self.change_error: JavaBackendError | None = None

    async def create_agent_execution(self, request):
        self.created_executions.append(request)
        return SimpleNamespace(id="execution-1", status="WAITING_CONFIRMATION")

    async def confirm_agent_execution(self, execution_id: str, *, owner_id: str):
        self.confirmed_executions.append((execution_id, owner_id))
        return SimpleNamespace(id=execution_id, status="PENDING")

    async def update_agent_execution(self, execution_id: str, request):
        self.execution_updates.append((execution_id, request))
        return SimpleNamespace(id=execution_id, status=request.status)

    async def change_learning_task_status(self, task_id: str, request):
        self.task_changes.append((task_id, request))
        if self.change_error is not None:
            raise self.change_error
        return LearningTask(
            id=task_id,
            plan_id="plan-1",
            title="完成 Spring MVC 接口",
            scheduled_date=request.scheduled_date or date(2026, 7, 26),
            estimated_minutes=60,
            status=request.status,
            version=request.expected_version + 1,
            completed_at="2026-07-26T01:00:00Z"
            if request.status == LearningTaskStatus.COMPLETED
            else None,
        )


def test_read_only_task_result_does_not_register_or_execute_write() -> None:
    from app.agent.task_conversation_models import TaskConversationStatus
    from app.agent.task_conversation_service import TaskConversationService

    recognition = FakeRecognitionService(
        [
            TaskRecognitionResult(
                status=TaskRecognitionStatus.LIST_READY,
                reply="今天有一个任务。",
                candidate_tasks=[candidate()],
            )
        ]
    )
    java = FakeJavaBackend()
    service = TaskConversationService(recognition, java)

    async def run_flow():
        conversation = await service.create_conversation(
            "user-1",
            date(2026, 7, 26),
        )
        return await service.send_message(
            conversation.conversation_id, "今天有什么任务？", "user-1"
        )

    result = asyncio.run(run_flow())

    assert result.status == TaskConversationStatus.COLLECTING
    assert result.candidate_tasks[0].id == "task-1"
    assert java.created_executions == []
    assert java.task_changes == []


def test_task_conversation_operations_reject_a_different_owner() -> None:
    from app.agent.task_conversation_service import (
        TaskConversationNotFoundError,
        TaskConversationService,
    )

    service = TaskConversationService(FakeRecognitionService([]), FakeJavaBackend())

    async def run_flow() -> None:
        conversation = await service.create_conversation("user-1", date(2026, 7, 26))
        with pytest.raises(TaskConversationNotFoundError):
            await service.get_conversation(conversation.conversation_id, "user-2")
        with pytest.raises(TaskConversationNotFoundError):
            await service.send_message(conversation.conversation_id, "越权消息", "user-2")
        with pytest.raises(TaskConversationNotFoundError):
            await service.confirm(conversation.conversation_id, "user-2")

    asyncio.run(run_flow())


def test_task_preview_waits_for_confirm_then_executes_exactly_once() -> None:
    from app.agent.task_conversation_models import TaskConversationStatus
    from app.agent.task_conversation_service import TaskConversationService

    recognition = FakeRecognitionService([preview(version=3)])
    java = FakeJavaBackend()
    service = TaskConversationService(recognition, java)

    async def run_flow():
        conversation = await service.create_conversation(
            "user-1",
            date(2026, 7, 26),
        )
        pending = await service.send_message(
            conversation.conversation_id,
            "Spring MVC 接口写完了",
            "user-1",
        )
        assert java.task_changes == []
        completed = await service.confirm(conversation.conversation_id, "user-1")
        repeated = await service.confirm(conversation.conversation_id, "user-1")
        return pending, completed, repeated

    pending, completed, repeated = asyncio.run(run_flow())

    assert pending.status == TaskConversationStatus.PREVIEW_READY
    assert pending.execution_id == "execution-1"
    assert completed.status == TaskConversationStatus.COMPLETED
    assert completed.updated_task is not None
    assert completed.updated_task.version == 4
    assert repeated.updated_task == completed.updated_task
    assert len(java.created_executions) == 1
    assert len(java.task_changes) == 1
    task_id, request = java.task_changes[0]
    assert task_id == "task-1"
    assert request.idempotency_key.startswith("task-action:")
    assert request.expected_version == 3
    assert request.status == LearningTaskStatus.COMPLETED
    assert java.confirmed_executions == [("execution-1", "user-1")]
    assert [request.status for _, request in java.execution_updates] == [
        "RUNNING",
        "SUCCEEDED",
    ]


def test_message_during_preview_replaces_draft_without_writing() -> None:
    from app.agent.task_conversation_models import TaskConversationStatus
    from app.agent.task_conversation_service import TaskConversationService

    recognition = FakeRecognitionService(
        [
            preview(),
            preview(
                status=LearningTaskStatus.DEFERRED,
                reason="今天时间不足",
                deferred_to=date(2026, 7, 28),
            ),
        ]
    )
    java = FakeJavaBackend()
    service = TaskConversationService(recognition, java)

    async def run_flow():
        conversation = await service.create_conversation(
            "user-1",
            date(2026, 7, 26),
        )
        await service.send_message(
            conversation.conversation_id, "这个任务完成了", "user-1"
        )
        revised = await service.send_message(
            conversation.conversation_id,
            "改成延期到 7 月 28 日，原因是今天时间不足",
            "user-1",
        )
        assert java.task_changes == []
        completed = await service.confirm(conversation.conversation_id, "user-1")
        return revised, completed

    revised, completed = asyncio.run(run_flow())

    assert revised.status == TaskConversationStatus.PREVIEW_READY
    assert revised.action_draft is not None
    assert revised.action_draft.target_status == LearningTaskStatus.DEFERRED
    assert completed.updated_task is not None
    assert completed.updated_task.status == LearningTaskStatus.DEFERRED
    assert java.task_changes[0][1].scheduled_date == date(2026, 7, 28)
    assert len(java.created_executions) == 1


def test_version_conflict_marks_execution_failed_and_remains_queryable() -> None:
    from app.agent.task_conversation_models import TaskConversationStatus
    from app.agent.task_conversation_service import (
        TaskConversationService,
        TaskVersionConflictError,
    )

    recognition = FakeRecognitionService([preview()])
    java = FakeJavaBackend()
    java.change_error = JavaBackendError(
        "Java 内部接口返回 HTTP 409",
        path="/internal/learning-tasks/task-1/status",
        status_code=409,
        detail="任务版本已变化",
    )
    service = TaskConversationService(recognition, java)

    async def run_flow():
        conversation = await service.create_conversation(
            "user-1",
            date(2026, 7, 26),
        )
        await service.send_message(
            conversation.conversation_id, "任务完成了", "user-1"
        )
        with pytest.raises(TaskVersionConflictError):
            await service.confirm(conversation.conversation_id, "user-1")
        return await service.get_conversation(conversation.conversation_id, "user-1")

    failed = asyncio.run(run_flow())

    assert failed.status == TaskConversationStatus.FAILED
    assert failed.error == "任务版本已变化"
    assert java.execution_updates[-1][1].status == "FAILED"
