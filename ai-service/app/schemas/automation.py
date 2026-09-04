"""主动自动化租约任务的 Java/Python 稳定契约。"""

from datetime import datetime
from typing import Literal

from app.schemas.learning import JavaContractModel


class AutomationJob(JavaContractModel):
    id: str
    rule_id: str
    owner_id: str
    execution_id: str
    type: Literal[
        "AUTHORIZED_PLAN_ADJUSTMENT",
        "OVERDUE_NODE_ROLLOVER",
        "QUIZ_GENERATION_RETRY",
        "WEAKNESS_REVIEW_REMINDER",
        "ARTIFACT_REVIEW_REMINDER",
    ]
    status: str
    scheduled_for: datetime
    worker_id: str | None = None
    lease_token: str
    lease_until: datetime
    attempts: int
