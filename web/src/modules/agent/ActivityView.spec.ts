import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ActivityView from './ActivityView.vue'
import { agentOpsApi } from '@/services/current/agentOps'
import { useUiActionAdapterStore } from '@/stores/uiActionAdapter'
import type { AgentExecution, AgentGrant, AuditLog } from '@/types/api'

vi.mock('@/services/current/agentOps', () => ({
  agentOpsApi: {
    listExecutions: vi.fn(),
    listGrants: vi.fn(),
    listAuditLogs: vi.fn(),
    createGrant: vi.fn(),
  },
}))

const mockExecutions: AgentExecution[] = [
  {
    id: 'exec-1',
    executionType: 'TASK_STATUS_CHANGE',
    status: 'SUCCEEDED',
    riskLevel: 'LOW',
    triggerType: 'USER_REQUEST',
    requiredScope: 'LEARNING_MANAGEMENT',
    summary: '查询用户学习进度',
    resultSummary: '查询完成',
    errorMessage: null,
    modelName: 'gpt-4o',
    promptTokens: 120,
    completionTokens: 45,
    latencyMs: 350,
    estimatedCost: 0.0012,
    idempotencyKey: 'idem-1',
    createdAt: '2026-09-15T08:00:00Z',
  },
  {
    id: 'exec-2',
    executionType: 'PLAN_ADJUSTMENT',
    status: 'WAITING_CONFIRMATION',
    riskLevel: 'HIGH',
    triggerType: 'NIGHTLY_CHECK',
    requiredScope: 'PLAN_GENERATION',
    summary: '生成周报草稿',
    resultSummary: null,
    errorMessage: null,
    modelName: 'gpt-4o',
    promptTokens: 200,
    completionTokens: 80,
    latencyMs: 500,
    estimatedCost: 0.0035,
    idempotencyKey: 'idem-2',
    createdAt: '2026-09-15T09:00:00Z',
  },
]

const mockGrants: AgentGrant[] = [
  {
    id: 'grant-1',
    scopes: ['LEARNING_MANAGEMENT', 'PLAN_GENERATION'],
    active: true,
    expiresAt: '2026-10-01T00:00:00Z',
  },
]

const mockAuditLogs: AuditLog[] = [
  {
    id: 1,
    action: 'AGENT_EXECUTION_TRIGGERED',
    targetType: 'EXECUTION',
    targetId: 'exec-1-long-uuid-1234',
    details: 'Triggered by user session',
    createdAt: '2026-09-15T08:00:01Z',
  },
]

describe('ActivityView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(agentOpsApi.listExecutions).mockResolvedValue(mockExecutions)
    vi.mocked(agentOpsApi.listGrants).mockResolvedValue(mockGrants)
    vi.mocked(agentOpsApi.listAuditLogs).mockResolvedValue(mockAuditLogs)
  })

  it('renders initial executions, grants, and audit logs and waiting confirmation badge', async () => {
    const wrapper = mount(ActivityView)
    await flushPromises()

    expect(wrapper.text()).toContain('执行与审计')
    expect(wrapper.text()).toContain('查询用户学习进度')

    // Badge for WAITING_CONFIRMATION
    const badge = wrapper.find('.tab-badge')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('1')

    // Switch to grants tab
    const tabs = wrapper.findAll('.tab')
    await tabs[1].trigger('click')
    expect(wrapper.text()).toContain('Agent 授权')
    expect(wrapper.text()).toContain('生效中')

    // Switch to audit tab
    await tabs[2].trigger('click')
    expect(wrapper.text()).toContain('AGENT_EXECUTION_TRIGGERED')
    expect(wrapper.text()).toContain('Triggered by user session')
  })

  describe('UI action adapter (REFRESH_RESOURCE AGENT_ACTIVITY / ACTIVITY)', () => {
    it('registers PageAdapters with routeKey AGENT_ACTIVITY on mount and unregisters on unmount', async () => {
      const adapterStore = useUiActionAdapterStore()
      expect(adapterStore.getAdaptersForRouteKey('AGENT_ACTIVITY').resourceManager).toBeUndefined()

      const wrapper = mount(ActivityView)
      await flushPromises()

      const adapters = adapterStore.getAdaptersForRouteKey('AGENT_ACTIVITY')
      expect(adapters.resourceManager).toBeDefined()

      wrapper.unmount()
      expect(adapterStore.getAdaptersForRouteKey('AGENT_ACTIVITY').resourceManager).toBeUndefined()
    })

    it('rejects any resource key other than ACTIVITY', async () => {
      mount(ActivityView)
      await flushPromises()
      const adapterStore = useUiActionAdapterStore()
      const resourceManager = adapterStore.getAdaptersForRouteKey('AGENT_ACTIVITY').resourceManager

      await expect(resourceManager?.refresh('ROADMAP')).rejects.toThrow('不支持刷新的资源: ROADMAP')
      await expect(resourceManager?.refresh('AGENT_ACTIVITY')).rejects.toThrow('不支持刷新的资源: AGENT_ACTIVITY')
      await expect(resourceManager?.refresh('AUDIT')).rejects.toThrow('不支持刷新的资源: AUDIT')
    })

    it('awaits listExecutions, listGrants, and listAuditLogs and updates all datasets on success', async () => {
      const wrapper = mount(ActivityView)
      await flushPromises()

      const updatedExecutions: AgentExecution[] = [
        {
          ...mockExecutions[0],
          id: 'exec-updated',
          summary: '最新已刷新的执行记录',
        },
      ]
      const updatedGrants: AgentGrant[] = [
        {
          id: 'grant-2',
          scopes: ['LEARNING_MANAGEMENT'],
          active: false,
          expiresAt: '2026-09-10T00:00:00Z',
        },
      ]
      const updatedAudit: AuditLog[] = [
        {
          id: 2,
          action: 'NEW_AUDIT_LOG_ACTION',
          targetType: 'GRANT',
          targetId: 'grant-2-long-uuid',
          details: 'Updated audit log entry',
          createdAt: '2026-09-15T10:00:00Z',
        },
      ]

      vi.mocked(agentOpsApi.listExecutions).mockResolvedValueOnce(updatedExecutions)
      vi.mocked(agentOpsApi.listGrants).mockResolvedValueOnce(updatedGrants)
      vi.mocked(agentOpsApi.listAuditLogs).mockResolvedValueOnce(updatedAudit)

      const adapterStore = useUiActionAdapterStore()
      const resourceManager = adapterStore.getAdaptersForRouteKey('AGENT_ACTIVITY').resourceManager

      const refreshResult = await resourceManager?.refresh('ACTIVITY')
      expect(refreshResult).toBe(true)

      await flushPromises()

      // Executions tab
      expect(wrapper.text()).toContain('最新已刷新的执行记录')

      // Grants tab
      const tabs = wrapper.findAll('.tab')
      await tabs[1].trigger('click')
      expect(wrapper.text()).toContain('已失效')

      // Audit tab
      await tabs[2].trigger('click')
      expect(wrapper.text()).toContain('NEW_AUDIT_LOG_ACTION')
    })

    it('rejects if any API call fails and preserves per-section truthful error states', async () => {
      const wrapper = mount(ActivityView)
      await flushPromises()

      vi.mocked(agentOpsApi.listExecutions).mockResolvedValueOnce(mockExecutions)
      vi.mocked(agentOpsApi.listGrants).mockRejectedValueOnce(new Error('500 Internal Server Error in Grants'))
      vi.mocked(agentOpsApi.listAuditLogs).mockResolvedValueOnce(mockAuditLogs)

      const adapterStore = useUiActionAdapterStore()
      const resourceManager = adapterStore.getAdaptersForRouteKey('AGENT_ACTIVITY').resourceManager

      await expect(resourceManager?.refresh('ACTIVITY')).rejects.toThrow('500 Internal Server Error in Grants')
      await flushPromises()

      // Grants tab shows error state (describeError maps generic Error to '发生未知错误')
      const tabs = wrapper.findAll('.tab')
      await tabs[1].trigger('click')
      expect(wrapper.text()).toContain('发生未知错误')
      expect(wrapper.find('.grant-list').exists()).toBe(false)
    })

    it('isolates stale/concurrent invocations: older slow response does not overwrite newer result', async () => {
      const wrapper = mount(ActivityView)
      await flushPromises()

      let resolveSlowExecutions!: (val: AgentExecution[]) => void
      const slowExecPromise = new Promise<AgentExecution[]>((res) => {
        resolveSlowExecutions = res
      })

      // Invoc 1: Slow executions
      vi.mocked(agentOpsApi.listExecutions).mockReturnValueOnce(slowExecPromise)
      vi.mocked(agentOpsApi.listGrants).mockResolvedValueOnce(mockGrants)
      vi.mocked(agentOpsApi.listAuditLogs).mockResolvedValueOnce(mockAuditLogs)

      const adapterStore = useUiActionAdapterStore()
      const resourceManager = adapterStore.getAdaptersForRouteKey('AGENT_ACTIVITY').resourceManager

      const p1 = resourceManager?.refresh('ACTIVITY')

      // Invoc 2: Fast execution with newer data
      const fastExecutions: AgentExecution[] = [
        {
          ...mockExecutions[0],
          id: 'fast-exec',
          summary: '较新的第二次刷新记录',
        },
      ]
      vi.mocked(agentOpsApi.listExecutions).mockResolvedValueOnce(fastExecutions)
      vi.mocked(agentOpsApi.listGrants).mockResolvedValueOnce(mockGrants)
      vi.mocked(agentOpsApi.listAuditLogs).mockResolvedValueOnce(mockAuditLogs)

      const p2 = resourceManager?.refresh('ACTIVITY')

      // Fast completes first
      await expect(p2).resolves.toBe(true)
      await flushPromises()
      expect(wrapper.text()).toContain('较新的第二次刷新记录')

      // Slow completes afterwards
      resolveSlowExecutions([
        {
          ...mockExecutions[0],
          id: 'slow-exec',
          summary: '陈旧的第一次刷新记录',
        },
      ])

      // p1 should reject or resolve false because it was superseded
      await expect(p1).rejects.toThrow(/superseded|stale|cancelled/i)
      await flushPromises()

      // The view still shows the newer result, not overwritten by stale
      expect(wrapper.text()).toContain('较新的第二次刷新记录')
      expect(wrapper.text()).not.toContain('陈旧的第一次刷新记录')
    })

    it('safely handles unmount during pending refresh without applying state or succeeding', async () => {
      const wrapper = mount(ActivityView)
      await flushPromises()

      let resolveExecutions!: (val: AgentExecution[]) => void
      const execPromise = new Promise<AgentExecution[]>((res) => {
        resolveExecutions = res
      })

      vi.mocked(agentOpsApi.listExecutions).mockReturnValueOnce(execPromise)
      vi.mocked(agentOpsApi.listGrants).mockResolvedValueOnce(mockGrants)
      vi.mocked(agentOpsApi.listAuditLogs).mockResolvedValueOnce(mockAuditLogs)

      const adapterStore = useUiActionAdapterStore()
      const resourceManager = adapterStore.getAdaptersForRouteKey('AGENT_ACTIVITY').resourceManager

      const refreshPromise = resourceManager?.refresh('ACTIVITY')
      wrapper.unmount()

      resolveExecutions(mockExecutions)
      await expect(refreshPromise).rejects.toThrow(/unmount|canceled|cancelled|superseded|inactive/i)
    })
  })
})
