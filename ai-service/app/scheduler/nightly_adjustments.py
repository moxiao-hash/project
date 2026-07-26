"""按用户本地日期补跑午夜计划偏差分析。"""

from datetime import datetime
from typing import Protocol

from app.agent.adjustment_service import PlanAdjustmentService
from app.schemas.learning import NightlyAdjustmentCandidate


class CandidateSource(Protocol):
    async def get_nightly_adjustment_candidates(
        self,
        *,
        at: datetime,
    ) -> list[NightlyAdjustmentCandidate]: ...


class NightlyAdjustmentScheduler:
    """每次运行都向 Java 查询尚未处理的日期，因此进程中断后也可补跑。"""

    def __init__(
        self,
        candidate_source: CandidateSource,
        adjustment_service: PlanAdjustmentService,
    ) -> None:
        self._candidate_source = candidate_source
        self._adjustment_service = adjustment_service

    async def run_due(self, at: datetime) -> None:
        candidates = await self._candidate_source.get_nightly_adjustment_candidates(
            at=at
        )
        for candidate in candidates:
            await self._adjustment_service.analyze(
                owner_id=candidate.owner_id,
                analysis_date=candidate.analysis_date,
                trigger_type="NIGHTLY_CHECK",
            )
