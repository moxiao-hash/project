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


class SecretRedactionFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        rendered = record.getMessage()
        rendered = _JSON_SECRET.sub(r"\1[REDACTED]", rendered)
        record.msg = _SECRET.sub(r"\1\2[REDACTED]", rendered)
        record.args = ()
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
