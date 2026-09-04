"""统一 Agent 与 Java 工具网关之间的稳定数据契约。"""

from enum import StrEnum
from typing import Any

from pydantic import Field

from app.schemas.learning import JavaContractModel


class ToolEffect(StrEnum):
    READ = "READ"
    NAVIGATE = "NAVIGATE"
    WRITE = "WRITE"


class ToolRiskLevel(StrEnum):
    NONE = "NONE"
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class ToolDescriptor(JavaContractModel):
    name: str
    version: int
    category: str
    effect: ToolEffect
    risk_level: ToolRiskLevel
    required_scope: str | None = None
    idempotency_required: bool = False
    input_schema: dict[str, Any]
    output_schema: dict[str, Any]


class PendingToolAction(JavaContractModel):
    action_id: str
    execution_id: str
    tool_name: str
    tool_version: int
    risk_level: ToolRiskLevel
    status: str
    summary: str
    arguments: dict[str, Any]
    result: Any | None = None
    error: str | None = None
    expires_at: str


class ToolInvocationResult(JavaContractModel):
    tool_name: str
    tool_version: int = 1
    data: Any | None = None
    truncated: bool = False
    action: PendingToolAction | None = None


class AssistantConversationStatus(StrEnum):
    READY = "READY"
    RUNNING = "RUNNING"
    WAITING_CONFIRMATION = "WAITING_CONFIRMATION"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class AssistantIntent(StrEnum):
    NAVIGATION = "NAVIGATION"
    WRONG_QUESTION_REVIEW = "WRONG_QUESTION_REVIEW"
    KNOWLEDGE = "KNOWLEDGE"
    PLAN = "PLAN"
    TASK = "TASK"
    TEACHING = "TEACHING"
    CLARIFY = "CLARIFY"


class UiAction(JavaContractModel):
    type: str = "NAVIGATE"
    route_key: str
    params: dict[str, str] = Field(default_factory=dict)
    reason: str


class PublicToolStep(JavaContractModel):
    tool_name: str
    status: str
    summary: str


class AssistantMessage(JavaContractModel):
    role: str
    content: str


class AssistantEvent(JavaContractModel):
    sequence: int = Field(ge=1)
    type: str
    conversation_id: str
    payload: dict[str, Any] = Field(default_factory=dict)


class AssistantConversationSnapshot(JavaContractModel):
    conversation_id: str
    owner_id: str
    status: AssistantConversationStatus
    reply: str
    messages: list[AssistantMessage] = Field(default_factory=list)
    intent: AssistantIntent | None = None
    tool_steps: list[PublicToolStep] = Field(default_factory=list)
    pending_action: PendingToolAction | None = None
    ui_actions: list[UiAction] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    model_name: str


class CreateAssistantConversationRequest(JavaContractModel):
    owner_id: str = Field(min_length=1)


class SendAssistantMessageRequest(JavaContractModel):
    owner_id: str = Field(min_length=1)
    message: str = Field(min_length=1, max_length=8000)
    idempotency_key: str = Field(min_length=1, max_length=180)
    client_context: dict[str, Any] = Field(default_factory=dict)
