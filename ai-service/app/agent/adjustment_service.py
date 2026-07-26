"""自适应计划分析的模型边界、确定性校验和治理编排。"""

from datetime import date
from typing import Any, Literal, Protocol

from langchain_core.messages import HumanMessage, SystemMessage

from app.agent.adjustment_models import (
    AdjustmentOperationType,
    PlanAdjustmentDraft,
    classify_adjustment_risk,
)
from app.clients.java_backend import JavaBackendClient
from app.schemas.agent import CreatePlanAdjustmentAgentExecutionRequest
from app.schemas.learning import (
    AdaptationContext,
    CreatePlanAdjustmentRequest,
    ExecutePlanAdjustmentRequest,
    LearningTaskStatus,
    PlanAdjustment,
)

TriggerType = Literal["USER_REQUEST", "NIGHTLY_CHECK"]


class AdjustmentOutputError(RuntimeError):
    """模型草稿无法通过结构或业务边界校验。"""


class AdjustmentGenerator(Protocol):
    async def generate(self, adaptation_context: AdaptationContext) -> PlanAdjustmentDraft: ...


class DeepSeekAdjustmentGenerator:
    """把 DeepSeek 限制为只返回计划调整候选，不赋予写权限。"""

    def __init__(self, chat_model: Any) -> None:
        self._model = chat_model.with_structured_output(
            PlanAdjustmentDraft,
            method="json_mode",
        )

    async def generate(self, adaptation_context: AdaptationContext) -> PlanAdjustmentDraft:
        context_json = adaptation_context.model_dump_json(by_alias=True)
        messages = [
            SystemMessage(
                content=(
                    "你是 StudyPilot 的学习计划调整助手。只能使用提供的真实任务 ID "
                    "和版本生成候选操作，不得删除目标或任务，不得声称已经执行。"
                )
            ),
            HumanMessage(
                content=(
                    "根据以下执行偏差生成最小必要调整。优先在现有计划周期内重新安排、"
                    "修正预计时长或拆分过大的 TODO 任务。严格返回约定 JSON。\n"
                    f"{context_json}"
                )
            ),
        ]
        for attempt in range(2):
            try:
                return PlanAdjustmentDraft.model_validate(
                    await self._model.ainvoke(messages)
                )
            except Exception as exc:
                if attempt == 1:
                    raise AdjustmentOutputError("模型未返回合法的计划调整草稿") from exc
                messages.append(HumanMessage(content="请修正字段和任务版本，只返回合法 JSON。"))
        raise AssertionError("unreachable")


class PlanAdjustmentService:
    """协调只读分析、模型候选、治理登记和 Java 原子执行。"""

    def __init__(
        self,
        generator: AdjustmentGenerator,
        java_backend: JavaBackendClient,
    ) -> None:
        self._generator = generator
        self._java = java_backend

    async def analyze(
        self,
        *,
        owner_id: str,
        analysis_date: date,
        trigger_type: TriggerType,
    ) -> PlanAdjustment:
        context = await self._java.get_adaptation_context(
            owner_id,
            analysis_date=analysis_date,
            window_days=14,
        )
        key = self._idempotency_key(owner_id, analysis_date, trigger_type)
        if not context.signals:
            return await self._java.create_plan_adjustment(
                CreatePlanAdjustmentRequest(
                    owner_id=owner_id,
                    plan_id=context.plan.id,
                    idempotency_key=key,
                    analysis_date=analysis_date,
                    trigger_type=trigger_type,
                    signals=[],
                    summary="当前执行记录没有达到需要调整计划的阈值",
                    operations=[],
                )
            )

        draft = await self._generator.generate(context)
        risk = self._validate_and_classify(context, draft)
        scope = (
            "SMALL_PLAN_ADJUSTMENT"
            if risk == "LOW"
            else "LARGE_PLAN_ADJUSTMENT"
        )
        execution = await self._java.create_agent_execution(
            CreatePlanAdjustmentAgentExecutionRequest(
                owner_id=owner_id,
                idempotency_key=f"{key}:execution",
                summary=draft.summary,
                trigger_type=trigger_type,
                risk_level=risk,
                required_scope=scope,
            )
        )
        adjustment = await self._java.create_plan_adjustment(
            CreatePlanAdjustmentRequest(
                owner_id=owner_id,
                plan_id=context.plan.id,
                idempotency_key=key,
                analysis_date=analysis_date,
                trigger_type=trigger_type,
                signals=[signal.type for signal in context.signals],
                summary=draft.summary,
                execution_id=execution.id,
                operations=draft.operations,
            )
        )
        if execution.status == "PENDING":
            return await self._java.execute_plan_adjustment(
                adjustment.id,
                ExecutePlanAdjustmentRequest(
                    owner_id=owner_id,
                    execution_id=execution.id,
                    expected_plan_version=adjustment.before_plan_version,
                ),
            )
        await self._java.create_notification(
            owner_id,
            "PLAN_ADJUSTMENT_READY",
            "学习计划调整待确认",
            draft.summary,
        )
        return adjustment

    async def get(self, adjustment_id: str) -> PlanAdjustment:
        return await self._java.get_plan_adjustment(adjustment_id)

    async def confirm(
        self,
        adjustment_id: str,
        owner_id: str,
    ) -> PlanAdjustment:
        adjustment = await self._java.get_plan_adjustment(adjustment_id)
        if adjustment.owner_id != owner_id:
            raise AdjustmentOutputError("计划调整不属于当前用户")
        if adjustment.status == "COMPLETED":
            return adjustment
        if not adjustment.execution_id:
            raise AdjustmentOutputError("该计划调整没有可确认的执行记录")
        execution = await self._java.confirm_agent_execution(
            adjustment.execution_id,
            owner_id=owner_id,
        )
        return await self._java.execute_plan_adjustment(
            adjustment.id,
            ExecutePlanAdjustmentRequest(
                owner_id=owner_id,
                execution_id=execution.id,
                expected_plan_version=adjustment.before_plan_version,
            ),
        )

    @staticmethod
    def _idempotency_key(
        owner_id: str,
        analysis_date: date,
        trigger_type: TriggerType,
    ) -> str:
        prefix = "nightly" if trigger_type == "NIGHTLY_CHECK" else "user"
        return f"plan-adjustment:{prefix}:{owner_id}:{analysis_date.isoformat()}"

    @staticmethod
    def _validate_and_classify(
        context: AdaptationContext,
        draft: PlanAdjustmentDraft,
    ) -> str:
        tasks = {task.id: task for task in context.tasks}
        projected_load: dict[date, int] = {}
        for task in context.tasks:
            if task.status == LearningTaskStatus.TODO:
                projected_load[task.scheduled_date] = (
                    projected_load.get(task.scheduled_date, 0)
                    + task.estimated_minutes
                )
        seen: set[str] = set()
        for operation in draft.operations:
            task = tasks.get(operation.task_id)
            if task is None or task.plan_id != context.plan.id:
                raise AdjustmentOutputError("模型引用了不属于当前计划的任务")
            if task.status != LearningTaskStatus.TODO:
                raise AdjustmentOutputError("模型只能调整 TODO 任务")
            if task.version != operation.expected_version:
                raise AdjustmentOutputError("模型返回了过期的任务版本")
            if task.id in seen:
                raise AdjustmentOutputError("同一草稿不能重复修改同一任务")
            seen.add(task.id)
            if operation.scheduled_date and operation.scheduled_date < context.plan.start_date:
                raise AdjustmentOutputError("任务日期不能早于计划开始日期")
            if operation.second_scheduled_date and (
                operation.second_scheduled_date < context.plan.start_date
            ):
                raise AdjustmentOutputError("拆分任务日期不能早于计划开始日期")

            if operation.type == AdjustmentOperationType.RESCHEDULE_TASK:
                projected_load[task.scheduled_date] -= task.estimated_minutes
                target = operation.scheduled_date
                projected_load[target] = projected_load.get(target, 0) + task.estimated_minutes
            elif operation.type == AdjustmentOperationType.UPDATE_ESTIMATE:
                projected_load[task.scheduled_date] += (
                    operation.estimated_minutes - task.estimated_minutes
                )
            elif operation.type == AdjustmentOperationType.SPLIT_TASK:
                projected_load[task.scheduled_date] += (
                    operation.first_estimated_minutes - task.estimated_minutes
                )
                target = operation.second_scheduled_date
                projected_load[target] = (
                    projected_load.get(target, 0)
                    + operation.second_estimated_minutes
                )

        risk = classify_adjustment_risk(
            draft,
            plan_end_date=context.plan.end_date,
        )
        if risk == "LOW" and any(
            minutes > context.daily_study_limit_minutes
            for minutes in projected_load.values()
        ):
            raise AdjustmentOutputError("小范围调整超过了用户每日学习上限")
        return risk
