import pytest

from app.assessment.generator import DeepSeekQuizGenerator
from app.assessment.models import QuizSource


class SequencedModel:
    def __init__(self, responses):
        self.responses = list(responses)
        self.messages = []

    def with_structured_output(self, *_args, **_kwargs):
        return self

    async def ainvoke(self, messages):
        self.messages.append(messages)
        return self.responses.pop(0)


def quiz_payload(high_frequency_ref: str):
    questions = []
    for index in range(5):
        questions.append(
            {
                "type": "SINGLE_CHOICE",
                "difficulty": "EASY",
                "knowledgePoint": f"概念{index}",
                "questionText": f"题目{index}",
                "options": ["A", "B"],
                "correctAnswers": ["A"],
                "explanation": "解析",
                "sourceIndexes": [0],
                "coverageNodeId": "node-current",
                "practical": index < 3,
                "highFrequencyRef": high_frequency_ref if index < 3 else None,
                "points": 20,
                "questionSignature": f"signature-{index}",
            }
        )
    return {"title": "节点测验", "questions": questions}


@pytest.mark.anyio
async def test_semantic_high_frequency_error_enters_model_correction_loop() -> None:
    model = SequencedModel([
        quiz_payload("equals"),
        quiz_payload("equals 与 =="),
    ])
    generator = DeepSeekQuizGenerator(model)
    context = {
        "node": {
            "id": "node-current",
            "title": "String 内容比较",
            "highFrequency": ["equals 与 =="],
        },
        "directPrerequisites": [],
    }
    sources = [
        QuizSource(
            sourceType="ROADMAP_CATALOG",
            title="String 内容比较",
            locator="roadmap-node:node-current",
            snippet="equals 与 ==",
        )
    ]

    generated = await generator.generate_node_quiz(
        context=context,
        sources=sources,
        recent_signatures=set(),
    )

    assert generated.questions[0].high_frequency_ref == "equals 与 =="
    assert len(model.messages) == 2
    assert "highFrequencyRef" in model.messages[1][-1].content


@pytest.mark.anyio
async def test_all_node_semantic_errors_enter_model_correction_loop() -> None:
    invalid = quiz_payload("equals 与 ==")
    invalid["questions"][0]["coverageNodeId"] = "outside-node"
    invalid["questions"][1]["questionSignature"] = "signature-0"
    valid = quiz_payload("equals 与 ==")
    model = SequencedModel([invalid, valid])
    generator = DeepSeekQuizGenerator(model)
    context = {
        "node": {
            "id": "node-current",
            "title": "String 内容比较",
            "highFrequency": ["equals 与 =="],
        },
        "directPrerequisites": [],
    }
    sources = [
        QuizSource(
            sourceType="ROADMAP_CATALOG",
            title="String 内容比较",
            locator="roadmap-node:node-current",
            snippet="equals 与 ==",
        )
    ]

    generated = await generator.generate_node_quiz(
        context=context,
        sources=sources,
        recent_signatures=set(),
    )

    assert generated.questions[0].coverage_node_id == "node-current"
    assert len(model.messages) == 2
    assert "coverageNodeId" in model.messages[1][-1].content
    assert "questionSignature" in model.messages[1][-1].content
