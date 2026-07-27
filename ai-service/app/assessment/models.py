"""测验生成和评估的强类型模型。"""

from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class QuestionType(StrEnum):
    SINGLE_CHOICE = "SINGLE_CHOICE"
    MULTIPLE_CHOICE = "MULTIPLE_CHOICE"
    CODING = "CODING"


class Difficulty(StrEnum):
    EASY = "EASY"
    MEDIUM = "MEDIUM"
    HARD = "HARD"


class CodingKind(StrEnum):
    CODE_COMPLETION = "CODE_COMPLETION"
    DEBUGGING = "DEBUGGING"
    METHOD_IMPLEMENTATION = "METHOD_IMPLEMENTATION"
    MINI_MODULE = "MINI_MODULE"


class WebSearchPolicy(StrEnum):
    AUTO = "AUTO"
    ENABLED = "ENABLED"
    DISABLED = "DISABLED"


class QuizSource(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    source_type: str
    title: str
    snippet: str
    material_id: str | None = None
    web_result_id: str | None = None
    locator: str | None = None


class CodingRubric(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    correctness: int = 40
    completeness: int = 25
    edge_cases: int = 20
    clarity_efficiency: int = 15


class GeneratedQuestion(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    type: QuestionType
    difficulty: Difficulty
    coding_kind: CodingKind | None = None
    language: str | None = None
    knowledge_point: str = Field(min_length=1, max_length=180)
    question_text: str = Field(min_length=1)
    options: list[str] = Field(default_factory=list)
    correct_answers: set[str] = Field(min_length=1)
    explanation: str = Field(min_length=1)
    starter_code: str | None = None
    rubric: CodingRubric | None = None
    reference_answer: str | None = None
    source_indexes: list[int] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_type_fields(self):
        if self.type == QuestionType.CODING and any(
            value is None
            for value in (
                self.coding_kind,
                self.language,
                self.starter_code,
                self.rubric,
                self.reference_answer,
            )
        ):
            raise ValueError("编程题缺少代码类型、语言、Rubric 或参考答案")
        if self.type != QuestionType.CODING and len(self.options) < 2:
            raise ValueError("选择题至少需要两个选项")
        return self


class GeneratedQuiz(BaseModel):
    title: str = Field(min_length=1, max_length=160)
    questions: list[GeneratedQuestion] = Field(min_length=5, max_length=5)


class GenerateQuizRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    owner_id: str = Field(min_length=1)
    task_id: str = Field(min_length=1)
    web_search: WebSearchPolicy = WebSearchPolicy.AUTO
