import asyncio
from datetime import date
from types import SimpleNamespace

import httpx
from pydantic import SecretStr

from app.agent.task_conversation_service import TaskConversationService
from app.agent.task_models import (
    TaskActionDraft,
    TaskCandidate,
    TaskRecognitionResult,
    TaskRecognitionStatus,
)
from app.api.task_conversations import get_task_conversation_service
from app.core.settings import Settings, get_settings
from app.main import app
from app.schemas.learning import LearningTask, LearningTaskStatus


class CompletingRecognizer:
    """模拟已经通过真实任务列表校验的模型识别结果。"""

    async def recognize(self, *, owner_id, message, target_date):
        assert owner_id == "user-e2e"
        assert message == "我已经完成 Spring MVC 接口任务"
        assert target_date == date(2026, 7, 26)
        task = TaskCandidate(
            id="task-e2e",
            title="完成 Spring MVC 接口",
            status=LearningTaskStatus.TODO,
            version=1,
        )
        return TaskRecognitionResult(
            status=TaskRecognitionStatus.PREVIEW_READY,
            reply="已识别完成操作，请确认。",
            candidate_tasks=[task],
            action_draft=TaskActionDraft(
                target_status=LearningTaskStatus.COMPLETED,
                task_id=task.id,
                task_title=task.title,
                expected_version=task.version,
            ),
        )


class RecordingJavaBackend:
    """记录跨服务副作用，用于验证确认边界和幂等行为。"""

    def __init__(self) -> None:
        self.execution_creations = []
        self.execution_confirmations = []
        self.execution_updates = []
        self.task_changes = []

    async def create_agent_execution(self, request):
        self.execution_creations.append(request)
        return SimpleNamespace(id="execution-e2e", status="WAITING_CONFIRMATION")

    async def confirm_agent_execution(self, execution_id, *, owner_id):
        self.execution_confirmations.append((execution_id, owner_id))
        return SimpleNamespace(id=execution_id, status="PENDING")

    async def update_agent_execution(self, execution_id, request):
        self.execution_updates.append((execution_id, request))
        return SimpleNamespace(id=execution_id, status=request.status)

    async def change_learning_task_status(self, task_id, request):
        self.task_changes.append((task_id, request))
        return LearningTask(
            id=task_id,
            plan_id="plan-e2e",
            title="完成 Spring MVC 接口",
            scheduled_date=date(2026, 7, 26),
            estimated_minutes=60,
            status=request.status,
            version=request.expected_version + 1,
            completed_at="2026-07-26T08:00:00Z",
        )


def test_task_agent_full_http_workflow_executes_only_after_confirm() -> None:
    """覆盖创建、预览、确认、查询和重复确认的完整 HTTP 闭环。"""

    async def run_workflow() -> tuple[list[httpx.Response], RecordingJavaBackend]:
        java = RecordingJavaBackend()
        service = TaskConversationService(CompletingRecognizer(), java)
        settings = Settings(
            internal_service_token=SecretStr("e2e-internal-token"),
            deepseek_api_key=SecretStr("not-used"),
        )
        app.dependency_overrides[get_settings] = lambda: settings
        app.dependency_overrides[get_task_conversation_service] = lambda: service
        headers = {"X-Internal-Service-Token": "e2e-internal-token"}
        transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
        try:
            async with httpx.AsyncClient(
                transport=transport,
                base_url="http://test",
                headers=headers,
            ) as client:
                created = await client.post(
                    "/internal/agent/task-conversations",
                    json={"ownerId": "user-e2e", "targetDate": "2026-07-26"},
                )
                conversation_id = created.json()["conversationId"]
                preview = await client.post(
                    f"/internal/agent/task-conversations/{conversation_id}/messages",
                    json={"message": "我已经完成 Spring MVC 接口任务"},
                )
                assert java.task_changes == []
                fetched = await client.get(
                    f"/internal/agent/task-conversations/{conversation_id}"
                )
                confirmed = await client.post(
                    f"/internal/agent/task-conversations/{conversation_id}/confirm"
                )
                repeated = await client.post(
                    f"/internal/agent/task-conversations/{conversation_id}/confirm"
                )
                return [created, preview, fetched, confirmed, repeated], java
        finally:
            app.dependency_overrides.clear()

    responses, java = asyncio.run(run_workflow())
    created, preview, fetched, confirmed, repeated = responses

    assert created.status_code == 201
    assert preview.json()["status"] == "PREVIEW_READY"
    assert fetched.json()["executionId"] == "execution-e2e"
    assert confirmed.json()["status"] == "COMPLETED"
    assert confirmed.json()["updatedTask"]["version"] == 2
    assert repeated.json() == confirmed.json()
    assert len(java.execution_creations) == 1
    assert len(java.execution_confirmations) == 1
    assert len(java.task_changes) == 1
    assert java.task_changes[0][1].idempotency_key.startswith("task-action:")
    assert [request.status for _, request in java.execution_updates] == [
        "RUNNING",
        "SUCCEEDED",
    ]
