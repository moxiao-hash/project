"""FastAPI 生命周期使用的 Agent 持久化装配。"""

from app.core.settings import Settings
from app.persistence.agent_state import (
    AgentPersistence,
    AgentStateKeyError,
)


class AgentPersistenceConfigurationError(RuntimeError):
    """持久化配置无法安全启动。"""


async def open_agent_persistence(settings: Settings) -> AgentPersistence:
    if settings.agent_worker_count != 1:
        raise AgentPersistenceConfigurationError("本地 SQLite Agent 状态仅支持单个 FastAPI worker")
    encoded_key = settings.langgraph_aes_key.get_secret_value()
    if not encoded_key:
        raise AgentPersistenceConfigurationError(
            "必须配置 LANGGRAPH_AES_KEY（Base64 编码的 32 字节密钥）"
        )
    try:
        return await AgentPersistence.open(settings.agent_state_db_path, encoded_key)
    except AgentStateKeyError as exc:
        raise AgentPersistenceConfigurationError(str(exc)) from exc
