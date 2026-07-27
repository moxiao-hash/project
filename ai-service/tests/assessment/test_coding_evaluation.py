import pytest

from app.assessment.evaluation import CodingEvaluationWorker, DeepSeekCodingEvaluator
from app.assessment.evaluation_models import CodingEvaluation, CodingEvaluationBatch

pytestmark = pytest.mark.anyio


class FakeJava:
    def __init__(self):
        self.completed = None
        self.heartbeats = 0

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

    async def heartbeat_coding_evaluation_job(self, job_id, worker_id):
        self.heartbeats += 1

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
    assert java.heartbeats >= 1


async def test_deepseek_evaluator_repairs_invalid_structure_once() -> None:
    valid = {
        "evaluations": [{
            "questionId": "question-1",
            "score": 90,
            "correctness": 38,
            "completeness": 23,
            "edgeCases": 15,
            "clarityEfficiency": 14,
            "issues": [],
            "feedback": "逻辑正确",
            "suggestedCode": "return a + b;",
        }]
    }

    class Structured:
        def __init__(self):
            self.responses = [{}, valid]
            self.calls = []

        async def ainvoke(self, messages):
            self.calls.append(messages)
            return self.responses.pop(0)

    class Chat:
        def __init__(self):
            self.structured = Structured()

        def with_structured_output(self, schema, *, method):
            return self.structured

    chat = Chat()
    result = await DeepSeekCodingEvaluator(chat).evaluate([{
        "questionId": "question-1",
        "questionText": "实现 add",
        "codeAnswer": "return a+b;",
    }])

    assert result.evaluations[0].score == 90
    assert len(chat.structured.calls) == 2
