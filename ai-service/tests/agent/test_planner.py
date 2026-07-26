import asyncio
from datetime import date

from langchain_core.messages import AIMessage, HumanMessage

from app.agent.models import ConversationStatus, PlannerTurn
from app.agent.planner import DeepSeekPlanner


class FakeStructuredModel:
    def __init__(self) -> None:
        self.calls = []

    async def ainvoke(self, messages):
        self.calls.append(messages)
        return {
            "reply": "还需要确认你每天可投入的时间。",
            "status": "COLLECTING",
            "draft": None,
        }


class FakeChatModel:
    def __init__(self, structured: FakeStructuredModel) -> None:
        self.structured = structured
        self.schema = None
        self.method = None

    def with_structured_output(self, schema, *, method):
        self.schema = schema
        self.method = method
        return self.structured


def test_deepseek_planner_builds_grounded_prompt_and_returns_structured_turn() -> None:
    structured = FakeStructuredModel()
    model = FakeChatModel(structured)
    planner = DeepSeekPlanner(model)
    state = {
        "messages": [
            HumanMessage(content="我想年底掌握 Java"),
            AIMessage(content="你每周能学习多久？"),
            HumanMessage(content="每周 10 小时"),
        ],
        "learning_context": {
            "goals": [
                {
                    "id": "goal-1",
                    "title": "年底掌握 Java 智能应用开发",
                    "target_date": date(2026, 12, 31).isoformat(),
                    "weekly_study_hours": 10,
                    "status": "ACTIVE",
                }
            ],
            "plans": [],
            "tasks": [{"title": "Spring Boot 入门", "status": "TODO"}],
            "materials": [{"title": "Java 笔记", "content_reference": None}],
            "mastery": [],
        },
        "goal_id": "goal-1",
        "knowledge_context": [
            {
                "source_type": "MATERIAL",
                "category": "SYLLABUS",
                "title": "Java 课程大纲",
                "locator": "第 1 章",
                "text": "必须先完成 Java 基础，再进入 Spring Boot。",
            },
            {
                "source_type": "WEB",
                "title": "Spring Boot 官方文档",
                "url": "https://spring.io/projects/spring-boot",
                "text": "当前版本至少需要 Java 17。",
            },
        ],
    }

    turn = asyncio.run(planner.generate(state))

    assert isinstance(turn, PlannerTurn)
    assert turn.status == ConversationStatus.COLLECTING
    assert model.schema is PlannerTurn
    assert model.method == "json_mode"
    prompt = "\n".join(str(message.content) for message in structured.calls[0])
    assert "年底掌握 Java 智能应用开发" in prompt
    assert "2026-12-31" in prompt
    assert "每周 10 小时" in prompt
    assert "Spring Boot 入门" in prompt
    assert "Java 笔记" in prompt
    assert "不得声称已经读取资料正文" in prompt
    assert "用户当前约束 > SYLLABUS" in prompt
    assert "必须先完成 Java 基础" in prompt
    assert "当前版本至少需要 Java 17" in prompt
    assert "来源冲突" in prompt
    assert "COLLECTING" in prompt
    assert "DRAFT_READY" in prompt
    # DeepSeek 的 json_mode 只保证 JSON 合法，不会自动看到 Pydantic schema。
    # 提示词必须包含两种状态的完整字段示例，否则模型可能返回无法校验的字段结构。
    assert '"status": "COLLECTING"' in prompt
    assert '"draft": null' in prompt
    assert '"scheduled_date": "2026-07-27"' in prompt
    assert '"estimated_minutes": 60' in prompt
    assert len(state["messages"]) == 3
