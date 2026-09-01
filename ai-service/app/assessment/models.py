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
    correct_answers: set[str] = Field(default_factory=set)
    explanation: str = Field(min_length=1)
    starter_code: str | None = None
    rubric: CodingRubric | None = None
    reference_answer: str | None = None
    source_indexes: list[int] = Field(min_length=1)

    @model_validator(mode="before")
    @classmethod
    def normalize_choice_labels(cls, data):
        """把模型常见的 ``A`` 答案映射回 ``A. 完整选项``。

        只接受唯一前缀匹配；无法确定的答案保持原样，随后由严格校验拒绝。
        """

        if not isinstance(data, dict):
            return data
        options = data.get("options")
        answer_key = (
            "correctAnswers" if "correctAnswers" in data else "correct_answers"
        )
        answers = data.get(answer_key)
        if not isinstance(options, list) or not isinstance(answers, (list, set)):
            return data
        normalized = []
        for answer in answers:
            if answer in options:
                normalized.append(answer)
                continue
            matches = [
                option
                for option in options
                if isinstance(answer, str)
                and isinstance(option, str)
                and (
                    option.startswith(answer + ".")
                    or option.startswith(answer + "、")
                    or option.startswith(answer + " ")
                )
            ]
            normalized.append(matches[0] if len(matches) == 1 else answer)
        data[answer_key] = normalized
        return data

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
        if self.type != QuestionType.CODING and not self.correct_answers:
            raise ValueError("选择题必须提供至少一个正确答案")
        if self.type != QuestionType.CODING and not self.correct_answers.issubset(
            set(self.options)
        ):
            raise ValueError("选择题正确答案必须来自 options")
        return self


class GeneratedQuiz(BaseModel):
    title: str = Field(min_length=1, max_length=160)
    questions: list[GeneratedQuestion] = Field(min_length=5, max_length=5)


class RoadmapGeneratedQuestion(GeneratedQuestion):
    coverage_node_id: str = Field(alias="coverageNodeId", min_length=1)
    practical: bool
    high_frequency_ref: str | None = Field(
        default=None, alias="highFrequencyRef", min_length=2, max_length=200
    )
    points: int = Field(gt=0, le=100)
    question_signature: str = Field(alias="questionSignature", min_length=1, max_length=64)

    @model_validator(mode="after")
    def require_high_frequency_reference_for_practical_question(self):
        """实践题必须声明它考查的目录高频点，便于后续确定性校验。"""

        if self.practical and self.high_frequency_ref is None:
            raise ValueError("实践题必须提供 highFrequencyRef")
        return self


class RoadmapGeneratedQuiz(BaseModel):
    title: str = Field(min_length=1, max_length=160)
    questions: list[RoadmapGeneratedQuestion] = Field(min_length=5, max_length=5)

    @model_validator(mode="after")
    def require_one_hundred_points(self):
        if sum(question.points for question in self.questions) != 100:
            raise ValueError("路线节点测验总分必须为 100")
        return self


class GenerateQuizRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    owner_id: str = Field(min_length=1)
    task_id: str | None = Field(default=None, min_length=1)
    lesson_id: str | None = Field(default=None, min_length=1)
    web_search: WebSearchPolicy = WebSearchPolicy.AUTO

    @model_validator(mode="after")
    def require_exactly_one_target(self):
        if (self.task_id is None) == (self.lesson_id is None):
            raise ValueError("taskId 与 lessonId 必须且只能提供一个")
        return self
