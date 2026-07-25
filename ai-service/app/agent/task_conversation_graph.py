"""带人工确认暂停点的任务状态操作 LangGraph。"""

from contextlib import suppress
from datetime import date
from typing import Annotated, Literal, TypedDict

from langchain_core.messages import AIMessage, AnyMessage, HumanMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.types import Command, interrupt

from app.agent.task_conversation_models import TaskConversationStatus
from app.agent.task_models import (
    TaskActionDraft,
    TaskRecognitionStatus,
)
from app.agent.task_service import TaskRecognitionService
from app.clients.java_backend import JavaBackendClient, JavaBackendError
from app.schemas.agent import (
    CreateTaskAgentExecutionRequest,
    UpdateAgentExecutionRequest,
)
from app.schemas.learning import ChangeLearningTaskStatusRequest


class TaskConversationState(TypedDict, total=False):
    conversation_id: str
    owner_id: str
    target_date: date
    messages: Annotated[list[AnyMessage], add_messages]
    status: str
    reply: str
    candidate_tasks: list[dict]
    action_draft: dict | None
    execution_id: str | None
    updated_task: dict | None
    error: str | None
    java_status_code: int | None


def build_task_conversation_graph(
    recognition_service: TaskRecognitionService,
    java_backend: JavaBackendClient,
):
    """构建识别、预览、暂停确认和幂等执行工作流。"""

    async def recognize_task(state: TaskConversationState) -> dict:
        user_message = next(
            message
            for message in reversed(state["messages"])
            if isinstance(message, HumanMessage)
        )
        result = await recognition_service.recognize(
            owner_id=state["owner_id"],
            message=str(user_message.content),
            target_date=state["target_date"],
        )
        preview_ready = result.status == TaskRecognitionStatus.PREVIEW_READY
        return {
            "messages": [AIMessage(content=result.reply)],
            "status": (
                TaskConversationStatus.PREVIEW_READY.value
                if preview_ready
                else TaskConversationStatus.COLLECTING.value
            ),
            "reply": result.reply,
            "candidate_tasks": [
                task.model_dump(mode="json") for task in result.candidate_tasks
            ],
            "action_draft": (
                result.action_draft.model_dump(mode="json")
                if result.action_draft is not None
                else None
            ),
            "updated_task": None,
            "error": None,
            "java_status_code": None,
        }

    def route_after_recognition(
        state: TaskConversationState,
    ) -> Literal["register_execution", "__end__"]:
        if state["status"] == TaskConversationStatus.PREVIEW_READY:
            return "register_execution"
        return END

    async def register_execution(state: TaskConversationState) -> dict:
        if state.get("execution_id"):
            return {}
        execution = await java_backend.create_agent_execution(
            CreateTaskAgentExecutionRequest(
                owner_id=state["owner_id"],
                idempotency_key=f"task-action:{state['conversation_id']}",
                summary="修改学习任务状态并等待用户确认",
            )
        )
        return {"execution_id": execution.id}

    def await_confirmation(
        state: TaskConversationState,
    ) -> Command[Literal["recognize_task", "execute_task"]]:
        decision = interrupt(
            {
                "type": "task_action_confirmation",
                "actionDraft": state["action_draft"],
            }
        )
        if decision["action"] == "revise":
            return Command(
                update={
                    "messages": [HumanMessage(content=decision["feedback"])],
                    "status": TaskConversationStatus.COLLECTING.value,
                    "action_draft": None,
                    "candidate_tasks": [],
                },
                goto="recognize_task",
            )
        return Command(goto="execute_task")

    async def execute_task(state: TaskConversationState) -> dict:
        execution_id = state["execution_id"]
        draft = TaskActionDraft.model_validate(state["action_draft"])
        try:
            await java_backend.confirm_agent_execution(
                execution_id,
                owner_id=state["owner_id"],
            )
            await java_backend.update_agent_execution(
                execution_id,
                UpdateAgentExecutionRequest(
                    status="RUNNING",
                    result_summary="正在执行用户确认的任务状态修改",
                ),
            )
            updated_task = await java_backend.change_learning_task_status(
                draft.task_id,
                ChangeLearningTaskStatusRequest(
                    owner_id=state["owner_id"],
                    idempotency_key=f"task-action:{state['conversation_id']}",
                    expected_version=draft.expected_version,
                    status=draft.target_status,
                    scheduled_date=draft.deferred_to,
                    reason=draft.reason,
                ),
            )
            await java_backend.update_agent_execution(
                execution_id,
                UpdateAgentExecutionRequest(
                    status="SUCCEEDED",
                    result_summary=f"已更新任务 {updated_task.id}",
                ),
            )
        except JavaBackendError as exc:
            detail = exc.detail or str(exc)
            with suppress(Exception):
                await java_backend.update_agent_execution(
                    execution_id,
                    UpdateAgentExecutionRequest(
                        status="FAILED",
                        error_message=detail[:1000],
                    ),
                )
            return {
                "status": TaskConversationStatus.FAILED.value,
                "reply": "任务操作执行失败。",
                "error": detail,
                "java_status_code": exc.status_code,
            }
        except Exception as exc:
            with suppress(Exception):
                await java_backend.update_agent_execution(
                    execution_id,
                    UpdateAgentExecutionRequest(
                        status="FAILED",
                        error_message=str(exc)[:1000],
                    ),
                )
            return {
                "status": TaskConversationStatus.FAILED.value,
                "reply": "任务操作执行失败。",
                "error": str(exc),
                "java_status_code": None,
            }

        reply = f"任务“{updated_task.title}”已更新为 {updated_task.status.value}。"
        return {
            "messages": [AIMessage(content=reply)],
            "status": TaskConversationStatus.COMPLETED.value,
            "reply": reply,
            "updated_task": updated_task.model_dump(mode="json"),
            "error": None,
            "java_status_code": None,
        }

    builder = StateGraph(TaskConversationState)
    builder.add_node("recognize_task", recognize_task)
    builder.add_node("register_execution", register_execution)
    builder.add_node("await_confirmation", await_confirmation)
    builder.add_node("execute_task", execute_task)
    builder.add_edge(START, "recognize_task")
    builder.add_conditional_edges("recognize_task", route_after_recognition)
    builder.add_edge("register_execution", "await_confirmation")
    builder.add_edge("execute_task", END)
    return builder.compile(checkpointer=InMemorySaver())
