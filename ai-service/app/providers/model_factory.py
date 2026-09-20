"""根据配置创建 LangChain 对话模型。

Agent 工作流只依赖 LangChain 的模型接口，而不直接依赖 DeepSeek SDK。以后接入
Ollama 时，只需要在这一层增加实现，不需要重写工作流节点。

返回值统一是 :class:`~app.providers.budgeted_model.BudgetedChatModel`：所有真实
provider 调用都必须先经过预算预占，结构化输出包装器也不能绕过。
"""

from langchain_openai import ChatOpenAI
from pydantic import SecretStr

from app.clients.java_backend import JavaBackendClient
from app.core.settings import Settings
from app.observability.model_metrics import ModelMetricsCallback
from app.observability.usage import (
    AssistantUsageReporter,
    ModelPurpose,
    ModelUsageCallback,
)
from app.providers.budget import ModelBudgetGuard
from app.providers.budgeted_model import BudgetedChatModel


class ModelConfigurationError(ValueError):
    """模型配置缺失或互相冲突。"""


def create_chat_model(
    settings: Settings,
    api_key: SecretStr | None = None,
    *,
    owner_id: str | None = None,
    purpose: str = ModelPurpose.UNKNOWN,
    reporter: AssistantUsageReporter | None = None,
    budget_guard: ModelBudgetGuard | None = None,
) -> BudgetedChatModel:
    """创建 DeepSeek 的 OpenAI 兼容客户端，并包装成预算执行边界。

    这里只构造客户端，不会立即发起网络请求，因此应用启动和单元测试不会消耗
    Token。第一次调用 ``invoke``/``ainvoke``/``astream`` 时才会先预占预算再访问
    模型服务。

    ``owner_id`` 与 ``purpose`` 用于用量归属；未显式给出 owner 时，回调与预算预占
    都会尝试从当前 ``usage_scope`` 读取，两者都缺失则拒绝调用（并有日志暴露未接入
    的边界）。``budget_guard`` 允许测试注入假实现。
    """

    resolved_key = (api_key or settings.deepseek_api_key).get_secret_value()
    if not resolved_key:
        raise ModelConfigurationError("尚未配置 DEEPSEEK_API_KEY，无法创建 DeepSeek 模型客户端")

    java = JavaBackendClient(settings)
    resolved_reporter = reporter if reporter is not None else AssistantUsageReporter(java)
    resolved_guard = budget_guard if budget_guard is not None else ModelBudgetGuard(java)
    chat_model = ChatOpenAI(
        api_key=resolved_key,
        base_url=settings.model_base_url,
        model=settings.model_name,
        temperature=0,
        callbacks=[
            ModelMetricsCallback(settings.model_provider, settings.model_name),
            ModelUsageCallback(
                provider=settings.model_provider,
                model=settings.model_name,
                reporter=resolved_reporter,
                owner_id=owner_id,
                purpose=purpose,
            ),
        ],
    )
    return BudgetedChatModel(
        chat_model,
        guard=resolved_guard,
        owner_id=owner_id,
        provider=settings.model_provider,
        model_name=settings.model_name,
        purpose=purpose,
    )
