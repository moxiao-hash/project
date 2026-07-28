import base64
from pathlib import Path

import pytest
from pydantic import SecretStr

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
