from pathlib import Path

import yaml


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


def test_full_stack_is_private_persistent_and_health_checked() -> None:
    root = Path(__file__).resolve().parents[2]
    compose = (root / "infra" / "docker-compose.yml").read_text()

    for service in ("mysql:", "backend:", "ai-service:", "web:", "prometheus:"):
        assert service in compose
    assert 'profiles: ["observability"]' in compose
    assert "JAVA_BACKEND_BASE_URL: http://backend:8080" in compose
    assert "AI_SERVICE_BASE_URL: http://ai-service:8000" in compose
    assert "AGENT_STATE_DB_PATH: /data/agent-state.sqlite3" in compose
    assert "QDRANT_PATH: /data/qdrant" in compose
    assert "studypilot-agent-data:/data" in compose
    assert "studypilot-hf-cache:/home/studypilot/.cache/huggingface" in compose
    assert '"127.0.0.1:${WEB_PORT:-5173}:80"' in compose
    assert "8000:8000" not in compose
    assert "condition: service_healthy" in compose


def test_container_and_nginx_contracts_do_not_expose_internal_api() -> None:
    root = Path(__file__).resolve().parents[2]
    ai_dockerfile = (root / "ai-service" / "Dockerfile").read_text()
    backend_dockerfile = (root / "backend" / "Dockerfile").read_text()
    web_dockerfile = (root / "web" / "Dockerfile").read_text()
    nginx = (root / "web" / "nginx.conf").read_text()

    assert "USER studypilot" in ai_dockerfile
    assert "--workers" in ai_dockerfile and '"1"' in ai_dockerfile
    assert "HEALTHCHECK" in ai_dockerfile
    assert "chown -R studypilot:studypilot /data" in ai_dockerfile
    assert "apk add --no-cache curl" in backend_dockerfile
    assert 'CMD ["curl", "--fail"' in backend_dockerfile
    runtime_stage = backend_dockerfile.split(
        "FROM eclipse-temurin:17-jre-alpine",
        1,
    )[1]
    assert "mkdir -p /app/data" in runtime_stage
    assert "chown -R studypilot:studypilot /app/data" in runtime_stage
    assert runtime_stage.index("mkdir -p /app/data") < runtime_stage.index("USER studypilot")
    assert runtime_stage.index(
        "chown -R studypilot:studypilot /app/data"
    ) < runtime_stage.index("USER studypilot")
    assert "FROM node:" in web_dockerfile
    assert "FROM nginx:" in web_dockerfile
    assert "location /api/" in nginx
    assert "proxy_pass http://backend:8080;" in nginx
    assert "try_files $uri $uri/ /index.html;" in nginx
    assert "location /internal" in nginx
    internal_block = nginx.split("location /internal", 1)[1].split("}", 1)[0]
    assert "return 404" in internal_block
    assert "ai-service" not in nginx
    assert "X-Request-ID" in nginx


def test_compose_has_no_development_secret_defaults() -> None:
    root = Path(__file__).resolve().parents[2]
    compose = (root / "infra" / "docker-compose.yml").read_text()
    example = (root / "infra" / ".env.example").read_text()

    for name in (
        "MYSQL_PASSWORD",
        "MYSQL_ROOT_PASSWORD",
        "INTERNAL_SERVICE_TOKEN",
        "AI_CREDENTIAL_MASTER_KEY",
        "LANGGRAPH_AES_KEY",
    ):
        assert f"${{{name}:?" in compose
    for forbidden in (
        "studypilot-root",
        "local-dev-internal-token",
        "sk-",
        "tvly-",
    ):
        assert forbidden not in compose
        assert forbidden not in example


def test_compose_wires_java_agent_gateway_to_internal_ai_service() -> None:
    root = Path(__file__).resolve().parents[2]
    document = yaml.safe_load((root / "infra" / "docker-compose.yml").read_text())
    backend_environment = document["services"]["backend"]["environment"]

    assert (
        backend_environment["STUDYPILOT_AI_SERVICE_BASE_URL"]
        == "http://ai-service:8000"
    )
