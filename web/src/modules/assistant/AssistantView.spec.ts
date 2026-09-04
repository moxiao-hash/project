import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AssistantView from './AssistantView.vue'
import { assistantApi } from '@/services/current/assistant'
import type { AssistantConversation } from '@/types/assistant'

const push = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ name: 'assistant', params: {}, query: {} }),
}))

vi.mock('@/services/current/assistant', () => ({
  assistantApi: {
    createConversation: vi.fn(),
    getConversation: vi.fn(),
    sendMessage: vi.fn(),
    confirmAction: vi.fn(),
    rejectAction: vi.fn(),
  },
}))

function snapshot(overrides: Partial<AssistantConversation> = {}): AssistantConversation {
  return {
    conversationId: 'conversation-1',
    status: 'COMPLETED',
    reply: '已为你打开错题集。',
    messages: [{ role: 'assistant', content: '已为你打开错题集。' }],
    intent: 'NAVIGATION',
    toolSteps: [{ toolName: 'navigation.resolve', status: 'SUCCEEDED', summary: '已解析页面' }],
    pendingAction: null,
    uiActions: [{ type: 'NAVIGATE', routeKey: 'WRONG_QUESTIONS', params: {}, reason: '查看错题' }],
    warnings: [],
    citations: [],
    modelName: 'deepseek-v4-flash',
    ...overrides,
  }
}

describe('AssistantView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionStorage.clear()
    vi.mocked(assistantApi.createConversation).mockResolvedValue(snapshot({ messages: [] }))
    vi.mocked(assistantApi.sendMessage).mockResolvedValue(snapshot())
  })

  it('creates one conversation, shows public tool steps and dispatches navigation', async () => {
    const wrapper = mount(AssistantView, {
      global: { plugins: [createPinia()], stubs: { Teleport: true } },
    })
    await flushPromises()
    await wrapper.get('textarea').setValue('打开我的错题集')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(assistantApi.sendMessage).toHaveBeenCalledWith(
      'conversation-1',
      expect.objectContaining({ message: '打开我的错题集' }),
    )
    expect(wrapper.text()).toContain('已解析页面')
    expect(wrapper.text()).toContain('deepseek-v4-flash')
    expect(push).toHaveBeenCalledWith({ name: 'wrong-questions' })
  })

  it('renders a confirmation card and only confirms through the dedicated api', async () => {
    vi.mocked(assistantApi.sendMessage).mockResolvedValue(snapshot({
      status: 'WAITING_CONFIRMATION',
      pendingAction: {
        actionId: 'action-1', executionId: 'execution-1', toolName: 'learning.task.update',
        riskLevel: 'HIGH', status: 'WAITING_CONFIRMATION', summary: '完成学习任务',
        arguments: {}, expiresAt: '2026-09-04T13:00:00Z',
      },
      uiActions: [],
    }))
    vi.mocked(assistantApi.confirmAction).mockResolvedValue(snapshot({ pendingAction: null }))
    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    await wrapper.get('textarea').setValue('完成任务')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('需要你的确认')
    await wrapper.get('[data-testid="confirm-action"]').trigger('click')
    await flushPromises()
    expect(assistantApi.confirmAction).toHaveBeenCalledWith('conversation-1', 'action-1')
  })

  it('shows grounded citations but never renders an unsafe source link', async () => {
    vi.mocked(assistantApi.createConversation).mockResolvedValue(snapshot({
      citations: [{
        sourceType: 'WEB', title: 'Redis 官方文档', snippet: 'Redis data types',
        url: 'javascript:alert(1)',
      }],
    }))
    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Redis 官方文档')
    expect(wrapper.find('.citation-card a').exists()).toBe(false)
  })
})
