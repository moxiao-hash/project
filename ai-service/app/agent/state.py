"""LangGraph 在单个 conversation thread 中保存的状态定义。"""

from typing import Annotated, Any, TypedDict

from langchain_core.messages import AnyMessage
from langgraph.graph.message import add_messages


class ConversationState(TypedDict, total=False):
    conversation_id: str
    owner_id: str
    goal_id: str
    messages: Annotated[list[AnyMessage], add_messages]
    learning_context: dict[str, Any]
    knowledge_context: list[dict[str, Any]]
    citations: list[dict[str, Any]]
    warnings: list[str]
    status: str
    reply: str
    draft: dict[str, Any] | None
    execution_id: str | None
    saved_plan_id: str | None
    error: str | None
