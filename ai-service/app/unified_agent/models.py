"""统一 Agent 与 Java 工具网关之间的稳定数据契约。"""

import re
from enum import StrEnum
from typing import Any

from pydantic import ConfigDict, Field, field_validator

from app.knowledge.models import KnowledgeCitation
from app.schemas.learning import JavaContractModel

#: `docs/agent-native-contract.md` v2 冻结的界面动作白名单。
ALLOWED_UI_ROUTE_KEYS = frozenset(
    {
        "DASHBOARD",
        "ASSISTANT",
        "ASSISTANT_HEALTH",
        "ROADMAP",
        "ROADMAP_STAGE",
        "ROADMAP_MODULE",
        "ROADMAP_NODE",
        "LEARNING_GOALS",
        "LEARNING_PLANS",
        "LEARNING_PLAN",
        "TODAY",
        "MATERIALS",
        "MATERIAL_DETAIL",
        "QUIZ",
        "QUIZ_ATTEMPT",
        "WRONG_QUESTIONS",
        "MASTERY",
        "KNOWLEDGE",
        "PLAN_ASSISTANT",
        "TASK_ASSISTANT",
        "NOTIFICATIONS",
        "AGENT_ACTIVITY",
        "LEARNING_SETTINGS",
        "AI_SETTINGS",
        "WORKSPACE_ARTIFACTS",
        "COURSES",
        "COURSE_DETAIL",
        "LESSON",
    }
)

#: 界面动作参数只允许安全业务标识符，拒绝任意路径、选择器和脚本。
UI_PARAM_VALUE_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

#: Task 30 冻结回执契约：``currentRoute`` 是前端注册的 route name（不是 URL）。
#: 与 ``web/src/app/router.ts`` 的 31 个具名路由逐一对应；Java 与 Python 各持一份。
ALLOWED_FRONTEND_ROUTE_NAMES = frozenset(
    {
        "login",
        "register",
        "assistant",
        "dashboard",
        "roadmap",
        "roadmap-stage",
        "roadmap-module",
        "roadmap-node",
        "goals",
        "courses",
        "course-detail",
        "lesson",
        "plans",
        "plan-detail",
        "today",
        "materials",
        "material-detail",
        "quiz",
        "attempt",
        "wrong-questions",
        "mastery",
        "knowledge",
        "agent-plan",
        "agent-tasks",
        "activity",
        "assistant-health",
        "notifications",
        "settings",
        "settings-ai",
        "workspace-artifacts",
        "not-found",
    }
)

#: 用户可见错误的最大长度；与 Java 侧裁剪上限保持一致。
MAX_ACTION_ERROR_LENGTH = 500

#: 错误信息中一旦出现这些片段，说明泄漏了堆栈、请求头或凭据，必须整体丢弃。
_FORBIDDEN_ERROR_FRAGMENTS = (
    "authorization",
    "bearer ",
    "x-internal-service-token",
    "internal-service-token",
    "api_key",
    "apikey",
    "secret",
    "password",
    "stacktrace",
    "stack trace",
    "traceback",
    "\nat ",
    "\tat ",
    "org.springframework.",
    "java.lang.",
    "com.moxiao.studypilot.",
)

#: URL / 路径 / 伪协议片段：错误信息只能是用户可见的短句，不得回传可执行目标。
_FORBIDDEN_ERROR_PATTERN = re.compile(
    r"(https?://|javascript:|data:text/html|<[^>]*>|\bsrc=|\bhref=)",
    re.IGNORECASE,
)


def sanitize_action_error(value: str | None) -> str | None:
    """裁剪用户可见错误：拒绝堆栈、请求头、凭据、URL、HTML 与脚本。

    返回 ``None`` 表示错误信息为空或包含禁止内容；调用方不得把它当作成功。
    """

    if value is None:
        return None
    normalized = " ".join(value.split())
    if not normalized:
        return None
    lowered = normalized.lower()
    if any(fragment in lowered for fragment in _FORBIDDEN_ERROR_FRAGMENTS):
        return None
    if _FORBIDDEN_ERROR_PATTERN.search(normalized):
        return None
    if len(normalized) > MAX_ACTION_ERROR_LENGTH:
        normalized = normalized[:MAX_ACTION_ERROR_LENGTH]
    # 去掉不可见控制字符，避免把不可信内容原样回灌到 UI 或日志。
    printable = "".join(ch for ch in normalized if ch.isprintable())
    return printable or None


class ToolEffect(StrEnum):
    READ = "READ"
    NAVIGATE = "NAVIGATE"
    WRITE = "WRITE"
    LOCAL = "LOCAL"


class ToolRiskLevel(StrEnum):
    NONE = "NONE"
    LOW = "LOW"
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

    @property
    def is_web_search(self) -> bool:
        """联网搜索工具是唯一允许消耗网络预算的类别。"""

        return self.name == "materials.web.search" or self.name.endswith(".web.search")


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
    DEVELOPER = "DEVELOPER"
    CLARIFY = "CLARIFY"


class UiAction(JavaContractModel):
    type: str = "NAVIGATE"
    route_key: str
    params: dict[str, str] = Field(default_factory=dict)
    reason: str

    @field_validator("route_key")
    @classmethod
    def _validate_route_key(cls, value: str) -> str:
        """只允许契约冻结的 routeKey；模型不能发明新页面。"""

        if value not in ALLOWED_UI_ROUTE_KEYS:
            raise ValueError(f"未注册的界面路由: {value}")
        return value

    @field_validator("params")
    @classmethod
    def _validate_params(cls, value: dict[str, str]) -> dict[str, str]:
        """参数只能是安全业务标识符，拒绝路径、选择器和任意文本。"""

        for item in value.values():
            if not isinstance(item, str) or not UI_PARAM_VALUE_PATTERN.match(item):
                raise ValueError("界面动作参数包含非法值")
        return value


class PublicToolStep(JavaContractModel):
    tool_name: str
    status: str
    summary: str


class AssistantMessage(JavaContractModel):
    role: str
    content: str
    # Task 29：终态消息也保留轮次身份与展示状态，刷新后才能准确恢复
    # 已完成/失败/取消的气泡，而不是只依赖浏览器内存。
    turn_id: str | None = None
    status: str | None = None


class AssistantEvent(JavaContractModel):
    sequence: int = Field(ge=1)
    type: str
    conversation_id: str
    payload: dict[str, Any] = Field(default_factory=dict)


class AssistantActiveTurn(JavaContractModel):
    """Task 29 冻结契约：进行中轮次的可恢复状态。

    ``assistantText`` 是**已经推送给客户端的前缀**，``lastDeltaIndex`` 是最后一条
    ``ASSISTANT_DELTA`` 的下标（尚未产生任何增量时为 ``-1``）。客户端刷新后用
    ``assistantText`` 还原已显示内容，再从 ``lastEventSequence`` 续传后缀，
    因此最终答案只会出现一次。
    """

    turn_id: str
    user_message: str
    assistant_text: str = ""
    last_delta_index: int = -1


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
    citations: list[KnowledgeCitation] = Field(default_factory=list)
    model_name: str
    #: Task 29：客户端刷新后据此续传事件流，不必猜测游标。
    last_event_sequence: int = Field(default=0, ge=0)
    #: Task 29：保留的轮次 ID 快捷字段；等价于 ``active_turn.turnId``。
    active_turn_id: str | None = None
    #: Task 29 冻结契约：进行中轮次的完整可恢复状态；无进行中轮次时为 ``None``。
    active_turn: AssistantActiveTurn | None = None


class CreateAssistantConversationRequest(JavaContractModel):
    owner_id: str = Field(min_length=1)


class SendAssistantMessageRequest(JavaContractModel):
    owner_id: str = Field(min_length=1)
    message: str = Field(min_length=1, max_length=8000)
    idempotency_key: str = Field(min_length=1, max_length=180)
    client_context: dict[str, Any] = Field(default_factory=dict)


class UiActionReceiptStatus(StrEnum):
    """Task 30 冻结枚举：只允许三种终态。"""

    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    REJECTED = "REJECTED"


class ActionReceiptDecision(StrEnum):
    """Supervisor 根据已校验回执做出的确定性决定。"""

    FINISHED = "FINISHED"
    RETRY_SCHEDULED = "RETRY_SCHEDULED"
    MANUAL_ENTRY = "MANUAL_ENTRY"


class AssistantActionReceipt(JavaContractModel):
    """Java 校验并转发的动作回执；Python 不再信任浏览器原始输入。"""

    action_id: str = Field(min_length=1, max_length=128)
    status: UiActionReceiptStatus
    current_route: str
    error: str | None = Field(default=None, max_length=MAX_ACTION_ERROR_LENGTH)

    @field_validator("action_id")
    @classmethod
    def _validate_action_id(cls, value: str) -> str:
        if not UI_PARAM_VALUE_PATTERN.match(value):
            raise ValueError("动作回执标识不合法")
        return value

    @field_validator("current_route")
    @classmethod
    def _validate_current_route(cls, value: str) -> str:
        if value not in ALLOWED_FRONTEND_ROUTE_NAMES:
            raise ValueError(f"未注册的前端路由名: {value}")
        return value


class AssistantActionReceiptRequest(AssistantActionReceipt):
    """内部端点请求体：额外携带经 Java Bearer 会话解析出的 ownerId。

    ``extra="forbid"``：即便内部调用方被绕过，也不得注入 DOM、URL、脚本等额外字段。
    """

    model_config = ConfigDict(extra="forbid")

    owner_id: str = Field(min_length=1)


class AssistantActionReceiptResult(JavaContractModel):
    """回执处理结果；只暴露公开状态与人工入口，不泄漏内部动作表。"""

    action_id: str
    status: UiActionReceiptStatus
    decision: ActionReceiptDecision
    retry_count: int = Field(default=0, ge=0)
    manual_route_key: str | None = None
    message: str
