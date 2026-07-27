import pytest

from app.assessment.evaluation import CodingEvaluationWorker
from app.assessment.evaluation_models import CodingEvaluation, CodingEvaluationBatch

pytestmark = pytest.mark.anyio


class FakeJava:
    def __init__(self):
        self.completed = None

    async def claim_coding_evaluation_job(self, worker_id):
        return {
            "jobId": "job-1",
            "answers": [
                {
                    "questionId": "question-1",
                    "questionText": "实现 add",
                    "codeAnswer": "return a+b;",
                    "rubric": {
                        "correctness": 40,
                        "completeness": 25,
                        "edgeCases": 20,
                        "clarityEfficiency": 15,
                    },
                }
            ],
        }

    async def complete_coding_evaluation_job(self, job_id, payload):
        self.completed = (job_id, payload)

    async def fail_coding_evaluation_job(self, job_id, payload):
        raise AssertionError("不应失败")


class FakeEvaluator:
    async def evaluate(self, answers):
        assert "return a+b;" in answers[0]["codeAnswer"]
        return CodingEvaluationBatch(
            evaluations=[
                CodingEvaluation(
                    questionId="question-1",
                    score=90,
                    correctness=38,
                    completeness=23,
                    edgeCases=15,
                    clarityEfficiency=14,
                    issues=[],
                    feedback="逻辑正确",
                    suggestedCode="return a + b;",
                )
            ]
        )


async def test_worker_completes_structured_text_only_evaluation() -> None:
    java = FakeJava()
    worker = CodingEvaluationWorker(java, FakeEvaluator(), worker_id="worker-1")

    processed = await worker.process_once()

    assert processed is True
    assert java.completed[0] == "job-1"
    assert java.completed[1]["evaluations"][0]["score"] == 90
