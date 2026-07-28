"""按用户解析 AI 服务凭据。"""

from enum import StrEnum
from hashlib import sha256

from pydantic import SecretStr

from app.clients.java_backend import JavaBackendClient, JavaBackendError
from app.core.settings import Settings


class CredentialProvider(StrEnum):
    DEEPSEEK = "deepseek"
    TAVILY = "tavily"


class CredentialServiceUnavailableError(RuntimeError):
    """Java 凭据服务不可用，此时禁止静默换用服务器身份。"""


class CredentialResolver:
    def __init__(self, java: JavaBackendClient, settings: Settings) -> None:
        self._java = java
        self._settings = settings

    async def resolve(
        self,
        owner_id: str,
        provider: CredentialProvider,
    ) -> SecretStr:
        try:
            return await self._java.get_ai_credential(owner_id, provider.value)
        except JavaBackendError as exc:
            if exc.status_code != 404:
                raise CredentialServiceUnavailableError(
                    "用户 AI 凭据服务暂时不可用"
                ) from exc
        # 只有明确的 404（用户未配置）才允许回退到开发环境默认值。
        if provider is CredentialProvider.DEEPSEEK:
            return self._settings.deepseek_api_key
        return self._settings.tavily_api_key


def credential_fingerprint(*values: SecretStr) -> str:
    """生成不可逆的内存比较值，用于检测 Key 轮换，不保留明文副本。"""

    digest = sha256()
    for value in values:
        digest.update(value.get_secret_value().encode())
        digest.update(b"\0")
    return digest.hexdigest()
