from datetime import date

from fastapi.testclient import TestClient

from app.api.plan_adjustments import get_plan_adjustment_service
from app.core.settings import get_settings
from app.main import app
from app.schemas.learning import PlanAdjustment


class FakeService:
    async def analyze(self, **kwargs):
        return adjustment("DRAFT_READY")

    async def get(self, adjustment_id):
        return adjustment("DRAFT_READY")

    async def confirm(self, adjustment_id, owner_id):
        return adjustment("COMPLETED")


def adjustment(status: str) -> PlanAdjustment:
    return PlanAdjustment(
        id="adjustment-1",
        owner_id="user-1",
        plan_id="plan-1",
        idempotency_key="plan-adjustment:user:user-1:2026-07-27",
        analysis_date=date(2026, 7, 27),
        trigger_type="USER_REQUEST",
        signals=["OVERDUE_TASKS"],
        summary="顺延逾期任务",
        operations=[],
        risk_level="LOW",
        status=status,
        execution_id="execution-1",
        before_plan_version=2,
        after_plan_version=3 if status == "COMPLETED" else None,
        created_at="2026-07-27T00:00:00Z",
        updated_at="2026-07-27T00:00:00Z",
    )


def test_plan_adjustment_endpoints_require_token_and_expose_confirmation() -> None:
    app.dependency_overrides[get_plan_adjustment_service] = lambda: FakeService()
    secret = type(
        "Secret",
        (),
        {"get_secret_value": lambda self: "token"},
    )()
    app.dependency_overrides[get_settings] = lambda: type(
        "TestSettings",
        (),
        {"internal_service_token": secret},
    )()
    client = TestClient(app)
    try:
        assert client.post(
            "/internal/agent/plan-adjustments/analyze",
            json={"ownerId": "user-1", "analysisDate": "2026-07-27"},
        ).status_code == 401

        response = client.post(
            "/internal/agent/plan-adjustments/analyze",
            headers={"X-Internal-Service-Token": "token"},
            json={"ownerId": "user-1", "analysisDate": "2026-07-27"},
        )
        assert response.status_code == 201
        assert response.json()["status"] == "DRAFT_READY"

        confirmed = client.post(
            "/internal/agent/plan-adjustments/adjustment-1/confirm",
            headers={"X-Internal-Service-Token": "token"},
            json={"ownerId": "user-1"},
        )
        assert confirmed.status_code == 200
        assert confirmed.json()["status"] == "COMPLETED"
    finally:
        app.dependency_overrides.clear()
