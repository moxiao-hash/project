"""集中管理 AI 服务配置。

业务代码不直接调用 ``os.getenv``。统一的 Settings 对象可以完成类型校验，也能在
测试中被替换，从而避免测试依赖开发者电脑上的真实环境变量。
"""

from functools import lru_cache
from typing import Literal

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """从环境变量和 ``ai-service/.env`` 加载运行配置。"""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "studypilot-ai"
    model_provider: Literal["deepseek"] = "deepseek"
    model_base_url: str = "https://api.deepseek.com"
    model_name: str = "deepseek-v4-pro"
    deepseek_api_key: SecretStr = SecretStr("")

    java_backend_base_url: str = "http://localhost:8080"
    internal_service_token: SecretStr = SecretStr("")
    nightly_adjustment_interval_minutes: int = 15
    material_processing_interval_seconds: int = 10
    material_worker_id: str = "studypilot-ai-local"
    coding_evaluation_interval_seconds: int = 5
    coding_evaluation_worker_id: str = "studypilot-ai-coding-local"
    qdrant_path: str = "./data/qdrant"
    fastembed_cache_path: str = "./data/fastembed"
    tavily_api_key: SecretStr = SecretStr("")
    tavily_base_url: str = "https://api.tavily.com"
    agent_state_db_path: str = "./data/agent-state.sqlite3"
    langgraph_aes_key: SecretStr = SecretStr("")
    agent_worker_count: int = 1

    @property
    def model_is_configured(self) -> bool:
        """只暴露是否配置 Key，不暴露 Key 的实际内容。"""

        return bool(self.deepseek_api_key.get_secret_value())


@lru_cache
def get_settings() -> Settings:
    """创建并缓存配置，避免每个 HTTP 请求都重新读取 ``.env``。"""

    return Settings()
