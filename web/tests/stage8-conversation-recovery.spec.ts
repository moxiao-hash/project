import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  KnowledgeConversation,
  PlanConversation,
  TaskConversation,
} from '@/types/agent'

const planned = vi.hoisted(() => ({
  createPlanConversation: vi.fn(),
  getPlanConversation: vi.fn(),
  sendPlanMessage: vi.fn(),
  confirmPlan: vi.fn(),
  createTaskConversation: vi.fn(),
  getTaskConversation: vi.fn(),
  sendTaskMessage: vi.fn(),
  confirmTaskAction: vi.fn(),
  createKnowledgeConversation: vi.fn(),
  getKnowledgeConversation: vi.fn(),
  sendKnowledgeMessage: vi.fn(),
  importWebResult: vi.fn(),
}))

const learningApi = vi.hoisted(() => ({
  listGoals: vi.fn(),
}))

vi.mock('@/services/planned', () => ({
  agentGateway: planned,
  gatewayMode: 'http',
}))
vi.mock('@/services/current/learning', () => ({ learningApi }))

import PlanChatView from '@/modules/agent/PlanChatView.vue'
import TaskAgentView from '@/modules/agent/TaskAgentView.vue'
import KnowledgeView from '@/modules/agent/KnowledgeView.vue'

const plan: PlanConversation = {
  conversationId: 'plan-conversation',
  goalId: 'goal-1',
  status: 'COLLECTING',
  reply: '继续描述你的目标',
  draft: null,
  savedPlanId: null,
  error: null,
  citations: [],
  warnings: [],
}
const draftPlan: PlanConversation = {
  ...plan,
  status: 'DRAFT_READY',
  reply: '请确认草稿',
  draft: {
    title: 'Java 学习计划',
    startDate: '2026-07-29',
    endDate: '2026-08-04',
    tasks: [{
      title: '学习依赖注入',
      scheduledDate: '2026-07-29',
      estimatedMinutes: 60,
    }],
  },
}
const task: TaskConversation = {
  conversationId: 'task-conversation',
  targetDate: '2026-07-28',
  status: 'COLLECTING',
  reply: '请选择任务',
  candidateTasks: [],
  actionDraft: null,
  executionId: null,
  updatedTask: null,
  error: null,
}
const knowledge: KnowledgeConversation = {
  conversationId: 'knowledge-conversation',
  mode: 'AUTO',
  status: 'ACTIVE',
  answer: '此前回答',
  retrievalMode: 'NONE',
  citations: [],
  warnings: [],
  modelProvider: 'deepseek',
  modelName: 'deepseek-v4-pro',
}

async function mountAt(component: object, url: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/agent', component }],
  })
  await router.push(url)
  await router.isReady()
  const wrapper = mount(component, {
    global: {
      plugins: [createPinia(), router],
      stubs: { Teleport: true },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('Agent 会话 URL 恢复', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    learningApi.listGoals.mockResolvedValue([{
      id: 'goal-1',
      title: 'Java',
      targetDate: '2026-12-31',
      weeklyStudyHours: 10,
      status: 'ACTIVE',
    }])
  })

  it('计划页从 conversationId 恢复而不重复创建', async () => {
    planned.getPlanConversation.mockResolvedValue(plan)
    const { wrapper } = await mountAt(PlanChatView, '/agent?conversationId=plan-conversation')

    expect(planned.getPlanConversation).toHaveBeenCalledWith('plan-conversation')
    expect(planned.createPlanConversation).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('继续描述你的目标')
  })

  it('任务页从 conversationId 恢复', async () => {
    planned.getTaskConversation.mockResolvedValue(task)
    const { wrapper } = await mountAt(TaskAgentView, '/agent?conversationId=task-conversation')

    expect(planned.getTaskConversation).toHaveBeenCalledWith('task-conversation')
    expect(wrapper.text()).toContain('请选择任务')
  })

  it('知识页从 conversationId 恢复且不伪造历史问题和来源', async () => {
    planned.getKnowledgeConversation.mockResolvedValue(knowledge)
    const { wrapper } = await mountAt(KnowledgeView, '/agent?conversationId=knowledge-conversation')

    expect(planned.getKnowledgeConversation).toHaveBeenCalledWith('knowledge-conversation')
    expect(wrapper.text()).toContain('此前回答')
    expect(wrapper.text()).toContain('deepseek / deepseek-v4-pro')
    expect(wrapper.text()).toContain('模型常识（无检索证据）')
    expect(wrapper.text()).toContain('未检索到可验证来源')
    expect(wrapper.text()).not.toContain('Spring Boot')
  })

  it('创建会话后把 conversationId 写入 query', async () => {
    planned.createTaskConversation.mockResolvedValue(task)
    const { wrapper, router } = await mountAt(TaskAgentView, '/agent')

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.conversationId).toBe('task-conversation')
  })

  it('结构化编辑草稿后发送明确修订消息，不直接确认保存', async () => {
    planned.createPlanConversation.mockResolvedValue(draftPlan)
    planned.sendPlanMessage.mockResolvedValue({
      ...draftPlan,
      reply: '已重新生成草稿',
    })
    const { wrapper } = await mountAt(PlanChatView, '/agent')

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="draft-title"]').setValue('Spring AI 学习计划')
    await wrapper.get('[data-test="draft-task-minutes-0"]').setValue(90)
    await wrapper.get('[data-test="submit-draft-revision"]').trigger('click')
    await flushPromises()

    expect(planned.sendPlanMessage).toHaveBeenCalledWith(
      'plan-conversation',
      '请按以下明确修改重新生成完整计划草案：\n' +
      '1. 将计划标题从“Java 学习计划”改为“Spring AI 学习计划”。\n' +
      '2. 将第 1 个任务“学习依赖注入”的预计时长从 60 分钟改为 90 分钟。',
    )
    expect(planned.confirmPlan).not.toHaveBeenCalled()
  })

  it('草稿存在未提交修改时禁止确认保存', async () => {
    planned.createPlanConversation.mockResolvedValue(draftPlan)
    const { wrapper } = await mountAt(PlanChatView, '/agent')

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="draft-title"]').setValue('尚未提交的新标题')

    const save = wrapper.get('[data-test="confirm-plan"]')
    expect(save.attributes('disabled')).toBeDefined()
    await save.trigger('click')

    expect(planned.confirmPlan).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请先提交表单修改')
  })

  it('草稿未修改时仍可通过专用确认接口保存', async () => {
    planned.createPlanConversation.mockResolvedValue(draftPlan)
    planned.confirmPlan.mockResolvedValue({
      ...draftPlan,
      status: 'COMPLETED',
      reply: '计划已保存',
      savedPlanId: 'plan-1',
    })
    const { wrapper } = await mountAt(PlanChatView, '/agent')

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()
    const save = wrapper.get('[data-test="confirm-plan"]')
    expect(save.attributes('disabled')).toBeUndefined()
    await save.trigger('click')
    await wrapper.get('.dialog-actions .btn-primary').trigger('click')
    await flushPromises()

    expect(planned.confirmPlan).toHaveBeenCalledWith('plan-conversation')
  })
})
