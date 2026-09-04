"""消费 Java 持久化的主动自动化规则，替代进程内夜间任务状态。"""

import asyncio
from collections.abc import Awaitable, Callable
from contextlib import suppress
from datetime import datetime, timedelta
from typing import Any, Protocol

from app.agent.adjustment_service import PlanAdjustmentService
from app.schemas.automation import AutomationJob


class AutomationJavaGateway(Protocol):
    async def claim_automation_job(
        self, worker_id: str, lease_seconds: int
    ) -> AutomationJob | None: ...

    async def get_nightly_adjustment_candidates(self, *, at: datetime) -> list[Any]: ...

    async def complete_automation_job(
        self, job_id: str, worker_id: str, lease_token: str, summary: str
    ) -> None: ...

    async def fail_automation_job(
        self, job_id: str, worker_id: str, lease_token: str, error: str
    ) -> None: ...

    async def heartbeat_automation_job(
        self,
        job_id: str,
        worker_id: str,
        lease_token: str,
        lease_seconds: int,
    ) -> None: ...

    async def invoke_agent_tool(
        self,
        name: str,
        owner_id: str,
        arguments: dict[str, Any],
        idempotency_key: str | None = None,
    ) -> dict[str, Any]: ...

    async def create_notification(
        self,
        owner_id: str,
        notification_type: str,
        title: str,
        content: str,
    ) -> None: ...


class ProactiveAutomationWorker:
    """每次只领取一个任务；失败写回 Java 后可按租约重试。"""

    def __init__(
        self,
        java: AutomationJavaGateway,
        *,
        worker_id: str,
        adjustment_service_factory: Callable[[str], Awaitable[PlanAdjustmentService]],
        lease_seconds: int = 120,
        heartbeat_interval_seconds: float | None = None,
    ) -> None:
        self._java = java
        self._worker_id = worker_id
        self._adjustment_service_factory = adjustment_service_factory
        self._lease_seconds = lease_seconds
        self._heartbeat_interval = (
            heartbeat_interval_seconds
            if heartbeat_interval_seconds is not None
            else max(1.0, lease_seconds / 3)
        )

    async def run_once(self, at: datetime) -> bool:
        job = await self._java.claim_automation_job(
            self._worker_id, self._lease_seconds
        )
        if job is None:
            return False
        try:
            heartbeat = asyncio.create_task(self._heartbeat(job))
            try:
                summary = await self._handle(job, at)
            finally:
                heartbeat.cancel()
                with suppress(asyncio.CancelledError):
                    await heartbeat
            await self._java.complete_automation_job(
                job.id,
                self._worker_id,
                job.lease_token,
                summary,
            )
        except Exception:
            # 错误正文可能包含外部服务细节或敏感内容，只写稳定、安全的摘要。
            await self._java.fail_automation_job(
                job.id,
                self._worker_id,
                job.lease_token,
                "主动自动化执行失败",
            )
        return True

    async def _heartbeat(self, job: AutomationJob) -> None:
        while True:
            await asyncio.sleep(self._heartbeat_interval)
            await self._java.heartbeat_automation_job(
                job.id,
                self._worker_id,
                job.lease_token,
                self._lease_seconds,
            )

    async def _handle(self, job: AutomationJob, at: datetime) -> str:
        if job.type == "AUTHORIZED_PLAN_ADJUSTMENT":
            candidates = await self._java.get_nightly_adjustment_candidates(at=at)
            candidate = next(
                (item for item in candidates if item.owner_id == job.owner_id),
                None,
            )
            if candidate is not None:
                service = await self._adjustment_service_factory(job.owner_id)
                await service.analyze(
                    owner_id=job.owner_id,
                    analysis_date=candidate.analysis_date,
                    trigger_type="NIGHTLY_CHECK",
                )
            return "已完成夜间学习计划分析"
        if job.type == "OVERDUE_NODE_ROLLOVER":
            start = at.date()
            await self._java.invoke_agent_tool(
                "schedule.refresh",
                job.owner_id,
                {"from": start.isoformat(), "to": (start + timedelta(days=6)).isoformat()},
                f"automation:{job.id}",
            )
            return "已滚动整理逾期学习节点"
        if job.type == "QUIZ_GENERATION_RETRY":
            context = await self._java.invoke_agent_tool(
                "learning.context.get", job.owner_id, {}
            )
            node_id = self._next_available_node_id(context.get("data"))
            if node_id is not None:
                await self._java.invoke_agent_tool(
                    "assessment.node_quiz.generate",
                    job.owner_id,
                    {"nodeId": node_id},
                    f"automation:{job.id}",
                )
            return "已检查并重试待生成的路线测验"
        if job.type == "WEAKNESS_REVIEW_REMINDER":
            await self._java.create_notification(
                job.owner_id,
                "TASK_OVERDUE",
                "今日薄弱点复习提醒",
                "可以打开掌握度或错题集，优先复习最近低于 70 分的知识点。",
            )
            return "已发送薄弱点复习提醒"
        if job.type == "ARTIFACT_REVIEW_REMINDER":
            await self._java.create_notification(
                job.owner_id,
                "AGENT_ACTION_READY",
                "实践成果等待验收",
                "你有实践成果需要继续测试或确认，请在工作区与成果页面处理。",
            )
            return "已发送待验收成果提醒"
        raise RuntimeError(f"尚未注册主动自动化处理器: {job.type}")

    @staticmethod
    def _next_available_node_id(data: Any) -> str | None:
        if not isinstance(data, dict):
            return None
        roadmap = data.get("roadmap")
        if not isinstance(roadmap, dict):
            return None
        for stage in roadmap.get("stages", []):
            if not isinstance(stage, dict):
                continue
            for node in stage.get("nodes", []):
                if (
                    isinstance(node, dict)
                    and node.get("displayStatus") in {"AVAILABLE", "IN_PROGRESS"}
                    and isinstance(node.get("id"), str)
                ):
                    return node["id"]
        return None
