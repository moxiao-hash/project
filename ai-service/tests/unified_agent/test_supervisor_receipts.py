"""Task 30：动作回执的归属、幂等、冲突与确定性降级测试。

回执只描述前端白名单动作的终态；Python 不得读取 DOM，也不得把失败回执
当作成功。所有断言针对真实 Supervisor 状态，而不是仅注册名。
"""

import asyncio
import base64
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.persistence.agent_state import AgentPersistence
from app.unified_agent.models import (
    ActionReceiptDecision,
    AssistantActionReceipt,
    ToolDescriptor,
    ToolEffect,
    ToolRiskLevel,
    UiActionReceiptStatus,
)
from app.unified_agent.supervisor import (
    AssistantActionNotFoundError,
    AssistantActionReceiptConflictError,
    AssistantConversationNotFoundError,
    UnifiedAgentSupervisor,
)

TEST_KEY = base64.b64encode(bytes(range(32))).decode()


def tool(name: str, effect: ToolEffect) -> ToolDescriptor:
    return ToolDescriptor(
        name=name,
        version=1,
        category="TEST",
        effect=effect,
        risk_level=ToolRiskLevel.NONE,
        input_schema={"type": "object"},
        output_schema={"type": "object"},
    )


class FakeJavaBackend:
    async def get_agent_tool_catalog(self):
        return [
            tool("learning.context.get", ToolEffect.READ),
            tool("navigation.resolve", ToolEffect.READ),
        ]

    async def invoke_agent_tool(self, name, owner_id, arguments, idempotency_key=None):
        if name == "learning.context.get":
            return {
                "toolName": name,
                "data": {"roadmap": {"stages": []}},
                "action": None,
            }
        if name == "navigation.resolve":
            return {"toolName": name, "data": {}, "action": None}
        raise AssertionError(name)


async def _conversation_with_ui_action():
    service = UnifiedAgentSupervisor(FakeJavaBackend(), model_name="test-model")
    conversation = await service.create_conversation("user-1")
    await service.send_message(
        conversation.conversation_id,
        "打开我的错题集",
        "assistant-turn:receipt-1",
        "user-1",
        {},
    )
    events = await service.list_events(conversation.conversation_id, "user-1", 0)
    ui_action_ids = [
        event.payload["actionId"]
        for event in events
        if event.type == "UI_ACTION"
    ]
    assert ui_action_ids, "导航轮次必须下发带稳定 actionId 的 UI_ACTION"
    return service, conversation.conversation_id, ui_action_ids[0], events


def _receipt(action_id: str, status: UiActionReceiptStatus, **overrides) -> AssistantActionReceipt:
    payload = {
        "action_id": action_id,
        "status": status,
        "current_route": "wrong-questions",
        "error": None,
    }
    payload.update(overrides)
    return AssistantActionReceipt(**payload)


def test_ui_action_events_carry_stable_action_ids_and_are_registered() -> None:
    async def scenario() -> None:
        service, conversation_id, action_id, events = await _conversation_with_ui_action()
        assert action_id
        # 同一会话内下发的动作 ID 不重复。
        all_ids = [event.payload["actionId"] for event in events if event.type == "UI_ACTION"]
        assert len(all_ids) == len(set(all_ids))
        # 未知 actionId 必须被拒绝，不能被当作已注册动作。
        with pytest.raises(AssistantActionNotFoundError):
            await service.record_action_receipt(
                conversation_id,
                "user-1",
                _receipt("not-a-real-action", UiActionReceiptStatus.SUCCEEDED),
            )

    asyncio.run(scenario())


def test_receipt_ownership_is_enforced_across_conversations_and_users() -> None:
    async def scenario() -> None:
        service, conversation_id, action_id, _ = await _conversation_with_ui_action()
        other = await service.create_conversation("user-2")
        with pytest.raises(AssistantConversationNotFoundError):
            await service.record_action_receipt(
                conversation_id,
                "user-2",
                _receipt(action_id, UiActionReceiptStatus.SUCCEEDED),
            )
        # 属于另一个会话的动作不能记入本会话。
        with pytest.raises(AssistantActionNotFoundError):
            await service.record_action_receipt(
                other.conversation_id,
                "user-2",
                _receipt(action_id, UiActionReceiptStatus.SUCCEEDED),
            )

    asyncio.run(scenario())


def test_succeeded_receipt_finishes_and_duplicate_terminal_is_idempotent() -> None:
    async def scenario() -> None:
        service, conversation_id, action_id, _ = await _conversation_with_ui_action()
        receipt = _receipt(action_id, UiActionReceiptStatus.SUCCEEDED)
        first = await service.record_action_receipt(conversation_id, "user-1", receipt)
        assert first.decision == ActionReceiptDecision.FINISHED
        assert first.status == UiActionReceiptStatus.SUCCEEDED
        assert first.retry_count == 0
        second = await service.record_action_receipt(conversation_id, "user-1", receipt)
        # 相同终态回执幂等：返回同一结果，不产生第二个副作用。
        assert second == first

    asyncio.run(scenario())


def test_conflicting_terminal_receipt_returns_conflict_without_overwriting() -> None:
    async def scenario() -> None:
        service, conversation_id, action_id, _ = await _conversation_with_ui_action()
        first = await service.record_action_receipt(
            conversation_id,
            "user-1",
            _receipt(action_id, UiActionReceiptStatus.SUCCEEDED),
        )
        with pytest.raises(AssistantActionReceiptConflictError):
            await service.record_action_receipt(
                conversation_id,
                "user-1",
                _receipt(action_id, UiActionReceiptStatus.FAILED, error="页面打开失败"),
            )
        # 原始终态未被覆盖。
        stored = await service.record_action_receipt(
            conversation_id,
            "user-1",
            _receipt(action_id, UiActionReceiptStatus.SUCCEEDED),
        )
        assert stored == first

    asyncio.run(scenario())


def test_failed_receipt_is_never_success_and_retries_are_bounded() -> None:
    async def scenario() -> None:
        service, conversation_id, action_id, events_before = await _conversation_with_ui_action()
        before_count = len(events_before)
        failed = _receipt(action_id, UiActionReceiptStatus.FAILED, error="自动打开页面失败")

        first = await service.record_action_receipt(conversation_id, "user-1", failed)
        assert first.status == UiActionReceiptStatus.FAILED
        assert first.status != UiActionReceiptStatus.SUCCEEDED
        assert first.decision in {
            ActionReceiptDecision.RETRY_SCHEDULED,
            ActionReceiptDecision.MANUAL_ENTRY,
        }
        assert first.retry_count <= 1

        # 冲突终态不允许把失败改写成成功。
        with pytest.raises(AssistantActionReceiptConflictError):
            await service.record_action_receipt(
                conversation_id,
                "user-1",
                _receipt(action_id, UiActionReceiptStatus.SUCCEEDED),
            )

        events_after = await service.list_events(conversation_id, "user-1", 0)
        assert len(events_after) >= before_count

    asyncio.run(scenario())


def test_rejected_receipt_offers_manual_entry_to_user_operation() -> None:
    async def scenario() -> None:
        service, conversation_id, action_id, _ = await _conversation_with_ui_action()
        result = await service.record_action_receipt(
            conversation_id,
            "user-1",
            _receipt(action_id, UiActionReceiptStatus.REJECTED),
        )
        assert result.status == UiActionReceiptStatus.REJECTED
        assert result.decision == ActionReceiptDecision.MANUAL_ENTRY
        assert result.manual_route_key == "WRONG_QUESTIONS"
        assert result.retry_count == 0

    asyncio.run(scenario())


def test_receipt_schema_rejects_urls_unregistered_routes_and_unsafe_errors() -> None:
    with pytest.raises(ValueError):
        _receipt("action-1", UiActionReceiptStatus.SUCCEEDED, current_route="https://evil")
    # routeKey（大写）不是 route name，必须拒绝。
    with pytest.raises(ValueError):
        _receipt("action-1", UiActionReceiptStatus.SUCCEEDED, current_route="DASHBOARD")
    with pytest.raises(ValueError):
        _receipt("action-1", UiActionReceiptStatus.SUCCEEDED, current_route="/dashboard")
    with pytest.raises(ValueError):
        _receipt("..bad", UiActionReceiptStatus.SUCCEEDED)
    with pytest.raises(ValueError):
        _receipt("action-1", "UNKNOWN")
    # 合法 route name 必须被接受。
    assert _receipt(
        "action-1", UiActionReceiptStatus.SUCCEEDED, current_route="dashboard"
    ).current_route == "dashboard"


def test_concurrent_terminal_receipts_commit_exactly_one_state() -> None:
    async def scenario() -> None:
        service, conversation_id, action_id, _ = await _conversation_with_ui_action()
        statuses = [
            UiActionReceiptStatus.SUCCEEDED,
            UiActionReceiptStatus.FAILED,
            UiActionReceiptStatus.REJECTED,
        ]
        results = await asyncio.gather(
            *(
                service.record_action_receipt(
                    conversation_id, "user-1", _receipt(action_id, status)
                )
                for status in statuses
            ),
            return_exceptions=True,
        )
        committed = [item for item in results if not isinstance(item, Exception)]
        conflicts = [
            item for item in results
            if isinstance(item, AssistantActionReceiptConflictError)
        ]
        # 并发提交只允许一个终态落库，其他请求必须冲突而不是覆盖。
        assert len(committed) == 1
        assert len(conflicts) == 2
        stored_status = committed[0].status
        replay = await service.record_action_receipt(
            conversation_id, "user-1", _receipt(action_id, stored_status)
        )
        assert replay == committed[0]

    asyncio.run(scenario())


class ReceiptStore:
    """可阻塞/可失败的会话存储，用于验证“先持久化、再发布”。"""

    def __init__(self) -> None:
        self.saved: dict[tuple[str, str], dict] = {}
        self.block_next = False
        self.fail_next = False
        self.save_entered = asyncio.Event()
        self.release = asyncio.Event()

    async def save(self, *, kind, conversation_id, owner_id, payload):
        if self.fail_next:
            self.fail_next = False
            raise RuntimeError("persistence unavailable")
        if self.block_next:
            self.block_next = False
            self.save_entered.set()
            await self.release.wait()
        self.saved[(conversation_id, kind)] = payload

    async def load(self, *, kind, conversation_id, owner_id):
        return self.saved.get((conversation_id, kind))


async def _service_with_store(store: ReceiptStore):
    service = UnifiedAgentSupervisor(
        FakeJavaBackend(),
        model_name="test-model",
        persistence=SimpleNamespace(store=store),
    )
    conversation = await service.create_conversation("user-1")
    await service.send_message(
        conversation.conversation_id,
        "打开我的错题集",
        "assistant-turn:persist-1",
        "user-1",
        {},
    )
    events = await service.list_events(conversation.conversation_id, "user-1", 0)
    action_id = next(
        event.payload["actionId"] for event in events if event.type == "UI_ACTION"
    )
    return service, conversation.conversation_id, action_id


def test_retry_action_is_durably_persisted_before_it_is_published() -> None:
    async def scenario() -> None:
        store = ReceiptStore()
        service, conversation_id, action_id = await _service_with_store(store)
        subscription = service._events.subscribe(conversation_id)
        store.block_next = True

        task = asyncio.create_task(
            service.record_action_receipt(
                conversation_id, "user-1", _receipt(action_id, UiActionReceiptStatus.FAILED)
            )
        )
        await asyncio.wait_for(store.save_entered.wait(), 1)
        early = await subscription.receive(0.1)
        assert early is None, "重试事件在持久化完成之前绝不能被发布"

        store.release.set()
        result = await task
        assert result.decision == ActionReceiptDecision.RETRY_SCHEDULED

        published = await subscription.receive(1)
        assert published is not None
        assert published.type == "UI_ACTION"
        saved = store.saved[(conversation_id, "unified-assistant")]
        assert published.sequence in [event["sequence"] for event in saved["events"]]
        assert published.payload["actionId"] in saved["uiActionsById"]
        service._events.unsubscribe(subscription)

    asyncio.run(scenario())


def test_retry_is_not_published_when_durable_save_fails() -> None:
    async def scenario() -> None:
        store = ReceiptStore()
        service, conversation_id, action_id = await _service_with_store(store)
        subscription = service._events.subscribe(conversation_id)
        store.fail_next = True

        with pytest.raises(RuntimeError):
            await service.record_action_receipt(
                conversation_id, "user-1", _receipt(action_id, UiActionReceiptStatus.FAILED)
            )
        assert await subscription.receive(0.2) is None, (
            "持久化失败时不得把未落库的重试事件暴露给订阅者"
        )
        service._events.unsubscribe(subscription)

    asyncio.run(scenario())


def test_concurrent_receipt_cannot_observe_action_before_durable_registration() -> None:
    async def scenario() -> None:
        store = ReceiptStore()
        service, conversation_id, action_id = await _service_with_store(store)
        subscription = service._events.subscribe(conversation_id)
        store.block_next = True

        first = asyncio.create_task(
            service.record_action_receipt(
                conversation_id, "user-1", _receipt(action_id, UiActionReceiptStatus.FAILED)
            )
        )
        await asyncio.wait_for(store.save_entered.wait(), 1)
        second = asyncio.create_task(
            service.record_action_receipt(
                conversation_id,
                "user-1",
                _receipt(action_id, UiActionReceiptStatus.SUCCEEDED),
            )
        )
        await asyncio.sleep(0.1)
        assert not second.done(), "并发回执必须等待首个回执完成持久化"
        assert await subscription.receive(0.05) is None

        store.release.set()
        assert (await first).decision == ActionReceiptDecision.RETRY_SCHEDULED
        with pytest.raises(AssistantActionReceiptConflictError):
            await second
        service._events.unsubscribe(subscription)

    asyncio.run(scenario())


def test_receipts_and_issued_actions_survive_service_restart(tmp_path: Path) -> None:
    asyncio.run(_receipts_survive_service_restart(tmp_path))
async def _receipts_survive_service_restart(tmp_path: Path) -> None:
    persistence = await AgentPersistence.open(tmp_path / "receipt.sqlite3", TEST_KEY)
    first = UnifiedAgentSupervisor(
        FakeJavaBackend(), model_name="test-model", persistence=persistence
    )
    conversation = await first.create_conversation("user-1")
    await first.send_message(
        conversation.conversation_id,
        "打开我的错题集",
        "assistant-turn:durable-receipt",
        "user-1",
        {},
    )
    events = await first.list_events(conversation.conversation_id, "user-1", 0)
    action_id = next(
        event.payload["actionId"] for event in events if event.type == "UI_ACTION"
    )
    # 重启后，已下发动作必须仍可接收回执，且重复回执不产生第二个副作用。
    second = UnifiedAgentSupervisor(
        FakeJavaBackend(), model_name="test-model", persistence=persistence
    )
    first_result = await second.record_action_receipt(
        conversation.conversation_id,
        "user-1",
        _receipt(action_id, UiActionReceiptStatus.SUCCEEDED),
    )
    assert first_result.decision == ActionReceiptDecision.FINISHED
    third = UnifiedAgentSupervisor(
        FakeJavaBackend(), model_name="test-model", persistence=persistence
    )
    replay = await third.record_action_receipt(
        conversation.conversation_id,
        "user-1",
        _receipt(action_id, UiActionReceiptStatus.SUCCEEDED),
    )
    assert replay == first_result
    with pytest.raises(AssistantActionReceiptConflictError):
        await third.record_action_receipt(
            conversation.conversation_id,
            "user-1",
            _receipt(action_id, UiActionReceiptStatus.FAILED, error="页面打开失败"),
        )
    await persistence.close()


def test_receipt_error_is_sanitized_before_crossing_the_boundary() -> None:
    receipt = _receipt(
        "action-1",
        UiActionReceiptStatus.FAILED,
        error="java.lang.IllegalStateException at com.moxiao.studypilot.Api",
    )
    assert receipt.error is not None
    # 一旦含堆栈/包名，整体不进入用户可见字段（模型校验保留原样前先被裁剪）。
    from app.unified_agent.models import sanitize_action_error

    assert sanitize_action_error(receipt.error) is None
    assert sanitize_action_error("Authorization: Bearer abc.def") is None
    assert sanitize_action_error("https://evil.example/cb") is None
    long_error = "错误" * 1000
    assert len(sanitize_action_error(long_error)) == 500
    assert sanitize_action_error("  自动打开页面失败  ") == "自动打开页面失败"
    assert sanitize_action_error(None) is None
