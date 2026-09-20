"""任务意图识别器的可替换接口与 DeepSeek 实现。"""

from datetime import date
from typing import Any, Protocol

from langchain_core.messages import HumanMessage, SystemMessage

from app.agent.task_models import TaskRecognitionOutput
from app.prompts.task_action import build_task_action_prompt
from app.schemas.learning import LearningTask


class TaskIntentRecognizer(Protocol):
    """让任务应用服务与具体模型供应商解耦。"""

    async def recognize(
        self,
        *,
        message: str,
        tasks: list[LearningTask],
        reference_date: date,
    ) -> TaskRecognitionOutput:
        """从真实候选任务中识别用户意图。"""

        ...


class TaskRecognitionOutputError(RuntimeError):
    """模型连续两次没有返回合法的任务识别结构。"""


class DeepSeekTaskRecognizer:
    """把 DeepSeek 输出约束为经过 Pydantic 校验的识别结果。"""

    def __init__(self, chat_model: Any) -> None:
        self._structured_model = chat_model.with_structured_output(
            TaskRecognitionOutput,
            method="json_mode",
        )

    async def recognize(
        self,
        *,
        message: str,
        tasks: list[LearningTask],
        reference_date: date,
    ) -> TaskRecognitionOutput:
        prompt = build_task_action_prompt(
            message=message,
            tasks=tasks,
            reference_date=reference_date,
        )
        messages = [
            SystemMessage(
                content=(
                    "你是 StudyPilot 的任务意图识别器。只识别用户意图和候选任务，"
                    "不执行任何操作，不展示思维链，并严格返回约定 JSON。"
                )
            ),
            HumanMessage(content=prompt),
        ]

        for attempt in range(2):
            try:
                output = await self._structured_model.ainvoke(messages)
                return TaskRecognitionOutput.model_validate(output)
            except Exception as exc:
                if attempt == 1:
                    raise TaskRecognitionOutputError(
                        "模型未能返回合法的任务识别结构"
                    ) from exc
                messages.append(
                    HumanMessage(
                        content=(
                            "上一次输出未通过结构校验。请只使用允许的 intent 和候选"
                            "任务 ID，缺失的信息保持 null，只返回合法 JSON。"
                        )
                    )
                )

        raise AssertionError("unreachable")
