import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AgentExecution } from '@/types/api'
import type { PlanAdjustment, PlanConversation } from '@/types/agent'

const agentOpsApiMock = vi.hoisted(() => ({
  listExecutions: vi.fn(),
  listGrants: vi.fn(),
  listAuditLogs: vi.fn(),
  createGrant: vi.fn(),
  confirmExecution: vi.fn(),
}))

const plannedMock = vi.hoisted(() => ({
  createPlanConversation: vi.fn(),
  sendPlanMessage: vi.fn(),
  confirmPlan: vi.fn(),
  analyzePlanAdjustment: vi.fn(),
  getPlanAdjustment: vi.fn(),
  confirmPlanAdjustment: vi.fn(),
}))

const learningApiMock = vi.hoisted(() => ({
  listGoals: vi.fn(),
}))

vi.mock('@/services/current/agentOps', () => ({ agentOpsApi: agentOpsApiMock }))
vi.mock('@/services/current/learning', () => ({ learningApi: learningApiMock }))
vi.mock('@/services/planned', () => ({
  agentGateway: plannedMock,
  gatewayMode: 'mock',
}))

import ActivityView from '@/modules/agent/ActivityView.vue'
import PlanAdjustmentPanel from '@/modules/agent/PlanAdjustmentPanel.vue'
import PlanChatView from '@/modules/agent/PlanChatView.vue'

function waitingExecution(): AgentExecution {
  return {
    id: 'exec-1',
    idempotencyKey: 'task-action:conversation-1',
    executionType: 'TASK_STATUS_CHANGE',
    triggerType: 'USER_REQUEST',
    riskLevel: 'HIGH',
    requiredScope: 'TASK_MANAGEMENT',
    status: 'WAITING_CONFIRMATION',
    summary: '完成任务',
    resultSummary: null,
    errorMessage: null,
    modelName: null,
    promptTokens: null,
    completionTokens: null,
    latencyMs: null,
    estimatedCost: null,
    createdAt: '2026-07-28T08:00:00Z',
  }
}

function planConversation(reply: string): PlanConversation {
  return {
    conversationId: 'conversation-1',
    goalId: 'goal-1',
    status: 'COLLECTING',
    reply,
    draft: null,
    savedPlanId: null,
    error: null,
    citations: [],
    warnings: [],
  }
}

describe('ActivityView 治理确认安全边界', () => {
  it('执行记录页只展示待确认状态，不提供无法恢复工作流的通用确认按钮', async () => {
    agentOpsApiMock.listExecutions.mockResolvedValueOnce([waitingExecution()])
    agentOpsApiMock.listGrants.mockResolvedValueOnce([])
    agentOpsApiMock.listAuditLogs.mockResolvedValueOnce([])

    const wrapper = mount(ActivityView, {
      global: {
        plugins: [createPinia()],
        stubs: { Teleport: true },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('请返回发起该操作的 Agent 页面')
    expect(wrapper.text()).not.toContain('查看并确认执行')
    expect(agentOpsApiMock.confirmExecution).not.toHaveBeenCalled()
  })
})

describe('PlanAdjustmentPanel 复习任务契约', () => {
  it('使用 Java INSERT_REVIEW_TASK 的 title/estimatedMinutes/scheduledDate 字段展示预览', async () => {
    const adjustment: PlanAdjustment = {
      id: 'adjustment-1',
      planId: 'plan-1',
      analysisDate: '2026-07-28',
      triggerType: 'USER_REQUEST',
      signals: [],
      summary: '插入复习任务',
      operations: [{
        type: 'INSERT_REVIEW_TASK',
        taskId: 'task-1',
        expectedVersion: 1,
        scheduledDate: '2026-07-29',
        estimatedMinutes: 30,
        title: '复习依赖注入',
        taskKind: 'REVIEW',
        firstTitle: null,
        firstEstimatedMinutes: null,
        secondTitle: null,
        secondScheduledDate: null,
        secondEstimatedMinutes: null,
        knowledgePoint: '依赖注入',
        sourceAttemptId: 'attempt-1',
      }],
      riskLevel: 'LOW',
      status: 'DRAFT_READY',
      executionId: 'exec-1',
      beforePlanVersion: 1,
      afterPlanVersion: null,
      error: null,
      createdAt: '2026-07-28T08:00:00Z',
      updatedAt: '2026-07-28T08:00:00Z',
    }
    plannedMock.analyzePlanAdjustment.mockResolvedValueOnce(adjustment)

    const wrapper = mount(PlanAdjustmentPanel, {
      props: { planId: 'plan-1' },
      global: { plugins: [createPinia()] },
    })
    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('复习依赖注入')
    expect(wrapper.text()).toContain('30 分钟')
    expect(wrapper.text()).toContain('2026-07-29')
    expect(wrapper.text()).not.toContain('undefined')
  })
})

describe('PlanChatView 后台等待', () => {
  beforeEach(() => {
    learningApiMock.listGoals.mockResolvedValue([{
      id: 'goal-1',
      title: '学习 Java',
      targetDate: '2026-12-31',
      weeklyStudyHours: 10,
      status: 'DRAFT',
    }])
    plannedMock.createPlanConversation.mockResolvedValue(planConversation('请描述学习时间'))
  })

  it('转入后台等待后禁止重复发送，并在原请求返回时同步回复', async () => {
    let resolveMessage!: (value: PlanConversation) => void
    plannedMock.sendPlanMessage.mockReturnValueOnce(
      new Promise<PlanConversation>((resolve) => { resolveMessage = resolve }),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: PlanChatView }],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(PlanChatView, {
      global: { plugins: [createPinia(), router] },
    })
    await flushPromises()
    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    const input = wrapper.get('input.input')
    await input.setValue('每周学习十小时')
    await wrapper.get('form.chat-input').trigger('submit.prevent')
    await wrapper.get('button.btn-secondary').trigger('click')

    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('后台处理中')

    resolveMessage(planConversation('还需要你的开始日期'))
    await flushPromises()

    expect(wrapper.text()).toContain('还需要你的开始日期')
  })
})
