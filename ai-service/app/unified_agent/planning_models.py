"""Task 28 多步规划器的结构化输出契约。

字段与枚举严格对应 `docs/agent-native-contract.md` v2 冻结版本第 2.1 节
`AssistantPlan` / `AssistantPlanStep`：

- 每轮最多 8 步（``stepId`` 只允许 ``s1``～``s8``）。
- ``dependsOn`` 只能引用前序步骤，且必须无环。
- 模型只提出计划，不执行工具；执行决定权始终在 Java 与 Python 策略层。

唯一一处相对契约文档的放宽：``planId`` 由服务端生成，模型可以不提供。这样既能
让外部消费方始终拿到非空计划 ID，又不会因为模型漏写一个随机 ID 而让整轮规划失败。
"""

from enum import StrEnum
from typing import Any
from uuid import UUID, uuid4

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class PlanContractModel(BaseModel):
    """规划契约基类：Python 使用 snake_case，模型 JSON 使用 camelCase。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class PlanIntent(StrEnum):
    """契约冻结的意图枚举，模型不能自定义新值。"""

    LEARNING_QUERY = "LEARNING_QUERY"
    ROADMAP_NAVIGATE = "ROADMAP_NAVIGATE"
    PLAN_ADJUSTMENT = "PLAN_ADJUSTMENT"
    QUIZ_PRACTICE = "QUIZ_PRACTICE"
    CODE_DEVELOPMENT = "CODE_DEVELOPMENT"
    CLARIFY = "CLARIFY"
    GENERAL_CHAT = "GENERAL_CHAT"


class AssistantPlanStep(PlanContractModel):
    """单个公开计划步骤；工具名必须来自 Java 已发布目录。"""

    step_id: str = Field(pattern=r"^s[1-8]$")
    tool_name: str = Field(min_length=1, max_length=120)
    arguments: dict[str, Any] = Field(default_factory=dict)
    depends_on: list[str] = Field(default_factory=list)


class AssistantPlan(PlanContractModel):
    """模型输出的公开计划；``summary`` 是唯一直接面向用户的模型文本。"""

    plan_id: UUID = Field(default_factory=uuid4)
    intent: PlanIntent
    confidence: float = Field(ge=0.0, le=1.0)
    summary: str = Field(min_length=1, max_length=200)
    steps: list[AssistantPlanStep] = Field(default_factory=list, max_length=8)
