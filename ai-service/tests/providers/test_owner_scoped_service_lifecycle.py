import gc
import weakref
from datetime import date
from types import SimpleNamespace

import pytest
from pydantic import SecretStr

from app.agent.grounding import PlanGrounding
from app.agent.models import ConversationStatus, PlanDraft, PlannerTurn, PlanTaskDraft
from app.api import conversations as conversations_api
from app.core.settings import Settings
from app.providers.credentials import CredentialProvider
from app.schemas.learning import LearningContext, LearningGoal

pytestmark = pytest.mark.anyio


class FakeJavaBackend:
    def __init__(self, _settings: Settings) -> None:
        pass

    async def get_learning_context(self, _owner_id: str) -> LearningContext:
        return LearningContext(
            goals=[
                LearningGoal(
                    id="goal-1",
                    title="学习 Java + AI",
                    target_date=date(2026, 12, 31),
                    weekly_study_hours=10,
                    status="ACTIVE",
                )
            ],
            plans=[],
            tasks=[],
            materials=[],
            mastery=[],
        )

    async def create_agent_execution(self, _request):
        return SimpleNamespace(id="execution-1", status="WAITING_CONFIRMATION")

    async def confirm_agent_execution(self, execution_id: str, *, owner_id: str):
        return SimpleNamespace(id=execution_id, owner_id=owner_id, status="PENDING")

    async def update_agent_execution(self, execution_id: str, request):
        return SimpleNamespace(id=execution_id, status=request.status)

    async def create_confirmed_learning_plan(self, _request):
        return SimpleNamespace(
            plan=SimpleNamespace(id="plan-1"),
            tasks=[SimpleNamespace(id="task-1")],
        )


class FakePlanner:
    def __init__(self, key: str) -> None:
        self.key = key

    async def generate(self, _state) -> PlannerTurn:
        return PlannerTurn(
            reply=self.key,
            status=ConversationStatus.DRAFT_READY,
            draft=PlanDraft(
                title="Java + AI 学习计划",
                start_date=date(2026, 7, 28),
                end_date=date(2026, 8, 3),
                tasks=[
                    PlanTaskDraft(
                        title="实现一个小功能",
                        scheduled_date=date(2026, 7, 29),
                        estimated_minutes=60,
                    )
                ],
            ),
        )


class FakeGrounding:
    async def retrieve(self, _owner_id: str, _query: str) -> PlanGrounding:
        return PlanGrounding()


async def test_runtime_ttl_and_capacity_keep_conversation_and_reinject_new_key(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    now = [0.0]
    keys = {
        ("owner-1", CredentialProvider.DEEPSEEK): "deepseek-old",
        ("owner-1", CredentialProvider.TAVILY): "tavily-old",
        ("owner-2", CredentialProvider.DEEPSEEK): "deepseek-two",
        ("owner-2", CredentialProvider.TAVILY): "tavily-two",
    }
    created_keys: list[str] = []
    planner_refs: list[weakref.ReferenceType[FakePlanner]] = []

    class FakeResolver:
        def __init__(self, _java, _settings) -> None:
            pass

        async def resolve(self, owner_id: str, provider: CredentialProvider) -> SecretStr:
            return SecretStr(keys[(owner_id, provider)])

    def create_model(_settings, key: SecretStr) -> FakePlanner:
        planner = FakePlanner(key.get_secret_value())
        created_keys.append(planner.key)
        planner_refs.append(weakref.ref(planner))
        return planner

    monkeypatch.setattr(conversations_api, "JavaBackendClient", FakeJavaBackend)
    monkeypatch.setattr(conversations_api, "CredentialResolver", FakeResolver)
    monkeypatch.setattr(conversations_api, "create_chat_model", create_model)
    monkeypatch.setattr(conversations_api, "DeepSeekPlanner", lambda model: model)
    monkeypatch.setattr(conversations_api, "get_hybrid_index", lambda _path: object())
    monkeypatch.setattr(conversations_api, "AsyncHybridRetriever", lambda _index: object())
    monkeypatch.setattr(conversations_api, "TavilySearchClient", lambda *_a, **_k: object())
    monkeypatch.setattr(conversations_api, "WebSearchService", lambda *_a: object())
    monkeypatch.setattr(conversations_api, "PlanGroundingService", lambda *_a: FakeGrounding())

    registry = conversations_api.OwnerScopedConversationServices(
        Settings(),
        max_runtime_entries=1,
        runtime_idle_ttl_seconds=10,
        clock=lambda: now[0],
    )
    owner_one = await registry.for_owner("owner-1")
    created = await owner_one.create_conversation("owner-1", "goal-1")
    await owner_one.send_message(created.conversation_id, "生成草稿", "owner-1")

    # 容量淘汰只释放 owner-1 的模型运行时，不删除它的会话/checkpointer。
    await registry.for_owner("owner-2")
    gc.collect()
    assert planner_refs[0]() is None

    keys[("owner-1", CredentialProvider.DEEPSEEK)] = "deepseek-new"
    restored = await registry.for_owner("owner-1")
    assert restored is owner_one
    assert (
        await restored.get_conversation(created.conversation_id, "owner-1")
    ).conversation_id == created.conversation_id
    assert created_keys[-1] == "deepseek-new"
    revised = await restored.send_message(
        created.conversation_id,
        "用新模型修改草稿",
        "owner-1",
    )
    assert revised.reply == "deepseek-new"
    completed = await restored.confirm(created.conversation_id, "owner-1")
    assert completed.status == ConversationStatus.COMPLETED

    # TTL 到期后同样只重建凭据运行时，会话身份仍保持不变。
    now[0] = 11
    restored_after_ttl = await registry.for_owner("owner-1")
    assert restored_after_ttl is owner_one
    assert (
        await restored_after_ttl.get_conversation(created.conversation_id, "owner-1")
    ).conversation_id == created.conversation_id
