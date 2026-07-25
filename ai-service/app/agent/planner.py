"""学习计划生成器的可替换接口与 DeepSeek 实现。"""

from typing import Any, Protocol

from langchain_core.messages import HumanMessage, SystemMessage

from app.agent.models import PlannerTurn
from app.agent.state import ConversationState
from app.prompts.learning_plan import build_learning_plan_prompt


class PlanTurnGenerator(Protocol):
    """让 LangGraph 与具体模型供应商解耦。

    单元测试可以注入不联网的 Fake，生产环境则注入 DeepSeek 实现。以后增加 Ollama
    时，图和会话服务都无需修改。
    """

    async def generate(self, state: ConversationState) -> PlannerTurn:
        """根据完整可见对话和学习上下文生成下一轮结构化结果。"""

        ...


class PlannerOutputError(RuntimeError):
    """模型连续两次没有返回符合契约的结构化结果。"""


class DeepSeekPlanner:
    """把 DeepSeek 的回复约束为经过 Pydantic 校验的 PlannerTurn。"""

    def __init__(self, chat_model: Any) -> None:
        self._structured_model = chat_model.with_structured_output(
            PlannerTurn,
            method="json_mode",
        )

    async def generate(self, state: ConversationState) -> PlannerTurn:
        prompt = build_learning_plan_prompt(state)
        messages = [
            SystemMessage(
                content=(
                    "你是 StudyPilot 的学习计划专家。只根据系统提供的数据和用户可见"
                    "对话工作，不展示思维链，并严格返回符合约定结构的 JSON。"
                )
            ),
            HumanMessage(content=prompt),
        ]

        for attempt in range(2):
            try:
                result = await self._structured_model.ainvoke(messages)
                return PlannerTurn.model_validate(result)
            except Exception as exc:
                if attempt == 1:
                    raise PlannerOutputError("模型未能返回合法的学习计划结构") from exc
                messages.append(
                    HumanMessage(
                        content=(
                            "上一次输出未通过结构校验。请修正字段、日期范围、任务数量和"
                            " status/draft 对应关系，只返回合法 JSON。"
                        )
                    )
                )

        raise AssertionError("unreachable")
