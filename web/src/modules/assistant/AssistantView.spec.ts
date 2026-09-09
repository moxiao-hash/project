import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AssistantView from './AssistantView.vue'
import { assistantApi, type AssistantEvent, type AssistantEventStreamOptions } from '@/services/current/assistant'
import type { AssistantConversation } from '@/types/assistant'

const push = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ name: 'assistant', params: {}, query: {} }),
}))

let mockEventOptions: AssistantEventStreamOptions | null = null
const mockCloseStream = vi.fn()

vi.mock('@/services/current/assistant', () => ({
  assistantApi: {
    createConversation: vi.fn(),
    getConversation: vi.fn(),
    sendMessage: vi.fn(),
    confirmAction: vi.fn(),
    rejectAction: vi.fn(),
    cancelTurn: vi.fn(),
    subscribeEvents: vi.fn((_conversationId: string, options?: AssistantEventStreamOptions) => {
      mockEventOptions = options || null
      return {
        close: mockCloseStream,
        getLastEventId: () => 0,
      }
    }),
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

function emitStreamEvent(event: AssistantEvent) {
  if (mockEventOptions?.onEvent) {
    mockEventOptions.onEvent(event)
  }
}

describe('AssistantView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionStorage.clear()
    mockEventOptions = null
    vi.mocked(assistantApi.createConversation).mockResolvedValue(snapshot({ messages: [] }))
    vi.mocked(assistantApi.sendMessage).mockResolvedValue(snapshot())
    vi.mocked(assistantApi.cancelTurn).mockResolvedValue(snapshot({ reply: '已请求取消当前轮次。' }))
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

  it('组件挂载时建立 SSE 订阅，卸载时关闭连接', async () => {
    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    expect(assistantApi.subscribeEvents).toHaveBeenCalledWith(
      'conversation-1',
      expect.objectContaining({ onEvent: expect.any(Function) }),
    )

    wrapper.unmount()
    expect(mockCloseStream).toHaveBeenCalled()
  })

  it('通过 ASSISTANT_DELTA 实时增量渲染打字机流式回复', async () => {
    let resolveSend: (conv: AssistantConversation) => void = () => {}
    vi.mocked(assistantApi.sendMessage).mockImplementation(
      () => new Promise((resolve) => { resolveSend = resolve }),
    )

    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    await wrapper.get('textarea').setValue('请讲解一下什么是聚簇索引')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // 此时 sendMessage 仍在进行中，用户端发送状态为 true
    expect(wrapper.text()).toContain('正在理解目标并调用应用工具…')

    // 收到第一个增量分片
    emitStreamEvent({
      sequence: 1,
      type: 'ASSISTANT_DELTA',
      conversationId: 'conversation-1',
      payload: { turnId: 't-test', index: 0, delta: '聚簇索引是' },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('聚簇索引是')

    // 收到第二个增量分片
    emitStreamEvent({
      sequence: 2,
      type: 'ASSISTANT_DELTA',
      conversationId: 'conversation-1',
      payload: { turnId: 't-test', index: 1, delta: '按照每张表的主键构建的 B+ 树' },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('聚簇索引是按照每张表的主键构建的 B+ 树')

    // 服务端完成轮次
    emitStreamEvent({
      sequence: 3,
      type: 'TURN_COMPLETED',
      conversationId: 'conversation-1',
      payload: { turnId: 't-test', reply: '聚簇索引是按照每张表的主键构建的 B+ 树。' },
    })
    resolveSend(snapshot({
      reply: '聚簇索引是按照每张表的主键构建的 B+ 树。',
      messages: [
        { role: 'user', content: '请讲解一下什么是聚簇索引' },
        { role: 'assistant', content: '聚簇索引是按照每张表的主键构建的 B+ 树。' },
      ],
    }))
    await flushPromises()

    expect(wrapper.text()).not.toContain('正在理解目标并调用应用工具…')
    expect(wrapper.text()).toContain('聚簇索引是按照每张表的主键构建的 B+ 树。')
  })

  it('实时流式更新工具调用过程 (TOOL_STARTED, TOOL_SUCCEEDED, TOOL_FAILED)', async () => {
    let resolveSend: (conv: AssistantConversation) => void = () => {}
    vi.mocked(assistantApi.sendMessage).mockImplementation(
      () => new Promise((resolve) => { resolveSend = resolve }),
    )

    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    await wrapper.get('textarea').setValue('检查我的学习进度')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // 模拟工具启动
    emitStreamEvent({
      sequence: 1,
      type: 'TOOL_STARTED',
      conversationId: 'conversation-1',
      payload: { toolName: 'learning.context.get' },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('learning.context.get')
    expect(wrapper.text()).toContain('RUNNING')

    // 模拟工具成功
    emitStreamEvent({
      sequence: 2,
      type: 'TOOL_SUCCEEDED',
      conversationId: 'conversation-1',
      payload: { toolName: 'learning.context.get', summary: '已加载当前路线进度' },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('已加载当前路线进度')
    expect(wrapper.text()).toContain('SUCCEEDED')

    resolveSend(snapshot({
      toolSteps: [{ toolName: 'learning.context.get', status: 'SUCCEEDED', summary: '已加载当前路线进度' }],
    }))
    await flushPromises()
  })

  it('支持流式推送 ACTION_PREVIEW 并呈现高风险确认卡片', async () => {
    let resolveSend: (conv: AssistantConversation) => void = () => {}
    vi.mocked(assistantApi.sendMessage).mockImplementation(
      () => new Promise((resolve) => { resolveSend = resolve }),
    )

    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    await wrapper.get('textarea').setValue('将学习时长设为30分钟')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    emitStreamEvent({
      sequence: 1,
      type: 'ACTION_PREVIEW',
      conversationId: 'conversation-1',
      payload: {
        turnId: 't-1',
        actionId: 'act-stream-1',
        summary: '将每日学习目标调整为 30 分钟',
        riskLevel: 'HIGH',
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('需要你的确认')
    expect(wrapper.text()).toContain('HIGH 风险')
    expect(wrapper.text()).toContain('将每日学习目标调整为 30 分钟')
    expect(wrapper.find('[data-testid="confirm-action"]').exists()).toBe(true)

    resolveSend(snapshot({
      status: 'WAITING_CONFIRMATION',
      pendingAction: {
        actionId: 'act-stream-1',
        executionId: 'exec-1',
        toolName: 'learning.goals.update',
        riskLevel: 'HIGH',
        status: 'WAITING_CONFIRMATION',
        summary: '将每日学习目标调整为 30 分钟',
        arguments: {},
        expiresAt: '2026-09-09T18:00:00Z',
      },
    }))
    await flushPromises()
  })

  it('通过 UI_ACTION 事件安全分发页面导航', async () => {
    let resolveSend: (conv: AssistantConversation) => void = () => {}
    vi.mocked(assistantApi.sendMessage).mockImplementation(
      () => new Promise((resolve) => { resolveSend = resolve }),
    )

    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    await wrapper.get('textarea').setValue('打开学习路线')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    emitStreamEvent({
      sequence: 1,
      type: 'UI_ACTION',
      conversationId: 'conversation-1',
      payload: {
        turnId: 't-1',
        type: 'NAVIGATE',
        routeKey: 'ROADMAP',
        params: {},
        reason: '查看学习路线',
      },
    })
    await flushPromises()

    expect(push).toHaveBeenCalledWith({ name: 'roadmap' })

    resolveSend(snapshot({
      uiActions: [{ type: 'NAVIGATE', routeKey: 'ROADMAP', params: {}, reason: '查看学习路线' }],
    }))
    await flushPromises()
  })

  it('支持在发送中点击取消按钮发起轮次中断，并响应 TURN_CANCELLED 事件', async () => {
    vi.mocked(assistantApi.sendMessage).mockImplementation(
      () => new Promise(() => {}),
    )

    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    await wrapper.get('textarea').setValue('生成复杂的全阶段学习计划')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // 处于发送状态，取消按钮可见
    const cancelButton = wrapper.find('[data-testid="cancel-turn"]')
    expect(cancelButton.exists()).toBe(true)

    await cancelButton.trigger('click')
    await flushPromises()

    expect(assistantApi.cancelTurn).toHaveBeenCalledWith(
      'conversation-1',
      expect.stringContaining('assistant-turn:'),
    )

    // 收到取消完成事件
    emitStreamEvent({
      sequence: 2,
      type: 'TURN_CANCELLED',
      conversationId: 'conversation-1',
      payload: {
        turnId: 't-cancel',
        reason: 'CANCEL_REQUESTED',
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('当前轮次已取消')
    expect(wrapper.text()).not.toContain('正在理解目标并调用应用工具…')
  })
})
