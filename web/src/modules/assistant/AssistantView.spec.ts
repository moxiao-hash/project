import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'

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
    reportActionReceipt: vi.fn().mockResolvedValue({
      actionId: 'mock-act',
      status: 'SUCCEEDED',
      currentRoute: 'assistant',
      error: null,
    }),
    subscribeEvents: vi.fn((_conversationId: string, options?: AssistantEventStreamOptions) => {
      mockEventOptions = options || null
      return {
        close: mockCloseStream,
        getLastEventId: () => 0,
        getStatus: () => 'connected',
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
    lastEventSequence: 10,
    ...overrides,
  }
}

function emitStreamEvent(event: AssistantEvent) {
  if (mockEventOptions?.onEvent) {
    mockEventOptions.onEvent(event)
  }
}

describe('AssistantView', () => {
  it('retains final citations and warnings when terminal SSE precedes the POST response', async () => {
    let finish!: (value: AssistantConversation) => void
    vi.mocked(assistantApi.sendMessage).mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    await wrapper.get('textarea').setValue('解释 Java')
    await wrapper.get('form').trigger('submit')
    const turnId = vi.mocked(assistantApi.sendMessage).mock.calls[0][1].idempotencyKey
    emitStreamEvent({ sequence: 11, type: 'TURN_COMPLETED', conversationId: 'conversation-1', payload: { turnId, reply: 'Java 回答' } })
    await flushPromises()
    finish(snapshot({ reply: 'Java 回答', uiActions: [], warnings: ['联网服务暂不可用'], citations: [{ sourceType: 'WEB', title: 'Java 官方来源', snippet: '类型说明', url: 'https://dev.java/' }] }))
    await flushPromises()
    expect(wrapper.text()).toContain('Java 官方来源')
    expect(wrapper.text()).toContain('联网服务暂不可用')
    wrapper.unmount()
  })

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

  it('组件挂载时建立 SSE 订阅，优先从 snapshot.lastEventSequence 起步，卸载时关闭连接', async () => {
    sessionStorage.setItem('studypilot.assistantConversationId', 'existing-conv')
    vi.mocked(assistantApi.getConversation).mockResolvedValue(snapshot({
      conversationId: 'existing-conv',
      lastEventSequence: 42,
    }))

    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    expect(assistantApi.subscribeEvents).toHaveBeenCalledWith(
      'existing-conv',
      expect.objectContaining({
        lastEventId: 42,
        onEvent: expect.any(Function),
      }),
    )

    wrapper.unmount()
    expect(mockCloseStream).toHaveBeenCalled()
  })

  it('在 POST 响应返回前，立即在 UI 渲染出站的用户消息', async () => {
    vi.mocked(assistantApi.sendMessage).mockImplementation(() => new Promise(() => {}))

    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    await wrapper.get('textarea').setValue('用户立即发送的提问')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // 即使后端尚未返回，用户消息已立即可见
    expect(wrapper.text()).toContain('用户立即发送的提问')
    expect(wrapper.find('.assistant-message.user').text()).toContain('用户立即发送的提问')
  })

  it('基于 turnId 隔离流式增量，并防止陈旧的 POST 响应覆盖较新的轮次', async () => {
    let resolveTurn1: (conv: AssistantConversation) => void = () => {}
    let capturedTurnId1 = ''

    vi.mocked(assistantApi.sendMessage).mockImplementation((_id, body) => {
      capturedTurnId1 = body.idempotencyKey
      return new Promise((resolve) => { resolveTurn1 = resolve })
    })

    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    await wrapper.get('textarea').setValue('第一轮问题')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // 收到属于 turn 1 的增量
    emitStreamEvent({
      sequence: 11,
      type: 'ASSISTANT_DELTA',
      conversationId: 'conversation-1',
      payload: { turnId: capturedTurnId1, index: 0, delta: '第一轮流式回答' },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('第一轮流式回答')

    // 收到不属于 turn 1 的杂乱增量，验证不会追加到 turn 1 中
    emitStreamEvent({
      sequence: 12,
      type: 'ASSISTANT_DELTA',
      conversationId: 'conversation-1',
      payload: { turnId: 'alien-turn', index: 0, delta: '无关其他轮次内容' },
    })
    await flushPromises()
    expect(wrapper.text()).not.toContain('无关其他轮次内容')

    // 模拟 turn 1 结束
    emitStreamEvent({
      sequence: 13,
      type: 'TURN_COMPLETED',
      conversationId: 'conversation-1',
      payload: { turnId: capturedTurnId1, reply: '第一轮流式回答完成。' },
    })
    resolveTurn1(snapshot({
      reply: '第一轮流式回答完成。',
    }))
    await flushPromises()

    expect(wrapper.text()).toContain('第一轮流式回答完成。')
  })

  it('失败或取消的半成品回答不得伪装成已完成回答', async () => {
    let capturedTurnId = ''
    vi.mocked(assistantApi.sendMessage).mockImplementation((_id, body) => {
      capturedTurnId = body.idempotencyKey
      return new Promise(() => {})
    })

    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    await wrapper.get('textarea').setValue('会出错的提问')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // 收到部分流式增量
    emitStreamEvent({
      sequence: 11,
      type: 'ASSISTANT_DELTA',
      conversationId: 'conversation-1',
      payload: { turnId: capturedTurnId, index: 0, delta: '半截临时文字' },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('半截临时文字')

    // 收到 TURN_FAILED 事件
    emitStreamEvent({
      sequence: 12,
      type: 'TURN_FAILED',
      conversationId: 'conversation-1',
      payload: { turnId: capturedTurnId, errorType: 'ModelTimeoutException' },
    })
    await flushPromises()

    // 半成品文字被明确替换，不再伪装成已完成回答
    expect(wrapper.text()).not.toContain('半截临时文字')
    expect(wrapper.text()).toContain('回答生成失败，请重试')
    expect(wrapper.find('.assistant-message.status-failed').exists()).toBe(true)
  })

  it('支持在两个不同轮次中导航至相同路由，且在同轮内去重', async () => {
    mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    // 第一轮：触发导航至 ROADMAP
    emitStreamEvent({
      sequence: 11,
      type: 'UI_ACTION',
      conversationId: 'conversation-1',
      payload: {
        turnId: 'turn-alpha',
        type: 'NAVIGATE',
        routeKey: 'ROADMAP',
        params: {},
        reason: '第一轮路线',
      },
    })
    // 同轮内重复事件
    emitStreamEvent({
      sequence: 12,
      type: 'UI_ACTION',
      conversationId: 'conversation-1',
      payload: {
        turnId: 'turn-alpha',
        type: 'NAVIGATE',
        routeKey: 'ROADMAP',
        params: {},
        reason: '第一轮路线重复',
      },
    })
    await flushPromises()
    expect(push).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenLastCalledWith({ name: 'roadmap' })

    // 第二轮：再次合法导航至相同的 ROADMAP 路由
    emitStreamEvent({
      sequence: 13,
      type: 'UI_ACTION',
      conversationId: 'conversation-1',
      payload: {
        turnId: 'turn-beta',
        type: 'NAVIGATE',
        routeKey: 'ROADMAP',
        params: {},
        reason: '第二轮路线',
      },
    })
    await flushPromises()
    expect(push).toHaveBeenCalledTimes(2)
  })

  it('UI Action 执行后向 Java 门面回传动作终态回执 (actionId/status/error/currentRoute)', async () => {
    mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    mockEventOptions?.onEvent?.({
      sequence: 1,
      type: 'UI_ACTION',
      conversationId: 'conversation-1',
      payload: {
        actionId: 'act-receipt-1',
        turnId: 'turn-receipt',
        type: 'NAVIGATE',
        routeKey: 'ROADMAP_NODE',
        params: { nodeId: 'node-1' },
        reason: '查看节点并回执',
      },
    })
    await flushPromises()

    expect(push).toHaveBeenCalledWith({ name: 'roadmap-node', params: { id: 'node-1' } })
    expect(assistantApi.reportActionReceipt).toHaveBeenCalledWith(
      'conversation-1',
      expect.objectContaining({
        actionId: 'act-receipt-1',
        status: 'SUCCEEDED',
        currentRoute: 'roadmap-node',
      }),
    )
  })

  it('相同 actionId 重复收到时保持幂等，不重复执行 push 与二次回执', async () => {
    mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    const eventPayload: import('@/types/assistant').AssistantEvent = {
      sequence: 2,
      type: 'UI_ACTION',
      conversationId: 'conversation-1',
      payload: {
        actionId: 'act-receipt-dup',
        turnId: 'turn-receipt-dup',
        type: 'NAVIGATE',
        routeKey: 'ROADMAP',
        params: {},
        reason: '路线导航',
      },
    }

    mockEventOptions?.onEvent?.(eventPayload)
    await flushPromises()
    const callCountPush = push.mock.calls.length
    const callCountReceipt = vi.mocked(assistantApi.reportActionReceipt).mock.calls.length

    // 重复发送同一 actionId
    mockEventOptions?.onEvent?.(eventPayload)
    await flushPromises()

    expect(push.mock.calls.length).toBe(callCountPush)
    expect(vi.mocked(assistantApi.reportActionReceipt).mock.calls.length).toBe(callCountReceipt)
  })

  it('页面重载时抑制历史 UI Action，不自动触发历史导航', async () => {
    sessionStorage.setItem('studypilot.assistantConversationId', 'saved-conv')
    vi.mocked(assistantApi.getConversation).mockResolvedValue(snapshot({
      conversationId: 'saved-conv',
      uiActions: [{ type: 'NAVIGATE', routeKey: 'DASHBOARD', params: {}, reason: '历史动作' }],
    }))

    mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    // 页面重载加载出的历史 uiActions 不应自动执行 push 导航
    expect(push).not.toHaveBeenCalled()
  })

  it('加载会话遇到非 404 错误（如 500/网络错误）时严禁静默创建新会话', async () => {
    sessionStorage.setItem('studypilot.assistantConversationId', 'error-conv')
    const serverError = new AxiosError(
      'Server Error',
      '500',
      { headers: new AxiosHeaders() },
      {},
      { status: 500, statusText: 'Internal Server Error', headers: {}, config: { headers: new AxiosHeaders() }, data: {} },
    )
    vi.mocked(assistantApi.getConversation).mockRejectedValue(serverError)

    mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    // 严禁在服务器异常时静默调用 createConversation 覆盖现有会话
    expect(assistantApi.createConversation).not.toHaveBeenCalled()
  })

  it('展示实时流式连接状态 (streamStatus)', async () => {
    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="stream-status"]').exists()).toBe(true)
    mockEventOptions?.onStatusChange?.('connected')
    await flushPromises()
    expect(wrapper.find('[data-testid="stream-status"]').text()).toBe('connected')
  })

  it('在建立 SSE 订阅前水合 snapshot.activeTurn，恢复出站消息、半成品槽位与发送中状态', async () => {
    sessionStorage.setItem('studypilot.assistantConversationId', 'active-conv')
    vi.mocked(assistantApi.getConversation).mockResolvedValue(snapshot({
      conversationId: 'active-conv',
      lastEventSequence: 20,
      activeTurnId: 'turn-running-1',
      activeTurn: {
        turnId: 'turn-running-1',
        userMessage: '正在进行中的复杂问题',
        assistantText: '已生成的前半部分回答',
        lastDeltaIndex: 3,
      },
    }))

    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    // 断言用户消息与未完成的助手回复槽位被成功水合
    expect(wrapper.text()).toContain('正在进行中的复杂问题')
    expect(wrapper.text()).toContain('已生成的前半部分回答')
    // 断言恢复为发送中状态
    expect(wrapper.find('[data-testid="cancel-turn"]').exists()).toBe(true)
    // 断言订阅从 lastEventSequence 20 起步
    expect(assistantApi.subscribeEvents).toHaveBeenCalledWith(
      'active-conv',
      expect.objectContaining({ lastEventId: 20 }),
    )

    // 后续 SSE 增量到达，能够继续无缝追加
    emitStreamEvent({
      sequence: 21,
      type: 'ASSISTANT_DELTA',
      conversationId: 'active-conv',
      payload: {
        turnId: 'turn-running-1',
        index: 4,
        delta: '以及后半部分继续流式',
      },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('已生成的前半部分回答以及后半部分继续流式')
  })

  it('陈旧 POST 竞态防御：滞后的 Turn A 响应在 Turn B 完成后返回，绝不可覆盖 Turn B 的 pendingAction、工具步骤或导航', async () => {
    let resolveTurnA: (conv: AssistantConversation) => void = () => {}
    let capturedTurnIdA = ''
    let callCount = 0

    vi.mocked(assistantApi.sendMessage).mockImplementation((_convId, body) => {
      callCount++
      if (callCount === 1) {
        capturedTurnIdA = body.idempotencyKey
        return new Promise((resolve) => { resolveTurnA = resolve })
      } else {
        return Promise.resolve(snapshot({
          status: 'WAITING_CONFIRMATION',
          pendingAction: {
            actionId: 'action-b',
            executionId: 'exec-b',
            toolName: 'learning.goals.update',
            riskLevel: 'HIGH',
            status: 'WAITING_CONFIRMATION',
            summary: 'Turn B 修改目标',
            arguments: {},
            expiresAt: '',
          },
          toolSteps: [{ toolName: 'goals.update', status: 'SUCCEEDED', summary: 'Turn B 工具步骤' }],
          uiActions: [{ type: 'NAVIGATE', routeKey: 'LEARNING_GOALS', params: {}, reason: '查看目标' }],
        }))
      }
    })

    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    // 1. 发起 Turn A
    await wrapper.get('textarea').setValue('提问 A')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // 2. Turn A 在流中被取消收尾，随后发起 Turn B 并完成
    emitStreamEvent({
      sequence: 21,
      type: 'TURN_CANCELLED',
      conversationId: 'conversation-1',
      payload: { turnId: capturedTurnIdA, reason: 'CANCEL_REQUESTED' },
    })
    await flushPromises()

    // 发起 Turn B
    await wrapper.get('textarea').setValue('提问 B')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // 此时 Turn B 的状态已经生效
    expect(wrapper.text()).toContain('Turn B 修改目标')
    expect(wrapper.text()).toContain('Turn B 工具步骤')
    expect(push).toHaveBeenLastCalledWith({ name: 'goals' })

    // 3. 此时早先迟延的 Turn A POST 响应终于返回
    resolveTurnA(snapshot({
      pendingAction: {
        actionId: 'stale-action-a',
        executionId: 'exec-a',
        toolName: 'stale.tool',
        riskLevel: 'HIGH',
        status: 'WAITING_CONFIRMATION',
        summary: '陈旧的 Turn A 动作',
        arguments: {},
        expiresAt: '',
      },
      toolSteps: [{ toolName: 'stale.tool', status: 'SUCCEEDED', summary: '陈旧的步骤 A' }],
      uiActions: [{ type: 'NAVIGATE', routeKey: 'DASHBOARD', params: {}, reason: '陈旧的导航' }],
    }))
    await flushPromises()

    // 断言 Turn A 绝不能覆盖 Turn B 的状态！
    expect(wrapper.text()).toContain('Turn B 修改目标')
    expect(wrapper.text()).not.toContain('陈旧的 Turn A 动作')
    expect(wrapper.text()).not.toContain('陈旧的步骤 A')
    expect(push).not.toHaveBeenLastCalledWith({ name: 'dashboard' })
  })

  it('在无预先槽位时收到 TURN_COMPLETED（如刷新恰逢终态），能正确恢复最终回复至消息流', async () => {
    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    // 直接收到未知槽位的 TURN_COMPLETED
    emitStreamEvent({
      sequence: 25,
      type: 'TURN_COMPLETED',
      conversationId: 'conversation-1',
      payload: {
        turnId: 'unslotted-turn',
        reply: '在缺少前序槽位时依然恢复的最终回答。',
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('在缺少前序槽位时依然恢复的最终回答。')
  })

  it('轮次失败或取消后，迟到的 ASSISTANT_DELTA 绝不能复活或篡改已失败的内容', async () => {
    let capturedTurnId = ''
    vi.mocked(assistantApi.sendMessage).mockImplementation((_id, body) => {
      capturedTurnId = body.idempotencyKey
      return new Promise(() => {})
    })

    const wrapper = mount(AssistantView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    await wrapper.get('textarea').setValue('提问将失败')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // 轮次失败
    emitStreamEvent({
      sequence: 30,
      type: 'TURN_FAILED',
      conversationId: 'conversation-1',
      payload: { turnId: capturedTurnId, errorType: 'TimeoutException' },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('回答生成失败，请重试')

    // 迟到的 ASSISTANT_DELTA 到达
    emitStreamEvent({
      sequence: 31,
      type: 'ASSISTANT_DELTA',
      conversationId: 'conversation-1',
      payload: { turnId: capturedTurnId, index: 99, delta: '迟到的恶意/僵尸增量' },
    })
    await flushPromises()

    // 绝不能复活或包含该迟到增量
    expect(wrapper.text()).not.toContain('迟到的恶意/僵尸增量')
    expect(wrapper.text()).toContain('回答生成失败，请重试')
  })
})
