/**
 * Cross-route UI Action adapter store (Task 30 remediation).
 *
 * Pages register their adapter capabilities on mount and deregister on unmount.
 * AssistantView reads the current adapters when building the UiActionContext.
 *
 * Owner-scoped: never exposes another owner's draft; cleared on logout/switch.
 * No credentials, PII or API responses stored here.
 */
import { defineStore } from 'pinia'
import { shallowRef } from 'vue'
import type { ModalManagerLike, FormDraftStoreLike, ResourceManagerLike, FocusManagerLike } from '@/modules/assistant/uiActionDispatcher'

export interface PageAdapters {
  /** Page route key (LEARNING_GOALS / LEARNING_PLANS / MATERIALS / etc.) */
  routeKey: string
  modalManager?: ModalManagerLike
  formDraftStore?: FormDraftStoreLike
  resourceManager?: ResourceManagerLike
  focusManager?: FocusManagerLike
}

interface AdapterWaiter {
  routeKey: string
  resolve: (adapters: Omit<PageAdapters, 'routeKey'>) => void
  reject: (error: Error) => void
  timer: ReturnType<typeof setTimeout>
}

export const useUiActionAdapterStore = defineStore('uiActionAdapter', () => {
  /** Currently registered adapters from mounted pages, keyed by routeKey */
  const adapters = shallowRef<Map<string, PageAdapters>>(new Map())

  /** Pending asynchronous waiters for specific routeKeys */
  const waiters = shallowRef<AdapterWaiter[]>([])

  /**
   * Page calls this on mount to advertise its capabilities.
   * Replaces any previous registration for the same routeKey and notifies matching waiters.
   * Returns an ownership-bound disposer function.
   */
  function register(page: PageAdapters): () => void {
    const nextAdapters = new Map(adapters.value)
    nextAdapters.set(page.routeKey, page)
    adapters.value = nextAdapters

    const remainingWaiters: AdapterWaiter[] = []
    const matchingWaiters: AdapterWaiter[] = []

    for (const waiter of waiters.value) {
      if (waiter.routeKey === page.routeKey) {
        matchingWaiters.push(waiter)
      } else {
        remainingWaiters.push(waiter)
      }
    }

    waiters.value = remainingWaiters

    for (const waiter of matchingWaiters) {
      clearTimeout(waiter.timer)
      waiter.resolve(getAdaptersForRouteKey(page.routeKey))
    }

    return () => {
      unregister(page.routeKey, page)
    }
  }

  /**
   * Page calls this on unmount to remove its adapters safely.
   * Compares identity before delete: if routeKey currently points to a different/newer
   * PageAdapters instance, this unmount is a safe no-op.
   */
  function unregister(routeKey: string, expectedRegistration: PageAdapters): void {
    if (!expectedRegistration || adapters.value.get(routeKey) !== expectedRegistration) {
      return
    }
    const nextAdapters = new Map(adapters.value)
    nextAdapters.delete(routeKey)
    adapters.value = nextAdapters
  }

  /**
   * Returns exact registered adapters for the given routeKey.
   * Strictly returns only direct page match or an empty capability object.
   * Cross-route merged fallback is strictly disallowed.
   */
  function getAdaptersForRouteKey(routeKey: string): Omit<PageAdapters, 'routeKey'> {
    const direct = adapters.value.get(routeKey)
    if (direct) {
      const { routeKey: _r, ...caps } = direct
      return caps
    }
    return {}
  }

  /**
   * Awaits live adapter registration for the specified routeKey.
   * - Resolves immediately if exact adapter is already live.
   * - Resolves when exact adapter is registered before timeout.
   * - Rejects with bounded timeout and cleans up timers/waiters.
   * - Rejects pending waiters on clearAll.
   */
  function awaitAdaptersForRouteKey(
    routeKey: string,
    timeoutMs: number = 2000,
  ): Promise<Omit<PageAdapters, 'routeKey'>> {
    const existing = adapters.value.get(routeKey)
    if (existing) {
      return Promise.resolve(getAdaptersForRouteKey(routeKey))
    }

    return new Promise<Omit<PageAdapters, 'routeKey'>>((resolve, reject) => {
      const timer = setTimeout(() => {
        const index = waiters.value.findIndex((w) => w === waiter)
        if (index !== -1) {
          const nextWaiters = [...waiters.value]
          nextWaiters.splice(index, 1)
          waiters.value = nextWaiters
        }
        reject(new Error(`等待页面适配器超时: ${routeKey} (${timeoutMs}ms)`))
      }, timeoutMs)

      const waiter: AdapterWaiter = {
        routeKey,
        resolve,
        reject,
        timer,
      }

      waiters.value = [...waiters.value, waiter]
    })
  }

  /** Clears all adapters and aborts all pending waiters — called on logout / owner switch. */
  function clearAll(): void {
    adapters.value = new Map()

    const pending = waiters.value
    waiters.value = []
    for (const waiter of pending) {
      clearTimeout(waiter.timer)
      waiter.reject(new Error('所有页面适配器等待已重置'))
    }
  }

  return {
    adapters,
    register,
    unregister,
    getAdaptersForRouteKey,
    awaitAdaptersForRouteKey,
    clearAll,
  }
})
