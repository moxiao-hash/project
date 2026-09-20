import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import MaterialsView from './MaterialsView.vue'
import { materialsApi } from '@/services/current/materials'
import { useUiActionAdapterStore } from '@/stores/uiActionAdapter'
import { dispatchUiAction } from '@/modules/assistant/uiActionDispatcher'

vi.mock('@/services/current/materials', () => ({
  materialsApi: {
    list: vi.fn(),
    createText: vi.fn(),
    createWeb: vi.fn(),
    uploadFile: vi.fn(),
  },
}))

describe('MaterialsView MATERIALS UI action adapter', () => {
  let router: ReturnType<typeof createRouter>
  let container: HTMLDivElement

  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(materialsApi.list).mockResolvedValue([])

    container = document.createElement('div')
    document.body.appendChild(container)

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/materials', name: 'materials', component: MaterialsView },
        { path: '/materials/:id', name: 'material-detail', component: { template: '<div>detail</div>' } },
      ],
    })
    await router.push('/materials')
    await router.isReady()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('registers the real MATERIALS adapter on mount and unregisters on unmount', async () => {
    const adapterStore = useUiActionAdapterStore()
    expect(adapterStore.getAdaptersForRouteKey('MATERIALS').modalManager).toBeUndefined()

    const wrapper = mount(MaterialsView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    const registered = adapterStore.getAdaptersForRouteKey('MATERIALS')
    expect(registered.modalManager).toBeDefined()
    expect(registered.formDraftStore).toBeDefined()
    expect(registered.resourceManager).toBeUndefined()

    wrapper.unmount()
    expect(adapterStore.getAdaptersForRouteKey('MATERIALS').modalManager).toBeUndefined()
  })

  it('handles OPEN_MODAL for IMPORT_MATERIAL: opens import panel and resolves true only after open', async () => {
    const adapterStore = useUiActionAdapterStore()
    const wrapper = mount(MaterialsView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    // Import panel should initially be closed
    expect(wrapper.find('.import-tabs').exists()).toBe(false)

    const receipt = await dispatchUiAction(
      {
        actionId: 'act-materials-open-import-modal',
        type: 'OPEN_MODAL',
        routeKey: 'MATERIALS',
        reason: '打开资料导入弹窗',
        params: {
          modalKey: 'IMPORT_MATERIAL',
        },
      },
      {
        router,
        ...adapterStore.getAdaptersForRouteKey('MATERIALS'),
      },
    )

    await flushPromises()
    expect(receipt.status).toBe('SUCCEEDED')
    expect(receipt.error).toBeNull()

    // Import panel is now open and observable
    expect(wrapper.find('.import-tabs').exists()).toBe(true)
    const titleInput = wrapper.find('input[placeholder="资料标题"]').element as HTMLInputElement
    expect(titleInput.value).toBe('')
  })

  it('handles PREFILL_FORM for MATERIAL_FORM: opens import UI and populates title and content without calling create or upload API', async () => {
    const adapterStore = useUiActionAdapterStore()
    const wrapper = mount(MaterialsView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    const receipt = await dispatchUiAction(
      {
        actionId: 'act-materials-prefill-form',
        type: 'PREFILL_FORM',
        routeKey: 'MATERIALS',
        reason: '预填资料表单',
        params: {
          formKey: 'MATERIAL_FORM',
          title: '深入理解 TypeScript 类型系统',
          content: '这是一份关于 TypeScript 高级类型的学习资料内容...',
        },
      },
      {
        router,
        ...adapterStore.getAdaptersForRouteKey('MATERIALS'),
      },
    )

    await flushPromises()
    expect(receipt.status).toBe('SUCCEEDED')
    expect(receipt.error).toBeNull()

    // Import panel should be opened
    expect(wrapper.find('.import-tabs').exists()).toBe(true)

    const titleInput = wrapper.find('input[placeholder="资料标题"]').element as HTMLInputElement
    const contentTextarea = wrapper.find('textarea[placeholder="粘贴文本内容…"]').element as HTMLTextAreaElement

    expect(titleInput.value).toBe('深入理解 TypeScript 类型系统')
    expect(contentTextarea.value).toBe('这是一份关于 TypeScript 高级类型的学习资料内容...')

    // Critical check: no API calls should be made
    expect(materialsApi.createText).not.toHaveBeenCalled()
    expect(materialsApi.createWeb).not.toHaveBeenCalled()
    expect(materialsApi.uploadFile).not.toHaveBeenCalled()
  })

  it('handles PREFILL_FORM with optional content omitted: populates title and leaves content empty without calling API', async () => {
    const adapterStore = useUiActionAdapterStore()
    const wrapper = mount(MaterialsView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    const receipt = await dispatchUiAction(
      {
        actionId: 'act-materials-prefill-optional-omitted',
        type: 'PREFILL_FORM',
        routeKey: 'MATERIALS',
        reason: '预填标题资料草稿',
        params: {
          formKey: 'MATERIAL_FORM',
          title: '仅包含标题的资料草稿',
        },
      },
      {
        router,
        ...adapterStore.getAdaptersForRouteKey('MATERIALS'),
      },
    )

    await flushPromises()
    expect(receipt.status).toBe('SUCCEEDED')

    const titleInput = wrapper.find('input[placeholder="资料标题"]').element as HTMLInputElement
    const contentTextarea = wrapper.find('textarea[placeholder="粘贴文本内容…"]').element as HTMLTextAreaElement

    expect(titleInput.value).toBe('仅包含标题的资料草稿')
    expect(contentTextarea.value).toBe('')

    expect(materialsApi.createText).not.toHaveBeenCalled()
    expect(materialsApi.createWeb).not.toHaveBeenCalled()
    expect(materialsApi.uploadFile).not.toHaveBeenCalled()
  })

  it('rejects PREFILL_FORM when import form contains non-empty unsaved user edits and preserves every existing value', async () => {
    const adapterStore = useUiActionAdapterStore()
    const wrapper = mount(MaterialsView, {
      attachTo: container,
      global: {
        plugins: [router],
      },
    })
    await flushPromises()

    // 1. User opens import panel and inputs draft content
    const toggleButton = wrapper.find('button.btn-primary')
    await toggleButton.trigger('click')
    await flushPromises()

    const titleInput = wrapper.find('input[placeholder="资料标题"]').element as HTMLInputElement
    titleInput.value = '用户手写的资料草稿'
    titleInput.dispatchEvent(new Event('input'))

    const contentTextarea = wrapper.find('textarea[placeholder="粘贴文本内容…"]').element as HTMLTextAreaElement
    contentTextarea.value = '用户手写的正文内容，绝不能被丢弃'
    contentTextarea.dispatchEvent(new Event('input'))

    await flushPromises()

    // 2. Assistant attempts PREFILL_FORM while unsaved edits exist
    const action = {
      actionId: 'act-material-unsaved-reject',
      type: 'PREFILL_FORM' as const,
      routeKey: 'MATERIALS',
      reason: '尝试覆盖用户草稿',
      params: {
        formKey: 'MATERIAL_FORM',
        title: '试图覆盖的 AI 标题',
        content: '试图覆盖的 AI 内容',
      },
    }

    await expect(
      dispatchUiAction(action, {
        router,
        ...adapterStore.getAdaptersForRouteKey('MATERIALS'),
      }),
    ).rejects.toThrow('资料表单存在未保存的修改')

    await flushPromises()

    // Existing user inputs MUST be completely preserved
    expect(titleInput.value).toBe('用户手写的资料草稿')
    expect(contentTextarea.value).toBe('用户手写的正文内容，绝不能被丢弃')

    // No API calls
    expect(materialsApi.createText).not.toHaveBeenCalled()
    expect(materialsApi.createWeb).not.toHaveBeenCalled()
    expect(materialsApi.uploadFile).not.toHaveBeenCalled()
  })
})
