import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AssistantHealthView from './AssistantHealthView.vue'
import type { AssistantHealth } from '@/types/assistant'

const { getAssistantHealth } = vi.hoisted(() => ({
  getAssistantHealth: vi.fn(),
}))

vi.mock('@/services/current/assistant', () => ({
  assistantApi: { getAssistantHealth },
}))

function createHealthFixture(overrides: Partial<AssistantHealth> = {}): AssistantHealth {
  return {
    // 采样计数
    costSamples: 10,
    tokenSamples: 10,
    latencySamples: 10,

    // 旧版执行治理指标 (Legacy Execution Metrics)
    totalExecutions: 12,
    successfulExecutions: 10,
    failedExecutions: 2,
    successRate: 0.833,
    promptTokens: 5000,
    completionTokens: 2500,
    estimatedCost: 0.05,
    averageLatencyMs: 650,
    pendingConfirmations: 1,

    // 真实模型遥测指标 (Model Telemetry)
    modelCalls: 10,
    failedModelCalls: 1,
    modelFailureRate: 0.1,
    modelPromptTokens: 12000,
    modelCachedPromptTokens: 4000,
    modelUncachedPromptTokens: 8000,
    modelCompletionTokens: 3000,
    modelReasoningTokens: 1000,
    modelTotalTokens: 16000, // 后端已准确求和，前端严禁二次相加
    unknownPriceCalls: 0,
    usageEstimatedCost: 0.0425,
    currency: 'USD',
    priceStatus: 'KNOWN',
    priceVersion: '2026-09-08',
    p50LatencyMs: 520,
    p95LatencyMs: 1280,

    models: [
      {
        modelName: 'deepseek-chat',
        provider: 'deepseek',
        calls: 8,
        failedCalls: 0,
        failureRate: 0,
        promptTokens: 10000,
        cachedPromptTokens: 4000,
        uncachedPromptTokens: 6000,
        completionTokens: 2500,
        reasoningTokens: 0,
        totalTokens: 12500,
        estimatedCost: 0.035,
        currency: 'USD',
        priceStatus: 'KNOWN',
        priceVersion: '2026-09-08',
        p50LatencyMs: 480,
        p95LatencyMs: 950,
      },
      {
        modelName: 'deepseek-reasoner',
        provider: 'deepseek',
        calls: 2,
        failedCalls: 1,
        failureRate: 0.5,
        promptTokens: 2000,
        cachedPromptTokens: 0,
        uncachedPromptTokens: 2000,
        completionTokens: 500,
        reasoningTokens: 1000,
        totalTokens: 3500,
        estimatedCost: 0.0075,
        currency: 'USD',
        priceStatus: 'KNOWN',
        priceVersion: '2026-09-08',
        p50LatencyMs: 890,
        p95LatencyMs: 1800,
      },
    ],
    ...overrides,
  }
}

describe('AssistantHealthView (Task 31 真实用量与模型遥测可观测性)', () => {
  beforeEach(() => {
    getAssistantHealth.mockReset()
    getAssistantHealth.mockResolvedValue(createHealthFixture())
  })

  it('模型遥测与执行记录分立展示：独立呈现模型可观测指标与执行层统计', async () => {
    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    // 验证两大核心分区的存在
    expect(wrapper.find('[data-testid="model-telemetry-section"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="legacy-execution-section"]').exists()).toBe(true)

    // 模型指标区展示
    const telemetrySection = wrapper.find('[data-testid="model-telemetry-section"]')
    expect(telemetrySection.text()).toContain('模型调用')
    expect(telemetrySection.text()).toContain('10')
    expect(telemetrySection.text()).toContain('失败 1 次 (10%)')

    // 执行层指标区展示
    const executionSection = wrapper.find('[data-testid="legacy-execution-section"]')
    expect(executionSection.text()).toContain('执行记录')
    expect(executionSection.text()).toContain('83.3%')
    expect(executionSection.text()).toContain('1 个操作等待确认')
  })

  it('Token 用量直接取后端 modelTotalTokens，绝不二次叠加 reasoningTokens', async () => {
    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    // 后端返回的 modelTotalTokens 为 16000
    // 如果前端错误地加上 reasoningTokens (1000)，会变成 17000
    const telemetrySection = wrapper.find('[data-testid="model-telemetry-section"]')
    expect(telemetrySection.text()).toContain('16,000')
    expect(telemetrySection.text()).not.toContain('17,000')
    expect(telemetrySection.text()).toContain('缓存命中 4,000 · 未缓存 8,000')
    expect(telemetrySection.text()).toContain('输出 3,000 · 思考 1,000')
  })

  it('费用展示动态货币符号（通常为 USD），严禁出现硬编码人民币符号 ¥', async () => {
    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    const text = wrapper.text()
    // 严禁包含硬编码人民币符号
    expect(text).not.toContain('¥')
    expect(text).not.toContain('￥')

    // 动态展示 USD 货币标识与数值
    expect(text).toContain('0.0425')
    expect(text).toContain('USD')
  })

  it('当 priceStatus 为 UNKNOWN 或金额为 null 时，显式展示未知状态，绝不记为 0', async () => {
    getAssistantHealth.mockResolvedValue(createHealthFixture({
      priceStatus: 'UNKNOWN',
      usageEstimatedCost: null,
      unknownPriceCalls: 3,
      models: [
        {
          modelName: 'unknown-model-x',
          provider: 'custom',
          calls: 3,
          failedCalls: 0,
          failureRate: 0,
          promptTokens: 1000,
          cachedPromptTokens: 0,
          uncachedPromptTokens: 1000,
          completionTokens: 200,
          reasoningTokens: 0,
          totalTokens: 1200,
          estimatedCost: null,
          currency: null,
          priceStatus: 'UNKNOWN',
          priceVersion: null,
          p50LatencyMs: 300,
          p95LatencyMs: 600,
        },
      ],
    }))

    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    const costBadge = wrapper.find('[data-testid="cost-unknown-badge"]')
    expect(costBadge.exists()).toBe(true)
    expect(costBadge.text()).toContain('价格未知')
    expect(wrapper.text()).toContain('含 3 次未计价调用')

    // 表格内模型项也必须显示价格未知，不能出现 $0 或 0.00
    const modelRow = wrapper.find('[data-testid="model-row-unknown-model-x"]')
    expect(modelRow.text()).toContain('未知')
    expect(modelRow.text()).not.toContain('$0')
  })

  it('无模型调用行时，真实呈现无数据空状态', async () => {
    getAssistantHealth.mockResolvedValue(createHealthFixture({
      modelCalls: 0,
      models: [],
    }))

    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    expect(wrapper.find('[data-testid="models-empty-state"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="models-empty-state"]').text()).toContain('暂无模型调用数据')
  })

  it('分模型详细指标展示：覆盖失败次数、缓存/未缓存 Prompt、P50/P95 与定价版本', async () => {
    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    const table = wrapper.find('[data-testid="models-table"]')
    expect(table.exists()).toBe(true)

    // 检查表头及明细字段
    expect(table.text()).toContain('deepseek-chat')
    expect(table.text()).toContain('deepseek-reasoner')
    expect(table.text()).toContain('deepseek')
    expect(table.text()).toContain('480 ms / 950 ms')
    expect(table.text()).toContain('890 ms / 1800 ms')
    expect(table.text()).toContain('2026-09-08')

    // 顶层分区展示整体 P50 / P95
    const telemetry = wrapper.find('[data-testid="model-telemetry-section"]')
    expect(telemetry.text()).toContain('520 ms')
    expect(telemetry.text()).toContain('1,280 ms')
  })

  it('彻底移除虚构的 budget UI，公开健康接口不包含 budget 字段', async () => {
    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    expect(wrapper.find('[data-testid="budget-alert"]').exists()).toBe(false)
    expect(wrapper.find('.budget-card').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('今日调用次数配额')
    expect(wrapper.text()).not.toContain('今日预计花费限额')
  })

  it('真实零行响应：modelCalls=0, models=[], priceStatus=NONE, usageEstimatedCost=0, currency=null, p50LatencyMs=0, p95LatencyMs=0 显示暂无数据而非 0 USD/0 ms/价格未知', async () => {
    getAssistantHealth.mockResolvedValue(createHealthFixture({
      modelCalls: 0,
      failedModelCalls: 0,
      modelFailureRate: 0,
      modelPromptTokens: 0,
      modelCachedPromptTokens: 0,
      modelUncachedPromptTokens: 0,
      modelCompletionTokens: 0,
      modelReasoningTokens: 0,
      modelTotalTokens: 0,
      unknownPriceCalls: 0,
      usageEstimatedCost: 0,
      currency: null,
      priceStatus: 'NONE',
      priceVersion: null,
      p50LatencyMs: 0,
      p95LatencyMs: 0,
      models: [],
    }))

    const wrapper = mount(AssistantHealthView)
    await flushPromises()

    const telemetrySection = wrapper.find('[data-testid="model-telemetry-section"]')
    expect(telemetrySection.exists()).toBe(true)

    // 严禁渲染 0 USD, 0 ms, 价格未知徽标或精确核算文案
    expect(telemetrySection.text()).not.toContain('0 USD')
    expect(telemetrySection.text()).not.toContain('0 ms')
    expect(telemetrySection.find('[data-testid="cost-unknown-badge"]').exists()).toBe(false)
    expect(telemetrySection.text()).not.toContain('精确核算')

    // 估算费用卡片与延迟卡片渲染明确的暂无数据
    const costCard = telemetrySection.find('[data-testid="model-cost-card"]')
    expect(costCard.exists()).toBe(true)
    expect(costCard.text()).toContain('暂无数据')
    expect(costCard.text()).toContain('暂无已计价调用')

    const latencyCard = telemetrySection.find('[data-testid="model-latency-card"]')
    expect(latencyCard.exists()).toBe(true)
    expect(latencyCard.text()).toContain('暂无数据')
    expect(latencyCard.text()).toContain('暂无延迟采样数据')
  })
})
