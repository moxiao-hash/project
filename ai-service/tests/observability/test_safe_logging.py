import logging

from app.observability.safe_logging import SecretRedactionFilter


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
