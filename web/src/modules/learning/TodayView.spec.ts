import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'

import TodayView from './TodayView.vue'
import { roadmapApi } from '@/services/roadmap'
import { learningApi } from '@/services/current/learning'
import { useUiActionAdapterStore } from '@/stores/uiActionAdapter'
import { dispatchUiAction } from '@/modules/assistant/uiActionDispatcher'
import type { RoadmapSchedule } from '@/types/roadmap'
import type { LearningTask } from '@/types/api'

vi.mock('@/services/roadmap', () => ({
  roadmapApi: {
    getSchedule: vi.fn(),
    getNodeQuiz: vi.fn(),
    retryNodeQuiz: vi.fn(),
  },
}))

vi.mock('@/services/current/learning', () => ({
  learningApi: {
    listTasks: vi.fn(),
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

describe('TodayView TODAY UI action adapter', () => {
  let router: ReturnType<typeof createRouter>

  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.resetAllMocks()

    vi.mocked(learningApi.listTasks).mockResolvedValue([])
    vi.mocked(roadmapApi.getSchedule).mockResolvedValue({
      scheduleId: 'sched-1',
      timeZone: 'Asia/Shanghai',
      dailyCapacityMinutes: 60,
      weekendsEnabled: true,
      days: [],
    })
    vi.mocked(roadmapApi.getNodeQuiz).mockResolvedValue({
      nodeId: 'node-1',
      status: 'READY',
      quizId: 'quiz-1',
      latestAttemptId: null,
      generation: {
        jobId: 'job-1',
        purpose: 'NODE',
        status: 'COMPLETED',
        retrySequence: 0,
        attemptCount: 1,
        quizId: 'quiz-1',
        lastError: null,
        leaseUntil: null,
        updatedAt: new Date().toISOString(),
      },
    })

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/today', name: 'today', component: TodayView },
        { path: '/roadmap', name: 'roadmap', component: { template: '<div />' } },
        { path: '/roadmap/nodes/:id', name: 'roadmap-node', component: { template: '<div />' } },
        { path: '/quizzes/:id', name: 'quiz', component: { template: '<div />' } },
        { path: '/attempts/:id', name: 'attempt', component: { template: '<div />' } },
        { path: '/plans', component: { template: '<div />' } },
        { path: '/agent/tasks', component: { template: '<div />' } },
      ],
    })
    await router.push('/today')
    await router.isReady()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('registers the real TODAY adapter on mount and unregisters on unmount', async () => {
    const adapterStore = useUiActionAdapterStore()
    expect(adapterStore.getAdaptersForRouteKey('TODAY').resourceManager).toBeUndefined()

    const wrapper = mount(TodayView, {
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    const registered = adapterStore.getAdaptersForRouteKey('TODAY')
    expect(registered.resourceManager).toBeDefined()
    expect(typeof registered.resourceManager?.refresh).toBe('function')

    wrapper.unmount()
    expect(adapterStore.getAdaptersForRouteKey('TODAY').resourceManager).toBeUndefined()
  })

  it('awaits real complete reload for the currently selected date and updates view', async () => {
    const adapterStore = useUiActionAdapterStore()
    const wrapper = mount(TodayView, {
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    // Change the selected date in the view
    const dateInput = wrapper.get('input[type="date"]')
    await dateInput.setValue('2026-10-25')
    await flushPromises()

    expect(learningApi.listTasks).toHaveBeenCalledWith('2026-10-25')
    expect(roadmapApi.getSchedule).toHaveBeenCalledWith('2026-10-25', '2026-10-25')

    // Mock new task data for the selected date
    const updatedTasks: LearningTask[] = [
      {
        id: 'task-101',
        title: '深入理解 JVM 垃圾回收机制',
        planId: 'plan-1',
        scheduledDate: '2026-10-25',
        estimatedMinutes: 45,
        actualMinutes: 0,
        status: 'TODO',
        version: 1,
        completedAt: null,
        taskKind: 'LEARNING',
        knowledgePoint: 'JVM GC',
        sourceAttemptId: null,
      },
    ]
    vi.mocked(learningApi.listTasks).mockResolvedValueOnce(updatedTasks)
    const updatedSchedule: RoadmapSchedule = {
      scheduleId: 'sched-2',
      timeZone: 'Asia/Shanghai',
      dailyCapacityMinutes: 60,
      weekendsEnabled: true,
      days: [
        {
          date: '2026-10-25',
          plannedMinutes: 45,
          items: [
            {
              id: 'item-101',
              nodeId: 'node-jvm',
              nodeCode: 'jvm-gc',
              title: 'JVM 内存与 GC',
              plannedMinutes: 45,
              status: 'STARTED',
            },
          ],
        },
      ],
    }
    vi.mocked(roadmapApi.getSchedule).mockResolvedValueOnce(updatedSchedule)

    const receipt = await dispatchUiAction(
      {
        actionId: 'act-today-refresh-tasks',
        type: 'REFRESH_RESOURCE',
        routeKey: 'TODAY',
        reason: '刷新今日任务与路线排期',
        params: {
          resourceKey: 'TODAY_TASKS',
        },
      },
      {
        router,
        ...adapterStore.getAdaptersForRouteKey('TODAY'),
      },
    )

    expect(receipt.status).toBe('SUCCEEDED')
    await flushPromises()

    expect(learningApi.listTasks).toHaveBeenLastCalledWith('2026-10-25')
    expect(roadmapApi.getSchedule).toHaveBeenLastCalledWith('2026-10-25', '2026-10-25')
    expect(wrapper.text()).toContain('深入理解 JVM 垃圾回收机制')
    expect(wrapper.text()).toContain('JVM 内存与 GC')
  })

  it('rejects unsupported resourceKey with an error', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(TodayView, {
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    const registered = adapterStore.getAdaptersForRouteKey('TODAY')
    await expect(registered.resourceManager!.refresh('ROADMAP')).rejects.toThrow(
      '不支持刷新的资源: ROADMAP',
    )
    await expect(registered.resourceManager!.refresh('LEARNING_GOALS')).rejects.toThrow(
      '不支持刷新的资源: LEARNING_GOALS',
    )
  })

  it('propagates API failures for learningApi.listTasks and roadmapApi.getSchedule', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(TodayView, {
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    const registered = adapterStore.getAdaptersForRouteKey('TODAY')

    // 1. Task failure propagates
    vi.mocked(learningApi.listTasks).mockRejectedValueOnce(new Error('任务加载失败'))
    await expect(registered.resourceManager!.refresh('TODAY_TASKS')).rejects.toThrow(
      '任务加载失败',
    )

    // 2. Schedule failure propagates (non-404)
    vi.mocked(learningApi.listTasks).mockResolvedValueOnce([])
    vi.mocked(roadmapApi.getSchedule).mockRejectedValueOnce(new Error('路线加载超时'))
    await expect(registered.resourceManager!.refresh('TODAY_TASKS')).rejects.toThrow(
      '路线加载超时',
    )
  })

  it('handles stale or canceled loads: stale loads do not report success and overlapping loads do not share mutable last-error state', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(TodayView, {
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    const registered = adapterStore.getAdaptersForRouteKey('TODAY')

    // Create two concurrent loads
    const firstDeferredTask = deferred<LearningTask[]>()
    const secondDeferredTask = deferred<LearningTask[]>()

    vi.mocked(learningApi.listTasks)
      .mockReturnValueOnce(firstDeferredTask.promise)
      .mockReturnValueOnce(secondDeferredTask.promise)

    // Trigger first refresh invocation
    const firstRefresh = registered.resourceManager!.refresh('TODAY_TASKS')

    // Trigger second refresh invocation, superseding the first
    const secondRefresh = registered.resourceManager!.refresh('TODAY_TASKS')

    // Resolve second one first with success
    secondDeferredTask.resolve([])
    const secondResult = await secondRefresh
    expect(secondResult).toBe(true)

    // First one was superseded/canceled - it must NOT report success!
    // It should reject with a canceled/stale error or rethrow
    firstDeferredTask.resolve([])
    await expect(firstRefresh).rejects.toThrow(/stale|canceled|superseded|已取消/i)

    // Verify separate per-invocation error tracking:
    // If request A fails and request B succeeds concurrently, request B is not tainted by request A's error
    const reqADeferred = deferred<LearningTask[]>()
    const reqBDeferred = deferred<LearningTask[]>()

    vi.mocked(learningApi.listTasks)
      .mockReturnValueOnce(reqADeferred.promise)
      .mockReturnValueOnce(reqBDeferred.promise)

    const refreshA = registered.resourceManager!.refresh('TODAY_TASKS')
    const refreshB = registered.resourceManager!.refresh('TODAY_TASKS')

    // A fails
    reqADeferred.reject(new Error('Error in A'))
    await expect(refreshA).rejects.toThrow()

    // B succeeds
    reqBDeferred.resolve([])
    const resB = await refreshB
    expect(resB).toBe(true)
  })

  it('rejects refresh after component unmount', async () => {
    const adapterStore = useUiActionAdapterStore()
    const wrapper = mount(TodayView, {
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    const rm = adapterStore.getAdaptersForRouteKey('TODAY').resourceManager!
    wrapper.unmount()

    // Invoking refresh on an unmounted component should reject
    await expect(rm.refresh('TODAY_TASKS')).rejects.toThrow(/unmount|inactive|已卸载/i)
  })
})
