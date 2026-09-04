import { describe, expect, it, vi } from 'vitest'

import { dispatchUiAction } from './uiActionDispatcher'

describe('assistant UI action dispatcher', () => {
  it('maps a registered route key without accepting arbitrary URLs', async () => {
    const push = vi.fn().mockResolvedValue(undefined)

    await dispatchUiAction(
      { type: 'NAVIGATE', routeKey: 'ROADMAP_NODE', params: { nodeId: 'node-1' }, reason: '继续学习' },
      { push },
    )

    expect(push).toHaveBeenCalledWith({ name: 'roadmap-node', params: { id: 'node-1' } })
  })

  it('rejects unknown routes and unexpected parameters', async () => {
    const router = { push: vi.fn() }

    await expect(dispatchUiAction(
      { type: 'NAVIGATE', routeKey: 'https://evil.example', params: {}, reason: 'bad' },
      router,
    )).rejects.toThrow('不受支持')
    await expect(dispatchUiAction(
      {
        type: 'NAVIGATE',
        routeKey: 'WRONG_QUESTIONS',
        params: { url: 'javascript:alert(1)' },
        reason: 'bad',
      },
      router,
    )).rejects.toThrow('参数')
    expect(router.push).not.toHaveBeenCalled()
  })

  it('covers every current StudyPilot page family through fixed route keys', async () => {
    const cases = [
      ['LEARNING_GOALS', {}, 'goals', undefined],
      ['LEARNING_PLANS', {}, 'plans', undefined],
      ['LEARNING_PLAN', { planId: 'plan-1' }, 'plan-detail', { id: 'plan-1' }],
      ['PLAN_ASSISTANT', {}, 'agent-plan', undefined],
      ['TASK_ASSISTANT', {}, 'agent-tasks', undefined],
      ['WORKSPACE_ARTIFACTS', {}, 'workspace-artifacts', undefined],
    ] as const

    for (const [routeKey, params, name, mappedParams] of cases) {
      const push = vi.fn().mockResolvedValue(undefined)
      await dispatchUiAction({ type: 'NAVIGATE', routeKey, params, reason: 'coverage' }, { push })
      expect(push).toHaveBeenCalledWith(mappedParams ? { name, params: mappedParams } : { name })
    }
  })
})
