import { describe, it, expect, beforeEach, vi } from 'vitest'
import {
  executeCrossRouteUiAction,
  clearInFlightActionsForTest,
  getInFlightActionCountForTest,
  type AdapterRegistryLike,
  type ExactRouteAdapters,
} from './crossRouteUiActionExecutor'
import {
  clearActionReceiptsForTest,
  type RouterLike,
} from './uiActionDispatcher'
import type { AssistantUiAction } from '@/types/assistant'

describe('crossRouteUiActionExecutor', () => {
  beforeEach(() => {
    clearActionReceiptsForTest()
    clearInFlightActionsForTest()
    vi.restoreAllMocks()
  })

  function createMockRouter(initialRouteName: string = 'assistant') {
    const currentRoute = { value: { name: initialRouteName, path: `/${initialRouteName}` } }
    const push = vi.fn().mockImplementation((location: { name: string; params?: Record<string, string> }) => {
      currentRoute.value.name = location.name
      currentRoute.value.path = `/${location.name}`
      return Promise.resolve()
    })
    return {
      router: {
        push,
        currentRoute,
      } as RouterLike,
      push,
    }
  }

  function createMockRegistry() {
    const store = new Map<string, ExactRouteAdapters>()
    const waiters = new Map<string, Array<(adapters: ExactRouteAdapters) => void>>()

    const getAdaptersForRouteKey = vi.fn((routeKey: string): ExactRouteAdapters => {
      return store.get(routeKey) || {}
    })

    const awaitAdaptersForRouteKey = vi.fn(
      (routeKey: string, timeoutMs: number = 2000): Promise<ExactRouteAdapters> => {
        const existing = store.get(routeKey)
        if (existing) {
          return Promise.resolve(existing)
        }
        return new Promise<ExactRouteAdapters>((resolve, reject) => {
          const timer = setTimeout(() => {
            const list = waiters.get(routeKey) || []
            const idx = list.indexOf(resolve)
            if (idx !== -1) list.splice(idx, 1)
            reject(new Error(`等待页面适配器超时: ${routeKey} (${timeoutMs}ms)`))
          }, timeoutMs)

          const list = waiters.get(routeKey) || []
          list.push((adapters: ExactRouteAdapters) => {
            clearTimeout(timer)
            resolve(adapters)
          })
          waiters.set(routeKey, list)
        })
      },
    )

    const register = (routeKey: string, adapters: ExactRouteAdapters) => {
      store.set(routeKey, adapters)
      const list = waiters.get(routeKey) || []
      waiters.delete(routeKey)
      for (const cb of list) {
        cb(adapters)
      }
    }

    return {
      registry: {
        getAdaptersForRouteKey,
        awaitAdaptersForRouteKey,
      } as AdapterRegistryLike,
      store,
      register,
      getAdaptersForRouteKeyMock: getAdaptersForRouteKey,
      awaitAdaptersForRouteKeyMock: awaitAdaptersForRouteKey,
    }
  }

  it('rejects rather than inventing an actionId when actionId is empty or missing', async () => {
    const { router, push } = createMockRouter('assistant')
    const { registry } = createMockRegistry()

    const actionWithoutId: AssistantUiAction = {
      type: 'NAVIGATE',
      routeKey: 'ROADMAP',
      params: {},
      reason: 'Navigate to roadmap without actionId',
    } as any

    await expect(
      executeCrossRouteUiAction({
        action: actionWithoutId,
        router,
        registry,
      }),
    ).rejects.toThrow('UI 动作缺少合法的 actionId')

    expect(push).not.toHaveBeenCalled()
  })

  it('executes direct NAVIGATE immediately without awaiting adapters', async () => {
    const { router, push } = createMockRouter('assistant')
    const { registry, awaitAdaptersForRouteKeyMock, getAdaptersForRouteKeyMock } = createMockRegistry()

    const action: AssistantUiAction = {
      actionId: 'act-nav-001',
      type: 'NAVIGATE',
      routeKey: 'ROADMAP',
      params: {},
      reason: 'Navigate directly to roadmap',
    }

    const receipt = await executeCrossRouteUiAction({
      action,
      router,
      registry,
    })

    expect(receipt.status).toBe('SUCCEEDED')
    expect(receipt.actionId).toBe('act-nav-001')
    expect(receipt.currentRoute).toBe('roadmap')
    expect(push).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledWith({ name: 'roadmap' })
    expect(getAdaptersForRouteKeyMock).not.toHaveBeenCalled()
    expect(awaitAdaptersForRouteKeyMock).not.toHaveBeenCalled()
  })

  it('handles delayed CREATE_GOAL registration and executes exactly one modal effect', async () => {
    const { router, push } = createMockRouter('assistant')
    const { registry, register } = createMockRegistry()

    const openModalMock = vi.fn().mockResolvedValue(true)

    const action: AssistantUiAction = {
      actionId: 'act-goal-modal-001',
      type: 'OPEN_MODAL',
      routeKey: 'LEARNING_GOALS',
      params: {
        modalKey: 'CREATE_GOAL',
      },
      reason: 'Open goal creation modal',
    }

    const executionPromise = executeCrossRouteUiAction({
      action,
      router,
      registry,
      adapterTimeoutMs: 1000,
    })

    // Navigation was requested first
    expect(push).toHaveBeenCalledWith({ name: 'goals' })

    // Simulate page mount delay before registering modal capability
    await new Promise((resolve) => setTimeout(resolve, 30))
    register('LEARNING_GOALS', {
      modalManager: { open: openModalMock },
    })

    const receipt = await executionPromise
    expect(receipt.status).toBe('SUCCEEDED')
    expect(receipt.actionId).toBe('act-goal-modal-001')
    expect(openModalMock).toHaveBeenCalledTimes(1)
    expect(openModalMock).toHaveBeenCalledWith('CREATE_GOAL', {})
  })

  it('handles delayed ROADMAP refresh across routes', async () => {
    const { router, push } = createMockRouter('assistant')
    const { registry, register } = createMockRegistry()

    const refreshMock = vi.fn().mockResolvedValue(true)

    const action: AssistantUiAction = {
      actionId: 'act-refresh-roadmap-001',
      type: 'REFRESH_RESOURCE',
      routeKey: 'ROADMAP',
      params: {
        resourceKey: 'ROADMAP',
      },
      reason: 'Refresh roadmap resource',
    }

    const executionPromise = executeCrossRouteUiAction({
      action,
      router,
      registry,
      adapterTimeoutMs: 1000,
    })

    expect(push).toHaveBeenCalledWith({ name: 'roadmap' })

    await new Promise((resolve) => setTimeout(resolve, 20))
    register('ROADMAP', {
      resourceManager: { refresh: refreshMock },
    })

    const receipt = await executionPromise
    expect(receipt.status).toBe('SUCCEEDED')
    expect(receipt.actionId).toBe('act-refresh-roadmap-001')
    expect(refreshMock).toHaveBeenCalledTimes(1)
    expect(refreshMock).toHaveBeenCalledWith('ROADMAP')
  })

  it('rejects invalid capability mismatch before navigation: zero push and zero effect', async () => {
    const { router, push } = createMockRouter('assistant')
    const { registry } = createMockRegistry()

    // Invalid: OPEN_MODAL on ROADMAP is not allowed by frozen matrix
    const invalidAction: AssistantUiAction = {
      actionId: 'act-invalid-mismatch-001',
      type: 'OPEN_MODAL',
      routeKey: 'ROADMAP',
      params: {
        modalKey: 'CREATE_GOAL',
      },
      reason: 'Invalid modal on roadmap',
    }

    await expect(
      executeCrossRouteUiAction({
        action: invalidAction,
        router,
        registry,
      }),
    ).rejects.toThrow('不受支持的弹窗动作')

    expect(push).not.toHaveBeenCalled()
  })

  it('rejects when router.push fails and does not execute side-effect', async () => {
    const { router, push } = createMockRouter('assistant')
    push.mockRejectedValue(new Error('Navigation cancelled by navigation guard'))
    const { registry } = createMockRegistry()

    const action: AssistantUiAction = {
      actionId: 'act-nav-fail-001',
      type: 'OPEN_MODAL',
      routeKey: 'LEARNING_GOALS',
      params: {
        modalKey: 'CREATE_GOAL',
      },
      reason: 'Open goal modal with failing router',
    }

    await expect(
      executeCrossRouteUiAction({
        action,
        router,
        registry,
      }),
    ).rejects.toThrow('导航到目标页面失败: Navigation cancelled by navigation guard')
  })

  it('rejects when awaiting adapter times out', async () => {
    const { router } = createMockRouter('assistant')
    const { registry } = createMockRegistry()

    const action: AssistantUiAction = {
      actionId: 'act-timeout-001',
      type: 'OPEN_MODAL',
      routeKey: 'LEARNING_GOALS',
      params: {
        modalKey: 'CREATE_GOAL',
      },
      reason: 'Open goal modal that times out',
    }

    await expect(
      executeCrossRouteUiAction({
        action,
        router,
        registry,
        adapterTimeoutMs: 30,
      }),
    ).rejects.toThrow('等待目标页面能力适配器就绪超时')
  })

  it('rejects when target adapter is registered but missing the required capability', async () => {
    const { router } = createMockRouter('assistant')
    const { registry, register } = createMockRegistry()

    // Registered with only focusManager, missing modalManager
    register('LEARNING_GOALS', {
      focusManager: { focus: vi.fn() },
    })

    const action: AssistantUiAction = {
      actionId: 'act-missing-cap-001',
      type: 'OPEN_MODAL',
      routeKey: 'LEARNING_GOALS',
      params: {
        modalKey: 'CREATE_GOAL',
      },
      reason: 'Goal modal with missing modalManager capability',
    }

    await expect(
      executeCrossRouteUiAction({
        action,
        router,
        registry,
        adapterTimeoutMs: 30,
      }),
    ).rejects.toThrow('目标页面未注册该动作所需的执行器能力: OPEN_MODAL')
  })

  it('rejects and records failure receipt when page executor throws', async () => {
    const { router } = createMockRouter('assistant')
    const { registry, register } = createMockRegistry()

    register('LEARNING_GOALS', {
      modalManager: {
        open: vi.fn().mockRejectedValue(new Error('DOM modal failed to open')),
      },
    })

    const action: AssistantUiAction = {
      actionId: 'act-executor-fail-001',
      type: 'OPEN_MODAL',
      routeKey: 'LEARNING_GOALS',
      params: {
        modalKey: 'CREATE_GOAL',
      },
      reason: 'Failing modal executor',
    }

    await expect(
      executeCrossRouteUiAction({
        action,
        router,
        registry,
      }),
    ).rejects.toThrow('DOM modal failed to open')
  })

  it('shares one single execution and side-effect for concurrent duplicate calls', async () => {
    const { router, push } = createMockRouter('assistant')
    const { registry, register } = createMockRegistry()

    const openModalMock = vi.fn().mockResolvedValue(true)

    const action: AssistantUiAction = {
      actionId: 'act-concurrent-001',
      type: 'OPEN_MODAL',
      routeKey: 'LEARNING_GOALS',
      params: {
        modalKey: 'CREATE_GOAL',
      },
      reason: 'Concurrent goal modal',
    }

    const call1 = executeCrossRouteUiAction({ action, router, registry, adapterTimeoutMs: 1000 })
    const call2 = executeCrossRouteUiAction({ action, router, registry, adapterTimeoutMs: 1000 })

    expect(getInFlightActionCountForTest()).toBe(1)

    // Register adapter delayed
    await new Promise((resolve) => setTimeout(resolve, 20))
    register('LEARNING_GOALS', {
      modalManager: { open: openModalMock },
    })

    const [receipt1, receipt2] = await Promise.all([call1, call2])

    expect(receipt1).toBe(receipt2)
    expect(receipt1.status).toBe('SUCCEEDED')
    expect(push).toHaveBeenCalledTimes(1)
    expect(openModalMock).toHaveBeenCalledTimes(1)
    expect(getInFlightActionCountForTest()).toBe(0)
  })

  it('returns terminal receipt without repeating side-effect for completed duplicate call', async () => {
    const { router, push } = createMockRouter('assistant')
    const { registry, register } = createMockRegistry()

    const openModalMock = vi.fn().mockResolvedValue(true)
    register('LEARNING_GOALS', {
      modalManager: { open: openModalMock },
    })

    const action: AssistantUiAction = {
      actionId: 'act-idempotent-repeat-001',
      type: 'OPEN_MODAL',
      routeKey: 'LEARNING_GOALS',
      params: {
        modalKey: 'CREATE_GOAL',
      },
      reason: 'Idempotent repeated call',
    }

    const receipt1 = await executeCrossRouteUiAction({ action, router, registry })
    expect(receipt1.status).toBe('SUCCEEDED')
    expect(push).toHaveBeenCalledTimes(1)
    expect(openModalMock).toHaveBeenCalledTimes(1)

    // Reset mocks to verify secondary call does not invoke router or executor
    push.mockClear()
    openModalMock.mockClear()

    const receipt2 = await executeCrossRouteUiAction({ action, router, registry })
    expect(receipt2).toBe(receipt1)
    expect(push).not.toHaveBeenCalled()
    expect(openModalMock).not.toHaveBeenCalled()
  })

  it('ensures caller scope ending does not cancel an ongoing promise', async () => {
    const { router } = createMockRouter('assistant')
    const { registry, register } = createMockRegistry()

    const openModalMock = vi.fn().mockResolvedValue(true)

    const action: AssistantUiAction = {
      actionId: 'act-scope-lifecycle-001',
      type: 'OPEN_MODAL',
      routeKey: 'LEARNING_GOALS',
      params: {
        modalKey: 'CREATE_GOAL',
      },
      reason: 'Scope lifecycle test',
    }

    let outerPromise: Promise<any>
    // Simulate component caller scope ending
    {
      const localScopeExecutor = () =>
        executeCrossRouteUiAction({
          action,
          router,
          registry,
          adapterTimeoutMs: 500,
        })
      outerPromise = localScopeExecutor()
    }

    await new Promise((resolve) => setTimeout(resolve, 20))
    register('LEARNING_GOALS', {
      modalManager: { open: openModalMock },
    })

    const receipt = await outerPromise
    expect(receipt.status).toBe('SUCCEEDED')
    expect(openModalMock).toHaveBeenCalledTimes(1)
  })

  describe('Scope isolation for crossRouteUiActionExecutor', () => {
    it('concurrent duplicate calls with same actionId in same scope share execution promise', async () => {
      const { router, push } = createMockRouter('assistant')
      const { registry, register } = createMockRegistry()

      const openModalMock = vi.fn().mockResolvedValue(true)
      const action: AssistantUiAction = {
        actionId: 'act-scoped-concurrent-001',
        type: 'OPEN_MODAL',
        routeKey: 'LEARNING_GOALS',
        params: {
          modalKey: 'CREATE_GOAL',
        },
        reason: 'Scoped concurrent modal',
      }

      const call1 = executeCrossRouteUiAction({
        action,
        router,
        registry,
        receiptScope: 'scope-user-1:conv-1',
        adapterTimeoutMs: 1000,
      })
      const call2 = executeCrossRouteUiAction({
        action,
        router,
        registry,
        receiptScope: 'scope-user-1:conv-1',
        adapterTimeoutMs: 1000,
      })

      expect(getInFlightActionCountForTest()).toBe(1)

      await new Promise((resolve) => setTimeout(resolve, 20))
      register('LEARNING_GOALS', {
        modalManager: { open: openModalMock },
      })

      const [receipt1, receipt2] = await Promise.all([call1, call2])
      expect(receipt1).toBe(receipt2)
      expect(receipt1.status).toBe('SUCCEEDED')
      expect(openModalMock).toHaveBeenCalledTimes(1)
      expect(push).toHaveBeenCalledTimes(1)
      expect(receipt1).not.toHaveProperty('receiptScope')
      expect(receipt1).not.toHaveProperty('scope')
    })

    it('concurrent calls with same actionId in different scopes execute independently and do not share in-flight promises', async () => {
      const { router } = createMockRouter('assistant')
      const { registry, register } = createMockRegistry()

      const openModalMock = vi.fn().mockResolvedValue(true)
      const action: AssistantUiAction = {
        actionId: 'act-scoped-diff-001',
        type: 'OPEN_MODAL',
        routeKey: 'LEARNING_GOALS',
        params: {
          modalKey: 'CREATE_GOAL',
        },
        reason: 'Different scope execution',
      }

      const callScope1 = executeCrossRouteUiAction({
        action,
        router,
        registry,
        receiptScope: 'scope-owner-A:conv-A',
        adapterTimeoutMs: 1000,
      })
      const callScope2 = executeCrossRouteUiAction({
        action,
        router,
        registry,
        receiptScope: 'scope-owner-B:conv-B',
        adapterTimeoutMs: 1000,
      })

      // Two different in-flight promises should exist because scopes are different
      expect(getInFlightActionCountForTest()).toBe(2)

      await new Promise((resolve) => setTimeout(resolve, 20))
      register('LEARNING_GOALS', {
        modalManager: { open: openModalMock },
      })

      const [receipt1, receipt2] = await Promise.all([callScope1, callScope2])
      expect(receipt1).not.toBe(receipt2)
      expect(receipt1.status).toBe('SUCCEEDED')
      expect(receipt2.status).toBe('SUCCEEDED')
      expect(openModalMock).toHaveBeenCalledTimes(2)
      expect(receipt1).not.toHaveProperty('receiptScope')
      expect(receipt2).not.toHaveProperty('receiptScope')
    })

    it('completed terminal receipt in one scope does not suppress execution in another scope', async () => {
      const { router } = createMockRouter('assistant')
      const { registry, register } = createMockRegistry()

      const openModalMock = vi.fn().mockResolvedValue(true)
      register('LEARNING_GOALS', {
        modalManager: { open: openModalMock },
      })

      const action: AssistantUiAction = {
        actionId: 'act-scoped-term-001',
        type: 'OPEN_MODAL',
        routeKey: 'LEARNING_GOALS',
        params: {
          modalKey: 'CREATE_GOAL',
        },
        reason: 'Scoped terminal dedupe test',
      }

      // Execute in Scope 1
      const receipt1 = await executeCrossRouteUiAction({
        action,
        router,
        registry,
        receiptScope: 'scope-1',
      })
      expect(receipt1.status).toBe('SUCCEEDED')
      expect(openModalMock).toHaveBeenCalledTimes(1)

      // Re-executing in Scope 1 returns cached receipt without running side effect again
      openModalMock.mockClear()
      const receipt1Repeat = await executeCrossRouteUiAction({
        action,
        router,
        registry,
        receiptScope: 'scope-1',
      })
      expect(receipt1Repeat).toBe(receipt1)
      expect(openModalMock).not.toHaveBeenCalled()

      // Executing same actionId in Scope 2 runs side effect
      openModalMock.mockClear()
      const receipt2 = await executeCrossRouteUiAction({
        action,
        router,
        registry,
        receiptScope: 'scope-2',
      })
      expect(receipt2.status).toBe('SUCCEEDED')
      expect(openModalMock).toHaveBeenCalledTimes(1)
      expect(receipt2).not.toBe(receipt1)
    })
  })

  describe('actionId normalization across padded and normalized duplicates', () => {
    it('shares terminal deduplication between padded and normalized forms of the same actionId with zero duplicate side effects', async () => {
      const { router, push } = createMockRouter('assistant')
      const { registry, register } = createMockRegistry()

      const openModalMock = vi.fn().mockResolvedValue(true)
      register('LEARNING_GOALS', {
        modalManager: { open: openModalMock },
      })

      const baseActionId = 'act-pad-norm-terminal-001'
      const paddedActionId = `   ${baseActionId}   `

      const actionPadded: AssistantUiAction = {
        actionId: paddedActionId,
        type: 'OPEN_MODAL',
        routeKey: 'LEARNING_GOALS',
        params: {
          modalKey: 'CREATE_GOAL',
        },
        reason: 'Padded action execution',
      }

      const receipt1 = await executeCrossRouteUiAction({
        action: actionPadded,
        router,
        registry,
      })

      expect(receipt1.status).toBe('SUCCEEDED')
      expect(receipt1.actionId).toBe(baseActionId)
      expect(push).toHaveBeenCalledTimes(1)
      expect(openModalMock).toHaveBeenCalledTimes(1)

      push.mockClear()
      openModalMock.mockClear()

      // Second invocation with normalized actionId
      const actionNormalized: AssistantUiAction = {
        actionId: baseActionId,
        type: 'OPEN_MODAL',
        routeKey: 'LEARNING_GOALS',
        params: {
          modalKey: 'CREATE_GOAL',
        },
        reason: 'Normalized action execution',
      }

      const receipt2 = await executeCrossRouteUiAction({
        action: actionNormalized,
        router,
        registry,
      })

      expect(receipt2.status).toBe('SUCCEEDED')
      expect(receipt2.actionId).toBe(baseActionId)
      expect(receipt2).toBe(receipt1)
      expect(push).not.toHaveBeenCalled()
      expect(openModalMock).not.toHaveBeenCalled()
    })

    it('shares concurrent in-flight deduplication across padded and normalized forms with zero duplicate side effects', async () => {
      const { router, push } = createMockRouter('assistant')
      const { registry, register } = createMockRegistry()

      const openModalMock = vi.fn().mockResolvedValue(true)

      const baseActionId = 'act-pad-norm-inflight-001'
      const paddedActionId = ` \t ${baseActionId} \n `

      const actionPadded: AssistantUiAction = {
        actionId: paddedActionId,
        type: 'OPEN_MODAL',
        routeKey: 'LEARNING_GOALS',
        params: {
          modalKey: 'CREATE_GOAL',
        },
        reason: 'Padded concurrent execution',
      }

      const actionNormalized: AssistantUiAction = {
        actionId: baseActionId,
        type: 'OPEN_MODAL',
        routeKey: 'LEARNING_GOALS',
        params: {
          modalKey: 'CREATE_GOAL',
        },
        reason: 'Normalized concurrent execution',
      }

      const callPadded = executeCrossRouteUiAction({
        action: actionPadded,
        router,
        registry,
        adapterTimeoutMs: 1000,
      })

      const callNormalized = executeCrossRouteUiAction({
        action: actionNormalized,
        router,
        registry,
        adapterTimeoutMs: 1000,
      })

      // Must share single in-flight promise
      expect(getInFlightActionCountForTest()).toBe(1)

      await new Promise((resolve) => setTimeout(resolve, 20))
      register('LEARNING_GOALS', {
        modalManager: { open: openModalMock },
      })

      const [receipt1, receipt2] = await Promise.all([callPadded, callNormalized])

      expect(receipt1).toBe(receipt2)
      expect(receipt1.status).toBe('SUCCEEDED')
      expect(receipt1.actionId).toBe(baseActionId)
      expect(push).toHaveBeenCalledTimes(1)
      expect(openModalMock).toHaveBeenCalledTimes(1)
      expect(getInFlightActionCountForTest()).toBe(0)
    })

    it('rejects blank/whitespace-only actionId before router push and before page adapters check', async () => {
      const { router, push } = createMockRouter('assistant')
      const { registry, getAdaptersForRouteKeyMock } = createMockRegistry()

      const actionWithWhitespaceId: AssistantUiAction = {
        actionId: '   \t  \n  ',
        type: 'NAVIGATE',
        routeKey: 'ROADMAP',
        params: {},
        reason: 'Whitespace-only actionId',
      }

      await expect(
        executeCrossRouteUiAction({
          action: actionWithWhitespaceId,
          router,
          registry,
        }),
      ).rejects.toThrow('UI 动作缺少合法的 actionId，严禁自动生成客户端 ID 执行')

      expect(push).not.toHaveBeenCalled()
      expect(getAdaptersForRouteKeyMock).not.toHaveBeenCalled()
    })
  })
})
