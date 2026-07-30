from datetime import date

from langchain_core.messages import HumanMessage

from app.prompts.learning_plan import build_learning_plan_prompt


def test_prompt_resolves_relative_dates_from_explicit_current_date() -> None:
    state = {
        "goal_id": "goal-1",
        "learning_context": {
            "time_zone": "Asia/Shanghai",
            "goals": [{"id": "goal-1", "title": "学习 Spring Boot"}],
            "plans": [],
            "tasks": [],
            "materials": [],
            "mastery": [],
        },
        "knowledge_context": [],
        "messages": [HumanMessage(content="就从今天开始")],
    }

    prompt = build_learning_plan_prompt(
        state,
        current_date=date(2026, 7, 30),
    )

    assert '"current_date": "2026-07-30"' in prompt
    assert '"time_zone": "Asia/Shanghai"' in prompt
    assert "今天、明天、本周等相对日期必须以上述 current_date 为基准" in prompt


def test_prompt_limits_plans_to_the_java_ai_project_stack() -> None:
    state = {
        "goal_id": "goal-1",
        "learning_context": {
            "goals": [{"id": "goal-1", "title": "继续项目学习"}],
            "plans": [],
            "tasks": [],
            "materials": [],
            "mastery": [],
        },
        "knowledge_context": [],
        "messages": [HumanMessage(content="给我安排下一阶段")],
    }

    prompt = build_learning_plan_prompt(state, current_date=date(2026, 7, 30))

    assert "Java、Spring Boot、MySQL、Vue/TypeScript、Python/FastAPI" in prompt
    assert "DeepSeek API、LangChain/LangGraph、RAG/Qdrant/Tavily、Git/Docker" in prompt
    assert "教程类资料优先黑马程序员" in prompt
    assert "时效事实优先官方文档" in prompt
