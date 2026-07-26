from datetime import date

from app.agent.adjustment_models import (
    AdjustmentOperation,
    AdjustmentOperationType,
    PlanAdjustmentDraft,
    classify_adjustment_risk,
)


def reschedule(task_id: str, target_date: date) -> AdjustmentOperation:
    return AdjustmentOperation(
        type=AdjustmentOperationType.RESCHEDULE_TASK,
        task_id=task_id,
        expected_version=1,
        scheduled_date=target_date,
    )


def test_three_operations_inside_plan_are_low_risk() -> None:
    draft = PlanAdjustmentDraft(
        summary="把三个逾期任务移到后续空闲日期。",
        operations=[
            reschedule("task-1", date(2026, 7, 28)),
            reschedule("task-2", date(2026, 7, 29)),
            reschedule("task-3", date(2026, 7, 30)),
        ],
    )

    risk = classify_adjustment_risk(
        draft,
        plan_end_date=date(2026, 8, 2),
    )

    assert risk == "LOW"


def test_more_than_three_operations_are_high_risk() -> None:
    draft = PlanAdjustmentDraft(
        summary="重新安排四个任务。",
        operations=[
            reschedule(f"task-{index}", date(2026, 7, 27 + index))
            for index in range(1, 5)
        ],
    )

    assert classify_adjustment_risk(
        draft,
        plan_end_date=date(2026, 8, 2),
    ) == "HIGH"


def test_date_beyond_plan_end_is_high_risk() -> None:
    draft = PlanAdjustmentDraft(
        summary="把任务移动到现有计划范围外。",
        operations=[reschedule("task-1", date(2026, 8, 3))],
    )

    assert classify_adjustment_risk(
        draft,
        plan_end_date=date(2026, 8, 2),
    ) == "HIGH"


def test_one_split_can_be_low_risk() -> None:
    draft = PlanAdjustmentDraft(
        summary="把一个过大的任务拆成两部分。",
        operations=[
            AdjustmentOperation(
                type=AdjustmentOperationType.SPLIT_TASK,
                task_id="task-1",
                expected_version=1,
                first_title="实现登录接口（一）",
                first_estimated_minutes=45,
                second_title="实现登录接口（二）",
                second_scheduled_date=date(2026, 7, 29),
                second_estimated_minutes=45,
            )
        ],
    )

    assert classify_adjustment_risk(
        draft,
        plan_end_date=date(2026, 8, 2),
    ) == "LOW"
