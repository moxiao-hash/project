import asyncio

import app.assessment.models as models
from app.assessment.service import RoadmapDiagnosticWorker, RoadmapGraduationWorker


class FakeBackend:
    def __init__(self):
        self.completed = None
        self.failed = None

    async def claim_roadmap_diagnostic_job(self, worker_id):
        return {
            "id": "diagnostic-1",
            "ownerId": "owner-1",
            "leaseToken": "lease-1",
            "questionTarget": 10,
            "nodeSnapshot": [
                {
                    "nodeId": f"node-{index}",
                    "nodeCode": f"node-{index}",
                    "moduleId": "module-1",
                    "title": f"节点 {index}",
                    "milestone": index == 9,
                }
                for index in range(10)
            ],
        }

    async def complete_roadmap_diagnostic_job(
        self, job_id, worker_id, lease_token, quiz_payload
    ):
        self.completed = (job_id, worker_id, lease_token, quiz_payload)

    async def fail_roadmap_diagnostic_job(
        self, job_id, worker_id, lease_token, error
    ):
        self.failed = (job_id, worker_id, lease_token, error)

    async def claim_roadmap_graduation_job(self, worker_id):
        return await self.claim_roadmap_diagnostic_job(worker_id)

    async def complete_roadmap_graduation_job(
        self, job_id, worker_id, lease_token, quiz_payload
    ):
        await self.complete_roadmap_diagnostic_job(
            job_id, worker_id, lease_token, quiz_payload
        )

    async def fail_roadmap_graduation_job(
        self, job_id, worker_id, lease_token, error
    ):
        await self.fail_roadmap_diagnostic_job(job_id, worker_id, lease_token, error)


class FakeGenerator:
    async def generate_diagnostic_quiz(self, *, context, sources):
        return models.RoadmapDiagnosticQuiz(
            title="Java 入学诊断",
            questions=[
                models.RoadmapGeneratedQuestion(
                    type="SINGLE_CHOICE",
                    difficulty="EASY",
                    knowledgePoint=f"知识点 {index}",
                    questionText=f"问题 {index}",
                    options=["A", "B"],
                    correctAnswers=["A"],
                    explanation="A 正确",
                    sourceIndexes=[index],
                    coverageNodeId=f"node-{index}",
                    practical=False,
                    points=10,
                    questionSignature=f"diagnostic-{index}",
                )
                for index in range(10)
            ],
        )


class SmallCatalogBackend(FakeBackend):
    async def claim_roadmap_diagnostic_job(self, worker_id):
        job = await super().claim_roadmap_diagnostic_job(worker_id)
        job["nodeSnapshot"] = job["nodeSnapshot"][:2]
        job["insufficientQuestionFallback"] = True
        return job


class SmallCatalogGenerator:
    async def generate_diagnostic_quiz(self, *, context, sources):
        return models.RoadmapDiagnosticQuiz(
            title="小目录诊断",
            questions=[
                models.RoadmapGeneratedQuestion(
                    type="SINGLE_CHOICE", difficulty="EASY",
                    knowledgePoint=f"知识点 {index}", questionText=f"问题 {index}",
                    options=["A", "B"], correctAnswers=["A"], explanation="A 正确",
                    sourceIndexes=[index % 2], coverageNodeId=f"node-{index % 2}",
                    practical=False, points=10,
                    questionSignature=f"small-diagnostic-{index}",
                )
                for index in range(10)
            ],
        )


def test_generates_ten_grounded_diagnostic_questions_and_completes_lease():
    backend = FakeBackend()
    worker = RoadmapDiagnosticWorker(
        backend, FakeGenerator(), worker_id="worker-1", model_name="deepseek-test"
    )

    assert asyncio.run(worker.process_once()) is True
    assert backend.failed is None
    _, _, _, quiz = backend.completed
    assert len(quiz["questions"]) == 10
    assert {item["coverageNodeId"] for item in quiz["questions"]} == {
        f"node-{index}" for index in range(10)
    }
    assert all(item["sources"] for item in quiz["questions"])


def test_graduation_worker_reuses_ten_question_grounding_contract():
    backend = FakeBackend()
    worker = RoadmapGraduationWorker(
        backend, FakeGenerator(), worker_id="graduation-1", model_name="deepseek-test"
    )

    assert asyncio.run(worker.process_once()) is True
    assert backend.completed is not None
    assert len(backend.completed[3]["questions"]) == 10


def test_insufficient_catalog_fallback_reuses_nodes_without_losing_ten_questions():
    backend = SmallCatalogBackend()
    worker = RoadmapDiagnosticWorker(
        backend, SmallCatalogGenerator(), worker_id="fallback-1", model_name="deepseek-test"
    )

    assert asyncio.run(worker.process_once()) is True
    assert backend.failed is None
    assert len(backend.completed[3]["questions"]) == 10
