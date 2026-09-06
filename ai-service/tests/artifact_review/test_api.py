import asyncio
from collections.abc import Iterator

import httpx
import pytest
from pydantic import SecretStr

from app.api.artifact_reviews import get_artifact_review_service
from app.artifact_review.models import ArtifactRubricResult
from app.core.settings import Settings, get_settings
from app.main import app


class FakeEvaluator:
    async def evaluate(self, payload):
        assert payload["files"][0]["path"] == "src/App.java"
        return ArtifactRubricResult(
            functionalCorrectness=36,
            requirementsCompleteness=22,
            testQuality=17,
            codeQuality=13,
            score=88,
            feedback="通过",
            strengths=["结构清晰"],
            issues=["补充边界测试"],
        )


@pytest.fixture(autouse=True)
def overrides() -> Iterator[None]:
    app.dependency_overrides[get_settings] = lambda: Settings(
        internal_service_token=SecretStr("test-token"),
        deepseek_api_key=SecretStr("unused"),
    )
    app.dependency_overrides[get_artifact_review_service] = lambda: FakeEvaluator()
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
                "/internal/artifacts/evaluate",
                headers=headers,
                json={
                    "ownerId": "owner-1",
                    "artifactId": "artifact-1",
                    "description": "实现服务",
                    "runnerEvidence": {
                        "executionId": "runner-1",
                        "templateType": "MAVEN_TEST",
                        "success": True,
                        "summary": "Tests run: 8, Failures: 0",
                    },
                    "files": [{
                        "path": "src/App.java",
                        "sha256": "a" * 64,
                        "content": "public class App {}",
                    }],
                },
            )

    return asyncio.run(request())


def test_artifact_review_requires_internal_token() -> None:
    assert send(token=None).status_code == 401


def test_artifact_review_returns_validated_rubric_and_model_name() -> None:
    response = send()

    assert response.status_code == 200
    assert response.json()["score"] == 88
    assert response.json()["functionalCorrectness"] == 36
    assert response.json()["modelName"] == "deepseek-v4-flash"
