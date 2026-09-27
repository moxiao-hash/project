import asyncio

from app.unified_agent.models import (
    AssistantConversationStatus,
    ToolDescriptor,
    ToolEffect,
    ToolRiskLevel,
)
from app.unified_agent.planner import PlannerOutcome, PlannerStatus
from app.unified_agent.planning_models import (
    AssistantPlan,
    AssistantPlanStep,
    PlanIntent,
)
from app.unified_agent.supervisor import UnifiedAgentSupervisor


def tool(name: str, effect: ToolEffect) -> ToolDescriptor:
    return ToolDescriptor(
        name=name,
        version=1,
        category="TEST",
        effect=effect,
        risk_level=ToolRiskLevel.NONE if effect == ToolEffect.READ else ToolRiskLevel.LOW,
        idempotency_required=effect == ToolEffect.WRITE,
        input_schema={"type": "object"},
        output_schema={"type": "object"},
    )


class FakeJavaBackend:
    def __init__(self, data_overrides=None):
        self.calls = []
        self.data_overrides = data_overrides or {}

    def is_healthy(self):
        return True

    async def get_agent_tool_catalog(self):
        return self.agent_tool_catalog("user-1")

    def agent_tool_catalog(self, owner_id):
        return [
            tool("learning.context.get", ToolEffect.READ),
            tool("roadmap.current.get", ToolEffect.READ),
            tool("assessment.mastery.list", ToolEffect.READ),
            tool("assessment.wrong_questions.summary", ToolEffect.READ),
            tool("learning.tasks.list", ToolEffect.READ),
            tool("materials.list", ToolEffect.READ),
            tool("automation.settings.get", ToolEffect.READ),
            tool("learning.plan.create", ToolEffect.LOCAL),
        ]

    async def invoke_agent_tool(self, name, owner_id, arguments, idempotency_key=None):
        self.calls.append((name, owner_id, arguments, idempotency_key))
        if name in self.data_overrides:
            return {
                "toolName": name,
                "data": self.data_overrides[name],
                "action": None,
            }
        if name == "learning.context.get":
            return {
                "toolName": name,
                "data": {
                    "roadmap": {
                        "title": "Java + AI 全栈工程师学习路线",
                        "completedRequiredNodes": 5,
                        "totalRequiredNodes": 64,
                        "stages": [
                            {
                                "title": "第一阶段：Java 核心语法",
                                "nodes": [
                                    {
                                        "id": "n1",
                                        "title": "环境搭建",
                                        "displayStatus": "COMPLETED",
                                    },
                                    {
                                        "id": "n2",
                                        "title": "变量与类型转换",
                                        "displayStatus": "AVAILABLE",
                                    },
                                ],
                            }
                        ],
                    },
                    "learning": {
                        "tasks": [
                            {"id": "t1", "title": "完成基础语法测验", "status": "TODO"},
                        ],
                        "mastery": [
                            {"knowledgePoint": "类型转换", "score": 45},
                            {"knowledgePoint": "面向对象基础", "score": 88},
                        ],
                    },
                    "wrongQuestions": {
                        "activeCount": 3,
                        "totalWrongAttempts": 6,
                    },
                },
                "action": None,
            }
        if name == "roadmap.current.get":
            return {
                "toolName": name,
                "data": {
                    "enrollmentId": "enroll-1",
                    "title": "Java + AI 全栈工程师学习路线",
                    "completedRequiredNodes": 5,
                    "totalRequiredNodes": 64,
                    "stages": [
                        {
                            "title": "第一阶段：Java 核心语法",
                            "nodes": [
                                {
                                    "id": "n1",
                                    "title": "环境搭建",
                                    "displayStatus": "COMPLETED",
                                },
                                {
                                    "id": "n2",
                                    "title": "变量与类型转换",
                                    "displayStatus": "AVAILABLE",
                                },
                            ],
                        }
                    ],
                },
                "action": None,
            }
        if name == "assessment.mastery.list":
            return {
                "toolName": name,
                "data": [
                    {"knowledgePoint": "类型转换", "score": 45},
                    {"knowledgePoint": "面向对象基础", "score": 88},
                ],
                "action": None,
            }
        if name == "assessment.wrong_questions.summary":
            return {
                "toolName": name,
                "data": {
                    "activeCount": 3,
                    "totalWrongAttempts": 6,
                    "needsReview": True,
                },
                "action": None,
            }
        if name == "learning.tasks.list":
            return {
                "toolName": name,
                "data": [
                    {"id": "t1", "title": "完成基础语法测验", "status": "TODO"},
                    {"id": "t2", "title": "复习变量定义", "status": "COMPLETED"},
                ],
                "action": None,
            }
        if name == "materials.list":
            return {
                "toolName": name,
                "data": [
                    {"id": "m1", "title": "Spring Boot 核心编程", "materialType": "ARTICLE"},
                    {"id": "m2", "title": "Java 并发实战精讲", "materialType": "PDF"},
                ],
                "action": None,
            }
        if name == "automation.settings.get":
            return {
                "toolName": name,
                "data": {"paused": False},
                "action": None,
            }
        if name == "learning.plan.create":
            return {
                "toolName": name,
                "data": None,
                "action": {
                    "actionId": "act-1",
                    "executionId": "exec-1",
                    "toolName": name,
                    "toolVersion": 1,
                    "riskLevel": "LOW",
                    "status": "WAITING_CONFIRMATION",
                    "summary": "创建每日学习计划",
                    "arguments": {"title": "新计划"},
                    "preview": {"title": "每日学习计划"},
                    "expiresAt": "2026-09-24T00:00:00Z",
                    "expectedVersion": 1,
                },
            }
        return {"toolName": name, "data": {}, "action": None}


class FakePlanner:
    def __init__(self, outcome: PlannerOutcome):
        self.outcome = outcome
        self.calls: list[dict] = []

    async def propose(self, *, message, context, client_context):
        self.calls.append(
            {"message": message, "context": context, "client_context": client_context}
        )
        return self.outcome


def test_read_only_plan_learning_progress_composes_factual_reply():
    """验证用户查询‘我当前的学习进度是？’时，返回基于真实工具输出的具体事实。"""
    async def run():
        java = FakeJavaBackend()
        plan = AssistantPlan(
            intent=PlanIntent.LEARNING_QUERY,
            confidence=0.95,
            summary="查询当前学习进度及薄弱点",
            steps=[
                AssistantPlanStep(step_id="s1", tool_name="learning.context.get"),
                AssistantPlanStep(
                    step_id="s2", tool_name="roadmap.current.get", depends_on=["s1"]
                ),
                AssistantPlanStep(
                    step_id="s3", tool_name="assessment.mastery.list", depends_on=["s1"]
                ),
                AssistantPlanStep(
                    step_id="s4",
                    tool_name="assessment.wrong_questions.summary",
                    depends_on=["s1"],
                ),
                AssistantPlanStep(
                    step_id="s5", tool_name="learning.tasks.list", depends_on=["s1"]
                ),
            ],
        )
        planner = FakePlanner(PlannerOutcome(status=PlannerStatus.PLAN, plan=plan))
        service = UnifiedAgentSupervisor(java, model_name="deepseek-v4-flash", planner=planner)
        convo = await service.create_conversation("user-1")

        result = await service.send_message(
            convo.conversation_id,
            "我当前的学习进度是？",
            "turn-progress-1",
            "user-1",
            {},
        )

        assert result.status == AssistantConversationStatus.COMPLETED
        assert result.pending_action is None

        # 核心断言：绝对不能只是硬编码的步骤计数！
        assert "已按计划完成 5 个步骤。" not in result.reply

        # 核心断言：必须包含从工具输出提取的真实学习进度事实
        assert "Java + AI 全栈工程师学习路线" in result.reply
        assert "5/64" in result.reply
        assert "下一个待学习节点为“变量与类型转换”" in result.reply
        # 严禁将 AVAILABLE 节点伪称为“当前正在学习”节点
        assert "当前正在学习节点为“变量与类型转换”" not in result.reply
        assert "类型转换" in result.reply  # 最薄弱点
        assert "45%" in result.reply
        assert "3" in result.reply  # 3 道错题待复习
        assert "整体共有 2 项任务" in result.reply

    asyncio.run(run())


def test_read_only_plan_no_enrollment_reports_honest_empty_state():
    """验证无路线报名时，诚实汇报未加入学习路线，严禁伪造进度或虚构节点。"""
    async def run():
        java = FakeJavaBackend(
            data_overrides={
                "roadmap.current.get": None,
                "learning.context.get": {
                    "roadmap": None,
                    "learning": {},
                    "wrongQuestions": None,
                },
            }
        )
        plan = AssistantPlan(
            intent=PlanIntent.LEARNING_QUERY,
            confidence=0.92,
            summary="查询学习路线进度",
            steps=[
                AssistantPlanStep(step_id="s1", tool_name="roadmap.current.get"),
            ],
        )
        planner = FakePlanner(PlannerOutcome(status=PlannerStatus.PLAN, plan=plan))
        service = UnifiedAgentSupervisor(java, model_name="deepseek-v4-flash", planner=planner)
        convo = await service.create_conversation("user-1")

        result = await service.send_message(
            convo.conversation_id,
            "我当前的学习进度是？",
            "turn-no-roadmap-1",
            "user-1",
            {},
        )

        assert result.status == AssistantConversationStatus.COMPLETED
        assert "已按计划完成 1 个步骤。" not in result.reply
        # 诚实汇报未加入学习路线
        assert any(
            phrase in result.reply
            for phrase in ("尚未加入", "暂无学习路线", "未加入学习路线", "没有进行中的学习路线")
        )
        # 严禁捏造虚假进度百分比
        assert "/64" not in result.reply

    asyncio.run(run())


def test_read_only_plan_tasks_query_composes_factual_task_summary():
    """验证未指定日期的任务查询计划按整体任务汇报，绝不把未限定日期的任务硬说成今日任务。"""
    async def run():
        java = FakeJavaBackend()
        plan = AssistantPlan(
            intent=PlanIntent.LEARNING_QUERY,
            confidence=0.90,
            summary="查询学习任务",
            steps=[
                AssistantPlanStep(step_id="s1", tool_name="learning.tasks.list"),
            ],
        )
        planner = FakePlanner(PlannerOutcome(status=PlannerStatus.PLAN, plan=plan))
        service = UnifiedAgentSupervisor(java, model_name="deepseek-v4-flash", planner=planner)
        convo = await service.create_conversation("user-1")

        result = await service.send_message(
            convo.conversation_id,
            "我的学习任务有哪些？",
            "turn-tasks-1",
            "user-1",
            {},
        )

        assert result.status == AssistantConversationStatus.COMPLETED
        assert "已按计划完成 1 个步骤。" not in result.reply
        assert "整体共有 2 项任务" in result.reply
        assert "今日" not in result.reply
        assert "完成基础语法测验" in result.reply

    asyncio.run(run())


def test_read_only_plan_dated_tasks_query_labels_date_factually():
    """验证带有明确 date 参数的任务查询计划能够诚实标注该具体日期或今日。"""
    async def run():
        java = FakeJavaBackend()
        # 1. 指定非今日的确定日期（如 2026-10-01）
        plan_future = AssistantPlan(
            intent=PlanIntent.LEARNING_QUERY,
            confidence=0.90,
            summary="查询指定日期学习任务",
            steps=[
                AssistantPlanStep(
                    step_id="s1",
                    tool_name="learning.tasks.list",
                    arguments={"date": "2026-10-01"},
                ),
            ],
        )
        planner = FakePlanner(PlannerOutcome(status=PlannerStatus.PLAN, plan=plan_future))
        service = UnifiedAgentSupervisor(java, model_name="deepseek-v4-flash", planner=planner)
        convo = await service.create_conversation("user-1")

        result = await service.send_message(
            convo.conversation_id,
            "10月1日有什么任务？",
            "turn-tasks-future-1",
            "user-1",
            {},
        )

        assert result.status == AssistantConversationStatus.COMPLETED
        assert "已按计划完成 1 个步骤。" not in result.reply
        # 诚实标明具体日期，绝不误标为今日
        assert "2026-10-01 共有 2 项任务" in result.reply
        assert "今日" not in result.reply

    asyncio.run(run())


def test_read_only_plan_skipped_tasks_does_not_claim_all_completed():
    """验证当任务全部处于 SKIPPED 状态时，严禁宣称‘全部完成’。"""
    async def run():
        java = FakeJavaBackend(
            data_overrides={
                "learning.tasks.list": [
                    {"id": "t1", "title": "选做扩展练习 A", "status": "SKIPPED"},
                    {"id": "t2", "title": "选做扩展练习 B", "status": "SKIPPED"},
                ]
            }
        )
        plan = AssistantPlan(
            intent=PlanIntent.LEARNING_QUERY,
            confidence=0.90,
            summary="查询任务",
            steps=[
                AssistantPlanStep(step_id="s1", tool_name="learning.tasks.list"),
            ],
        )
        planner = FakePlanner(PlannerOutcome(status=PlannerStatus.PLAN, plan=plan))
        service = UnifiedAgentSupervisor(java, model_name="deepseek-v4-flash", planner=planner)
        convo = await service.create_conversation("user-1")

        result = await service.send_message(
            convo.conversation_id,
            "我的学习任务完成了吗？",
            "turn-tasks-skipped-1",
            "user-1",
            {},
        )

        assert result.status == AssistantConversationStatus.COMPLETED
        assert "已按计划完成 1 个步骤。" not in result.reply
        # 严禁宣称全部完成！
        assert "全部完成" not in result.reply
        assert "跳过 2 项" in result.reply

    asyncio.run(run())


def test_read_only_plan_materials_query_composes_materials_summary():
    """验证资料库查询计划能够诚实汇报资料数量与代表性名称。"""
    async def run():
        java = FakeJavaBackend()
        plan = AssistantPlan(
            intent=PlanIntent.LEARNING_QUERY,
            confidence=0.92,
            summary="查询学习资料",
            steps=[
                AssistantPlanStep(step_id="s1", tool_name="materials.list"),
            ],
        )
        planner = FakePlanner(PlannerOutcome(status=PlannerStatus.PLAN, plan=plan))
        service = UnifiedAgentSupervisor(java, model_name="deepseek-v4-flash", planner=planner)
        convo = await service.create_conversation("user-1")

        result = await service.send_message(
            convo.conversation_id,
            "我有哪些学习资料？",
            "turn-materials-1",
            "user-1",
            {},
        )

        assert result.status == AssistantConversationStatus.COMPLETED
        assert "已按计划完成 1 个步骤。" not in result.reply
        assert "Spring Boot 核心编程" in result.reply
        assert "2" in result.reply

    asyncio.run(run())


def test_read_only_plan_unhandled_query_reports_limitation_honestly():
    """验证未提取到具体业务数据的只读查询诚实陈述限制，绝不以空洞的步骤计数冒充成功回答。"""
    async def run():
        java = FakeJavaBackend()
        plan = AssistantPlan(
            intent=PlanIntent.LEARNING_QUERY,
            confidence=0.90,
            summary="查询自动化设置",
            steps=[
                AssistantPlanStep(step_id="s1", tool_name="automation.settings.get"),
            ],
        )
        planner = FakePlanner(PlannerOutcome(status=PlannerStatus.PLAN, plan=plan))
        service = UnifiedAgentSupervisor(java, model_name="deepseek-v4-flash", planner=planner)
        convo = await service.create_conversation("user-1")

        result = await service.send_message(
            convo.conversation_id,
            "我的主动自动化设置状态？",
            "turn-auto-1",
            "user-1",
            {},
        )

        assert result.status == AssistantConversationStatus.COMPLETED
        assert "已按计划完成 1 个步骤。" not in result.reply
        assert "已执行查询步骤，但未获取到可展示的具体学习数据。" in result.reply

    asyncio.run(run())


def test_write_plan_with_pending_confirmation_preserves_preview_behavior():
    """验证写操作计划在遇到待确认动作时，继续保留卡片确认提示与状态，不破坏治理。"""
    async def run():
        java = FakeJavaBackend()
        plan = AssistantPlan(
            intent=PlanIntent.PLAN_ADJUSTMENT,
            confidence=0.95,
            summary="创建学习计划",
            steps=[
                AssistantPlanStep(
                    step_id="s1",
                    tool_name="learning.plan.create",
                    arguments={"title": "新计划"},
                ),
            ],
        )
        planner = FakePlanner(PlannerOutcome(status=PlannerStatus.PLAN, plan=plan))
        service = UnifiedAgentSupervisor(java, model_name="deepseek-v4-flash", planner=planner)
        convo = await service.create_conversation("user-1")

        result = await service.send_message(
            convo.conversation_id,
            "帮我制定学习计划",
            "turn-write-1",
            "user-1",
            {},
        )

        assert result.status == AssistantConversationStatus.WAITING_CONFIRMATION
        assert result.pending_action is not None
        assert result.pending_action.tool_name == "learning.plan.create"
        assert "确认" in result.reply

    asyncio.run(run())
