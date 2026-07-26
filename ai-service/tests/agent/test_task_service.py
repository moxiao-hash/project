import asyncio
from datetime import date

import pytest

from app.agent.task_models import TaskIntent, TaskRecognitionOutput
from app.schemas.learning import LearningTask, LearningTaskStatus


def task(
    task_id: str,
    title: str,
    *,
    status: LearningTaskStatus = LearningTaskStatus.TODO,
    version: int = 1,
) -> LearningTask:
    return LearningTask(
        id=task_id,
        plan_id="plan-1",
        title=title,
        scheduled_date=date(2026, 7, 26),
        estimated_minutes=60,
        status=status,
        version=version,
    )


class FakeJavaBackend:
    def __init__(self, tasks: list[LearningTask]) -> None:
        self.tasks = tasks
        self.calls = []

    async def get_learning_tasks(
        self,
        owner_id: str,
        *,
        target_date: date,
    ) -> list[LearningTask]:
        self.calls.append((owner_id, target_date))
        return self.tasks


class FakeRecognizer:
    def __init__(self, output: TaskRecognitionOutput) -> None:
        self.output = output
        self.calls = []

    async def recognize(self, *, message, tasks, reference_date):
        self.calls.append((message, tasks, reference_date))
        return self.output


def output(
    intent: TaskIntent,
    candidate_ids: list[str] | None = None,
    *,
    reason: str | None = None,
    deferred_to: date | None = None,
    actual_minutes: int | None = None,
) -> TaskRecognitionOutput:
    return TaskRecognitionOutput(
        intent=intent,
        candidate_task_ids=candidate_ids or [],
        reason=reason,
        deferred_to=deferred_to,
        actual_minutes=actual_minutes,
        reply="模型识别结果。",
    )


def recognize(java_backend, recognizer, message: str = "处理一下今天的任务"):
    from app.agent.task_service import TaskRecognitionService

    service = TaskRecognitionService(recognizer, java_backend)
    return asyncio.run(
        service.recognize(
            owner_id="user-123",
            message=message,
            target_date=date(2026, 7, 26),
        )
    )


def test_task_service_returns_no_tasks_without_calling_model() -> None:
    from app.agent.task_models import TaskRecognitionStatus

    java_backend = FakeJavaBackend([])
    recognizer = FakeRecognizer(output(TaskIntent.LIST_TASKS))

    result = recognize(java_backend, recognizer, "今天有什么任务？")

    assert result.status == TaskRecognitionStatus.NO_TASKS
    assert result.candidate_tasks == []
    assert recognizer.calls == []
    assert java_backend.calls == [("user-123", date(2026, 7, 26))]


def test_task_service_lists_only_real_java_tasks() -> None:
    from app.agent.task_models import TaskRecognitionStatus

    java_tasks = [
        task("task-1", "完成 Spring MVC 接口"),
        task("task-2", "阅读 FastAPI 路由文档"),
    ]

    result = recognize(
        FakeJavaBackend(java_tasks),
        FakeRecognizer(output(TaskIntent.LIST_TASKS)),
        "今天有什么任务？",
    )

    assert result.status == TaskRecognitionStatus.LIST_READY
    assert [candidate.id for candidate in result.candidate_tasks] == [
        "task-1",
        "task-2",
    ]
    assert "完成 Spring MVC 接口" in result.reply
    assert "阅读 FastAPI 路由文档" in result.reply


def test_task_service_builds_read_only_preview_for_one_completed_candidate() -> None:
    from app.agent.task_models import TaskRecognitionStatus

    java_backend = FakeJavaBackend(
        [task("task-1", "完成 Spring MVC 接口", version=3)]
    )
    result = recognize(
        java_backend,
        FakeRecognizer(output(TaskIntent.COMPLETE_TASK, ["task-1"])),
        "Spring MVC 接口写完了",
    )

    assert result.status == TaskRecognitionStatus.PREVIEW_READY
    assert result.action_draft is not None
    assert result.action_draft.task_id == "task-1"
    assert result.action_draft.expected_version == 3
    assert result.action_draft.target_status == LearningTaskStatus.COMPLETED
    assert java_backend.calls == [("user-123", date(2026, 7, 26))]


def test_task_service_preserves_actual_minutes_in_completion_preview() -> None:
    result = recognize(
        FakeJavaBackend([task("task-1", "完成 Spring MVC 接口")]),
        FakeRecognizer(
            output(
                TaskIntent.COMPLETE_TASK,
                ["task-1"],
                actual_minutes=80,
            )
        ),
        "Spring MVC 接口完成了，用了 80 分钟",
    )

    assert result.action_draft is not None
    assert result.action_draft.actual_minutes == 80


def test_task_service_builds_skip_preview_with_reason() -> None:
    from app.agent.task_models import TaskRecognitionStatus

    result = recognize(
        FakeJavaBackend([task("task-1", "阅读数据库事务文档")]),
        FakeRecognizer(
            output(
                TaskIntent.SKIP_TASK,
                ["task-1"],
                reason="今天优先处理项目编码",
            )
        ),
    )

    assert result.status == TaskRecognitionStatus.PREVIEW_READY
    assert result.action_draft is not None
    assert result.action_draft.target_status == LearningTaskStatus.SKIPPED
    assert result.action_draft.reason == "今天优先处理项目编码"


def test_task_service_builds_defer_preview_with_future_date() -> None:
    from app.agent.task_models import TaskRecognitionStatus

    result = recognize(
        FakeJavaBackend([task("task-1", "完成 FastAPI 路由练习")]),
        FakeRecognizer(
            output(
                TaskIntent.DEFER_TASK,
                ["task-1"],
                reason="今天时间不足",
                deferred_to=date(2026, 7, 28),
            )
        ),
    )

    assert result.status == TaskRecognitionStatus.PREVIEW_READY
    assert result.action_draft is not None
    assert result.action_draft.target_status == LearningTaskStatus.DEFERRED
    assert result.action_draft.deferred_to == date(2026, 7, 28)


def test_task_service_requires_clarification_for_multiple_candidates() -> None:
    from app.agent.task_models import TaskRecognitionStatus

    result = recognize(
        FakeJavaBackend(
            [
                task("task-1", "Spring Boot 登录接口"),
                task("task-2", "Spring Boot 任务接口"),
            ]
        ),
        FakeRecognizer(
            output(TaskIntent.COMPLETE_TASK, ["task-1", "task-2"])
        ),
        "Spring Boot 写完了",
    )

    assert result.status == TaskRecognitionStatus.CLARIFICATION_REQUIRED
    assert result.action_draft is None
    assert [candidate.id for candidate in result.candidate_tasks] == [
        "task-1",
        "task-2",
    ]
    assert "Spring Boot 登录接口" in result.reply
    assert "Spring Boot 任务接口" in result.reply


def test_task_service_rejects_model_task_id_not_returned_by_java() -> None:
    from app.agent.task_models import TaskRecognitionStatus

    result = recognize(
        FakeJavaBackend([task("task-1", "真实任务")]),
        FakeRecognizer(output(TaskIntent.COMPLETE_TASK, ["invented-task"])),
    )

    assert result.status == TaskRecognitionStatus.CLARIFICATION_REQUIRED
    assert result.action_draft is None
    assert [candidate.id for candidate in result.candidate_tasks] == ["task-1"]


@pytest.mark.parametrize(
    "recognition",
    [
        output(TaskIntent.SKIP_TASK, ["task-1"]),
        output(
            TaskIntent.DEFER_TASK,
            ["task-1"],
            deferred_to=date(2026, 7, 28),
        ),
        output(
            TaskIntent.DEFER_TASK,
            ["task-1"],
            reason="今天时间不足",
        ),
        output(
            TaskIntent.DEFER_TASK,
            ["task-1"],
            reason="今天时间不足",
            deferred_to=date(2026, 7, 26),
        ),
    ],
)
def test_task_service_requires_skip_and_defer_details(
    recognition: TaskRecognitionOutput,
) -> None:
    from app.agent.task_models import TaskRecognitionStatus

    result = recognize(
        FakeJavaBackend([task("task-1", "任务字段校验")]),
        FakeRecognizer(recognition),
    )

    assert result.status == TaskRecognitionStatus.CLARIFICATION_REQUIRED
    assert result.action_draft is None


@pytest.mark.parametrize(
    "current_status",
    [LearningTaskStatus.COMPLETED, LearningTaskStatus.SKIPPED],
)
def test_task_service_rejects_terminal_task(
    current_status: LearningTaskStatus,
) -> None:
    from app.agent.task_models import TaskRecognitionStatus

    result = recognize(
        FakeJavaBackend(
            [task("task-1", "已经结束的任务", status=current_status)]
        ),
        FakeRecognizer(output(TaskIntent.COMPLETE_TASK, ["task-1"])),
    )

    assert result.status == TaskRecognitionStatus.CLARIFICATION_REQUIRED
    assert result.action_draft is None


def test_task_service_marks_unknown_intent_as_unsupported() -> None:
    from app.agent.task_models import TaskRecognitionStatus

    result = recognize(
        FakeJavaBackend([task("task-1", "学习任务")]),
        FakeRecognizer(output(TaskIntent.UNKNOWN)),
        "帮我写一首诗",
    )

    assert result.status == TaskRecognitionStatus.UNSUPPORTED
    assert result.action_draft is None
