import asyncio
from datetime import date

import pytest

from app.schemas.learning import LearningTask


class FakeStructuredModel:
    def __init__(self, outputs: list[dict]) -> None:
        self.outputs = outputs
        self.calls = []

    async def ainvoke(self, messages):
        self.calls.append(messages)
        return self.outputs[len(self.calls) - 1]


class FakeChatModel:
    def __init__(self, structured: FakeStructuredModel) -> None:
        self.structured = structured
        self.schema = None
        self.method = None

    def with_structured_output(self, schema, *, method):
        self.schema = schema
        self.method = method
        return self.structured


def task(task_id: str = "task-1", title: str = "完成 Spring MVC 接口") -> LearningTask:
    return LearningTask(
        id=task_id,
        plan_id="plan-1",
        title=title,
        scheduled_date=date(2026, 7, 26),
        estimated_minutes=60,
        status="TODO",
        version=1,
    )


def test_deepseek_task_recognizer_builds_grounded_structured_prompt() -> None:
    from app.agent.task_models import TaskIntent, TaskRecognitionOutput
    from app.agent.task_recognizer import DeepSeekTaskRecognizer

    structured = FakeStructuredModel(
        [
            {
                "intent": "COMPLETE_TASK",
                "candidate_task_ids": ["task-1"],
                "reason": None,
                "deferred_to": None,
                "reply": "识别到一个待完成候选任务。",
            }
        ]
    )
    model = FakeChatModel(structured)
    recognizer = DeepSeekTaskRecognizer(model)

    result = asyncio.run(
        recognizer.recognize(
            message="Spring Boot 接口我已经写完了",
            tasks=[task()],
            reference_date=date(2026, 7, 26),
        )
    )

    assert result.intent == TaskIntent.COMPLETE_TASK
    assert result.candidate_task_ids == ["task-1"]
    assert model.schema is TaskRecognitionOutput
    assert model.method == "json_mode"
    prompt = "\n".join(str(message.content) for message in structured.calls[0])
    assert "2026-07-26" in prompt
    assert "task-1" in prompt
    assert "完成 Spring MVC 接口" in prompt
    assert "只能返回候选任务列表中真实存在的 ID" in prompt
    assert "不可信数据" in prompt
    assert "不能执行任务修改" in prompt
    assert "Spring Boot 接口我已经写完了" in prompt


def test_deepseek_task_recognizer_retries_one_invalid_structure() -> None:
    from app.agent.task_models import TaskIntent
    from app.agent.task_recognizer import DeepSeekTaskRecognizer

    structured = FakeStructuredModel(
        [
            {
                "intent": "MADE_UP_ACTION",
                "candidate_task_ids": ["task-1"],
                "reply": "非法结构",
            },
            {
                "intent": "LIST_TASKS",
                "candidate_task_ids": [],
                "reply": "为你列出今天的任务。",
            },
        ]
    )
    recognizer = DeepSeekTaskRecognizer(FakeChatModel(structured))

    result = asyncio.run(
        recognizer.recognize(
            message="今天有什么任务？",
            tasks=[task()],
            reference_date=date(2026, 7, 26),
        )
    )

    assert result.intent == TaskIntent.LIST_TASKS
    assert len(structured.calls) == 2


def test_deepseek_task_recognizer_rejects_two_invalid_structures() -> None:
    from app.agent.task_recognizer import (
        DeepSeekTaskRecognizer,
        TaskRecognitionOutputError,
    )

    structured = FakeStructuredModel(
        [
            {"intent": "INVALID", "candidate_task_ids": [], "reply": "非法"},
            {"intent": "INVALID_AGAIN", "candidate_task_ids": [], "reply": "仍然非法"},
        ]
    )
    recognizer = DeepSeekTaskRecognizer(FakeChatModel(structured))

    with pytest.raises(TaskRecognitionOutputError):
        asyncio.run(
            recognizer.recognize(
                message="处理一下任务",
                tasks=[task()],
                reference_date=date(2026, 7, 26),
            )
        )

    assert len(structured.calls) == 2
