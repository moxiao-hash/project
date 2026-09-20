import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import PlansView from './PlansView.vue'
import { learningApi } from '@/services/current/learning'
import { useUiActionAdapterStore } from '@/stores/uiActionAdapter'
import { dispatchUiAction } from '@/modules/assistant/uiActionDispatcher'

vi.mock('@/services/current/learning', () => ({
  learningApi: {
    listPlans: vi.fn(),
    listGoals: vi.fn(),
    createPlan: vi.fn(),
    confirmPlan: vi.fn(),
  },
}))

describe('PlansView LEARNING_PLANS UI action adapter', () => {
  let router: ReturnType<typeof createRouter>
  let container: HTMLDivElement

  const mockGoals = [
    { id: 'g-1', title: 'Goal 1 - Spring Boot', targetDate: '2026-10-01', weeklyStudyHours: 10, status: 'IN_PROGRESS' },
    { id: 'g-2', title: 'Goal 2 - Vue 3', targetDate: '2026-11-01', weeklyStudyHours: 15, status: 'IN_PROGRESS' },
  ]

  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(learningApi.listPlans).mockResolvedValue([])
    vi.mocked(learningApi.listGoals).mockResolvedValue(mockGoals as any)

    container = document.createElement('div')
    document.body.appendChild(container)

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/plans', name: 'plans', component: PlansView },
        { path: '/goals', name: 'goals', component: { template: '<div>goals</div>' } },
        { path: '/agent/plan', name: 'agent-plan', component: { template: '<div>agent-plan</div>' } },
      ],
    })
    await router.push('/plans')
    await router.isReady()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('registers the real LEARNING_PLANS adapter on mount and unregisters on unmount', async () => {
    const adapterStore = useUiActionAdapterStore()
    expect(adapterStore.getAdaptersForRouteKey('LEARNING_PLANS').modalManager).toBeUndefined()

    const wrapper = mount(PlansView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    const registered = adapterStore.getAdaptersForRouteKey('LEARNING_PLANS')
    expect(registered.modalManager).toBeDefined()
    expect(registered.formDraftStore).toBeDefined()
    expect(registered.focusManager).toBeDefined()
    expect(registered.resourceManager).toBeDefined()

    wrapper.unmount()
    expect(adapterStore.getAdaptersForRouteKey('LEARNING_PLANS').modalManager).toBeUndefined()
  })

  it('handles OPEN_MODAL for CREATE_PLAN: opens create dialog and resolves true in new-item mode', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(PlansView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    expect(document.body.querySelector('.dialog')).toBeNull()

    const receipt = await dispatchUiAction(
      {
        actionId: 'act-plans-open-create-modal',
        reason: 'User wants to create a new plan',
        type: 'OPEN_MODAL',
        routeKey: 'LEARNING_PLANS',
        params: {
          modalKey: 'CREATE_PLAN',
        },
      },
      {
        router,
        ...adapterStore.getAdaptersForRouteKey('LEARNING_PLANS'),
      },
    )

    await flushPromises()
    expect(receipt.status).toBe('SUCCEEDED')
    expect(receipt.error).toBeNull()

    const dialog = document.body.querySelector('.dialog')
    expect(dialog).not.toBeNull()
    expect(dialog?.querySelector('h3')?.textContent).toBe('新建计划')

    const titleInput = dialog?.querySelector('input[placeholder*="第一阶段"]') as HTMLInputElement
    expect(titleInput.value).toBe('')
  })

  it('handles PREFILL_FORM for PLAN_FORM: populates title and optional fields without calling save/create API', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(PlansView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    const receipt = await dispatchUiAction(
      {
        actionId: 'act-plans-prefill-form',
        reason: 'Prefill initial learning plan draft',
        type: 'PREFILL_FORM',
        routeKey: 'LEARNING_PLANS',
        params: {
          formKey: 'PLAN_FORM',
          title: 'Spring Boot 基础入门四周速成',
          goalId: 'g-2',
          startDate: '2026-09-20',
          endDate: '2026-10-20',
        },
      },
      {
        router,
        ...adapterStore.getAdaptersForRouteKey('LEARNING_PLANS'),
      },
    )

    await flushPromises()
    expect(receipt.status).toBe('SUCCEEDED')
    expect(receipt.error).toBeNull()

    const dialog = document.body.querySelector('.dialog')
    expect(dialog).not.toBeNull()
    expect(dialog?.querySelector('h3')?.textContent).toBe('新建计划')

    const goalSelect = dialog?.querySelector('select') as HTMLSelectElement
    const titleInput = dialog?.querySelector('input[placeholder*="第一阶段"]') as HTMLInputElement
    const dateInputs = dialog?.querySelectorAll('input[type="date"]') as NodeListOf<HTMLInputElement>

    expect(titleInput.value).toBe('Spring Boot 基础入门四周速成')
    expect(goalSelect.value).toBe('g-2')
    expect(dateInputs[0].value).toBe('2026-09-20')
    expect(dateInputs[1].value).toBe('2026-10-20')

    // Never invokes createPlan API
    expect(learningApi.createPlan).not.toHaveBeenCalled()
  })

  it('rejects PREFILL_FORM with forbidden fields such as targetDate or dailyMinutes', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(PlansView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    await expect(
      dispatchUiAction(
        {
          actionId: 'act-plans-reject-forbidden-fields',
          reason: 'Attempt prefill with unsupported targetDate',
          type: 'PREFILL_FORM',
          routeKey: 'LEARNING_PLANS',
          params: {
            formKey: 'PLAN_FORM',
            title: '测试计划',
            targetDate: '2026-10-20',
          },
        },
        {
          router,
          ...adapterStore.getAdaptersForRouteKey('LEARNING_PLANS'),
        },
      ),
    ).rejects.toThrow('表单草稿包含未授权字段: targetDate')

    expect(learningApi.createPlan).not.toHaveBeenCalled()
  })

  it('preserves non-empty unsaved user edits: rejects PREFILL_FORM and leaves every value unchanged', async () => {
    const adapterStore = useUiActionAdapterStore()
    const wrapper = mount(PlansView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    // 1. User opens create dialog and enters edits manually
    const createButton = wrapper.find('button.btn-primary')
    await createButton.trigger('click')
    await flushPromises()

    const dialog = document.body.querySelector('.dialog')
    expect(dialog).not.toBeNull()

    const titleInput = dialog?.querySelector('input[placeholder*="第一阶段"]') as HTMLInputElement
    titleInput.value = '用户手写未保存的计划标题'
    titleInput.dispatchEvent(new Event('input'))

    const dateInputs = dialog?.querySelectorAll('input[type="date"]') as NodeListOf<HTMLInputElement>
    dateInputs[1].value = '2026-12-01'
    dateInputs[1].dispatchEvent(new Event('input'))

    await flushPromises()

    // 2. Assistant attempts PREFILL_FORM while unsaved edits exist
    await expect(
      dispatchUiAction(
        {
          actionId: 'act-plans-preserve-unsaved-edits',
          reason: 'Attempt overwrite user edits',
          type: 'PREFILL_FORM',
          routeKey: 'LEARNING_PLANS',
          params: {
            formKey: 'PLAN_FORM',
            title: '覆盖的 AI 标题',
            endDate: '2026-12-31',
          },
        },
        {
          router,
          ...adapterStore.getAdaptersForRouteKey('LEARNING_PLANS'),
        },
      ),
    ).rejects.toThrow('计划表单存在未保存的修改')

    await flushPromises()

    // Values remain unchanged
    expect(titleInput.value).toBe('用户手写未保存的计划标题')
    expect(dateInputs[1].value).toBe('2026-12-01')
    expect(learningApi.createPlan).not.toHaveBeenCalled()
  })

  it('handles FOCUS_ELEMENT PLAN_TITLE_INPUT: opens dialog if closed, focuses title input, and verifies focus', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(PlansView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    // Dialog closed initially
    expect(document.body.querySelector('.dialog')).toBeNull()

    const receipt = await dispatchUiAction(
      {
        actionId: 'act-plans-focus-title-input',
        reason: 'Focus title input',
        type: 'FOCUS_ELEMENT',
        routeKey: 'LEARNING_PLANS',
        params: {
          elementKey: 'PLAN_TITLE_INPUT',
        },
      },
      {
        router,
        ...adapterStore.getAdaptersForRouteKey('LEARNING_PLANS'),
      },
    )

    await flushPromises()
    expect(receipt.status).toBe('SUCCEEDED')

    const dialog = document.body.querySelector('.dialog')
    expect(dialog).not.toBeNull()

    const titleInput = dialog?.querySelector('input[placeholder*="第一阶段"]') as HTMLInputElement
    expect(document.activeElement).toBe(titleInput)
  })

  it('focusManager rejects or throws when focus cannot be fulfilled or unsupported elementKey provided', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(PlansView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    const adapters = adapterStore.getAdaptersForRouteKey('LEARNING_PLANS')
    await expect(adapters.focusManager?.focus('UNKNOWN_KEY' as any)).rejects.toThrow(
      '不支持的聚焦元素: UNKNOWN_KEY',
    )
  })

  it('handles REFRESH_RESOURCE LEARNING_PLANS: awaits load and propagates failures', async () => {
    const adapterStore = useUiActionAdapterStore()
    mount(PlansView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    expect(learningApi.listPlans).toHaveBeenCalledTimes(1)
    expect(learningApi.listGoals).toHaveBeenCalledTimes(1)

    const receipt = await dispatchUiAction(
      {
        actionId: 'act-plans-refresh-resource',
        reason: 'Refresh learning plans',
        type: 'REFRESH_RESOURCE',
        routeKey: 'LEARNING_PLANS',
        params: {
          resourceKey: 'LEARNING_PLANS',
        },
      },
      {
        router,
        ...adapterStore.getAdaptersForRouteKey('LEARNING_PLANS'),
      },
    )

    expect(receipt.status).toBe('SUCCEEDED')
    expect(learningApi.listPlans).toHaveBeenCalledTimes(2)

    // Verify error propagation
    vi.mocked(learningApi.listPlans).mockRejectedValueOnce(new Error('Network offline'))
    await expect(
      dispatchUiAction(
        {
          actionId: 'act-refresh-fail',
          reason: 'Refresh learning plans on network error',
          type: 'REFRESH_RESOURCE',
          routeKey: 'LEARNING_PLANS',
          params: {
            resourceKey: 'LEARNING_PLANS',
          },
        },
        {
          router,
          ...adapterStore.getAdaptersForRouteKey('LEARNING_PLANS'),
        },
      ),
    ).rejects.toThrow('Network offline')
  })
})
