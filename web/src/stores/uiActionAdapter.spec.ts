/**
 * uiActionAdapter store — unit tests for Task 30 phase 2 & Bounded Deliverable A.
 * These tests verify exact register/unregister lifecycle, strict absence of cross-route fallback,
 * and awaitAdaptersForRouteKey with timeout, cleanup, and deterministic fake timers.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUiActionAdapterStore } from './uiActionAdapter'
import type { PageAdapters } from './uiActionAdapter'

function makeAdapter(routeKey: string, capabilities: Partial<Omit<PageAdapters, 'routeKey'>> = {}): PageAdapters {
  return { routeKey, ...capabilities }
}

describe('useUiActionAdapterStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  describe('exact registration & no cross-route fallback', () => {
    it('registers an adapter and retrieves it by routeKey', async () => {
      const store = useUiActionAdapterStore()
      let openedModalKey = ''
      const modalManager = {
        open: async (key: string) => {
          openedModalKey = key
        },
      }
      store.register(makeAdapter('LEARNING_GOALS', { modalManager }))

      const result = store.getAdaptersForRouteKey('LEARNING_GOALS')
      expect(result.modalManager).toBeDefined()
      await result.modalManager?.open('CREATE_GOAL')
      expect(openedModalKey).toBe('CREATE_GOAL')
    })

    it('unregister removes the adapter so its capabilities are gone when identity matches', () => {
      const store = useUiActionAdapterStore()
      const registration = makeAdapter('LEARNING_GOALS', { modalManager: { open: async () => {} } })
      store.register(registration)
      store.unregister('LEARNING_GOALS', registration)

      const result = store.getAdaptersForRouteKey('LEARNING_GOALS')
      expect(result.modalManager).toBeUndefined()
    })

    it('regression: stale unregister is a no-op when newer registration exists; unregistering new removes it', () => {
      const store = useUiActionAdapterStore()
      const oldAdapter = makeAdapter('LEARNING_GOALS', {
        modalManager: { open: async () => {} },
      })
      const newAdapter = makeAdapter('LEARNING_GOALS', {
        modalManager: { open: async () => {} },
      })

      // Register old object, then register new object for same route
      store.register(oldAdapter)
      store.register(newAdapter)

      // Unregister old registration: should be a no-op, new remains active
      store.unregister('LEARNING_GOALS', oldAdapter)
      const afterOldUnregister = store.getAdaptersForRouteKey('LEARNING_GOALS')
      expect(afterOldUnregister.modalManager).toBe(newAdapter.modalManager)

      // Unregister new registration: removes it
      store.unregister('LEARNING_GOALS', newAdapter)
      const afterNewUnregister = store.getAdaptersForRouteKey('LEARNING_GOALS')
      expect(afterNewUnregister.modalManager).toBeUndefined()
    })

    it('regression: disposer returned from register unregisters only its own registration', () => {
      const store = useUiActionAdapterStore()
      const oldAdapter = makeAdapter('LEARNING_GOALS', {
        modalManager: { open: async () => {} },
      })
      const newAdapter = makeAdapter('LEARNING_GOALS', {
        modalManager: { open: async () => {} },
      })

      const disposeOld = store.register(oldAdapter)
      const disposeNew = store.register(newAdapter)

      // Invoking stale disposer does not evict new registration
      disposeOld()
      expect(store.getAdaptersForRouteKey('LEARNING_GOALS').modalManager).toBe(newAdapter.modalManager)

      // Invoking current disposer removes it
      disposeNew()
      expect(store.getAdaptersForRouteKey('LEARNING_GOALS').modalManager).toBeUndefined()
    })

    it('latest registration wins for same routeKey', async () => {
      const store = useUiActionAdapterStore()
      let opened = ''
      const m1 = {
        open: async () => {
          opened = 'm1'
        },
      }
      const m2 = {
        open: async () => {
          opened = 'm2'
        },
      }
      store.register(makeAdapter('LEARNING_GOALS', { modalManager: m1 }))
      store.register(makeAdapter('LEARNING_GOALS', { modalManager: m2 }))

      const adapter = store.getAdaptersForRouteKey('LEARNING_GOALS')
      await adapter.modalManager?.open('MODAL')
      expect(opened).toBe('m2')
    })

    it('clearAll removes all adapters', () => {
      const store = useUiActionAdapterStore()
      store.register(makeAdapter('LEARNING_GOALS', { modalManager: { open: async () => {} } }))
      store.register(makeAdapter('LEARNING_PLANS', { modalManager: { open: async () => {} } }))
      store.clearAll()

      expect(store.getAdaptersForRouteKey('LEARNING_GOALS').modalManager).toBeUndefined()
      expect(store.getAdaptersForRouteKey('LEARNING_PLANS').modalManager).toBeUndefined()
    })

    it('returns empty capability object when no direct routeKey match (no cross-route fallback)', () => {
      const store = useUiActionAdapterStore()
      const rm = {
        refresh: async () => {},
      }
      store.register(makeAdapter('ROADMAP', { resourceManager: rm }))

      // When querying another route, cross-route merged fallback is strictly forbidden
      const result = store.getAdaptersForRouteKey('SOME_OTHER_KEY')
      expect(result.resourceManager).toBeUndefined()
      expect(result.modalManager).toBeUndefined()
      expect(result.formDraftStore).toBeUndefined()
      expect(result.focusManager).toBeUndefined()
      expect(result).toEqual({})
    })

    it('multiple pages do NOT leak capabilities across route boundaries', () => {
      const store = useUiActionAdapterStore()
      const mm = { open: async () => {} }
      const rm = { refresh: async () => {} }
      store.register(makeAdapter('LEARNING_GOALS', { modalManager: mm }))
      store.register(makeAdapter('ROADMAP', { resourceManager: rm }))

      const nonExistent = store.getAdaptersForRouteKey('NONEXISTENT')
      expect(nonExistent).toEqual({})

      const goals = store.getAdaptersForRouteKey('LEARNING_GOALS')
      expect(goals.modalManager).toBe(mm)
      expect(goals.resourceManager).toBeUndefined()

      const roadmap = store.getAdaptersForRouteKey('ROADMAP')
      expect(roadmap.resourceManager).toBe(rm)
      expect(roadmap.modalManager).toBeUndefined()
    })
  })

  describe('awaitAdaptersForRouteKey', () => {
    it('resolves immediately if exact adapter is already live', async () => {
      const store = useUiActionAdapterStore()
      const mm = { open: async () => {} }
      store.register(makeAdapter('LEARNING_GOALS', { modalManager: mm }))

      const result = await store.awaitAdaptersForRouteKey('LEARNING_GOALS', 500)
      expect(result.modalManager).toBe(mm)
    })

    it('resolves when exact adapter is registered after wait starts', async () => {
      vi.useFakeTimers()
      const store = useUiActionAdapterStore()
      const mm = { open: async () => {} }

      const promise = store.awaitAdaptersForRouteKey('LEARNING_GOALS', 1000)

      // Registration occurs later
      vi.advanceTimersByTime(200)
      store.register(makeAdapter('LEARNING_GOALS', { modalManager: mm }))

      const resolved = await promise
      expect(resolved.modalManager).toBe(mm)
    })

    it('does NOT resolve when unrelated route adapter is registered', async () => {
      vi.useFakeTimers()
      const store = useUiActionAdapterStore()
      const mm = { open: async () => {} }

      const promise = store.awaitAdaptersForRouteKey('LEARNING_GOALS', 500)

      // Unrelated route registration
      store.register(makeAdapter('ROADMAP', { modalManager: mm }))
      vi.advanceTimersByTime(200)

      // Should still be waiting; timing out at 500ms
      const rejectionPromise = expect(promise).rejects.toThrow(
        /等待页面适配器超时: LEARNING_GOALS/
      )
      vi.advanceTimersByTime(300)
      await rejectionPromise
    })

    it('rejects clearly with bounded timeout and cleans timer/waiter', async () => {
      vi.useFakeTimers()
      const store = useUiActionAdapterStore()

      const promise = store.awaitAdaptersForRouteKey('MATERIALS', 300)

      const rejectionPromise = expect(promise).rejects.toThrow(
        '等待页面适配器超时: MATERIALS (300ms)'
      )
      vi.advanceTimersByTime(300)
      await rejectionPromise

      // Late registration after timeout should not trigger old waiter
      const lateMm = { open: async () => {} }
      store.register(makeAdapter('MATERIALS', { modalManager: lateMm }))
      expect(store.getAdaptersForRouteKey('MATERIALS').modalManager).toBe(lateMm)
    })

    it('rejects pending waiters when clearAll is called and cleans waiters', async () => {
      vi.useFakeTimers()
      const store = useUiActionAdapterStore()

      const promise1 = store.awaitAdaptersForRouteKey('LEARNING_GOALS', 1000)
      const promise2 = store.awaitAdaptersForRouteKey('LEARNING_PLANS', 1000)

      store.clearAll()

      await expect(promise1).rejects.toThrow('所有页面适配器等待已重置')
      await expect(promise2).rejects.toThrow('所有页面适配器等待已重置')
    })
  })
})
