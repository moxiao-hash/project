"""日志敏感字段兜底脱敏。

业务日志仍应只记录事件和关联 ID，而不记录请求正文；本过滤器用于防止异常对象
意外把常见凭据字段写入日志。
"""

import logging
import re

_SECRET = re.compile(
    r"(?i)(authorization|x-internal-service-token|api[_-]?key)"
    r"(\s*[:=]\s*)(bearer\s+)?[^\s,;}\"]+"
)
_JSON_SECRET = re.compile(
    r"""(?i)(["'](?:authorization|x-internal-service-token|api[_-]?key)["']"""
    r"""\s*:\s*["'])[^"']+"""
)


def _redact(value: str) -> str:
    value = _JSON_SECRET.sub(r"\1[REDACTED]", value)
    return _SECRET.sub(r"\1\2[REDACTED]", value)


class SecretRedactionFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        if (
            record.name == "uvicorn.access"
            and isinstance(record.args, tuple)
            and len(record.args) == 5
        ):
            # AccessFormatter unpacks this five-element tuple to derive
            # client_addr/request_line/status_code. Flattening it via
            # getMessage() makes every Uvicorn access log fail to format.
            record.args = tuple(
                _redact(value) if isinstance(value, str) else value
                for value in record.args
            )
        else:
            record.msg = _redact(record.getMessage())
            record.args = ()
        if record.exc_info is not None:
            # 先格式化再脱敏，并移除原始异常元组，防止 Formatter 再次输出原文。
            record.exc_text = _redact(
                logging.Formatter().formatException(record.exc_info)
            )
            record.exc_info = None
        if record.stack_info:
            record.stack_info = _redact(record.stack_info)
        return True


def install_secret_redaction() -> None:
    """把过滤器安装到 Uvicorn 完成配置后实际使用的所有 handler。

    Uvicorn 会在导入应用前后重新配置 named logger，因此这里只在模块导入时调用
    不足以覆盖生产日志。函数可重复调用，每个 handler 最多安装一个过滤器。
    """

    for logger_name in ("", "uvicorn", "uvicorn.error", "uvicorn.access"):
        logger = logging.getLogger(logger_name)
        for handler in logger.handlers:
            if not any(
                isinstance(item, SecretRedactionFilter)
                for item in handler.filters
            ):
                handler.addFilter(SecretRedactionFilter())
