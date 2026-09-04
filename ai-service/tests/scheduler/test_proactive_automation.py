import asyncio
from datetime import UTC, date, datetime

from app.scheduler.proactive_automation import ProactiveAutomationWorker
from app.schemas.automation import AutomationJob


class FakeJava:
    def __init__(self, job_type: str = "AUTHORIZED_PLAN_ADJUSTMENT") -> None:
        self.job_type = job_type
        self.completed: list[tuple[str, str]] = []
        self.failed: list[tuple[str, str]] = []
        self.tool_calls: list[tuple[str, dict, str | None]] = []
        self.heartbeats: list[str] = []

    async def claim_automation_job(self, worker_id, lease_seconds):
        return AutomationJob(
            id="job-1",
            ruleId="rule-1",
            ownerId="user-1",
            executionId="execution-1",
            type=self.job_type,
            status="PROCESSING",
            scheduledFor="2026-07-27T00:15:00Z",
            workerId=worker_id,
            leaseToken="lease-1",
            leaseUntil="2026-07-27T00:16:00Z",
            attempts=1,
        )

    async def get_nightly_adjustment_candidates(self, *, at):
        from app.schemas.learning import NightlyAdjustmentCandidate

        return [
            NightlyAdjustmentCandidate(ownerId="user-1", analysisDate=date(2026, 7, 26))
        ]

    async def complete_automation_job(self, job_id, worker_id, lease_token, summary):
        self.completed.append((job_id, summary))

    async def fail_automation_job(self, job_id, worker_id, lease_token, error):
        self.failed.append((job_id, error))

    async def heartbeat_automation_job(
        self, job_id, worker_id, lease_token, lease_seconds
    ):
        self.heartbeats.append(job_id)

    async def invoke_agent_tool(
        self, name, owner_id, arguments, idempotency_key=None
    ):
        self.tool_calls.append((name, arguments, idempotency_key))
        return {"toolName": name, "data": {}, "action": None}

    async def create_notification(self, owner_id, notification_type, title, content):
        self.tool_calls.append(("notification", {"title": title, "content": content}, None))


class FakeAdjustmentService:
    def __init__(self, *, fails: bool = False) -> None:
        self.fails = fails
        self.calls: list[dict] = []

    async def analyze(self, **kwargs):
        self.calls.append(kwargs)
        if self.fails:
            raise RuntimeError("model unavailable")


def test_worker_claims_durable_rule_and_completes_existing_nightly_analysis() -> None:
    async def run() -> None:
        java = FakeJava()
        service = FakeAdjustmentService()
        worker = ProactiveAutomationWorker(
            java,
            worker_id="automation-worker",
            adjustment_service_factory=lambda _owner_id: asyncio.sleep(0, result=service),
        )

        handled = await worker.run_once(datetime(2026, 7, 27, 0, 17, tzinfo=UTC))

        assert handled is True
        assert service.calls == [{
            "owner_id": "user-1",
            "analysis_date": date(2026, 7, 26),
            "trigger_type": "NIGHTLY_CHECK",
        }]
        assert java.completed == [("job-1", "已完成夜间学习计划分析")]
        assert java.failed == []

    asyncio.run(run())


def test_worker_reports_failure_to_java_lease_queue() -> None:
    async def run() -> None:
        java = FakeJava()
        service = FakeAdjustmentService(fails=True)
        worker = ProactiveAutomationWorker(
            java,
            worker_id="automation-worker",
            adjustment_service_factory=lambda _owner_id: asyncio.sleep(0, result=service),
        )

        handled = await worker.run_once(datetime(2026, 7, 27, 0, 17, tzinfo=UTC))

        assert handled is True
        assert java.completed == []
        assert java.failed == [("job-1", "主动自动化执行失败")]

    asyncio.run(run())


def test_overdue_rollover_uses_governed_schedule_tool() -> None:
    async def run() -> None:
        java = FakeJava("OVERDUE_NODE_ROLLOVER")
        worker = ProactiveAutomationWorker(
            java,
            worker_id="automation-worker",
            adjustment_service_factory=lambda _owner_id: asyncio.sleep(0),
        )

        await worker.run_once(datetime(2026, 7, 27, 0, 17, tzinfo=UTC))

        assert java.tool_calls == [(
            "schedule.refresh",
            {"from": "2026-07-27", "to": "2026-08-02"},
            "automation:job-1",
        )]
        assert java.completed == [("job-1", "已滚动整理逾期学习节点")]

    asyncio.run(run())


def test_long_running_handler_renews_its_lease() -> None:
    async def run() -> None:
        java = FakeJava()
        service = FakeAdjustmentService()

        async def slow_analyze(**kwargs):
            service.calls.append(kwargs)
            await asyncio.sleep(0.04)

        service.analyze = slow_analyze
        worker = ProactiveAutomationWorker(
            java,
            worker_id="automation-worker",
            adjustment_service_factory=lambda _owner_id: asyncio.sleep(0, result=service),
            heartbeat_interval_seconds=0.01,
        )

        await worker.run_once(datetime(2026, 7, 27, 0, 17, tzinfo=UTC))

        assert java.heartbeats

    asyncio.run(run())
