"""学习计划会话的 LangGraph 工作流。"""

from contextlib import suppress
from typing import Literal

from langchain_core.messages import AIMessage, HumanMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt

from app.agent.models import ConversationStatus, PlanDraft
from app.agent.planner import PlanTurnGenerator
from app.agent.state import ConversationState
from app.clients.java_backend import JavaBackendClient
from app.schemas.agent import (
    CreateAgentExecutionRequest,
    UpdateAgentExecutionRequest,
)
from app.schemas.learning import (
    CreateConfirmedLearningPlanRequest,
    CreateConfirmedTaskRequest,
)


def build_learning_plan_graph(
    planner: PlanTurnGenerator,
    java_backend: JavaBackendClient,
):
    """构建带短期记忆和人工确认暂停点的学习计划图。"""

    async def plan_turn(state: ConversationState) -> dict:
        turn = await planner.generate(state)
        return {
            "messages": [AIMessage(content=turn.reply)],
            "reply": turn.reply,
            "status": turn.status.value,
            "draft": (turn.draft.model_dump(mode="json") if turn.draft is not None else None),
            "error": None,
        }

    def route_after_planning(
        state: ConversationState,
    ) -> Literal["register_execution", "__end__"]:
        if state["status"] == ConversationStatus.DRAFT_READY:
            return "register_execution"
        return END

    async def register_execution(state: ConversationState) -> dict:
        """在展示确认按钮前登记高风险行为，但此时绝不保存学习计划。"""

        if state.get("execution_id"):
            return {}
        execution = await java_backend.create_agent_execution(
            CreateAgentExecutionRequest(
                owner_id=state["owner_id"],
                idempotency_key=f"plan-generation:{state['conversation_id']}",
                summary="生成学习计划并等待用户确认",
            )
        )
        return {"execution_id": execution.id}

    def await_approval(
        state: ConversationState,
    ) -> Command[Literal["plan_turn", "persist_plan"]]:
        """暂停图；只有外部 confirm 接口才能发送 approve 决策。"""

        decision = interrupt(
            {
                "type": "plan_approval",
                "draft": state["draft"],
            }
        )
        if decision["action"] == "revise":
            return Command(
                update={
                    "messages": [HumanMessage(content=decision["feedback"])],
                    "status": ConversationStatus.COLLECTING.value,
                    "draft": None,
                },
                goto="plan_turn",
            )
        return Command(goto="persist_plan")

    async def persist_plan(state: ConversationState) -> dict:
        """确认后依次推进审计状态，并让 Java 在一个事务中完成最终写入。"""

        execution_id = state["execution_id"]
        draft = PlanDraft.model_validate(state["draft"])
        try:
            await java_backend.confirm_agent_execution(
                execution_id,
                owner_id=state["owner_id"],
            )
            await java_backend.update_agent_execution(
                execution_id,
                UpdateAgentExecutionRequest(
                    status="RUNNING",
                    result_summary="正在保存用户确认的学习计划",
                ),
            )
            result = await java_backend.create_confirmed_learning_plan(
                CreateConfirmedLearningPlanRequest(
                    owner_id=state["owner_id"],
                    goal_id=state["goal_id"],
                    idempotency_key=f"plan-generation:{state['conversation_id']}",
                    title=draft.title,
                    start_date=draft.start_date,
                    end_date=draft.end_date,
                    tasks=[
                        CreateConfirmedTaskRequest(
                            title=task.title,
                            scheduled_date=task.scheduled_date,
                            estimated_minutes=task.estimated_minutes,
                        )
                        for task in draft.tasks
                    ],
                )
            )
            await java_backend.update_agent_execution(
                execution_id,
                UpdateAgentExecutionRequest(
                    status="SUCCEEDED",
                    result_summary=f"已创建计划 {result.plan.id}",
                ),
            )
        except Exception as exc:
            # 失败更新是尽力而为；不能让二次网络错误覆盖最初的根因。
            with suppress(Exception):
                await java_backend.update_agent_execution(
                    execution_id,
                    UpdateAgentExecutionRequest(
                        status="FAILED",
                        error_message=str(exc)[:1000],
                    ),
                )
            raise

        reply = "学习计划已确认并保存。"
        return {
            "messages": [AIMessage(content=reply)],
            "status": ConversationStatus.COMPLETED.value,
            "reply": reply,
            "saved_plan_id": result.plan.id,
        }

    builder = StateGraph(ConversationState)
    builder.add_node("plan_turn", plan_turn)
    builder.add_node("register_execution", register_execution)
    builder.add_node("await_approval", await_approval)
    builder.add_node("persist_plan", persist_plan)
    builder.add_edge(START, "plan_turn")
    builder.add_conditional_edges("plan_turn", route_after_planning)
    builder.add_edge("register_execution", "await_approval")
    builder.add_edge("persist_plan", END)
    return builder.compile(checkpointer=InMemorySaver())
