"""Java 与 Local Runner 之间的签名信封协议。"""

from __future__ import annotations

import hashlib
import hmac
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


class ProtocolError(ValueError):
    """执行信封未通过安全验证。"""


def _epoch_millis(value: Any) -> str:
    if value in (None, ""):
        return ""
    parsed = _parse_time(value)
    return str(int(parsed.timestamp() * 1000))


def _parse_time(value: Any) -> datetime:
    if not isinstance(value, str):
        raise ProtocolError("invalid timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ProtocolError("invalid timestamp") from exc
    if parsed.tzinfo is None:
        raise ProtocolError("timestamp must include timezone")
    return parsed.astimezone(timezone.utc)


def canonical_payload(envelope: dict[str, Any]) -> bytes:
    """生成与 Java RunnerSecurityService 完全一致的长度前缀载荷。"""

    tokens = envelope.get("commandTokens")
    if not isinstance(tokens, list) or not all(isinstance(item, str) for item in tokens):
        raise ProtocolError("invalid command tokens")
    values: list[str] = [
        str(envelope.get("protocolVersion") or ""),
        str(envelope.get("executionId") or ""),
        str(envelope.get("ownerId") or ""),
        str(envelope.get("workspaceId") or ""),
        str(envelope.get("templateType") or ""),
        str(envelope.get("riskLevel") or ""),
        str(envelope.get("workspacePath") or ""),
        str(len(tokens)),
        *tokens,
        str(envelope.get("isolationMode") or ""),
        str(bool(envelope.get("networkDisabled"))).lower(),
        str(envelope.get("memoryLimit") or ""),
        str(envelope.get("cpuLimit") or ""),
        str(envelope.get("timeoutSeconds") or ""),
        _epoch_millis(envelope.get("issuedAt")),
        _epoch_millis(envelope.get("confirmedAt")),
        _epoch_millis(envelope.get("expiresAt")),
        str(envelope.get("nonce") or ""),
    ]
    return "".join(f"{len(value)}#{value}" for value in values).encode()


class EnvelopeVerifier:
    """验签并把 nonce 持久化，进程重启后仍能阻止重放。"""

    def __init__(self, signing_secret: str, nonce_database: Path) -> None:
        if len(signing_secret.encode()) < 32:
            raise ProtocolError("signing secret must contain at least 32 bytes")
        self._secret = signing_secret.encode()
        self._database = Path(nonce_database)
        self._database.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with self._connect() as connection:
            connection.execute(
                "create table if not exists consumed_nonces ("
                "nonce text primary key, expires_at text not null)"
            )

    def verify_and_consume(self, envelope: dict[str, Any], now: datetime | None = None) -> None:
        current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
        self._validate_structure(envelope)
        if envelope.get("protocolVersion") != "v1":
            raise ProtocolError("unsupported protocol")
        if envelope.get("isolationMode") != "LOCAL_RUNNER":
            raise ProtocolError("invalid isolation mode")
        issued = _parse_time(envelope.get("issuedAt"))
        expires = _parse_time(envelope.get("expiresAt"))
        if expires <= current or issued > current + timedelta(seconds=30):
            raise ProtocolError("expired execution envelope")
        if expires > issued + timedelta(minutes=10):
            raise ProtocolError("expiry exceeds ten minutes")
        if envelope.get("riskLevel") == "HIGH":
            confirmed = envelope.get("confirmedAt")
            if confirmed is None:
                raise ProtocolError("high-risk execution requires confirmation")
            confirmed_at = _parse_time(confirmed)
            if confirmed_at > issued or confirmed_at < issued - timedelta(minutes=10):
                raise ProtocolError("invalid confirmation timestamp")
        signature = envelope.get("signature")
        if not isinstance(signature, str):
            raise ProtocolError("missing signature")
        expected = hmac.new(self._secret, canonical_payload(envelope), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, signature.lower()):
            raise ProtocolError("invalid signature")
        nonce = envelope.get("nonce")
        if not isinstance(nonce, str) or not nonce:
            raise ProtocolError("missing nonce")
        self._consume_nonce(nonce, expires, current)

    def _validate_structure(self, envelope: dict[str, Any]) -> None:
        required_text = ("executionId", "ownerId", "workspaceId", "workspacePath")
        if any(
            not isinstance(envelope.get(field), str) or not envelope.get(field)
            for field in required_text
        ):
            raise ProtocolError("invalid security metadata")
        if envelope.get("templateType") not in {
            "MAVEN_TEST",
            "MAVEN_COMPILE",
            "NPM_TEST",
            "PYTEST",
            "PREPARE_DEPENDENCIES",
        }:
            raise ProtocolError("invalid security metadata")
        if envelope.get("riskLevel") not in {"LOW", "HIGH"}:
            raise ProtocolError("invalid security metadata")
        if type(envelope.get("networkDisabled")) is not bool:
            raise ProtocolError("invalid security metadata")
        timeout = envelope.get("timeoutSeconds")
        if type(timeout) is not int or timeout < 1 or timeout > 300:
            raise ProtocolError("invalid security metadata")

    def _consume_nonce(self, nonce: str, expires: datetime, now: datetime) -> None:
        try:
            with self._connect() as connection:
                connection.execute("delete from consumed_nonces where expires_at < ?", (now.isoformat(),))
                connection.execute(
                    "insert into consumed_nonces(nonce, expires_at) values (?, ?)",
                    (nonce, expires.isoformat()),
                )
        except sqlite3.IntegrityError as exc:
            raise ProtocolError("replay detected") from exc

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self._database, timeout=5)
