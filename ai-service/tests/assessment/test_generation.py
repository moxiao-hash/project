import pytest

from app.assessment.models import (
    CodingKind,
    CodingRubric,
    Difficulty,
    GeneratedQuestion,
    GeneratedQuiz,
    QuestionType,
    WebSearchPolicy,
)
from app.assessment.service import (
    PrivateAssessmentSourceError,
    QuizGenerationPolicy,
    QuizGenerationService,
)
from app.retrieval.models import RetrievedEvidence
from app.schemas.learning import (
    LearningContext,
    LearningGoal,
    LearningPlan,
    LearningTask,
    LearningTaskStatus,
)


@pytest.mark.parametrize(
    ("score", "choice_count", "coding_count", "difficulty"),
    [
        (None, 4, 1, Difficulty.EASY),
        (39.9, 4, 1, Difficulty.EASY),
        (40, 3, 2, Difficulty.MEDIUM),
        (69.99, 3, 2, Difficulty.MEDIUM),
        (70, 2, 3, Difficulty.HARD),
    ],
)
def test_fixed_five_question_mix_changes_with_mastery(
    score, choice_count, coding_count, difficulty
) -> None:
    mix = QuizGenerationPolicy.for_mastery(score)

    assert sum(mix.values()) == 5
    assert mix[QuestionType.SINGLE_CHOICE] + mix[QuestionType.MULTIPLE_CHOICE] == (
        choice_count
    )
    assert mix[QuestionType.CODING] == coding_count
    assert mix.difficulty == difficulty


class FakeContext:
    async def get_learning_context(self, owner_id):
        return LearningContext(
            goals=[
                LearningGoal(
                    id="goal-1",
                    title="学习 Java",
                    targetDate="2026-12-31",
                    weeklyStudyHours=10,
                    status="ACTIVE",
                )
            ],
            plans=[
                LearningPlan(
                    id="plan-1",
                    goalId="goal-1",
                    title="Java 计划",
                    startDate="2026-07-27",
                    endDate="2026-08-31",
                    status="ACTIVE",
                    version=1,
                )
            ],
            tasks=[
                LearningTask(
                    id="task-1",
                    planId="plan-1",
                    title="Java 方法基础",
                    scheduledDate="2026-07-27",
                    estimatedMinutes=60,
                    status=LearningTaskStatus.TODO,
                    version=1,
                )
            ],
            materials=[],
            mastery=[],
        )

    async def create_quiz(self, payload):
        self.payload = payload
        return {"id": "quiz-1", "title": payload["title"], "questions": []}


class FakeRetriever:
    def __init__(self, privacy="NORMAL"):
        self.privacy = privacy

    async def search(self, owner_id, query):
        return [
            RetrievedEvidence(
                material_id="material-1",
                title="Java 笔记",
                text="Java 方法可以返回计算结果。",
                locator="chunk 1",
                category="LEARNING_MATERIAL",
                privacy_level=self.privacy,
                score=0.9,
            )
        ]


class FakeWeb:
    async def search(self, owner_id, query):
        raise AssertionError("稳定基础题不应强制联网")


class FakeGenerator:
    async def generate(self, *, task, mix, sources):
        questions = []
        types = (
            [QuestionType.SINGLE_CHOICE] * mix[QuestionType.SINGLE_CHOICE]
            + [QuestionType.MULTIPLE_CHOICE] * mix[QuestionType.MULTIPLE_CHOICE]
            + [QuestionType.CODING] * mix[QuestionType.CODING]
        )
        for index, question_type in enumerate(types):
            coding = question_type == QuestionType.CODING
            questions.append(
                GeneratedQuestion(
                    type=question_type,
                    difficulty=mix.difficulty,
                    codingKind=CodingKind.CODE_COMPLETION if coding else None,
                    language="JAVA" if coding else None,
                    knowledgePoint="Java 方法",
                    questionText=f"问题 {index + 1}",
                    options=[] if coding else ["A", "B"],
                    correctAnswers={"return a + b;"} if coding else {"A"},
                    explanation="解析",
                    starterCode="int add(int a,int b){}" if coding else None,
                    rubric=CodingRubric() if coding else None,
                    referenceAnswer="return a + b;" if coding else None,
                    sourceIndexes=[0],
                )
            )
        return GeneratedQuiz(title="Java 方法测验", questions=questions)


@pytest.mark.anyio
async def test_generation_persists_exact_mix_and_material_sources() -> None:
    java = FakeContext()
    service = QuizGenerationService(java, FakeRetriever(), FakeWeb(), FakeGenerator())

    result = await service.generate(
        "user-1",
        "task-1",
        WebSearchPolicy.AUTO,
    )

    assert result["id"] == "quiz-1"
    assert java.payload["taskId"] == "task-1"
    assert len(java.payload["questions"]) == 5
    assert java.payload["questions"][0]["sources"][0]["materialId"] == "material-1"


@pytest.mark.anyio
async def test_private_hit_refuses_generation_before_model_or_web() -> None:
    java = FakeContext()
    service = QuizGenerationService(
        java,
        FakeRetriever(privacy="LOCAL_ONLY"),
        FakeWeb(),
        FakeGenerator(),
    )

    with pytest.raises(PrivateAssessmentSourceError):
        await service.generate("user-1", "task-1", WebSearchPolicy.ENABLED)
