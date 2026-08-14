import pytest

from app import main as main_module
from app.core.settings import Settings


@pytest.mark.anyio
async def test_scheduler_runner_builds_one_durable_owner_scoped_roadmap_worker(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    constructed = []

    class FakeJava:
        def __init__(self, settings) -> None:
            self.settings = settings

    class FakeWorker:
        def __init__(self, backend, generator, web_search, **kwargs) -> None:
            constructed.append((backend, generator, web_search, kwargs))

        async def process_once(self) -> bool:
            return True

    settings = Settings(_env_file=None, roadmap_quiz_worker_id="roadmap-worker-test")
    monkeypatch.setattr(main_module, "get_settings", lambda: settings)
    monkeypatch.setattr(main_module, "JavaBackendClient", FakeJava)
    monkeypatch.setattr(main_module, "RoadmapQuizWorker", FakeWorker, raising=False)
    monkeypatch.setattr(main_module, "_roadmap_quiz_worker", None, raising=False)

    runner = getattr(main_module, "run_roadmap_quiz_job", None)
    assert runner is not None, "应用调度器尚未接入路线节点测验 worker"
    await runner()
    await runner()

    assert len(constructed) == 1
    backend, generator, web_search, options = constructed[0]
    assert isinstance(backend, FakeJava)
    assert generator is None
    assert web_search is None
    assert options["worker_id"] == "roadmap-worker-test"
    assert callable(options["generator_factory"])
    assert callable(options["web_search_factory"])
