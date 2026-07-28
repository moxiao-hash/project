import asyncio
from datetime import UTC, date, datetime

from app.scheduler.nightly_adjustments import NightlyAdjustmentScheduler
from app.schemas.learning import NightlyAdjustmentCandidate


class FakeCandidateSource:
    async def get_nightly_adjustment_candidates(self, *, at):
        return [
            NightlyAdjustmentCandidate(
                owner_id="user-1",
                analysis_date=date(2026, 7, 26),
            ),
            NightlyAdjustmentCandidate(
                owner_id="user-2",
                analysis_date=date(2026, 7, 26),
            ),
        ]


class FakeAdjustmentService:
    def __init__(self) -> None:
        self.calls = []

    async def analyze(self, **kwargs):
        self.calls.append(kwargs)


def test_scheduler_compensates_for_missed_midnight_run() -> None:
    service = FakeAdjustmentService()
    scheduler = NightlyAdjustmentScheduler(FakeCandidateSource(), service)

    asyncio.run(
        scheduler.run_due(datetime(2026, 7, 27, 0, 17, tzinfo=UTC))
    )

    assert service.calls == [
        {
            "owner_id": "user-1",
            "analysis_date": date(2026, 7, 26),
            "trigger_type": "NIGHTLY_CHECK",
        },
        {
            "owner_id": "user-2",
            "analysis_date": date(2026, 7, 26),
            "trigger_type": "NIGHTLY_CHECK",
        },
    ]


def test_scheduler_resolves_service_per_owner_and_continues_after_failure() -> None:
    services = {
        "user-1": FakeAdjustmentService(),
        "user-2": FakeAdjustmentService(),
    }
    resolved: list[str] = []

    async def service_for(owner_id: str):
        resolved.append(owner_id)
        if owner_id == "user-1":
            raise RuntimeError("credential unavailable")
        return services[owner_id]

    scheduler = NightlyAdjustmentScheduler(
        FakeCandidateSource(),
        None,
        adjustment_service_factory=service_for,
    )
    asyncio.run(scheduler.run_due(datetime(2026, 7, 27, 0, 17, tzinfo=UTC)))

    assert resolved == ["user-1", "user-2"]
    assert len(services["user-2"].calls) == 1
