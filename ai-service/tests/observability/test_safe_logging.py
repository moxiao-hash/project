import base64
import logging
from io import StringIO
from pathlib import Path

import pytest
import uvicorn
from fastapi import FastAPI
from pydantic import SecretStr

from app import main as main_module
from app.core.settings import Settings
from app.observability.safe_logging import SecretRedactionFilter, install_secret_redaction


def test_log_filter_removes_credentials_without_touching_safe_fields() -> None:
    record = logging.LogRecord(
        "test",
        logging.INFO,
        __file__,
        1,
        "requestId=req-1 Authorization: Bearer super-secret "
        "api_key=another-secret X-Internal-Service-Token: service-secret "
        '{"apiKey":"json-secret"}',
        (),
        None,
    )

    SecretRedactionFilter().filter(record)
    rendered = record.getMessage()

    assert "req-1" in rendered
    assert "super-secret" not in rendered
    assert "another-secret" not in rendered
    assert "service-secret" not in rendered
    assert "json-secret" not in rendered
    assert rendered.count("[REDACTED]") == 4


def test_install_after_uvicorn_configuration_covers_real_handlers() -> None:
    uvicorn.Config("app.main:app", log_config=uvicorn.config.LOGGING_CONFIG).configure_logging()
    stream = StringIO()
    handler = logging.StreamHandler(stream)
    logging.getLogger("uvicorn.error").addHandler(handler)
    try:
        install_secret_redaction()
        install_secret_redaction()

        loggers = [
            logging.getLogger(),
            logging.getLogger("uvicorn"),
            logging.getLogger("uvicorn.error"),
            logging.getLogger("uvicorn.access"),
        ]
        actual_handlers = {
            current_handler
            for logger in loggers
            for current_handler in logger.handlers
        }
        assert actual_handlers
        assert all(
            sum(
                isinstance(item, SecretRedactionFilter)
                for item in current_handler.filters
            )
            == 1
            for current_handler in actual_handlers
        )

        logging.getLogger("uvicorn.error").warning(
            "requestId=req-7 ordinary=kept "
            "Authorization: Bearer logging-test-secret "
            "api_key=logging-api-secret "
            "X-Internal-Service-Token: logging-service-secret"
        )
        rendered = stream.getvalue()
        assert "requestId=req-7" in rendered
        assert "ordinary=kept" in rendered
        assert "logging-test-secret" not in rendered
        assert "logging-api-secret" not in rendered
        assert "logging-service-secret" not in rendered
    finally:
        logging.getLogger("uvicorn.error").removeHandler(handler)


@pytest.mark.anyio
async def test_lifespan_reinstalls_filter_after_uvicorn_logging_setup(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    uvicorn.Config("app.main:app", log_config=uvicorn.config.LOGGING_CONFIG).configure_logging()
    settings = Settings(
        _env_file=None,
        agent_state_db_path=str(tmp_path / "agent-state.sqlite3"),
        langgraph_aes_key=SecretStr(base64.b64encode(bytes(range(32))).decode()),
        agent_worker_count=1,
    )
    monkeypatch.setattr(main_module, "get_settings", lambda: settings)

    async with main_module.lifespan(FastAPI()):
        handlers = {
            handler
            for logger_name in ("", "uvicorn", "uvicorn.error", "uvicorn.access")
            for handler in logging.getLogger(logger_name).handlers
        }
        assert handlers
        assert all(
            any(isinstance(item, SecretRedactionFilter) for item in handler.filters)
            for handler in handlers
        )
