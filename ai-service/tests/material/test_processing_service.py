import pytest

from app.material.models import MaterialAnalysis
from app.material.processing import MaterialProcessingService, ProcessingJob


class FakeBackend:
    def __init__(self) -> None:
        self.completed: dict | None = None
        self.failed: str | None = None

    async def claim_material_job(self, worker_id: str, lease_seconds: int) -> ProcessingJob:
        return ProcessingJob(
            job_id="job-1",
            material_id="material-1",
            owner_id="owner-1",
            title="Spring 笔记",
            material_type="TEXT",
            category="PERSONAL_NOTE",
            privacy_level="NORMAL",
        )

    async def download_material_content(self, material_id: str) -> bytes:
        return "依赖注入降低耦合。".encode()

    async def complete_material_job(self, job_id: str, payload: dict) -> None:
        self.completed = payload

    async def fail_material_job(self, job_id: str, worker_id: str, error: str) -> None:
        self.failed = error


class StubAnalyzer:
    async def analyze(self, privacy_level: str, title: str, chunks: list) -> MaterialAnalysis:
        return MaterialAnalysis(
            summary="依赖注入摘要",
            tags=("Spring",),
            knowledge_points=("依赖注入",),
        )


class RecordingIndex:
    def __init__(self) -> None:
        self.material_id: str | None = None

    def upsert(self, material, chunks) -> None:
        self.material_id = material.material_id


@pytest.mark.anyio
async def test_claims_parses_and_completes_one_material_job() -> None:
    backend = FakeBackend()
    index = RecordingIndex()
    service = MaterialProcessingService(
        backend,
        StubAnalyzer(),
        worker_id="worker-test",
        index=index,
    )

    processed = await service.process_once()

    assert processed is True
    assert backend.failed is None
    assert backend.completed is not None
    assert backend.completed["workerId"] == "worker-test"
    assert backend.completed["summary"] == "依赖注入摘要"
    assert backend.completed["chunks"][0]["locator"] == "正文 / chunk 1"
    assert backend.completed["contentReference"] == "qdrant://material-1"
    assert index.material_id == "material-1"


@pytest.mark.anyio
async def test_reports_safe_failure_to_java() -> None:
    backend = FakeBackend()

    class BrokenBackend(FakeBackend):
        async def download_material_content(self, material_id: str) -> bytes:
            raise RuntimeError("secret stack detail")

    backend = BrokenBackend()
    service = MaterialProcessingService(backend, StubAnalyzer(), worker_id="worker-test")

    processed = await service.process_once()

    assert processed is True
    assert backend.completed is None
    assert backend.failed == "资料处理失败，请检查文件格式或稍后重试"
