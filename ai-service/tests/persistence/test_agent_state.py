import base64
import sqlite3
from pathlib import Path

import pytest

from app.persistence.agent_state import (
    AgentPersistence,
    AgentStateDecryptionError,
    AgentStateKeyError,
    AgentStateSchemaError,
)

pytestmark = pytest.mark.anyio

TEST_KEY = base64.b64encode(bytes(range(32))).decode()
OTHER_KEY = base64.b64encode(bytes(reversed(range(32)))).decode()


async def test_state_survives_reopen_without_plaintext_on_disk(tmp_path: Path) -> None:
    path = tmp_path / "agent-state.sqlite3"
    persistence = await AgentPersistence.open(path, TEST_KEY)
    await persistence.store.save(
        kind="knowledge",
        conversation_id="conversation-1",
        owner_id="private-owner",
        payload={
            "message": "private user message",
            "answer": "private model answer",
        },
    )
    await persistence.close()

    disk = path.read_bytes()
    assert b"private-owner" not in disk
    assert b"private user message" not in disk
    assert b"private model answer" not in disk

    reopened = await AgentPersistence.open(path, TEST_KEY)
    assert await reopened.store.load(
        kind="knowledge",
        conversation_id="conversation-1",
        owner_id="private-owner",
    ) == {
        "message": "private user message",
        "answer": "private model answer",
    }
    assert (
        await reopened.store.load(
            kind="knowledge",
            conversation_id="conversation-1",
            owner_id="another-owner",
        )
        is None
    )
    await reopened.close()


async def test_wrong_key_fails_closed_without_returning_data(tmp_path: Path) -> None:
    path = tmp_path / "agent-state.sqlite3"
    first = await AgentPersistence.open(path, TEST_KEY)
    await first.store.save(
        kind="plan",
        conversation_id="conversation-1",
        owner_id="owner-1",
        payload={"draft": "secret draft"},
    )
    await first.close()

    with pytest.raises(AgentStateDecryptionError):
        await AgentPersistence.open(path, OTHER_KEY)


@pytest.mark.parametrize(
    "value",
    ["", "not-base64", base64.b64encode(b"too-short").decode()],
)
async def test_key_must_be_base64_encoded_32_bytes(
    tmp_path: Path,
    value: str,
) -> None:
    with pytest.raises(AgentStateKeyError):
        await AgentPersistence.open(tmp_path / "state.sqlite3", value)


async def test_newer_database_schema_is_rejected_safely(tmp_path: Path) -> None:
    path = tmp_path / "agent-state.sqlite3"
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA user_version=99")
    connection.close()

    with pytest.raises(AgentStateSchemaError):
        await AgentPersistence.open(path, TEST_KEY)
