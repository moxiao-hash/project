from __future__ import annotations

import hashlib
import hmac
from datetime import datetime, timedelta, timezone

import pytest

from studypilot_runner.protocol import (
    EnvelopeVerifier,
    ProtocolError,
    canonical_payload,
)

SECRET = "runner-test-secret-that-is-at-least-32-bytes"


def envelope(**overrides: object) -> dict[str, object]:
    issued = datetime(2026, 9, 6, 4, 0, tzinfo=timezone.utc)
    value: dict[str, object] = {
        "protocolVersion": "v1",
        "executionId": "execution-1",
        "ownerId": "owner-1",
        "workspaceId": "workspace-1",
        "templateType": "MAVEN_TEST",
        "riskLevel": "LOW",
        "workspacePath": "/workspace/project",
        "commandTokens": ["mvn", "test"],
        "isolationMode": "LOCAL_RUNNER",
        "networkDisabled": True,
        "memoryLimit": "512m",
        "cpuLimit": "1.0",
        "timeoutSeconds": 60,
        "issuedAt": issued.isoformat().replace("+00:00", "Z"),
        "confirmedAt": None,
        "expiresAt": (issued + timedelta(minutes=10)).isoformat().replace("+00:00", "Z"),
        "nonce": "nonce-1",
    }
    value.update(overrides)
    value["signature"] = hmac.new(
        SECRET.encode(), canonical_payload(value), hashlib.sha256
    ).hexdigest()
    return value


def test_verifies_signature_and_persists_nonce_replay_protection(tmp_path) -> None:
    now = datetime(2026, 9, 6, 4, 1, tzinfo=timezone.utc)
    first = EnvelopeVerifier(SECRET, tmp_path / "nonces.sqlite3")
    first.verify_and_consume(envelope(), now)

    restarted = EnvelopeVerifier(SECRET, tmp_path / "nonces.sqlite3")
    with pytest.raises(ProtocolError, match="replay"):
        restarted.verify_and_consume(envelope(), now)


@pytest.mark.parametrize(
    ("change", "message"),
    [
        ({"workspaceId": "other"}, "signature"),
        ({"isolationMode": "DOCKER"}, "isolation"),
        ({"expiresAt": "2026-09-06T04:20:00Z"}, "expiry"),
    ],
)
def test_rejects_tampering_and_invalid_security_metadata(tmp_path, change, message) -> None:
    signed = envelope()
    signed.update(change)
    verifier = EnvelopeVerifier(SECRET, tmp_path / "nonces.sqlite3")
    with pytest.raises(ProtocolError, match=message):
        verifier.verify_and_consume(
            signed, datetime(2026, 9, 6, 4, 1, tzinfo=timezone.utc)
        )


def test_high_risk_envelope_requires_confirmation(tmp_path) -> None:
    verifier = EnvelopeVerifier(SECRET, tmp_path / "nonces.sqlite3")
    with pytest.raises(ProtocolError, match="confirmation"):
        verifier.verify_and_consume(
            envelope(riskLevel="HIGH"),
            datetime(2026, 9, 6, 4, 1, tzinfo=timezone.utc),
        )


@pytest.mark.parametrize(
    "change",
    [
        {"ownerId": ""},
        {"workspaceId": ""},
        {"riskLevel": "UNKNOWN"},
        {"networkDisabled": "true"},
        {"timeoutSeconds": True},
    ],
)
def test_rejects_signed_but_structurally_invalid_metadata(tmp_path, change) -> None:
    verifier = EnvelopeVerifier(SECRET, tmp_path / "nonces.sqlite3")
    with pytest.raises(ProtocolError, match="metadata"):
        verifier.verify_and_consume(
            envelope(**change),
            datetime(2026, 9, 6, 4, 1, tzinfo=timezone.utc),
        )
