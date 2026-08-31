"""自适应题目配比和测验生成编排。"""

import asyncio
import logging
from collections.abc import Awaitable, Callable
from datetime import date
from typing import Any, Protocol
from urllib.parse import urlparse

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

logger = logging.getLogger(__name__)


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

    async def get_lesson_context(
        self,
        owner_id: str,
        lesson_id: str,
    ) -> dict[str, Any]: ...


class AssessmentRetriever(Protocol):
    async def search(self, owner_id: str, query: str) -> list[RetrievedEvidence]: ...


class AssessmentWebSearch(Protocol):
    async def search(
        self, owner_id: str, query: str, *, include_domains: tuple[str, ...] = ()
    ) -> WebSearchOutcome: ...


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
        task_id: str | None,
        web_policy: WebSearchPolicy,
        *,
        lesson_id: str | None = None,
    ) -> dict[str, Any]:
        context = await self._java.get_learning_context(owner_id)
        lesson_sources: list[QuizSource] = []
        if lesson_id is not None:
            lesson_context = await self._java.get_lesson_context(owner_id, lesson_id)
            lesson = lesson_context["lesson"]
            task = LearningTask(
                id=f"lesson:{lesson_id}",
                planId=f"course:{lesson.get('moduleId', 'unknown')}",
                title=lesson["title"],
                scheduledDate=date.today(),
                estimatedMinutes=lesson.get("estimatedMinutes", 60),
                status="TODO",
                version=1,
            )
            lesson_sources = [
                QuizSource(
                    source_type="LESSON_SOURCE",
                    title=source["title"],
                    locator=source.get("locator"),
                    snippet=source.get("locator") or source["title"],
                )
                for source in lesson.get("sources", [])
            ]
            if not lesson_sources:
                lesson_sources.append(
                    QuizSource(
                        source_type="LESSON_SOURCE",
                        title=lesson["title"],
                        locator="课时讲义",
                        snippet=lesson.get("summary", lesson["title"]),
                    )
                )
        else:
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
        sources = [
            *lesson_sources,
            *(self._material_source(item) for item in evidence),
        ]
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
        payload = {
            "ownerId": owner_id,
            "title": generated.title,
            "modelName": "deepseek",
            "questions": questions,
        }
        if lesson_id is not None:
            payload["lessonId"] = lesson_id
        else:
            payload["taskId"] = task_id
        return await self._java.create_quiz(payload)

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


class RoadmapQuizWorker:
    """领取 Java 持久化任务并生成受节点图约束的五题测验。"""

    def __init__(
        self,
        backend: Any,
        generator: Any,
        web_search: Any,
        *,
        generator_factory: Callable[[str], Awaitable[Any]] | None = None,
        web_search_factory: Callable[[str], Awaitable[Any]] | None = None,
        worker_id: str,
        model_name: str,
        heartbeat_interval_seconds: float = 30,
    ) -> None:
        self._backend = backend
        self._generator = generator
        self._web_search = web_search
        self._generator_factory = generator_factory
        self._web_search_factory = web_search_factory
        self._worker_id = worker_id
        self._model_name = model_name
        self._heartbeat_interval_seconds = heartbeat_interval_seconds

    async def process_once(self) -> bool:
        job = await self._backend.claim_roadmap_quiz_job(self._worker_id)
        if job is None:
            return False
        job_id = job["id"]
        lease_token = job["leaseToken"]
        stop_heartbeat = asyncio.Event()
        heartbeat = asyncio.create_task(
            self._heartbeat(job_id, lease_token, stop_heartbeat)
        )
        try:
            context = await self._backend.get_roadmap_quiz_context(
                job_id, self._worker_id, lease_token
            )
            sources = self._catalog_sources(context)
            if self._is_explicitly_time_sensitive(context):
                sources.extend(await self._official_web_sources(context))
            recent = set(context.get("recentQuestionSignatures", []))
            generator = self._generator
            if self._generator_factory is not None:
                generator = await self._generator_factory(job["ownerId"])
            generated = await generator.generate_node_quiz(
                context=context,
                sources=sources,
                recent_signatures=recent,
            )
            self._validate_node_quiz(generated, context, recent, sources)
            questions = []
            for question in generated.questions:
                payload = question.model_dump(
                    by_alias=True,
                    mode="json",
                    exclude={"source_indexes"},
                )
                payload["sources"] = [
                    source.model_dump(by_alias=True, mode="json", exclude_none=True)
                    for index, source in enumerate(sources)
                    if index in question.source_indexes
                ]
                questions.append(payload)
            quiz = await self._backend.create_quiz(
                {
                    "ownerId": job["ownerId"],
                    "roadmapNodeId": job["nodeId"],
                    # 绑定信息来自已领取的持久化任务，而不是模型上下文。
                    # 这样即使上下文 DTO 以后精简字段，测验仍然绑定到创建
                    # 任务时的原路线，升级路线后也不会误写当前路线。
                    "userRoadmapId": job["userRoadmapId"],
                    "userRoadmapNodeId": job["userRoadmapNodeId"],
                    "roadmapTemplateId": job["roadmapTemplateId"],
                    "purpose": "NODE",
                    "title": generated.title,
                    "modelName": self._model_name,
                    "questions": questions,
                }
            )
            try:
                await self._backend.complete_roadmap_quiz_job(
                    job_id, self._worker_id, lease_token, quiz["id"]
                )
            except (TimeoutError, ConnectionError):
                await self._backend.complete_roadmap_quiz_job(
                    job_id, self._worker_id, lease_token, quiz["id"]
                )
        except Exception as exc:
            try:
                await self._backend.fail_roadmap_quiz_job(
                    job_id,
                    self._worker_id,
                    lease_token,
                    f"路线测验生成失败: {type(exc).__name__}",
                )
            except Exception:
                logger.warning("路线测验失败回报被租约 fencing 拒绝", exc_info=True)
        finally:
            stop_heartbeat.set()
            await heartbeat
        return True

    async def _heartbeat(
        self, job_id: str, lease_token: str, stopped: asyncio.Event
    ) -> None:
        while True:
            try:
                await asyncio.wait_for(
                    stopped.wait(), timeout=self._heartbeat_interval_seconds
                )
                return
            except TimeoutError:
                try:
                    await self._backend.heartbeat_roadmap_quiz_job(
                        job_id, self._worker_id, lease_token
                    )
                except Exception:
                    # 心跳失败可能只是短暂的网络故障；最终 complete/fail 仍由
                    # Java 的 leaseToken fencing 决定该 Worker 是否有写权限。
                    logger.warning("路线测验任务心跳失败", exc_info=True)

    @staticmethod
    def _catalog_sources(context: dict[str, Any]) -> list[QuizSource]:
        nodes = [context["node"], *context.get("directPrerequisites", [])]
        return [
            QuizSource(
                source_type="ROADMAP_CATALOG",
                title=node["title"],
                locator=f"roadmap-node:{node['id']}",
                snippet="；".join(
                    [
                        *node.get("objectives", []),
                        *node.get("highFrequency", []),
                        *[
                            item.get("prompt", "") if isinstance(item, dict) else item
                            for item in node.get("quizBlueprint", [])
                        ],
                    ]
                ),
            )
            for node in nodes
        ]

    @staticmethod
    def _is_explicitly_time_sensitive(context: dict[str, Any]) -> bool:
        return any(
            isinstance(item, dict) and item.get("timeSensitive") is True
            for item in context["node"].get("quizBlueprint", [])
        )

    async def _official_web_sources(self, context: dict[str, Any]) -> list[QuizSource]:
        web_search = self._web_search
        if self._web_search_factory is not None:
            web_search = await self._web_search_factory(context["ownerId"])
        if web_search is None:
            return []
        allowed = tuple(context.get("officialDomains", []))
        outcome = await web_search.search(
            context["ownerId"],
            context["node"]["title"],
            include_domains=allowed,
        )
        return [
            QuizSource(
                source_type="WEB",
                web_result_id=result.result_id,
                title=result.title,
                locator=result.url,
                snippet=result.snippet,
            )
            for result in outcome.results
            if self._is_official(result.url, allowed)
        ]

    @staticmethod
    def _is_official(url: str, allowed: tuple[str, ...]) -> bool:
        host = (urlparse(url).hostname or "").lower()
        return any(host == domain or host.endswith("." + domain) for domain in allowed)

    @staticmethod
    def _validate_node_quiz(
        generated, context: dict[str, Any], recent: set[str], sources: list[QuizSource]
    ) -> None:
        current = context["node"]["id"]
        allowed = {current} | {
            node["id"] for node in context.get("directPrerequisites", [])
        }
        coverage = [question.coverage_node_id for question in generated.questions]
        signatures = [question.question_signature for question in generated.questions]
        if len(generated.questions) != 5 or sum(q.points for q in generated.questions) != 100:
            raise InvalidGeneratedQuizError("路线节点测验必须恰好五题且总分为100")
        if any(question.points != 20 for question in generated.questions):
            raise InvalidGeneratedQuizError("路线节点测验每题必须为20分")
        if coverage.count(current) < 3 or not set(coverage) <= allowed:
            raise InvalidGeneratedQuizError("题目超出当前节点或直接前置范围")
        if sum(question.practical for question in generated.questions) < 3:
            raise InvalidGeneratedQuizError("高频实用题不足三题")
        node_by_source = {
            index: (
                source.locator.removeprefix("roadmap-node:")
                if source.source_type == "ROADMAP_CATALOG"
                else current
            )
            for index, source in enumerate(sources)
        }
        high_frequency = " ".join(
            item
            for node in [context["node"], *context.get("directPrerequisites", [])]
            for item in node.get("highFrequency", [])
        ).lower()
        for question in generated.questions:
            if not question.source_indexes or any(
                index < 0 or index >= len(sources) for index in question.source_indexes
            ):
                raise InvalidGeneratedQuizError("题目来源索引为空或越界")
            referenced_nodes = {node_by_source[index] for index in question.source_indexes}
            if question.coverage_node_id not in referenced_nodes:
                raise InvalidGeneratedQuizError("题目覆盖节点与目录来源不匹配")
            if question.practical and not any(
                token.lower() in high_frequency
                for token in (question.knowledge_point, question.question_text)
            ):
                raise InvalidGeneratedQuizError("实用题缺少高频内容依据")
        if len(set(signatures)) != 5 or recent.intersection(signatures):
            raise InvalidGeneratedQuizError("题目签名与近期测验重复")
