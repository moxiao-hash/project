"""自适应题目配比和测验生成编排。"""

from typing import Any, Protocol

from app.assessment.models import (
    Difficulty,
    GeneratedQuiz,
    QuestionType,
    QuizSource,
    WebSearchPolicy,
)
from app.retrieval.models import RetrievedEvidence
from app.schemas.learning import LearningContext, LearningTask
from app.search.models import WebSearchOutcome


class QuizMix(dict[QuestionType, int]):
    def __init__(
        self,
        single: int,
        multiple: int,
        coding: int,
        difficulty: Difficulty,
    ) -> None:
        super().__init__(
            {
                QuestionType.SINGLE_CHOICE: single,
                QuestionType.MULTIPLE_CHOICE: multiple,
                QuestionType.CODING: coding,
            }
        )
        self.difficulty = difficulty


class QuizGenerationPolicy:
    @staticmethod
    def for_mastery(score: float | None) -> QuizMix:
        if score is None or score < 40:
            return QuizMix(3, 1, 1, Difficulty.EASY)
        if score < 70:
            return QuizMix(2, 1, 2, Difficulty.MEDIUM)
        return QuizMix(1, 1, 3, Difficulty.HARD)


class PrivateAssessmentSourceError(ValueError):
    pass


class AssessmentTaskNotFoundError(LookupError):
    pass


class InvalidGeneratedQuizError(RuntimeError):
    pass


class AssessmentJavaBackend(Protocol):
    async def get_learning_context(self, owner_id: str) -> LearningContext: ...

    async def create_quiz(self, payload: dict[str, Any]) -> dict[str, Any]: ...


class AssessmentRetriever(Protocol):
    async def search(self, owner_id: str, query: str) -> list[RetrievedEvidence]: ...


class AssessmentWebSearch(Protocol):
    async def search(self, owner_id: str, query: str) -> WebSearchOutcome: ...


class QuizGenerator(Protocol):
    async def generate(
        self,
        *,
        task: LearningTask,
        mix: QuizMix,
        sources: list[QuizSource],
    ) -> GeneratedQuiz: ...


class QuizGenerationService:
    def __init__(
        self,
        java_backend: AssessmentJavaBackend,
        retriever: AssessmentRetriever,
        web_search: AssessmentWebSearch,
        generator: QuizGenerator,
    ) -> None:
        self._java = java_backend
        self._retriever = retriever
        self._web = web_search
        self._generator = generator

    async def generate(
        self,
        owner_id: str,
        task_id: str,
        web_policy: WebSearchPolicy,
    ) -> dict[str, Any]:
        context = await self._java.get_learning_context(owner_id)
        task = next((item for item in context.tasks if item.id == task_id), None)
        if task is None:
            raise AssessmentTaskNotFoundError("学习任务不存在或不属于当前用户")
        evidence = await self._retriever.search(owner_id, task.title)
        if any(
            item.privacy_level in {"SENSITIVE", "LOCAL_ONLY"} for item in evidence
        ):
            raise PrivateAssessmentSourceError(
                "命中隐私资料，未向 DeepSeek 或 Tavily 发送题目上下文"
            )
        sources = [self._material_source(item) for item in evidence]
        if self._should_search(task.title, web_policy):
            outcome = await self._web.search(owner_id, task.title)
            sources.extend(
                QuizSource(
                    source_type="WEB",
                    web_result_id=item.result_id,
                    title=item.title,
                    locator=item.url,
                    snippet=item.snippet,
                )
                for item in outcome.results
            )
        if not sources:
            sources.append(
                QuizSource(
                    source_type="MODEL_KNOWLEDGE",
                    title="模型稳定基础知识",
                    locator="模型常识",
                    snippet=f"围绕学习任务“{task.title}”生成基础概念练习。",
                )
            )
        score = min((item.score for item in context.mastery), default=None)
        mix = QuizGenerationPolicy.for_mastery(score)
        generated = await self._generator.generate(
            task=task,
            mix=mix,
            sources=sources,
        )
        self._validate_generated(generated, mix, len(sources))
        questions: list[dict[str, Any]] = []
        for question in generated.questions:
            payload = question.model_dump(
                by_alias=True,
                mode="json",
                exclude={"source_indexes"},
            )
            payload["sources"] = [
                sources[index].model_dump(by_alias=True, mode="json", exclude_none=True)
                for index in question.source_indexes
            ]
            questions.append(payload)
        return await self._java.create_quiz(
            {
                "ownerId": owner_id,
                "taskId": task_id,
                "title": generated.title,
                "modelName": "deepseek",
                "questions": questions,
            }
        )

    @staticmethod
    def _material_source(item: RetrievedEvidence) -> QuizSource:
        return QuizSource(
            source_type="MATERIAL",
            material_id=item.material_id,
            title=item.title,
            locator=item.locator,
            snippet=item.text,
        )

    @staticmethod
    def _should_search(query: str, policy: WebSearchPolicy) -> bool:
        if policy == WebSearchPolicy.ENABLED:
            return True
        if policy == WebSearchPolicy.DISABLED:
            return False
        lowered = query.lower()
        return any(
            marker in lowered
            for marker in ("当前", "最新", "版本", "api", "current", "latest", "version")
        )

    @staticmethod
    def _validate_generated(
        quiz: GeneratedQuiz,
        mix: QuizMix,
        source_count: int,
    ) -> None:
        counts = {question_type: 0 for question_type in QuestionType}
        for question in quiz.questions:
            counts[question.type] += 1
            if question.difficulty != mix.difficulty:
                raise InvalidGeneratedQuizError("题目难度与掌握度策略不一致")
            if any(index < 0 or index >= source_count for index in question.source_indexes):
                raise InvalidGeneratedQuizError("题目引用了不存在的来源")
        if any(counts[item] != mix[item] for item in QuestionType):
            raise InvalidGeneratedQuizError("模型题型配比不符合自适应策略")
