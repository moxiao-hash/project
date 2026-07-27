import asyncio
from collections.abc import Iterator

import httpx
import pytest
from pydantic import SecretStr

from app.api.quiz_generation import get_quiz_generation_service
from app.clients.java_backend import JavaBackendError
from app.core.settings import Settings, get_settings
from app.main import app


class FakeService:
    async def generate(self, owner_id, task_id, web_search):
        assert (owner_id, task_id, web_search.value) == ("user-1", "task-1", "AUTO")
        return {"id": "quiz-1", "title": "任务测验", "questions": []}


@pytest.fixture(autouse=True)
def overrides() -> Iterator[None]:
    app.dependency_overrides[get_settings] = lambda: Settings(
        internal_service_token=SecretStr("test-token"),
        deepseek_api_key=SecretStr("unused"),
    )
    app.dependency_overrides[get_quiz_generation_service] = lambda: FakeService()
    yield
    app.dependency_overrides.clear()


def send(token="test-token"):
    async def request():
        headers = {"X-Internal-Service-Token": token} if token else {}
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://test",
        ) as client:
            return await client.post(
                "/internal/assessment/quizzes/generate",
                headers=headers,
                json={"ownerId": "user-1", "taskId": "task-1", "webSearch": "AUTO"},
            )

    return asyncio.run(request())


def test_generation_requires_internal_token() -> None:
    assert send(token=None).status_code == 401


def test_generation_returns_persisted_quiz() -> None:
    response = send()

    assert response.status_code == 201
    assert response.json()["id"] == "quiz-1"


def test_generation_reports_safe_java_contract_rejection() -> None:
    class RejectingService:
        async def generate(self, owner_id, task_id, web_search):
            raise JavaBackendError(
                "rejected",
                path="/internal/quizzes",
                status_code=400,
                detail="选择题必须提供选项内的正确答案",
            )

    app.dependency_overrides[get_quiz_generation_service] = lambda: RejectingService()

    response = send()

    assert response.status_code == 502
    assert response.json()["detail"] == (
        "Java 拒绝保存测验：选择题必须提供选项内的正确答案"
    )
