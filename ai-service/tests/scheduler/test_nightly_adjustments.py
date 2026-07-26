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
            )
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
        }
    ]
