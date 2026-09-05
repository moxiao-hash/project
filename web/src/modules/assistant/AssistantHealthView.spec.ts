import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AssistantHealthView from './AssistantHealthView.vue'

const { getAssistantHealth } = vi.hoisted(() => ({
  getAssistantHealth: vi.fn(),
}))

vi.mock('@/services/current/assistant', () => ({
  assistantApi: { getAssistantHealth },
}))

describe('AssistantHealthView', () => {
  beforeEach(() => {
    getAssistantHealth.mockReset()
    getAssistantHealth.mockResolvedValue({
      totalExecutions: 10,
      successfulExecutions: 8,
      failedExecutions: 2,
      successRate: 0.8,
      promptTokens: 1200,
      completionTokens: 600,
      estimatedCost: 0.025,
      averageLatencyMs: 850,
      pendingConfirmations: 1,
      costSamples: 3, tokenSamples: 3, latencySamples: 3,
    })
  })

  it('未采集的费用和用量显示暂无数据', async () => {
    getAssistantHealth.mockResolvedValue({
      totalExecutions: 0, successfulExecutions: 0, failedExecutions: 0,
      successRate: 0, promptTokens: 0, completionTokens: 0,
      estimatedCost: 0, averageLatencyMs: 0, pendingConfirmations: 0,
      costSamples: 0, tokenSamples: 0, latencySamples: 0,
    })
    const wrapper = mount(AssistantHealthView)
    await flushPromises()
    expect(wrapper.text().match(/暂无数据/g)?.length).toBe(4)
  })

  it('展示个人 Agent 的成功率、成本和待确认操作', async () => {
    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    expect(wrapper.text()).toContain('80%')
    expect(wrapper.text()).toContain('1,800')
    expect(wrapper.text()).toContain('0.025')
    expect(wrapper.text()).toContain('1 个操作等待确认')
  })
})
