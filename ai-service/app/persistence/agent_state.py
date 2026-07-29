"""加密的 Agent 会话元数据与 LangGraph checkpoint 持久化。"""

from __future__ import annotations

import base64
import binascii
import fcntl
import hashlib
import hmac
import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import aiosqlite
from Crypto.Cipher import AES
from langgraph.checkpoint.serde.encrypted import EncryptedSerializer
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver


class AgentStateKeyError(ValueError):
    """加密主密钥缺失或格式错误。"""


class AgentStateDecryptionError(RuntimeError):
    """密钥错误或密文损坏；不向上层暴露密文细节。"""


class AgentStateSchemaError(RuntimeError):
    """数据库来自当前程序无法读取的更新版本。"""


class AgentStateProcessLockError(RuntimeError):
    """另一个 FastAPI 进程已经持有本地 Agent 状态数据库。"""


class EncryptedConversationStore:
    """在 SQLite 中保存 AES-GCM 密文；owner 仅保存不可逆 HMAC。"""

    def __init__(self, connection: aiosqlite.Connection, key: bytes) -> None:
        self._connection = connection
        self._key = key

    async def setup(self) -> None:
        cursor = await self._connection.execute("PRAGMA user_version")
        row = await cursor.fetchone()
        await cursor.close()
        version = int(row[0])
        if version > 1:
            raise AgentStateSchemaError(f"Agent 状态数据库版本 {version} 高于当前支持的版本 1")
        await self._connection.execute(
            """
            CREATE TABLE IF NOT EXISTS agent_state_metadata (
                key TEXT PRIMARY KEY,
                nonce BLOB NOT NULL,
                ciphertext BLOB NOT NULL
            )
            """
        )
        await self._verify_key()
        await self._connection.execute(
            """
            CREATE TABLE IF NOT EXISTS agent_conversations (
                conversation_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                owner_hash BLOB NOT NULL,
                nonce BLOB NOT NULL,
                ciphertext BLOB NOT NULL,
                schema_version INTEGER NOT NULL DEFAULT 1,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (conversation_id, kind)
            )
            """
        )
        await self._connection.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_agent_conversations_owner
            ON agent_conversations(kind, owner_hash)
            """
        )
        if version == 0:
            await self._connection.execute("PRAGMA user_version=1")
        await self._connection.commit()

    async def _verify_key(self) -> None:
        cursor = await self._connection.execute(
            "SELECT nonce, ciphertext FROM agent_state_metadata WHERE key = 'key_check'"
        )
        row = await cursor.fetchone()
        await cursor.close()
        aad = b"studypilot-agent-state-key-check-v1"
        if row is None:
            nonce = AES.get_random_bytes(12)
            cipher = AES.new(self._key, AES.MODE_GCM, nonce=nonce)
            cipher.update(aad)
            encrypted, tag = cipher.encrypt_and_digest(b"valid")
            await self._connection.execute(
                """
                INSERT INTO agent_state_metadata(key, nonce, ciphertext)
                VALUES ('key_check', ?, ?)
                """,
                (nonce, tag + encrypted),
            )
            await self._connection.commit()
            return
        nonce, encrypted = row
        try:
            cipher = AES.new(self._key, AES.MODE_GCM, nonce=nonce)
            cipher.update(aad)
            plaintext = cipher.decrypt_and_verify(encrypted[16:], encrypted[:16])
        except ValueError as exc:
            raise AgentStateDecryptionError(
                "Agent 状态数据库无法解密，请检查 LANGGRAPH_AES_KEY"
            ) from exc
        if plaintext != b"valid":
            raise AgentStateDecryptionError("Agent 状态数据库密钥校验失败")

    async def save(
        self,
        *,
        kind: str,
        conversation_id: str,
        owner_id: str,
        payload: dict[str, Any],
    ) -> None:
        plaintext = json.dumps(
            payload,
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode()
        nonce = AES.get_random_bytes(12)
        cipher = AES.new(self._key, AES.MODE_GCM, nonce=nonce)
        cipher.update(self._aad(kind, conversation_id, owner_id))
        encrypted, tag = cipher.encrypt_and_digest(plaintext)
        await self._connection.execute(
            """
            INSERT INTO agent_conversations(
                conversation_id, kind, owner_hash, nonce, ciphertext,
                schema_version, updated_at
            ) VALUES (?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP)
            ON CONFLICT(conversation_id, kind) DO UPDATE SET
                owner_hash = excluded.owner_hash,
                nonce = excluded.nonce,
                ciphertext = excluded.ciphertext,
                schema_version = excluded.schema_version,
                updated_at = CURRENT_TIMESTAMP
            """,
            (
                conversation_id,
                kind,
                self._owner_hash(owner_id),
                nonce,
                tag + encrypted,
            ),
        )
        await self._connection.commit()

    async def load(
        self,
        *,
        kind: str,
        conversation_id: str,
        owner_id: str,
    ) -> dict[str, Any] | None:
        cursor = await self._connection.execute(
            """
            SELECT nonce, ciphertext
            FROM agent_conversations
            WHERE conversation_id = ? AND kind = ? AND owner_hash = ?
            """,
            (conversation_id, kind, self._owner_hash(owner_id)),
        )
        row = await cursor.fetchone()
        await cursor.close()
        if row is None:
            return None
        nonce, encrypted = row
        try:
            cipher = AES.new(self._key, AES.MODE_GCM, nonce=nonce)
            cipher.update(self._aad(kind, conversation_id, owner_id))
            plaintext = cipher.decrypt_and_verify(encrypted[16:], encrypted[:16])
            value = json.loads(plaintext)
        except (ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
            raise AgentStateDecryptionError(
                "Agent 会话状态无法解密，请检查 LANGGRAPH_AES_KEY"
            ) from exc
        if not isinstance(value, dict):
            raise AgentStateDecryptionError("Agent 会话状态格式无效")
        return value

    def _owner_hash(self, owner_id: str) -> bytes:
        return hmac.new(
            self._key,
            f"owner:{owner_id}".encode(),
            hashlib.sha256,
        ).digest()

    @staticmethod
    def _aad(kind: str, conversation_id: str, owner_id: str) -> bytes:
        return f"{kind}\0{conversation_id}\0{owner_id}".encode()


@dataclass
class AgentPersistence:
    """同一连接承载业务快照与 LangGraph checkpoint，供整个进程共享。"""

    connection: aiosqlite.Connection
    store: EncryptedConversationStore
    checkpointer: AsyncSqliteSaver
    lock_fd: int

    @classmethod
    async def open(cls, path: str | Path, encoded_key: str) -> AgentPersistence:
        key = _decode_key(encoded_key)
        db_path = Path(path)
        db_path.parent.mkdir(parents=True, exist_ok=True)
        lock_fd = _acquire_process_lock(db_path)
        connection: aiosqlite.Connection | None = None
        try:
            connection = await aiosqlite.connect(db_path)
            await connection.execute("PRAGMA journal_mode=WAL")
            await connection.execute("PRAGMA busy_timeout=5000")
            await connection.execute("PRAGMA foreign_keys=ON")
            store = EncryptedConversationStore(connection, key)
            await store.setup()
            serializer = EncryptedSerializer.from_pycryptodome_aes(key=key)
            checkpointer = AsyncSqliteSaver(connection, serde=serializer)
            await checkpointer.setup()
            return cls(connection, store, checkpointer, lock_fd)
        except Exception:
            if connection is not None:
                await connection.close()
            _release_process_lock(lock_fd)
            raise

    async def close(self) -> None:
        try:
            await self.connection.close()
        finally:
            if self.lock_fd >= 0:
                _release_process_lock(self.lock_fd)
                self.lock_fd = -1


def _decode_key(value: str) -> bytes:
    try:
        key = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise AgentStateKeyError("LANGGRAPH_AES_KEY 必须是 Base64 编码的 32 字节密钥") from exc
    if len(key) != 32:
        raise AgentStateKeyError("LANGGRAPH_AES_KEY 必须是 Base64 编码的 32 字节密钥")
    return key


def _acquire_process_lock(db_path: Path) -> int:
    lock_path = db_path.with_name(f"{db_path.name}.lock")
    lock_fd = os.open(lock_path, os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as exc:
        os.close(lock_fd)
        raise AgentStateProcessLockError(
            "Agent 状态 SQLite 仅单进程运行；当前数据库已被另一个进程占用"
        ) from exc
    return lock_fd


def _release_process_lock(lock_fd: int) -> None:
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_UN)
    finally:
        os.close(lock_fd)
