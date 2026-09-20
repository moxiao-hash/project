"""真实 provider 调用的唯一预算执行入口。

所有模型客户端都由 :func:`app.providers.model_factory.create_chat_model` 包装成
:class:`BudgetedChatModel`。它拦截 ``invoke`` / ``ainvoke`` / ``astream``，并在
``with_structured_output`` / ``bind`` 返回的包装器上继续生效，因此不存在绕过路径。

每次真实调用都：

1. 用当前 owner 向 Java 原子预占一个许可（失败关闭，见 :mod:`app.providers.budget`）；
2. 用这次许可下发的 ``maxOutputTokensPerTurn`` 绑定 ``max_tokens``；
3. 把预占 id 写进用量回调上下文，让用量记录与预占共享同一幂等键；
4. provider 抛错时释放许可，成功时由用量回调按实际用量终结许可。
"""

from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from typing import Any

from app.observability.usage import ModelPurpose, current_usage_scope, reservation_scope
from app.providers.budget import BudgetPermit, ModelBudgetExceededError, ModelBudgetGuard


class BudgetedChatModel:
    """在真实 provider 调用前后执行预算预占的模型包装器。"""

    def __init__(
        self,
        runnable: Any,
        *,
        guard: ModelBudgetGuard,
        owner_id: str | None,
        provider: str,
        model_name: str,
        purpose: str = ModelPurpose.UNKNOWN,
    ) -> None:
        self._runnable = runnable
        self._guard = guard
        self._owner_id = owner_id
        self._provider = provider
        self._model_name = model_name
        self._purpose = purpose

    @property
    def owner_id(self) -> str | None:
        return self._owner_id

    @property
    def underlying(self) -> Any:
        """被包装的 LangChain runnable；供测试与装配检查使用。"""

        return self._runnable

    def _resolve_owner(self) -> str | None:
        scope = current_usage_scope()
        return (scope.owner_id if scope is not None else None) or self._owner_id

    def _wrap(self, runnable: Any) -> BudgetedChatModel:
        return BudgetedChatModel(
            runnable,
            guard=self._guard,
            owner_id=self._owner_id,
            provider=self._provider,
            model_name=self._model_name,
            purpose=self._purpose,
        )

    async def _permit(self) -> BudgetPermit:
        owner_id = self._resolve_owner()
        if not owner_id:
            raise ModelBudgetExceededError("MODEL_OWNER_UNKNOWN")
        permit = await self._guard.reserve(
            owner_id=owner_id,
            provider=self._provider,
            model_name=self._model_name,
            purpose=self._purpose,
        )
        if not permit.allowed:
            raise ModelBudgetExceededError(
                permit.reason,
                max_output_tokens_per_turn=permit.max_output_tokens_per_turn,
            )
        return permit

    def _bound(self, permit: BudgetPermit) -> Any:
        if permit.max_output_tokens_per_turn:
            return self._runnable.bind(max_tokens=permit.max_output_tokens_per_turn)
        return self._runnable

    async def ainvoke(self, input: Any, config: Any = None, **kwargs: Any) -> Any:
        permit = await self._permit()
        try:
            with reservation_scope(permit.reservation_id):
                return await self._bound(permit).ainvoke(input, config, **kwargs)
        except BaseException:
            await self._guard.release(permit.reservation_id)
            raise

    def invoke(self, input: Any, config: Any = None, **kwargs: Any) -> Any:
        return _run_sync(self.ainvoke(input, config, **kwargs))

    async def astream(self, input: Any, config: Any = None, **kwargs: Any):
        permit = await self._permit()
        try:
            with reservation_scope(permit.reservation_id):
                async for chunk in self._bound(permit).astream(input, config, **kwargs):
                    yield chunk
        except BaseException:
            await self._guard.release(permit.reservation_id)
            raise

    def with_structured_output(self, schema: Any, **kwargs: Any) -> BudgetedChatModel:
        return self._wrap(self._runnable.with_structured_output(schema, **kwargs))

    def bind(self, **kwargs: Any) -> BudgetedChatModel:
        return self._wrap(self._runnable.bind(**kwargs))

    def __getattr__(self, name: str) -> Any:
        # 未显式包装的属性（如 model_name、callbacks）透传给底层 LangChain 对象。
        if name == "_runnable":
            raise AttributeError(name)
        return getattr(self._runnable, name)


def _run_sync(coroutine: Any) -> Any:
    """在没有事件循环时直接运行；在循环线程上则交给独立线程，避免死锁。"""

    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.run(coroutine)
    with ThreadPoolExecutor(max_workers=1) as pool:
        return pool.submit(asyncio.run, coroutine).result()


__all__ = ["BudgetedChatModel"]
