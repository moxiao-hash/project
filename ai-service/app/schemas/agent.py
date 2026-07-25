"""Agent 治理相关的 Java/Python 内部接口契约。"""

from datetime import datetime
from decimal import Decimal
from typing import Literal

from app.schemas.learning import JavaContractModel

ExecutionStatus = Literal[
    "WAITING_AUTHORIZATION",
    "WAITING_CONFIRMATION",
    "PENDING",
    "RUNNING",
    "SUCCEEDED",
    "FAILED",
]


class CreateAgentExecutionRequest(JavaContractModel):
    """登记一次需要用户确认和审计的学习计划生成行为。"""

    owner_id: str
    idempotency_key: str
    summary: str
    execution_type: Literal["PLAN_GENERATION"] = "PLAN_GENERATION"
    trigger_type: Literal["USER_REQUEST"] = "USER_REQUEST"
    risk_level: Literal["HIGH"] = "HIGH"
    required_scope: Literal["PLAN_GENERATION"] = "PLAN_GENERATION"


class CreateTaskAgentExecutionRequest(JavaContractModel):
    """登记一次必须由用户逐次确认的任务状态修改。"""

    owner_id: str
    idempotency_key: str
    summary: str
    execution_type: Literal["TASK_STATUS_CHANGE"] = "TASK_STATUS_CHANGE"
    trigger_type: Literal["USER_REQUEST"] = "USER_REQUEST"
    risk_level: Literal["HIGH"] = "HIGH"
    required_scope: Literal["TASK_MANAGEMENT"] = "TASK_MANAGEMENT"


class AgentExecution(JavaContractModel):
    id: str
    idempotency_key: str
    execution_type: str
    trigger_type: str
    risk_level: str
    required_scope: str
    status: ExecutionStatus
    summary: str
    result_summary: str | None = None
    error_message: str | None = None
    model_name: str | None = None
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    latency_ms: int | None = None
    estimated_cost: Decimal | None = None
    created_at: datetime


class UpdateAgentExecutionRequest(JavaContractModel):
    status: ExecutionStatus
    result_summary: str | None = None
    error_message: str | None = None
    model_name: str | None = None
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    latency_ms: int | None = None
    estimated_cost: Decimal | None = None
