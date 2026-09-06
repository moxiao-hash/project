from __future__ import annotations

import hashlib
import hmac
import json
import socket
import stat
import tempfile
import threading
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from studypilot_runner.protocol import EnvelopeVerifier, canonical_payload
from studypilot_runner.server import (
    LocalRunnerApplication,
    RequestRejected,
    create_server,
)

SECRET = "runner-test-secret-that-is-at-least-32-bytes"


class FakeEngine:
    def __init__(self) -> None:
        self.requests: list[dict[str, object]] = []

    def execute(self, request: dict[str, object]) -> dict[str, object]:
        self.requests.append(request)
        return {
            "exitCode": 0,
            "stdoutSummary": "tests passed",
            "stderrSummary": "",
            "success": True,
            "durationMillis": 8,
        }


def signed_request(workspace, **changes: object) -> dict[str, object]:
    issued = datetime.now(timezone.utc) - timedelta(seconds=1)
    envelope: dict[str, object] = {
        "protocolVersion": "v1",
        "executionId": "execution-1",
        "ownerId": "owner-1",
        "workspaceId": "workspace-1",
        "templateType": "MAVEN_TEST",
        "riskLevel": "LOW",
        "workspacePath": str(workspace),
        "commandTokens": ["mvn", "test"],
        "isolationMode": "LOCAL_RUNNER",
        "networkDisabled": True,
        "memoryLimit": "512m",
        "cpuLimit": "1.0",
        "timeoutSeconds": 60,
        "issuedAt": issued.isoformat().replace("+00:00", "Z"),
        "confirmedAt": None,
        "expiresAt": (issued + timedelta(minutes=10)).isoformat().replace("+00:00", "Z"),
        "nonce": "nonce-server-1",
    }
    envelope.update(changes)
    envelope["signature"] = hmac.new(
        SECRET.encode(), canonical_payload(envelope), hashlib.sha256
    ).hexdigest()
    return {
        "envelope": envelope,
        "workspaceId": envelope["workspaceId"],
        "templateType": envelope["templateType"],
    }


def test_executes_only_after_outer_contract_signature_and_workspace_checks(tmp_path) -> None:
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    engine = FakeEngine()
    app = LocalRunnerApplication(
        EnvelopeVerifier(SECRET, tmp_path / "nonces.sqlite3"), engine, [tmp_path]
    )

    result = app.handle(signed_request(workspace))

    assert result["status"] == "SUCCEEDED"
    assert result["workspaceId"] == "workspace-1"
    assert result["commandTokens"] == ["mvn", "test"]
    assert len(engine.requests) == 1


def test_rejects_outer_metadata_mismatch_and_workspace_escape(tmp_path) -> None:
    allowed = tmp_path / "allowed"
    allowed.mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    engine = FakeEngine()
    app = LocalRunnerApplication(
        EnvelopeVerifier(SECRET, tmp_path / "nonces.sqlite3"), engine, [allowed]
    )
    mismatched = signed_request(allowed)
    mismatched["workspaceId"] = "other"
    with pytest.raises(RequestRejected, match="metadata"):
        app.handle(mismatched)
    with pytest.raises(RequestRejected, match="allowed roots"):
        app.handle(signed_request(outside, nonce="nonce-server-2"))
    assert engine.requests == []


def test_rejects_symlink_workspace_even_when_target_is_allowed(tmp_path) -> None:
    actual = tmp_path / "actual"
    actual.mkdir()
    link = tmp_path / "link"
    link.symlink_to(actual, target_is_directory=True)
    app = LocalRunnerApplication(
        EnvelopeVerifier(SECRET, tmp_path / "nonces.sqlite3"), FakeEngine(), [tmp_path]
    )
    with pytest.raises(RequestRejected, match="canonical"):
        app.handle(signed_request(link))


def test_unix_server_uses_owner_only_socket_and_json_line_protocol(tmp_path) -> None:
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    app = LocalRunnerApplication(
        EnvelopeVerifier(SECRET, tmp_path / "nonces.sqlite3"), FakeEngine(), [tmp_path]
    )
    socket_directory = Path(
        tempfile.mkdtemp(prefix=f"sp-{uuid.uuid4().hex[:8]}-", dir="/tmp")
    )
    socket_path = socket_directory / "runner.sock"
    server = create_server(socket_path, app)
    thread = threading.Thread(target=server.handle_request)
    thread.start()
    try:
        assert stat.S_IMODE(socket_path.stat().st_mode) == 0o600
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.connect(str(socket_path))
            client.sendall(json.dumps(signed_request(workspace)).encode() + b"\n")
            response = json.loads(client.makefile("rb").readline())
        assert response["status"] == "SUCCEEDED"
    finally:
        thread.join(timeout=2)
        server.server_close()
        socket_path.unlink(missing_ok=True)
        socket_directory.rmdir()
