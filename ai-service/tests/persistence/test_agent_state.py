import asyncio
import base64
import os
import sqlite3
import subprocess
import sys
from contextlib import suppress
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
    assert persistence.connection is not persistence.checkpoint_connection
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


async def test_lock_backend_failure_does_not_leave_database_locked(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from app.persistence import agent_state

    original_acquire = agent_state.FileLock.acquire

    def fail_acquire(_self, *_args, **_kwargs):
        raise OSError("lock backend failed")

    monkeypatch.setattr(agent_state.FileLock, "acquire", fail_acquire)
    with pytest.raises(OSError, match="lock backend failed"):
        await AgentPersistence.open(tmp_path / "agent-state.sqlite3", TEST_KEY)

    monkeypatch.setattr(agent_state.FileLock, "acquire", original_acquire)
    reopened = await AgentPersistence.open(
        tmp_path / "agent-state.sqlite3",
        TEST_KEY,
    )
    await reopened.close()


async def test_cancelled_checkpoint_write_cannot_commit_store_transaction(
    tmp_path: Path,
) -> None:
    persistence = await AgentPersistence.open(
        tmp_path / "agent-state.sqlite3",
        TEST_KEY,
    )
    await persistence.connection.execute("CREATE TABLE transaction_probe(value TEXT)")
    await persistence.connection.commit()
    await persistence.connection.execute("BEGIN IMMEDIATE")
    await persistence.connection.execute(
        "INSERT INTO transaction_probe(value) VALUES ('uncommitted')"
    )

    checkpoint_write = asyncio.create_task(
        persistence.checkpointer.aput_writes(
            {
                "configurable": {
                    "thread_id": "thread-1",
                    "checkpoint_ns": "",
                    "checkpoint_id": "checkpoint-1",
                }
            },
            [("messages", "checkpoint value")],
            "task-1",
        )
    )
    await asyncio.sleep(0.05)
    checkpoint_write.cancel()
    await persistence.connection.rollback()
    with suppress(asyncio.CancelledError):
        await checkpoint_write

    cursor = await persistence.connection.execute("SELECT COUNT(*) FROM transaction_probe")
    assert (await cursor.fetchone())[0] == 0
    await cursor.close()
    await persistence.close()


async def test_open_cancellation_releases_lock_and_closes_first_connection(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from app.persistence import agent_state

    original_connect = agent_state.aiosqlite.connect
    opened_connections = []

    async def cancel_second_connection(path):
        if opened_connections:
            raise asyncio.CancelledError
        connection = await original_connect(path)
        original_close = connection.close
        close_called = False

        async def tracked_close():
            nonlocal close_called
            close_called = True
            await original_close()

        connection.close = tracked_close
        opened_connections.append((connection, lambda: close_called))
        return connection

    monkeypatch.setattr(agent_state.aiosqlite, "connect", cancel_second_connection)
    with pytest.raises(asyncio.CancelledError):
        await AgentPersistence.open(
            tmp_path / "agent-state.sqlite3",
            TEST_KEY,
        )
    assert opened_connections[0][1]()

    monkeypatch.setattr(agent_state.aiosqlite, "connect", original_connect)
    reopened = await AgentPersistence.open(
        tmp_path / "agent-state.sqlite3",
        TEST_KEY,
    )
    await reopened.close()


async def test_close_continues_after_first_connection_error_and_releases_lock(
    tmp_path: Path,
) -> None:
    path = tmp_path / "agent-state.sqlite3"
    persistence = await AgentPersistence.open(path, TEST_KEY)
    checkpoint_close = persistence.checkpoint_connection.close
    store_close = persistence.connection.close
    store_closed = False

    async def close_checkpoint_then_fail():
        await checkpoint_close()
        raise OSError("checkpoint close failed")

    async def tracked_store_close():
        nonlocal store_closed
        store_closed = True
        await store_close()

    persistence.checkpoint_connection.close = close_checkpoint_then_fail
    persistence.connection.close = tracked_store_close
    with pytest.raises(BaseException, match="checkpoint close failed"):
        await persistence.close()
    assert store_closed

    reopened = await AgentPersistence.open(path, TEST_KEY)
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
    reopened = await AgentPersistence.open(path, TEST_KEY)
    await reopened.close()


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


async def test_second_process_is_rejected_until_first_process_closes(
    tmp_path: Path,
) -> None:
    path = tmp_path / "agent-state.sqlite3"
    first = await AgentPersistence.open(path, TEST_KEY)
    script = """
import asyncio
import sys
from app.persistence.agent_state import AgentPersistence, AgentStateProcessLockError

async def main():
    try:
        persistence = await AgentPersistence.open(sys.argv[1], sys.argv[2])
    except AgentStateProcessLockError as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(23)
    await persistence.close()

asyncio.run(main())
"""
    environment = os.environ.copy()
    environment["PYTHONPATH"] = str(Path(__file__).resolve().parents[2])
    blocked = subprocess.run(
        [sys.executable, "-c", script, str(path), TEST_KEY],
        capture_output=True,
        text=True,
        env=environment,
        timeout=5,
        check=False,
    )
    assert blocked.returncode == 23
    assert "仅单进程" in blocked.stderr

    await first.close()
    reopened = subprocess.run(
        [sys.executable, "-c", script, str(path), TEST_KEY],
        capture_output=True,
        text=True,
        env=environment,
        timeout=5,
        check=False,
    )
    assert reopened.returncode == 0, reopened.stderr


async def test_process_crash_releases_os_lock(tmp_path: Path) -> None:
    path = tmp_path / "agent-state.sqlite3"
    holder_script = """
import asyncio
import sys
from app.persistence.agent_state import AgentPersistence

async def main():
    persistence = await AgentPersistence.open(sys.argv[1], sys.argv[2])
    print("READY", flush=True)
    await asyncio.Event().wait()

asyncio.run(main())
"""
    probe_script = """
import asyncio
import sys
from app.persistence.agent_state import AgentPersistence

async def main():
    persistence = await AgentPersistence.open(sys.argv[1], sys.argv[2])
    await persistence.close()

asyncio.run(main())
"""
    environment = os.environ.copy()
    environment["PYTHONPATH"] = str(Path(__file__).resolve().parents[2])
    holder = subprocess.Popen(
        [sys.executable, "-c", holder_script, str(path), TEST_KEY],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=environment,
    )
    try:
        assert holder.stdout is not None
        assert holder.stdout.readline().strip() == "READY"
        holder.kill()
        holder.wait(timeout=5)
        reopened = subprocess.run(
            [sys.executable, "-c", probe_script, str(path), TEST_KEY],
            capture_output=True,
            text=True,
            env=environment,
            timeout=5,
            check=False,
        )
        assert reopened.returncode == 0, reopened.stderr
    finally:
        if holder.poll() is None:
            holder.kill()
            holder.wait(timeout=5)
