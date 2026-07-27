"""代码文本评估的结构化契约。"""

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class CodingEvaluation(BaseModel):
    """DeepSeek 对一道编程题的分项评价。

    四个分项的上限固定在领域规则中，不能由题目正文或用户代码改变。
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    question_id: str = Field(min_length=1)
    score: float = Field(ge=0, le=100)
    correctness: float = Field(ge=0, le=40)
    completeness: float = Field(ge=0, le=25)
    edge_cases: float = Field(ge=0, le=20)
    clarity_efficiency: float = Field(ge=0, le=15)
    issues: list[str] = Field(default_factory=list)
    feedback: str = Field(min_length=1)
    suggested_code: str = Field(min_length=1)

    @model_validator(mode="after")
    def validate_total(self):
        component_total = (
            self.correctness
            + self.completeness
            + self.edge_cases
            + self.clarity_efficiency
        )
        if abs(component_total - self.score) > 0.01:
            raise ValueError("总分必须等于四个固定 Rubric 分项之和")
        return self


class CodingEvaluationBatch(BaseModel):
    evaluations: list[CodingEvaluation] = Field(min_length=1)
