import base64
from pathlib import Path

import pytest
from pydantic import SecretStr

from app import main as main_module
from app.core.settings import Settings
from app.persistence.lifecycle import AgentPersistenceConfigurationError, open_agent_persistence

pytestmark = pytest.mark.anyio


async def test_lifecycle_opens_configured_shared_database(tmp_path: Path) -> None:
    settings = Settings(
        _env_file=None,
        agent_state_db_path=str(tmp_path / "agent-state.sqlite3"),
        langgraph_aes_key=SecretStr(base64.b64encode(bytes(range(32))).decode()),
        agent_worker_count=1,
    )
    persistence = await open_agent_persistence(settings)
    assert persistence.connection is not None
    await persistence.close()


@pytest.mark.parametrize(
    ("key", "workers"),
    [
        ("", 1),
        (base64.b64encode(bytes(range(32))).decode(), 2),
    ],
)
async def test_lifecycle_rejects_missing_key_or_multiple_workers(
    tmp_path: Path,
    key: str,
    workers: int,
) -> None:
    settings = Settings(
        _env_file=None,
        agent_state_db_path=str(tmp_path / "agent-state.sqlite3"),
        langgraph_aes_key=SecretStr(key),
        agent_worker_count=workers,
    )
    with pytest.raises(AgentPersistenceConfigurationError):
        await open_agent_persistence(settings)


async def test_lifespan_releases_process_lock_when_scheduler_start_fails(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = Settings(
        _env_file=None,
        agent_state_db_path=str(tmp_path / "agent-state.sqlite3"),
        langgraph_aes_key=SecretStr(base64.b64encode(bytes(range(32))).decode()),
        agent_worker_count=1,
    )

    class BrokenScheduler:
        def __init__(self, **_kwargs) -> None:
            pass

        def add_job(self, *_args, **_kwargs) -> None:
            pass

        def start(self) -> None:
            raise RuntimeError("scheduler failed")

        def shutdown(self, **_kwargs) -> None:
            pass

    monkeypatch.setattr(main_module, "get_settings", lambda: settings)
    monkeypatch.setattr(main_module, "AsyncIOScheduler", BrokenScheduler)

    with pytest.raises(RuntimeError, match="scheduler failed"):
        async with main_module.lifespan(main_module.FastAPI()):
            pass

    reopened = await open_agent_persistence(settings)
    await reopened.close()


async def test_lifespan_releases_process_lock_when_registry_construction_fails(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = Settings(
        _env_file=None,
        agent_state_db_path=str(tmp_path / "agent-state.sqlite3"),
        langgraph_aes_key=SecretStr(base64.b64encode(bytes(range(32))).decode()),
        agent_worker_count=1,
    )
    application = main_module.FastAPI()

    class BrokenRegistry:
        def __init__(self, *_args, **_kwargs) -> None:
            raise RuntimeError("registry construction failed")

    monkeypatch.setattr(main_module, "get_settings", lambda: settings)
    monkeypatch.setattr(main_module, "OwnerScopedConversationServices", BrokenRegistry)

    with pytest.raises(RuntimeError, match="registry construction failed"):
        async with main_module.lifespan(application):
            pass

    reopen_error: Exception | None = None
    reopened = None
    try:
        reopened = await open_agent_persistence(settings)
    except Exception as exc:
        reopen_error = exc
    finally:
        stale = getattr(application.state, "agent_persistence", None)
        if stale is not None:
            await stale.close()
        if reopened is not None:
            await reopened.close()

    assert reopen_error is None
    assert getattr(application.state, "agent_persistence", None) is None
    assert getattr(application.state, "conversation_service", None) is None
