import asyncio
import base64
import gc
import weakref
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
from app.persistence.agent_state import AgentPersistence
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


class BarrierRecognitionService(FakeRecognitionService):
    def __init__(self) -> None:
        super().__init__(
            [
                TaskRecognitionResult(
                    status=TaskRecognitionStatus.LIST_READY,
                    reply="今天有一个任务。",
                    candidate_tasks=[candidate()],
                )
            ]
        )
        self.started = asyncio.Event()
        self.release = asyncio.Event()

    async def recognize(self, *, owner_id, message, target_date):
        self.started.set()
        await self.release.wait()
        return await super().recognize(
            owner_id=owner_id,
            message=message,
            target_date=target_date,
        )


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
        await service.send_message(conversation.conversation_id, "这个任务完成了", "user-1")
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
        await service.send_message(conversation.conversation_id, "任务完成了", "user-1")
        with pytest.raises(TaskVersionConflictError):
            await service.confirm(conversation.conversation_id, "user-1")
        return await service.get_conversation(conversation.conversation_id, "user-1")

    failed = asyncio.run(run_flow())

    assert failed.status == TaskConversationStatus.FAILED
    assert failed.error == "任务版本已变化"
    assert java.execution_updates[-1][1].status == "FAILED"


def test_clearing_task_runtime_releases_recognizer_but_keeps_conversation() -> None:
    from app.agent.task_conversation_service import TaskConversationService

    recognition = FakeRecognitionService([])
    recognition_ref = weakref.ref(recognition)
    service = TaskConversationService(recognition, FakeJavaBackend())

    async def create() -> str:
        snapshot = await service.create_conversation("user-1", date(2026, 7, 26))
        return snapshot.conversation_id

    conversation_id = asyncio.run(create())
    service.clear_runtime()
    del recognition
    gc.collect()

    assert recognition_ref() is None
    snapshot = asyncio.run(service.get_conversation(conversation_id, "user-1"))
    assert snapshot.conversation_id == conversation_id


def test_active_task_request_leases_recognizer_across_runtime_clear() -> None:
    from app.agent.task_conversation_service import TaskConversationService

    recognition_holder = [BarrierRecognitionService()]
    recognition_ref = weakref.ref(recognition_holder[0])
    service = TaskConversationService(recognition_holder[0], FakeJavaBackend())

    async def run_flow() -> None:
        conversation = await service.create_conversation(
            "user-1",
            date(2026, 7, 26),
        )
        in_flight = asyncio.create_task(
            service.send_message(
                conversation.conversation_id,
                "今天有什么任务？",
                "user-1",
            )
        )
        await recognition_holder[0].started.wait()
        service.clear_runtime()
        assert recognition_ref() is not None
        recognition_holder[0].release.set()
        snapshot = await in_flight
        assert snapshot.reply == "今天有一个任务。"

    asyncio.run(run_flow())
    recognition_holder.clear()
    gc.collect()
    assert recognition_ref() is None


def test_task_preview_executes_once_after_process_restart(tmp_path) -> None:
    from app.agent.task_conversation_models import TaskConversationStatus
    from app.agent.task_conversation_service import TaskConversationService

    key = base64.b64encode(bytes(range(32))).decode()
    db_path = tmp_path / "agent-state.sqlite3"
    java = FakeJavaBackend()

    async def run_flow() -> None:
        first_persistence = await AgentPersistence.open(db_path, key)
        first_service = TaskConversationService(
            FakeRecognitionService([preview(version=3)]),
            java,
            persistence=first_persistence,
        )
        created = await first_service.create_conversation(
            "user-1",
            date(2026, 7, 26),
        )
        pending = await first_service.send_message(
            created.conversation_id,
            "任务完成了",
            "user-1",
        )
        assert pending.status == TaskConversationStatus.PREVIEW_READY
        await first_persistence.close()

        second_persistence = await AgentPersistence.open(db_path, key)
        second_service = TaskConversationService(
            FakeRecognitionService([]),
            java,
            persistence=second_persistence,
        )
        completed = await second_service.confirm(created.conversation_id, "user-1")
        repeated = await second_service.confirm(created.conversation_id, "user-1")
        assert completed.status == TaskConversationStatus.COMPLETED
        assert repeated.updated_task == completed.updated_task
        await second_persistence.close()

    asyncio.run(run_flow())
    assert len(java.task_changes) == 1


def test_restarted_conversation_rebuilds_one_shared_lock(tmp_path) -> None:
    from app.agent.task_conversation_service import (
        TaskConversationBusyError,
        TaskConversationService,
    )

    key = base64.b64encode(bytes(range(32))).decode()
    db_path = tmp_path / "agent-state.sqlite3"

    async def run_flow() -> None:
        first_persistence = await AgentPersistence.open(db_path, key)
        first = TaskConversationService(
            FakeRecognitionService([]),
            FakeJavaBackend(),
            persistence=first_persistence,
        )
        created = await first.create_conversation("user-1", date(2026, 7, 26))
        await first_persistence.close()

        second_persistence = await AgentPersistence.open(db_path, key)
        try:
            original_load = second_persistence.store.load
            first_loading = asyncio.Event()
            release_load = asyncio.Event()

            async def synchronized_load(**kwargs):
                first_loading.set()
                await release_load.wait()
                return await original_load(**kwargs)

            second_persistence.store.load = synchronized_load
            recognition = BarrierRecognitionService()
            second = TaskConversationService(
                recognition,
                FakeJavaBackend(),
                persistence=second_persistence,
            )
            first_request = asyncio.create_task(
                second.send_message(created.conversation_id, "查询任务", "user-1")
            )
            await first_loading.wait()
            second_request = asyncio.create_task(
                second.send_message(created.conversation_id, "重复查询", "user-1")
            )
            await asyncio.sleep(0)
            release_load.set()
            await recognition.started.wait()
            try:
                with pytest.raises(TaskConversationBusyError):
                    await asyncio.wait_for(second_request, timeout=0.2)
            finally:
                recognition.release.set()
                await asyncio.gather(first_request, return_exceptions=True)
        finally:
            await second_persistence.close()

    asyncio.run(run_flow())


def test_task_checkpoint_recovers_when_snapshot_save_fails(tmp_path) -> None:
    from app.agent.task_conversation_models import TaskConversationStatus
    from app.agent.task_conversation_service import TaskConversationService

    key = base64.b64encode(bytes(range(32))).decode()
    db_path = tmp_path / "agent-state.sqlite3"
    java = FakeJavaBackend()

    async def run_flow() -> None:
        first_persistence = await AgentPersistence.open(db_path, key)
        first = TaskConversationService(
            FakeRecognitionService([preview(version=3)]),
            java,
            persistence=first_persistence,
        )
        created = await first.create_conversation(
            "user-1",
            date(2026, 7, 26),
        )
        original_save = first_persistence.store.save

        async def fail_save(**_kwargs):
            raise OSError("metadata unavailable")

        first_persistence.store.save = fail_save
        pending = await first.send_message(
            created.conversation_id,
            "任务完成了",
            "user-1",
        )
        assert pending.status == TaskConversationStatus.PREVIEW_READY
        first_persistence.store.save = original_save
        await first_persistence.close()

        second_persistence = await AgentPersistence.open(db_path, key)
        second = TaskConversationService(
            FakeRecognitionService([]),
            java,
            persistence=second_persistence,
        )
        restored = await second.get_conversation(
            created.conversation_id,
            "user-1",
        )
        assert restored.status == TaskConversationStatus.PREVIEW_READY
        await second.confirm(created.conversation_id, "user-1")
        await second.confirm(created.conversation_id, "user-1")
        await second_persistence.close()

    asyncio.run(run_flow())
    assert len(java.task_changes) == 1
