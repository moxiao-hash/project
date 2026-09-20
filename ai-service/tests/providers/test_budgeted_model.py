"""预算执行边界：每次 provider 调用都预占、绑定当次 max_tokens，且无法绕过。"""

import asyncio
import contextlib
from datetime import date

import pytest

from app.agent.planner import DeepSeekPlanner
from app.agent.task_recognizer import DeepSeekTaskRecognizer
from app.artifact_review.evaluator import DeepSeekArtifactEvaluator
from app.assessment.evaluation import DeepSeekCodingEvaluator
from app.assessment.generator import DeepSeekQuizGenerator
from app.assessment.models import Difficulty, QuizSource
from app.assessment.service import QuizMix
from app.knowledge.answering import DeepSeekKnowledgeAnswerer
from app.material.analysis import DeepSeekMaterialAnalyzer
from app.material.models import MaterialChunk
from app.providers.budget import BudgetPermit, ModelBudgetExceededError
from app.providers.budgeted_model import BudgetedChatModel
from app.schemas.learning import LearningTask
from app.teaching.answering import DeepSeekTeachingAnswerer
from app.unified_agent.planner import AssistantPlanner
from app.unified_agent.policy_validator import PlanPolicyValidator


class RecordingRunnable:
    """底层假 runnable：记录真实 provider 调用与每次绑定的 max_tokens。"""

    def __init__(self, result=None, chunks=None) -> None:
        self.result = result if result is not None else {}
        self.chunks = chunks or []
        self.calls: list[object] = []
        self.bound: list[dict] = []

    def with_structured_output(self, _schema, **_kwargs):
        return self

    def bind(self, **kwargs):
        self.bound.append(kwargs)
        return self

    async def ainvoke(self, messages, config=None, **kwargs):
        self.calls.append(messages)
        return self.result

    async def astream(self, messages, config=None, **kwargs):
        self.calls.append(messages)
        for chunk in self.chunks:
            yield type("Chunk", (), {"content": chunk})()


class SequencedGuard:
    """每次预占返回一个新上限，用来证明调用使用的是"当次"决策。"""

    def __init__(self, allow: bool = True) -> None:
        self.allow = allow
        self.reserves: list[dict] = []
        self.released: list[str] = []

    async def reserve(self, **kwargs):
        self.reserves.append(kwargs)
        if not self.allow:
            return BudgetPermit(None, False, "DAILY_MODEL_CALLS_EXHAUSTED", 256)
        return BudgetPermit(
            f"reservation-{len(self.reserves)}",
            True,
            "WITHIN_BUDGET",
            1000 + len(self.reserves),
        )

    async def release(self, reservation_id):
        self.released.append(reservation_id)


class FailingGuard:
    """模拟预占端点不可达：边界必须失败关闭。"""

    async def reserve(self, **kwargs):
        return BudgetPermit(None, False, "BUDGET_CHECK_UNAVAILABLE")

    async def release(self, reservation_id):
        return None


def _model(guard, *, owner_id: str = "owner-1", runnable=None) -> tuple:
    runnable = runnable if runnable is not None else RecordingRunnable()
    model = BudgetedChatModel(
        runnable,
        guard=guard,
        owner_id=owner_id,
        provider="deepseek",
        model_name="deepseek-flash",
        purpose="QUIZ_GENERATION",
    )
    return model, runnable


@pytest.mark.anyio
async def test_two_calls_perform_two_reservations_and_use_each_fresh_cap():
    guard = SequencedGuard()
    model, runnable = _model(guard)

    await model.ainvoke([{"role": "user", "content": "first"}])
    await model.ainvoke([{"role": "user", "content": "second"}])

    assert len(guard.reserves) == 2
    assert len(runnable.calls) == 2
    assert [entry["max_tokens"] for entry in runnable.bound] == [1001, 1002]


@pytest.mark.anyio
async def test_second_call_is_blocked_after_first_consumes_the_last_permit():
    class SinglePermitGuard(SequencedGuard):
        def __init__(self) -> None:
            super().__init__()
            self._remaining = 1
            self._lock = asyncio.Lock()

        async def reserve(self, **kwargs):
            async with self._lock:
                self.reserves.append(kwargs)
                await asyncio.sleep(0)
                if self._remaining == 0:
                    return BudgetPermit(None, False, "DAILY_MODEL_CALLS_EXHAUSTED", 256)
                self._remaining -= 1
                return BudgetPermit(
                    f"reservation-{len(self.reserves)}", True, "WITHIN_BUDGET", 2048
                )

    guard = SinglePermitGuard()
    model, runnable = _model(guard)

    await model.ainvoke([{"role": "user", "content": "first"}])
    with pytest.raises(ModelBudgetExceededError):
        await model.ainvoke([{"role": "user", "content": "second"}])

    assert len(guard.reserves) == 2
    assert len(runnable.calls) == 1


@pytest.mark.anyio
async def test_two_concurrent_calls_against_one_permit_make_exactly_one_provider_call():
    class SinglePermitGuard(SequencedGuard):
        def __init__(self) -> None:
            super().__init__()
            self._remaining = 1
            self._lock = asyncio.Lock()

        async def reserve(self, **kwargs):
            async with self._lock:
                self.reserves.append(kwargs)
                await asyncio.sleep(0)
                if self._remaining == 0:
                    return BudgetPermit(None, False, "DAILY_MODEL_CALLS_EXHAUSTED", 256)
                self._remaining -= 1
                return BudgetPermit(
                    f"reservation-{len(self.reserves)}", True, "WITHIN_BUDGET", 2048
                )

    guard = SinglePermitGuard()
    model, runnable = _model(guard)

    results = await asyncio.gather(
        model.ainvoke([{"role": "user", "content": "a"}]),
        model.ainvoke([{"role": "user", "content": "b"}]),
        return_exceptions=True,
    )

    blocked = [item for item in results if isinstance(item, ModelBudgetExceededError)]
    assert len(blocked) == 1
    assert len(runnable.calls) == 1


@pytest.mark.anyio
async def test_structured_output_wrapper_still_reserves_and_binds():
    from pydantic import BaseModel

    class Answer(BaseModel):
        value: str

    guard = SequencedGuard()
    model, runnable = _model(guard)
    structured = model.with_structured_output(Answer)

    await structured.ainvoke([{"role": "user", "content": "x"}])

    assert len(guard.reserves) == 1
    assert runnable.bound == [{"max_tokens": 1001}]


@pytest.mark.anyio
async def test_sync_invoke_reserves_and_binds():
    guard = SequencedGuard()
    model, runnable = _model(guard)

    model.invoke([{"role": "user", "content": "x"}])

    assert len(guard.reserves) == 1
    assert runnable.bound == [{"max_tokens": 1001}]


@pytest.mark.anyio
async def test_astream_reserves_binds_and_releases_on_failure():
    guard = SequencedGuard()
    runnable = RecordingRunnable(chunks=["a", "b"])
    model, _ = _model(guard, runnable=runnable)

    chunks = [chunk async for chunk in model.astream([{"role": "user", "content": "x"}])]
    assert [chunk.content for chunk in chunks] == ["a", "b"]
    assert runnable.bound == [{"max_tokens": 1001}]

    class ExplodingRunnable(RecordingRunnable):
        async def astream(self, messages, config=None, **kwargs):
            self.calls.append(messages)
            raise RuntimeError("provider down")
            yield  # pragma: no cover - 保持异步生成器语义

    exploding = ExplodingRunnable()
    failing_model, _ = _model(SequencedGuard(), runnable=exploding)
    with pytest.raises(RuntimeError):
        [chunk async for chunk in failing_model.astream([{"role": "user", "content": "x"}])]


@pytest.mark.anyio
async def test_provider_error_releases_the_reservation():
    class ExplodingRunnable(RecordingRunnable):
        async def ainvoke(self, messages, config=None, **kwargs):
            self.calls.append(messages)
            raise RuntimeError("provider down")

    guard = SequencedGuard()
    model, _ = _model(guard, runnable=ExplodingRunnable())
    with pytest.raises(RuntimeError):
        await model.ainvoke([{"role": "user", "content": "x"}])
    assert guard.released == ["reservation-1"]


@pytest.mark.anyio
async def test_failure_closed_guard_blocks_the_provider_call():
    model, runnable = _model(FailingGuard())
    with pytest.raises(ModelBudgetExceededError) as raised:
        await model.ainvoke([{"role": "user", "content": "x"}])
    assert raised.value.reason == "BUDGET_CHECK_UNAVAILABLE"
    assert runnable.calls == []


@pytest.mark.anyio
async def test_cached_model_rechecks_budget_on_every_invocation():
    guard = SequencedGuard()
    model, _ = _model(guard)
    for _ in range(3):
        await model.ainvoke([{"role": "user", "content": "x"}])
    assert len(guard.reserves) == 3


@pytest.mark.anyio
async def test_cached_background_service_rechecks_budget_on_every_invocation():
    guard = SequencedGuard()
    model, runnable = _model(guard)
    # 后台资料/测验服务按 owner 缓存同一个分析器实例；缓存不能绕过预算重查。
    analyzer = DeepSeekMaterialAnalyzer(model)
    chunks = [MaterialChunk(position=0, text="正文", locator="loc")]
    with contextlib.suppress(Exception):
        await analyzer.analyze("标题", chunks)
    with contextlib.suppress(Exception):
        await analyzer.analyze("标题", chunks)

    assert len(guard.reserves) == 2
    assert len(runnable.calls) == 2
    assert [entry["max_tokens"] for entry in runnable.bound] == [1001, 1002]


@pytest.mark.anyio
async def test_independent_owners_do_not_share_decisions():
    class OwnerGuard:
        def __init__(self) -> None:
            self.owners: list[str] = []

        async def reserve(self, **kwargs):
            self.owners.append(kwargs["owner_id"])
            return BudgetPermit(
                f"reservation-{kwargs['owner_id']}", True, "WITHIN_BUDGET", 4096
            )

        async def release(self, reservation_id):
            return None

    guard = OwnerGuard()
    alice, alice_runnable = _model(guard, owner_id="alice")
    bob, bob_runnable = _model(guard, owner_id="bob")

    await alice.ainvoke([{"role": "user", "content": "a"}])
    await bob.ainvoke([{"role": "user", "content": "b"}])

    assert guard.owners == ["alice", "bob"]
    assert alice_runnable.calls and bob_runnable.calls


def _learning_task() -> LearningTask:
    return LearningTask(
        id="task-1",
        plan_id="plan-1",
        title="完成 Spring MVC 接口",
        scheduled_date=date(2026, 7, 26),
        estimated_minutes=60,
        status="TODO",
        version=1,
    )


def _quiz_sources() -> list[QuizSource]:
    return [QuizSource(source_type="MATERIAL", title="讲义", snippet="依赖注入")]


def _planner_state() -> dict:
    from langchain_core.messages import AIMessage, HumanMessage

    return {
        "messages": [
            HumanMessage(content="我想年底掌握 Java"),
            AIMessage(content="你每周能学习多久？"),
            HumanMessage(content="每周 10 小时"),
        ],
        "learning_context": {
            "goals": [],
            "plans": [],
            "tasks": [],
            "materials": [],
            "mastery": [],
        },
        "goal_id": "goal-1",
        "knowledge_context": [],
    }


class _FakeAdaptationContext:
    def model_dump_json(self, **_kwargs) -> str:
        return "{}"


@pytest.mark.anyio
async def test_every_model_boundary_binds_the_current_permit_output_cap():
    """列出所有真实 provider 入口，证明每个请求都带上当次许可的 max_tokens。"""

    async def material(model):
        await DeepSeekMaterialAnalyzer(model).analyze(
            "标题", [MaterialChunk(position=0, text="正文", locator="loc")]
        )

    async def quiz(model):
        await DeepSeekQuizGenerator(model).generate(
            task=_learning_task(),
            mix=QuizMix(3, 1, 1, Difficulty.EASY),
            sources=_quiz_sources(),
        )

    async def diagnostic_and_graduation(model):
        # 诊断与阶段毕业共用同一个生成器方法。
        await DeepSeekQuizGenerator(model).generate_diagnostic_quiz(
            context={"nodeSnapshot": [{"nodeId": "n1"}]},
            sources=_quiz_sources(),
        )

    async def node_quiz(model):
        # 曾遗漏 bind_max_output_tokens 的路径；现在必须由边界统一绑定。
        await DeepSeekQuizGenerator(model).generate_node_quiz(
            context={"node": {"id": "n1", "title": "节点"}},
            sources=_quiz_sources(),
            recent_signatures=set(),
        )

    async def coding(model):
        await DeepSeekCodingEvaluator(model).evaluate(
            [{"questionId": "q1", "answer": "x"}]
        )

    async def rubric(model):
        await DeepSeekArtifactEvaluator(model).evaluate({"content": "x"})

    async def agent_planning(model):
        await DeepSeekPlanner(model).generate(_planner_state())

    async def unified_planning(model):
        planner = AssistantPlanner(
            model=model, catalog={}, validator=PlanPolicyValidator({})
        )
        await planner.propose(message="打开学习路线", context={}, client_context={})

    async def teaching(model):
        await DeepSeekTeachingAnswerer(
            model, model_provider="deepseek", model_name="deepseek-flash"
        ).answer(question="为什么用 DTO？", lesson={}, history=[])

    async def knowledge_answer(model):
        await DeepSeekKnowledgeAnswerer(
            model, model_provider="deepseek", model_name="deepseek-flash"
        ).answer(question="解释依赖注入", history=[], materials=[], web_results=[])

    async def knowledge_stream(model):
        answerer = DeepSeekKnowledgeAnswerer(
            model, model_provider="deepseek", model_name="deepseek-flash"
        )
        [chunk async for chunk in answerer.astream(
            question="解释依赖注入", history=[], materials=[], web_results=[]
        )]

    async def adjustment(model):
        from app.agent.adjustment_service import DeepSeekAdjustmentGenerator

        await DeepSeekAdjustmentGenerator(model).generate(_FakeAdaptationContext())

    async def task_recognition(model):
        await DeepSeekTaskRecognizer(model).recognize(
            message="写完了", tasks=[], reference_date=date(2026, 7, 26)
        )

    cases = [
        ("material", material),
        ("quiz", quiz),
        ("diagnostic/graduation", diagnostic_and_graduation),
        ("node quiz", node_quiz),
        ("coding evaluation", coding),
        ("rubric", rubric),
        ("agent planning", agent_planning),
        ("unified planning", unified_planning),
        ("teaching", teaching),
        ("knowledge answering", knowledge_answer),
        ("knowledge streaming", knowledge_stream),
        ("plan adjustment", adjustment),
        ("task recognition", task_recognition),
    ]

    for name, invoke in cases:
        guard = SequencedGuard()
        model, runnable = _model(guard)
        # 结果校验失败会重试；重试同样必须重新预占并绑定新上限。
        with contextlib.suppress(Exception):
            await invoke(model)
        assert runnable.calls, f"{name} 未到达真实 provider 调用"
        assert len(guard.reserves) == len(runnable.calls), name
        expected = [1000 + index for index in range(1, len(runnable.calls) + 1)]
        assert [entry["max_tokens"] for entry in runnable.bound] == expected, name
