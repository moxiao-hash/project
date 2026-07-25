"""根据配置创建 LangChain 对话模型。

Agent 工作流只依赖 LangChain 的模型接口，而不直接依赖 DeepSeek SDK。以后接入
Ollama 时，只需要在这一层增加实现，不需要重写工作流节点。
"""

from langchain_openai import ChatOpenAI

from app.core.settings import Settings


class ModelConfigurationError(ValueError):
    """模型配置缺失或互相冲突。"""


def create_chat_model(settings: Settings) -> ChatOpenAI:
    """创建 DeepSeek 的 OpenAI 兼容客户端。

    这里只构造客户端，不会立即发起网络请求，因此应用启动和单元测试不会消耗
    Token。第一次调用 ``invoke``/``ainvoke`` 时才会访问模型服务。
    """

    api_key = settings.deepseek_api_key.get_secret_value()
    if not api_key:
        raise ModelConfigurationError("尚未配置 DEEPSEEK_API_KEY，无法创建 DeepSeek 模型客户端")

    return ChatOpenAI(
        api_key=api_key,
        base_url=settings.model_base_url,
        model=settings.model_name,
        temperature=0,
    )
