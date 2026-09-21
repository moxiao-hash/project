import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AssistantHealthView from './AssistantHealthView.vue'

const { getAssistantHealth } = vi.hoisted(() => ({
  getAssistantHealth: vi.fn(),
}))

vi.mock('@/services/current/assistant', () => ({
  assistantApi: { getAssistantHealth },
}))

describe('AssistantHealthView (Task 31 真实用量、价格与预算可观测性)', () => {
  beforeEach(() => {
    getAssistantHealth.mockReset()
    getAssistantHealth.mockResolvedValue({
      totalExecutions: 10,
      successfulExecutions: 8,
      failedExecutions: 2,
      successRate: 0.8,
      promptTokens: 1200,
      completionTokens: 600,
      reasoningTokens: 200,
      estimatedCost: 0.025,
      isCostEstimated: true,
      averageLatencyMs: 850,
      p50LatencyMs: 720,
      p95LatencyMs: 1450,
      pendingConfirmations: 1,
      costSamples: 3,
      tokenSamples: 3,
      latencySamples: 3,
      models: [
        {
          modelId: 'deepseek-chat',
          callCount: 8,
          promptTokens: 1000,
          completionTokens: 500,
          reasoningTokens: 0,
          estimatedCost: 0.015,
          isEstimated: true,
        },
        {
          modelId: 'experimental-model-v1',
          callCount: 2,
          promptTokens: 200,
          completionTokens: 100,
          reasoningTokens: 200,
          estimatedCost: 0,
          isEstimated: false,
        },
      ],
      budget: {
        dailyCallsLimit: 100,
        dailyCallsUsed: 85,
        dailyCostLimit: 5.0,
        dailyCostUsed: 1.25,
        maxOutputTokensPerTurn: 4096,
        budgetExhausted: false,
        exhaustedReason: null,
      },
    })
  })

  it('未采集的费用和用量显示暂无数据', async () => {
    getAssistantHealth.mockResolvedValue({
      totalExecutions: 0,
      successfulExecutions: 0,
      failedExecutions: 0,
      successRate: 0,
      promptTokens: 0,
      completionTokens: 0,
      estimatedCost: 0,
      averageLatencyMs: 0,
      pendingConfirmations: 0,
      costSamples: 0,
      tokenSamples: 0,
      latencySamples: 0,
    })
    const wrapper = mount(AssistantHealthView)
    await flushPromises()
    expect(wrapper.text().match(/暂无数据/g)?.length).toBe(4)
  })

  it('展示个人 Agent 的成功率、成本和待确认操作', async () => {
    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    expect(wrapper.text()).toContain('80%')
    expect(wrapper.text()).toContain('2,000') // 1200 prompt + 600 completion + 200 reasoning
    expect(wrapper.text()).toContain('0.025')
    expect(wrapper.text()).toContain('1 个操作等待确认')
  })

  it('分模型用量明细：正确渲染模型列表、Token 及未知价格的“不可估算”标记', async () => {
    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    // 验证模型 ID 正常展示
    expect(wrapper.text()).toContain('deepseek-chat')
    expect(wrapper.text()).toContain('experimental-model-v1')

    // 验证已知模型费用正常格式化
    expect(wrapper.text()).toContain('0.015')

    // 验证未录入价格的模型严禁显示为 0，必须明确标为“不可估算”
    expect(wrapper.text()).toContain('不可估算')
    expect(wrapper.find('[data-testid="unestimated-badge"]').exists()).toBe(true)
  })

  it('延迟可观测性：同时展示 P50 与 P95 延迟分位数指标', async () => {
    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    expect(wrapper.text()).toContain('P50 延迟')
    expect(wrapper.text()).toContain('720 ms')
    expect(wrapper.text()).toContain('P95 延迟')
    expect(wrapper.text()).toContain('1,450 ms')
  })

  it('用户每日预算展示：正常状态下展示调用配额进度与花费限额', async () => {
    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    expect(wrapper.text()).toContain('今日调用次数配额')
    expect(wrapper.text()).toContain('85 / 100')
    expect(wrapper.text()).toContain('今日预计花费限额')
    expect(wrapper.text()).toContain('¥1.25 / ¥5.00')
    expect(wrapper.find('[data-testid="budget-alert"]').exists()).toBe(false)
  })

  it('预算耗尽拦截：当每日预算耗尽时渲染醒目的预算告警与降级提示', async () => {
    getAssistantHealth.mockResolvedValueOnce({
      totalExecutions: 100,
      successfulExecutions: 95,
      failedExecutions: 5,
      successRate: 0.95,
      promptTokens: 50000,
      completionTokens: 20000,
      estimatedCost: 5.2,
      isCostEstimated: true,
      averageLatencyMs: 900,
      p50LatencyMs: 800,
      p95LatencyMs: 1600,
      pendingConfirmations: 0,
      costSamples: 100,
      tokenSamples: 100,
      latencySamples: 100,
      budget: {
        dailyCallsLimit: 100,
        dailyCallsUsed: 100,
        dailyCostLimit: 5.0,
        dailyCostUsed: 5.2,
        maxOutputTokensPerTurn: 4096,
        budgetExhausted: true,
        exhaustedReason: '今日调用次数已达上限（100次），纯 Java 查阅与导航仍可使用',
      },
    })

    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    const alert = wrapper.find('[data-testid="budget-alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('今日调用次数已达上限')
    expect(alert.text()).toContain('纯 Java 查阅与导航仍可使用')
  })

  it('隐私安全防线：页面严禁透传或渲染任何用户 Prompt 明文与敏感凭据', async () => {
    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    const pageText = wrapper.text()
    expect(pageText).not.toContain('promptText')
    expect(pageText).not.toContain('apiKey')
    expect(pageText).not.toContain('Bearer')
    expect(pageText).not.toContain('sk-')
  })
})
