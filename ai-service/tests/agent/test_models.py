from datetime import date

import pytest
from pydantic import ValidationError

from app.agent.models import (
    ConversationStatus,
    PlanDraft,
    PlannerTurn,
    PlanTaskDraft,
)


def valid_draft() -> PlanDraft:
    return PlanDraft(
        title="Java 后端阶段学习计划",
        start_date=date(2026, 7, 26),
        end_date=date(2026, 8, 2),
        tasks=[
            PlanTaskDraft(
                title="完成 Spring MVC 参数接收练习",
                scheduled_date=date(2026, 7, 27),
                estimated_minutes=60,
            )
        ],
    )


def test_plan_draft_accepts_tasks_inside_plan_date_range() -> None:
    draft = valid_draft()

    assert draft.tasks[0].estimated_minutes == 60
    assert draft.start_date <= draft.tasks[0].scheduled_date <= draft.end_date


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("scheduled_date", date(2026, 8, 3)),
        ("estimated_minutes", 4),
        ("estimated_minutes", 721),
    ],
)
def test_plan_draft_rejects_unsafe_task_boundaries(field: str, value: object) -> None:
    task = {
        "title": "越界任务",
        "scheduled_date": date(2026, 7, 27),
        "estimated_minutes": 60,
    }
    task[field] = value

    with pytest.raises(ValidationError):
        PlanDraft(
            title="不合法计划",
            start_date=date(2026, 7, 26),
            end_date=date(2026, 8, 2),
            tasks=[task],
        )


def test_plan_draft_rejects_more_than_one_hundred_tasks() -> None:
    task = {
        "title": "重复任务",
        "scheduled_date": date(2026, 7, 27),
        "estimated_minutes": 30,
    }

    with pytest.raises(ValidationError):
        PlanDraft(
            title="任务过多",
            start_date=date(2026, 7, 26),
            end_date=date(2026, 8, 2),
            tasks=[task] * 101,
        )


def test_planner_turn_requires_draft_only_when_ready() -> None:
    collecting = PlannerTurn(
        reply="你每天大约能学习多长时间？",
        status=ConversationStatus.COLLECTING,
    )
    ready = PlannerTurn(
        reply="计划已经生成，请确认或告诉我需要修改的地方。",
        status=ConversationStatus.DRAFT_READY,
        draft=valid_draft(),
    )

    assert collecting.draft is None
    assert ready.draft is not None

    with pytest.raises(ValidationError):
        PlannerTurn(
            reply="计划已经生成。",
            status=ConversationStatus.DRAFT_READY,
        )

    with pytest.raises(ValidationError):
        PlannerTurn(
            reply="还需要了解你的时间。",
            status=ConversationStatus.COLLECTING,
            draft=valid_draft(),
        )
