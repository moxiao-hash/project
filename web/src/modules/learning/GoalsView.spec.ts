import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import GoalsView from './GoalsView.vue'
import { learningApi } from '@/services/current/learning'
import { useUiActionAdapterStore } from '@/stores/uiActionAdapter'
import { dispatchUiAction } from '@/modules/assistant/uiActionDispatcher'

vi.mock('@/services/current/learning', () => ({
  learningApi: {
    listGoals: vi.fn(),
    createGoal: vi.fn(),
    updateGoal: vi.fn(),
  },
}))

describe('GoalsView LEARNING_GOALS UI action adapter', () => {
  let router: ReturnType<typeof createRouter>
  let container: HTMLDivElement

  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(learningApi.listGoals).mockResolvedValue([])

    container = document.createElement('div')
    document.body.appendChild(container)

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/goals', name: 'goals', component: GoalsView },
        { path: '/plans', name: 'plans', component: { template: '<div>plans</div>' } },
        { path: '/agent/plan', name: 'agent-plan', component: { template: '<div>agent-plan</div>' } },
      ],
    })
    await router.push('/goals')
    await router.isReady()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('registers the real LEARNING_GOALS adapter on mount and unregisters on unmount', async () => {
    const adapterStore = useUiActionAdapterStore()
    expect(adapterStore.getAdaptersForRouteKey('LEARNING_GOALS').modalManager).toBeUndefined()

    const wrapper = mount(GoalsView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    const registered = adapterStore.getAdaptersForRouteKey('LEARNING_GOALS')
    expect(registered.modalManager).toBeDefined()
    expect(registered.formDraftStore).toBeDefined()
    expect(registered.resourceManager).toBeDefined()

    wrapper.unmount()
    expect(adapterStore.getAdaptersForRouteKey('LEARNING_GOALS').modalManager).toBeUndefined()
  })

  it('handles OPEN_MODAL for CREATE_GOAL: opens create dialog and resolves true in new-item mode', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(GoalsView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    // Dialog should initially be closed
    expect(document.body.querySelector('.dialog')).toBeNull()

    const receipt = await dispatchUiAction(
      {
        actionId: 'act-goals-open-create-modal',
        type: 'OPEN_MODAL',
        routeKey: 'LEARNING_GOALS',
        reason: '打开新建目标弹窗',
        params: {
          modalKey: 'CREATE_GOAL',
        },
      },
      {
        router,
        ...adapterStore.getAdaptersForRouteKey('LEARNING_GOALS'),
      },
    )

    await flushPromises()
    expect(receipt.status).toBe('SUCCEEDED')
    expect(receipt.error).toBeNull()

    const dialog = document.body.querySelector('.dialog')
    expect(dialog).not.toBeNull()
    expect(dialog?.querySelector('h3')?.textContent).toBe('新建目标')

    // Inputs should be initialized to default empty state
    const titleInput = dialog?.querySelector('input[placeholder*="Spring Boot"]') as HTMLInputElement
    expect(titleInput.value).toBe('')
  })

  it('handles PREFILL_FORM for GOAL_FORM: opens dialog and populates form fields without calling save API', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(GoalsView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    const receipt = await dispatchUiAction(
      {
        actionId: 'act-goals-prefill-form',
        type: 'PREFILL_FORM',
        routeKey: 'LEARNING_GOALS',
        reason: '预填新建目标表单',
        params: {
          formKey: 'GOAL_FORM',
          title: '30 天掌握 Vue 3 核心与生态',
          targetDate: '2026-10-15',
          weeklyStudyHours: '15',
        },
      },
      {
        router,
        ...adapterStore.getAdaptersForRouteKey('LEARNING_GOALS'),
      },
    )

    await flushPromises()
    expect(receipt.status).toBe('SUCCEEDED')
    expect(receipt.error).toBeNull()

    // Modal dialog is opened in new-item mode
    const dialog = document.body.querySelector('.dialog')
    expect(dialog).not.toBeNull()
    expect(dialog?.querySelector('h3')?.textContent).toBe('新建目标')

    const titleInput = dialog?.querySelector('input[placeholder*="Spring Boot"]') as HTMLInputElement
    const dateInput = dialog?.querySelector('input[type="date"]') as HTMLInputElement
    const hoursInput = dialog?.querySelector('input[type="number"]') as HTMLInputElement

    expect(titleInput.value).toBe('30 天掌握 Vue 3 核心与生态')
    expect(dateInput.value).toBe('2026-10-15')
    expect(hoursInput.value).toBe('15')

    // Critical check: NO save/create/update API should be called
    expect(learningApi.createGoal).not.toHaveBeenCalled()
    expect(learningApi.updateGoal).not.toHaveBeenCalled()
  })

  it('handles PREFILL_FORM with optional fields omitted: populates title and defaults without calling save API', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(GoalsView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    const receipt = await dispatchUiAction(
      {
        actionId: 'act-goals-prefill-optional-omitted',
        type: 'PREFILL_FORM',
        routeKey: 'LEARNING_GOALS',
        reason: '预填部分目标表单',
        params: {
          formKey: 'GOAL_FORM',
          title: '精通 TypeScript 高级类型',
        },
      },
      {
        router,
        ...adapterStore.getAdaptersForRouteKey('LEARNING_GOALS'),
      },
    )

    await flushPromises()
    expect(receipt.status).toBe('SUCCEEDED')

    const dialog = document.body.querySelector('.dialog')
    expect(dialog).not.toBeNull()

    const titleInput = dialog?.querySelector('input[placeholder*="Spring Boot"]') as HTMLInputElement
    const dateInput = dialog?.querySelector('input[type="date"]') as HTMLInputElement
    const hoursInput = dialog?.querySelector('input[type="number"]') as HTMLInputElement

    expect(titleInput.value).toBe('精通 TypeScript 高级类型')
    expect(dateInput.value).toBe('')
    expect(hoursInput.value).toBe('10') // default value preserved

    expect(learningApi.createGoal).not.toHaveBeenCalled()
  })

  it('rejects PREFILL_FORM when create form contains non-empty unsaved user edits and preserves every existing value', async () => {
    const adapterStore = useUiActionAdapterStore()
    const wrapper = mount(GoalsView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    // 1. User opens create dialog and enters partial edits
    const createButton = wrapper.find('button.btn-primary')
    await createButton.trigger('click')
    await flushPromises()

    const dialog = document.body.querySelector('.dialog')
    expect(dialog).not.toBeNull()

    const titleInput = dialog?.querySelector('input[placeholder*="Spring Boot"]') as HTMLInputElement
    titleInput.value = '用户正在手写的未保存目标'
    titleInput.dispatchEvent(new Event('input'))

    const dateInput = dialog?.querySelector('input[type="date"]') as HTMLInputElement
    dateInput.value = '2026-11-20'
    dateInput.dispatchEvent(new Event('input'))

    const hoursInput = dialog?.querySelector('input[type="number"]') as HTMLInputElement
    hoursInput.value = '25'
    hoursInput.dispatchEvent(new Event('input'))

    await flushPromises()

    // 2. Assistant attempts PREFILL_FORM while unsaved edits exist
    const action = {
      actionId: 'act-goal-unsaved-reject',
      type: 'PREFILL_FORM' as const,
      routeKey: 'LEARNING_GOALS',
      reason: '尝试覆盖未保存的目标表单',
      params: {
        formKey: 'GOAL_FORM',
        title: '试图覆盖的 AI 目标',
        targetDate: '2026-12-31',
        weeklyStudyHours: '5',
      },
    }

    await expect(
      dispatchUiAction(action, {
        router,
        ...adapterStore.getAdaptersForRouteKey('LEARNING_GOALS'),
      }),
    ).rejects.toThrow('目标表单存在未保存的修改')

    await flushPromises()

    // Existing user inputs MUST be completely preserved
    expect(titleInput.value).toBe('用户正在手写的未保存目标')
    expect(dateInput.value).toBe('2026-11-20')
    expect(hoursInput.value).toBe('25')

    // No API calls
    expect(learningApi.createGoal).not.toHaveBeenCalled()
    expect(learningApi.updateGoal).not.toHaveBeenCalled()
  })

  it('refreshes goals list via REFRESH_RESOURCE without modal or form side-effects', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(GoalsView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    expect(learningApi.listGoals).toHaveBeenCalledTimes(1)

    const receipt = await dispatchUiAction(
      {
        actionId: 'act-goals-refresh-resource',
        type: 'REFRESH_RESOURCE',
        routeKey: 'LEARNING_GOALS',
        reason: '刷新目标列表',
        params: {
          resourceKey: 'LEARNING_GOALS',
        },
      },
      {
        router,
        ...adapterStore.getAdaptersForRouteKey('LEARNING_GOALS'),
      },
    )

    expect(receipt.status).toBe('SUCCEEDED')
    expect(learningApi.listGoals).toHaveBeenCalledTimes(2)
  })

  it('rejects REFRESH_RESOURCE and propagates learningApi.listGoals failure when refresh fails', async () => {
    const adapterStore = useUiActionAdapterStore()
    const wrapper = mount(GoalsView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    expect(learningApi.listGoals).toHaveBeenCalledTimes(1)

    vi.mocked(learningApi.listGoals).mockRejectedValueOnce(new Error('目标列表同步失败'))

    const action = {
      actionId: 'act-goal-refresh-fail',
      type: 'REFRESH_RESOURCE' as const,
      routeKey: 'LEARNING_GOALS',
      reason: '刷新失败测试',
      params: {
        resourceKey: 'LEARNING_GOALS',
      },
    }

    await expect(
      dispatchUiAction(action, {
        router,
        ...adapterStore.getAdaptersForRouteKey('LEARNING_GOALS'),
      }),
    ).rejects.toThrow('目标列表同步失败')

    await flushPromises()
    expect(wrapper.text()).toContain('发生未知错误')
  })
})
