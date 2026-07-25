"""对话式任务识别的确定性应用服务。"""

from datetime import date

from app.agent.task_models import (
    TaskActionDraft,
    TaskCandidate,
    TaskIntent,
    TaskRecognitionOutput,
    TaskRecognitionResult,
    TaskRecognitionStatus,
)
from app.agent.task_recognizer import TaskIntentRecognizer
from app.clients.java_backend import JavaBackendClient
from app.schemas.learning import LearningTask, LearningTaskStatus


class TaskRecognitionService:
    """把非权威模型输出解析成安全、只读的任务操作候选。"""

    def __init__(
        self,
        recognizer: TaskIntentRecognizer,
        java_backend: JavaBackendClient,
    ) -> None:
        self._recognizer = recognizer
        self._java_backend = java_backend

    async def recognize(
        self,
        *,
        owner_id: str,
        message: str,
        target_date: date,
    ) -> TaskRecognitionResult:
        """识别任务意图；本方法不会调用任何 Java 写接口。"""

        tasks = await self._java_backend.get_learning_tasks(
            owner_id,
            target_date=target_date,
        )
        if not tasks:
            return TaskRecognitionResult(
                status=TaskRecognitionStatus.NO_TASKS,
                reply=f"{target_date.isoformat()} 没有安排学习任务。",
            )

        output = await self._recognizer.recognize(
            message=message,
            tasks=tasks,
            reference_date=target_date,
        )
        if output.intent == TaskIntent.LIST_TASKS:
            return TaskRecognitionResult(
                status=TaskRecognitionStatus.LIST_READY,
                reply=self._format_task_list(
                    f"{target_date.isoformat()} 的学习任务：",
                    tasks,
                ),
                candidate_tasks=self._to_candidates(tasks),
            )
        if output.intent == TaskIntent.UNKNOWN:
            return TaskRecognitionResult(
                status=TaskRecognitionStatus.UNSUPPORTED,
                reply="我还不能确定这是任务查询、完成、跳过还是延期请求，请换一种说法。",
                candidate_tasks=self._to_candidates(tasks),
            )
        return self._resolve_action(output, tasks, target_date)

    def _resolve_action(
        self,
        output: TaskRecognitionOutput,
        tasks: list[LearningTask],
        reference_date: date,
    ) -> TaskRecognitionResult:
        task_by_id = {task.id: task for task in tasks}
        unique_ids = list(dict.fromkeys(output.candidate_task_ids))
        if not unique_ids or any(task_id not in task_by_id for task_id in unique_ids):
            return self._clarification(
                "没有找到唯一且有效的候选任务，请从以下任务中明确选择：",
                tasks,
            )

        selected = [task_by_id[task_id] for task_id in unique_ids]
        if len(selected) != 1:
            return self._clarification(
                "你的描述可能对应多个任务，请明确选择其中一个：",
                selected,
            )

        task = selected[0]
        if task.status not in {
            LearningTaskStatus.TODO,
            LearningTaskStatus.DEFERRED,
        }:
            return self._clarification(
                f"“{task.title}”已经是 {task.status.value} 状态，请选择其他任务：",
                tasks,
            )

        reason = output.reason.strip() if output.reason else None
        if output.intent == TaskIntent.SKIP_TASK and not reason:
            return self._clarification(
                f"跳过“{task.title}”需要说明原因，请补充原因。",
                [task],
            )
        if output.intent == TaskIntent.DEFER_TASK:
            if not reason:
                return self._clarification(
                    f"延期“{task.title}”需要说明原因，请补充原因。",
                    [task],
                )
            if output.deferred_to is None:
                return self._clarification(
                    f"请说明要把“{task.title}”延期到哪一天。",
                    [task],
                )
            if not output.deferred_to > reference_date:
                return self._clarification(
                    f"延期日期必须晚于 {reference_date.isoformat()}，请提供新的日期。",
                    [task],
                )

        target_status = {
            TaskIntent.COMPLETE_TASK: LearningTaskStatus.COMPLETED,
            TaskIntent.SKIP_TASK: LearningTaskStatus.SKIPPED,
            TaskIntent.DEFER_TASK: LearningTaskStatus.DEFERRED,
        }[output.intent]
        draft = TaskActionDraft(
            target_status=target_status,
            task_id=task.id,
            task_title=task.title,
            expected_version=task.version,
            reason=reason,
            deferred_to=(
                output.deferred_to
                if output.intent == TaskIntent.DEFER_TASK
                else None
            ),
        )
        return TaskRecognitionResult(
            status=TaskRecognitionStatus.PREVIEW_READY,
            reply=f"已识别对“{task.title}”的操作，请在执行前确认。",
            candidate_tasks=self._to_candidates([task]),
            action_draft=draft,
        )

    def _clarification(
        self,
        message: str,
        tasks: list[LearningTask],
    ) -> TaskRecognitionResult:
        return TaskRecognitionResult(
            status=TaskRecognitionStatus.CLARIFICATION_REQUIRED,
            reply=self._format_task_list(message, tasks),
            candidate_tasks=self._to_candidates(tasks),
        )

    @staticmethod
    def _to_candidates(tasks: list[LearningTask]) -> list[TaskCandidate]:
        return [
            TaskCandidate(
                id=task.id,
                title=task.title,
                status=task.status,
                version=task.version,
            )
            for task in tasks
        ]

    @staticmethod
    def _format_task_list(prefix: str, tasks: list[LearningTask]) -> str:
        lines = [
            f"{index}. {task.title}（{task.status.value}）"
            for index, task in enumerate(tasks, start=1)
        ]
        return "\n".join([prefix, *lines])
