import pytest

import app.assessment.models as assessment_models
import app.assessment.service as assessment_service
from app.search.models import WebSearchOutcome, WebSearchResult


class FakeBackend:
    def __init__(self, *, time_sensitive: bool = False) -> None:
        self.time_sensitive = time_sensitive
        self.created = None
        self.completed = None
        self.failed = None

    async def claim_roadmap_quiz_job(self, worker_id: str):
        return {
            "id": "job-1",
            "ownerId": "owner-1",
            "nodeId": "node-current",
            "attemptCount": 1,
            "leaseToken": "lease-1",
        }

    async def get_roadmap_quiz_context(self, job_id: str):
        return {
            "jobId": job_id,
            "ownerId": "owner-1",
            "node": {
                "id": "node-current",
                "code": "string-content-comparison",
                "title": "String 内容比较",
                "objectives": ["使用 equals 比较内容", "解释字符串池"],
                "highFrequency": ["equals 与 ==", "null 安全比较"],
                "commonMistakes": ["用 == 比较两个 new String"],
                "quizBlueprint": [
                    {
                        "prompt": "判断 new String(\"a\") == \"a\"",
                        "timeSensitive": self.time_sensitive,
                    },
                    {"prompt": "使用 Objects.equals 处理 null", "timeSensitive": False},
                ],
            },
            "directPrerequisites": [
                {
                    "id": "node-prereq",
                    "code": "arrays-basic-traversal",
                    "title": "数组遍历",
                    "highFrequency": ["索引边界"],
                    "quizBlueprint": ["定位 i <= array.length 的越界"],
                }
            ],
            "recentQuestionSignatures": ["old-signature"],
            "officialDomains": ["docs.oracle.com"],
        }

    async def create_quiz(self, payload):
        self.created = payload
        return {"id": "quiz-1"}

    async def complete_roadmap_quiz_job(
        self, job_id: str, worker_id: str, lease_token: str, quiz_id: str
    ):
        self.completed = (job_id, worker_id, lease_token, quiz_id)

    async def fail_roadmap_quiz_job(
        self, job_id: str, worker_id: str, lease_token: str, error: str
    ):
        self.failed = (job_id, worker_id, lease_token, error)


class FakeGenerator:
    async def generate_node_quiz(self, *, context, sources, recent_signatures):
        question_type = assessment_models.RoadmapGeneratedQuestion
        quiz_type = assessment_models.RoadmapGeneratedQuiz
        questions = []
        coverage = ["node-current", "node-current", "node-current", "node-prereq", "node-current"]
        concepts = ["equals", "字符串池", "Objects.equals", "索引边界", "null"]
        for index, (node_id, concept) in enumerate(zip(coverage, concepts, strict=True)):
            questions.append(
                question_type(
                    type="SINGLE_CHOICE",
                    difficulty="EASY",
                    knowledgePoint=concept,
                    questionText=f"题目 {index + 1}: {concept}",
                    options=["A", "B"],
                    correctAnswers=["A"],
                    explanation=f"解析 {concept}",
                    sourceIndexes=[0],
                    coverageNodeId=node_id,
                    practical=True,
                    points=20,
                    questionSignature=f"signature-{index}",
                )
            )
        return quiz_type(title="String 节点测验", questions=questions)


class NoWeb:
    async def search(self, owner_id: str, query: str):
        raise AssertionError("稳定基础 blueprint 不得调用 Tavily")


@pytest.mark.anyio
async def test_worker_persists_exact_grounded_mix_without_searching_stable_basics() -> None:
    worker_type = getattr(assessment_service, "RoadmapQuizWorker", None)
    assert worker_type is not None, "缺少 durable roadmap quiz worker"
    backend = FakeBackend()
    worker = worker_type(
        backend,
        FakeGenerator(),
        NoWeb(),
        worker_id="roadmap-worker",
        model_name="deepseek-test",
    )

    assert await worker.process_once() is True

    payload = backend.created
    assert payload["purpose"] == "NODE"
    assert payload["roadmapNodeId"] == "node-current"
    assert payload["modelName"] == "deepseek-test"
    assert len(payload["questions"]) == 5
    assert sum(question["points"] for question in payload["questions"]) == 100
    assert all(len(question["sources"]) == 1 for question in payload["questions"])
    assert all(
        question["sources"][0]["locator"] == "roadmap-node:node-current"
        for question in payload["questions"]
    )
    assert (
        sum(question["coverageNodeId"] == "node-current" for question in payload["questions"])
        >= 3
    )
    assert {question["coverageNodeId"] for question in payload["questions"]} <= {
        "node-current",
        "node-prereq",
    }
    assert sum(question["practical"] for question in payload["questions"]) >= 3
    assert all(
        question["questionSignature"] != "old-signature"
        for question in payload["questions"]
    )
    assert backend.completed == ("job-1", "roadmap-worker", "lease-1", "quiz-1")
    assert backend.failed is None


class OfficialOnlyWeb:
    def __init__(self) -> None:
        self.calls = 0

    async def search(self, owner_id: str, query: str):
        self.calls += 1
        return WebSearchOutcome(
            query=query,
            results=(
                WebSearchResult("Oracle", "https://docs.oracle.com/javase/spec", "JLS", 0.9, "r1"),
                WebSearchResult("Blog", "https://example.com/post", "untrusted", 0.8, "r2"),
            ),
        )


@pytest.mark.anyio
async def test_explicit_time_sensitive_blueprint_uses_only_official_web_sources() -> None:
    worker_type = getattr(assessment_service, "RoadmapQuizWorker", None)
    assert worker_type is not None
    backend = FakeBackend(time_sensitive=True)
    web = OfficialOnlyWeb()

    class WebGroundedGenerator(FakeGenerator):
        async def generate_node_quiz(self, **kwargs):
            quiz = await super().generate_node_quiz(**kwargs)
            quiz.questions[0].source_indexes = [len(kwargs["sources"]) - 1]
            return quiz

    worker = worker_type(
        backend,
        WebGroundedGenerator(),
        web,
        worker_id="roadmap-worker",
        model_name="deepseek-test",
    )

    await worker.process_once()

    assert web.calls == 1
    web_sources = [
        source
        for source in backend.created["questions"][0]["sources"]
        if source["sourceType"] == "WEB"
    ]
    assert [source["locator"] for source in web_sources] == [
        "https://docs.oracle.com/javase/spec"
    ]


@pytest.mark.anyio
async def test_recent_or_out_of_scope_question_causes_retriable_failure_without_completion(
) -> None:
    worker_type = getattr(assessment_service, "RoadmapQuizWorker", None)
    assert worker_type is not None

    class RepeatingGenerator(FakeGenerator):
        async def generate_node_quiz(self, **kwargs):
            quiz = await super().generate_node_quiz(**kwargs)
            quiz.questions[0].question_signature = "old-signature"
            quiz.questions[4].coverage_node_id = "transitive-prerequisite"
            return quiz

    backend = FakeBackend()
    worker = worker_type(
        backend,
        RepeatingGenerator(),
        NoWeb(),
        worker_id="roadmap-worker",
        model_name="deepseek-test",
    )

    assert await worker.process_once() is True

    assert backend.created is None
    assert backend.completed is None
    assert backend.failed is not None
    assert "InvalidGeneratedQuizError" in backend.failed[3]
