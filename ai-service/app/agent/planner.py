"""学习计划生成器的可替换接口。"""

from typing import Protocol

from app.agent.models import PlannerTurn
from app.agent.state import ConversationState


class PlanTurnGenerator(Protocol):
    """让 LangGraph 与具体模型供应商解耦。

    单元测试可以注入不联网的 Fake，生产环境则注入 DeepSeek 实现。以后增加 Ollama
    时，图和会话服务都无需修改。
    """

    async def generate(self, state: ConversationState) -> PlannerTurn:
        """根据完整可见对话和学习上下文生成下一轮结构化结果。"""

        ...
