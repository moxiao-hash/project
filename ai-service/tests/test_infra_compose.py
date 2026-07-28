from pathlib import Path


def test_backend_receives_required_stable_credential_master_key() -> None:
    compose = (
        Path(__file__).resolve().parents[2] / "infra" / "docker-compose.yml"
    ).read_text()

    assert (
        "AI_CREDENTIAL_MASTER_KEY: "
        "${AI_CREDENTIAL_MASTER_KEY:?set AI_CREDENTIAL_MASTER_KEY in infra/.env}"
    ) in compose
    assert (
        "INTERNAL_SERVICE_TOKEN: "
        "${INTERNAL_SERVICE_TOKEN:?set INTERNAL_SERVICE_TOKEN in infra/.env}"
    ) in compose
    assert '"127.0.0.1:${BACKEND_PORT:-8080}:8080"' in compose
    assert "local-dev-internal-token" not in compose
