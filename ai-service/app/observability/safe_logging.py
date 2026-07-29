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
    redactor = SecretRedactionFilter()
    root = logging.getLogger()
    for handler in root.handlers:
        if not any(isinstance(item, SecretRedactionFilter) for item in handler.filters):
            handler.addFilter(redactor)
