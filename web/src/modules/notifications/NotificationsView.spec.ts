import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'

import NotificationsView from './NotificationsView.vue'
import { agentOpsApi } from '@/services/current/agentOps'
import { useUiActionAdapterStore } from '@/stores/uiActionAdapter'
import { dispatchUiAction } from '@/modules/assistant/uiActionDispatcher'
import type { Notification } from '@/types/api'

vi.mock('@/services/current/agentOps', () => ({
  agentOpsApi: {
    listNotifications: vi.fn(),
    markNotificationRead: vi.fn(),
  },
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('NotificationsView NOTIFICATIONS UI action adapter', () => {
  let router: ReturnType<typeof createRouter>
  let pinia: ReturnType<typeof createPinia>

  beforeEach(async () => {
    pinia = createPinia()
    setActivePinia(pinia)
    vi.resetAllMocks()

    vi.mocked(agentOpsApi.listNotifications).mockResolvedValue([])

    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/notifications', name: 'notifications', component: NotificationsView }],
    })
    await router.push('/notifications')
    await router.isReady()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('registers the real NOTIFICATIONS adapter on mount and unregisters on unmount', async () => {
    const adapterStore = useUiActionAdapterStore()
    expect(adapterStore.getAdaptersForRouteKey('NOTIFICATIONS').resourceManager).toBeUndefined()

    const wrapper = mount(NotificationsView, {
      global: {
        plugins: [pinia, router],
      },
    })
    await flushPromises()

    const registered = adapterStore.getAdaptersForRouteKey('NOTIFICATIONS')
    expect(registered.resourceManager).toBeDefined()
    expect(typeof registered.resourceManager?.refresh).toBe('function')

    wrapper.unmount()
    expect(adapterStore.getAdaptersForRouteKey('NOTIFICATIONS').resourceManager).toBeUndefined()
  })

  it('awaits real complete reload of notifications and updates rendered notifications', async () => {
    const adapterStore = useUiActionAdapterStore()
    const wrapper = mount(NotificationsView, {
      global: {
        plugins: [pinia, router],
      },
    })
    await flushPromises()

    expect(agentOpsApi.listNotifications).toHaveBeenCalledTimes(1)

    const updatedNotifications: Notification[] = [
      {
        id: 'notif-1',
        type: 'PLAN_ADJUSTED',
        title: '学习计划已动态调整',
        content: '由于前序任务延期，后续阶段节点已自动顺延。',
        read: false,
        createdAt: '2026-09-16T08:30:00Z',
        readAt: null,
      },
    ]
    vi.mocked(agentOpsApi.listNotifications).mockResolvedValueOnce(updatedNotifications)

    const receipt = await dispatchUiAction(
      {
        actionId: 'act-notifications-refresh',
        type: 'REFRESH_RESOURCE',
        routeKey: 'NOTIFICATIONS',
        reason: '刷新通知列表',
        params: {
          resourceKey: 'NOTIFICATIONS',
        },
      },
      {
        router,
        ...adapterStore.getAdaptersForRouteKey('NOTIFICATIONS'),
      },
    )

    expect(receipt.status).toBe('SUCCEEDED')
    await flushPromises()

    expect(agentOpsApi.listNotifications).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('学习计划已动态调整')
    expect(wrapper.text()).toContain('由于前序任务延期，后续阶段节点已自动顺延。')
  })

  it('rejects unsupported resourceKey with an error', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(NotificationsView, {
      global: {
        plugins: [pinia, router],
      },
    })
    await flushPromises()

    const registered = adapterStore.getAdaptersForRouteKey('NOTIFICATIONS')
    await expect(registered.resourceManager!.refresh('TODAY_TASKS')).rejects.toThrow(
      '不支持刷新的资源: TODAY_TASKS',
    )
    await expect(registered.resourceManager!.refresh('ROADMAP')).rejects.toThrow(
      '不支持刷新的资源: ROADMAP',
    )
  })

  it('propagates API failures to the adapter caller while rendering error state in UI', async () => {
    const adapterStore = useUiActionAdapterStore()
    const wrapper = mount(NotificationsView, {
      global: {
        plugins: [pinia, router],
      },
    })
    await flushPromises()

    const registered = adapterStore.getAdaptersForRouteKey('NOTIFICATIONS')
    const failureError = new Error('网络请求异常')
    vi.mocked(agentOpsApi.listNotifications).mockRejectedValueOnce(failureError)

    await expect(registered.resourceManager!.refresh('NOTIFICATIONS')).rejects.toThrow('网络请求异常')
    await flushPromises()

    // ErrorState is rendered with error message (described via describeError)
    expect(wrapper.findComponent({ name: 'ErrorState' }).exists()).toBe(true)
    expect(wrapper.text()).toContain('加载失败')
  })

  it('handles stale or canceled loads: superseded requests reject and do not update UI or report success', async () => {
    const adapterStore = useUiActionAdapterStore()
    const wrapper = mount(NotificationsView, {
      global: {
        plugins: [pinia, router],
      },
    })
    await flushPromises()

    const registered = adapterStore.getAdaptersForRouteKey('NOTIFICATIONS')

    const firstDeferred = deferred<Notification[]>()
    const secondDeferred = deferred<Notification[]>()

    vi.mocked(agentOpsApi.listNotifications)
      .mockReturnValueOnce(firstDeferred.promise)
      .mockReturnValueOnce(secondDeferred.promise)

    const firstRefresh = registered.resourceManager!.refresh('NOTIFICATIONS')
    const secondRefresh = registered.resourceManager!.refresh('NOTIFICATIONS')

    const secondNotifs: Notification[] = [
      {
        id: 'notif-2',
        type: 'MATERIAL_READY',
        title: '资料就绪通知',
        content: '新资料提取完毕',
        read: false,
        createdAt: '2026-09-16T09:00:00Z',
        readAt: null,
      },
    ]
    secondDeferred.resolve(secondNotifs)
    const secondResult = await secondRefresh
    expect(secondResult).toBe(true)

    // First request is superseded
    const firstNotifs: Notification[] = [
      {
        id: 'notif-stale',
        type: 'QUIZ_READY',
        title: '过期测验通知',
        content: '过期内容',
        read: false,
        createdAt: '2026-09-16T07:00:00Z',
        readAt: null,
      },
    ]
    firstDeferred.resolve(firstNotifs)
    await expect(firstRefresh).rejects.toThrow(/stale|canceled|superseded|已取消/i)

    await flushPromises()
    expect(wrapper.text()).toContain('资料就绪通知')
    expect(wrapper.text()).not.toContain('过期测验通知')
  })

  it('does not taint concurrent or subsequent refresh results with previous errors', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(NotificationsView, {
      global: {
        plugins: [pinia, router],
      },
    })
    await flushPromises()

    const registered = adapterStore.getAdaptersForRouteKey('NOTIFICATIONS')

    const reqADeferred = deferred<Notification[]>()
    const reqBDeferred = deferred<Notification[]>()

    vi.mocked(agentOpsApi.listNotifications)
      .mockReturnValueOnce(reqADeferred.promise)
      .mockReturnValueOnce(reqBDeferred.promise)

    const refreshA = registered.resourceManager!.refresh('NOTIFICATIONS')
    const refreshB = registered.resourceManager!.refresh('NOTIFICATIONS')

    reqADeferred.reject(new Error('A failed'))
    await expect(refreshA).rejects.toThrow()

    reqBDeferred.resolve([])
    const resB = await refreshB
    expect(resB).toBe(true)
  })

  it('rejects refresh after component unmount and prevents UI mutation', async () => {
    const adapterStore = useUiActionAdapterStore()
    const wrapper = mount(NotificationsView, {
      global: {
        plugins: [pinia, router],
      },
    })
    await flushPromises()

    const rm = adapterStore.getAdaptersForRouteKey('NOTIFICATIONS').resourceManager!
    wrapper.unmount()

    await expect(rm.refresh('NOTIFICATIONS')).rejects.toThrow(/卸载|inactive/i)
  })
})
