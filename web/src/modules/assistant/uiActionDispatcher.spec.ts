import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { AssistantUiAction } from '@/types/assistant'
import {
  clearActionReceiptsForTest,
  dispatchUiAction,
  getActionReceipt,
  recordActionReceipt,
  validateUiActionCapability,
  type UiActionContext,
} from './uiActionDispatcher'

describe('assistant UI action dispatcher (Task 30)', () => {
  let router: { push: ReturnType<typeof vi.fn>; currentRoute: { value: { name: string; path: string } } }
  let modalManager: { open: ReturnType<typeof vi.fn> }
  let formDraftStore: { setDraft: ReturnType<typeof vi.fn>; lastDraft: Record<string, unknown> | null }
  let resourceManager: { refresh: ReturnType<typeof vi.fn> }
  let focusManager: { focus: ReturnType<typeof vi.fn> }
  let context: UiActionContext

  beforeEach(() => {
    clearActionReceiptsForTest()
    router = {
      push: vi.fn().mockResolvedValue(undefined),
      currentRoute: { value: { name: 'assistant', path: '/assistant' } },
    }
    modalManager = {
      open: vi.fn().mockResolvedValue(true),
    }
    formDraftStore = {
      setDraft: vi.fn((_formKey, draft) => {
        formDraftStore.lastDraft = draft
      }),
      lastDraft: null,
    }
    resourceManager = {
      refresh: vi.fn().mockResolvedValue(true),
    }
    focusManager = {
      focus: vi.fn().mockReturnValue(true),
    }
    context = {
      router,
      modalManager,
      formDraftStore,
      resourceManager,
      focusManager,
    }
  })

  describe('1. NAVIGATE action', () => {
    it('maps registered route key without accepting arbitrary URLs and returns SUCCEEDED receipt', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-nav-1',
        type: 'NAVIGATE',
        routeKey: 'ROADMAP_NODE',
        params: { nodeId: 'node-1' },
        reason: '继续学习节点',
      }

      const receipt = await dispatchUiAction(action, context)

      expect(router.push).toHaveBeenCalledWith({ name: 'roadmap-node', params: { id: 'node-1' } })
      expect(receipt).toEqual({
        actionId: 'act-nav-1',
        status: 'SUCCEEDED',
        currentRoute: 'roadmap-node',
        error: null,
      })
    })

    it('rejects unknown routes and returns REJECTED receipt with sanitized error and route name', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-nav-2',
        type: 'NAVIGATE',
        routeKey: 'https://evil.com/phishing',
        params: {},
        reason: '恶意跳转',
      }

      await expect(dispatchUiAction(action, context)).rejects.toThrow('不受支持的页面动作')
      expect(router.push).not.toHaveBeenCalled()

      const cached = getActionReceipt('act-nav-2')
      expect(cached?.status).toBe('REJECTED')
      expect(cached?.currentRoute).toBe('assistant')
      expect(cached?.error).not.toMatch(/https:\/\/evil\.com/)
    })

    it('returns FAILED receipt if router.push throws an error', async () => {
      router.push.mockRejectedValueOnce(new Error('Navigation cancelled by navigation guard'))

      const action: AssistantUiAction = {
        actionId: 'act-nav-fail',
        type: 'NAVIGATE',
        routeKey: 'DASHBOARD',
        params: {},
        reason: '进入仪表盘',
      }

      await expect(dispatchUiAction(action, context)).rejects.toThrow('导航失败')
      const cached = getActionReceipt('act-nav-fail')
      expect(cached?.status).toBe('FAILED')
      expect(cached?.currentRoute).toBe('assistant')
      expect(cached?.error).toBe('导航失败')
    })

    it('covers all registered StudyPilot page families through fixed route keys', async () => {
      const cases = [
        ['DASHBOARD', {}, 'dashboard', undefined],
        ['ASSISTANT', {}, 'assistant', undefined],
        ['ASSISTANT_HEALTH', {}, 'assistant-health', undefined],
        ['ROADMAP', {}, 'roadmap', undefined],
        ['ROADMAP_STAGE', { stageId: 'stage-1' }, 'roadmap-stage', { id: 'stage-1' }],
        ['ROADMAP_MODULE', { moduleId: 'module-1' }, 'roadmap-module', { id: 'module-1' }],
        ['ROADMAP_NODE', { nodeId: 'node-1' }, 'roadmap-node', { id: 'node-1' }],
        ['LEARNING_GOALS', {}, 'goals', undefined],
        ['LEARNING_PLANS', {}, 'plans', undefined],
        ['LEARNING_PLAN', { planId: 'plan-1' }, 'plan-detail', { id: 'plan-1' }],
        ['COURSES', {}, 'courses', undefined],
        ['COURSE_DETAIL', { courseSlug: 'spring-boot' }, 'course-detail', { slug: 'spring-boot' }],
        ['LESSON', { lessonId: 'lesson-1' }, 'lesson', { lessonId: 'lesson-1' }],
        ['TODAY', {}, 'today', undefined],
        ['MATERIALS', {}, 'materials', undefined],
        ['MATERIAL_DETAIL', { materialId: 'mat-1' }, 'material-detail', { id: 'mat-1' }],
        ['QUIZ', { quizId: 'quiz-1' }, 'quiz', { id: 'quiz-1' }],
        ['QUIZ_ATTEMPT', { attemptId: 'att-1' }, 'attempt', { id: 'att-1' }],
        ['WRONG_QUESTIONS', {}, 'wrong-questions', undefined],
        ['MASTERY', {}, 'mastery', undefined],
        ['KNOWLEDGE', {}, 'knowledge', undefined],
        ['PLAN_ASSISTANT', {}, 'agent-plan', undefined],
        ['TASK_ASSISTANT', {}, 'agent-tasks', undefined],
        ['NOTIFICATIONS', {}, 'notifications', undefined],
        ['AGENT_ACTIVITY', {}, 'activity', undefined],
        ['LEARNING_SETTINGS', {}, 'settings', undefined],
        ['AI_SETTINGS', {}, 'settings-ai', undefined],
        ['WORKSPACE_ARTIFACTS', {}, 'workspace-artifacts', undefined],
      ] as const

      for (const [routeKey, params, name, mappedParams] of cases) {
        const push = vi.fn().mockResolvedValue(undefined)
        const mockContext: UiActionContext = {
          router: {
            push,
            currentRoute: { value: { name, path: `/${name}` } },
          },
        }
        const receipt = await dispatchUiAction(
          { actionId: `cov-${routeKey}`, type: 'NAVIGATE', routeKey, params, reason: 'coverage' },
          mockContext,
        )
        expect(push).toHaveBeenCalledWith(mappedParams ? { name, params: mappedParams } : { name })
        expect(receipt.status).toBe('SUCCEEDED')
        expect(receipt.currentRoute).toBe(name)
      }
    })
  })

  describe('2. OPEN_MODAL action & frozen matrix', () => {
    it('accepts all frozen modal route/modalKey pairs', async () => {
      const cases = [
        ['LEARNING_GOALS', 'CREATE_GOAL'],
        ['LEARNING_PLANS', 'CREATE_PLAN'],
        ['MATERIALS', 'IMPORT_MATERIAL'],
      ] as const

      for (const [routeKey, modalKey] of cases) {
        const action: AssistantUiAction = {
          actionId: `act-modal-${routeKey}`,
          type: 'OPEN_MODAL',
          routeKey,
          params: { modalKey },
          reason: '打开弹窗',
        }

        const receipt = await dispatchUiAction(action, context)
        expect(modalManager.open).toHaveBeenCalledWith(modalKey, {})
        expect(receipt.status).toBe('SUCCEEDED')
      }
    })

    it('strictly rejects mismatched routeKey and modalKey before invoking modalManager', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-modal-mismatch',
        type: 'OPEN_MODAL',
        routeKey: 'MATERIALS', // Expects IMPORT_MATERIAL
        params: { modalKey: 'CREATE_GOAL' },
        reason: '跨路由弹窗不合法',
      }

      await expect(dispatchUiAction(action, context)).rejects.toThrow('不受支持的弹窗动作')
      expect(modalManager.open).not.toHaveBeenCalled()
    })

    it('strictly rejects unsupported placeholder modals (e.g. CONFIRM_ACTION, REGISTER_WORKSPACE)', async () => {
      const unsupported = ['CONFIRM_ACTION', 'REGISTER_WORKSPACE', 'NODE_QUIZ_PREVIEW', 'TASK_CONFIRMATION']
      for (const modalKey of unsupported) {
        const action: AssistantUiAction = {
          actionId: `act-modal-unsupp-${modalKey}`,
          type: 'OPEN_MODAL',
          routeKey: 'ROADMAP',
          params: { modalKey },
          reason: '占位符弹窗已废弃',
        }
        await expect(dispatchUiAction(action, context)).rejects.toThrow('不受支持的弹窗动作')
        expect(modalManager.open).not.toHaveBeenCalled()
      }
    })

    it('does not report success when the registered modal executor is unavailable', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-modal-missing-executor',
        type: 'OPEN_MODAL',
        routeKey: 'LEARNING_GOALS',
        params: { modalKey: 'CREATE_GOAL' },
        reason: '创建目标',
      }

      await expect(dispatchUiAction(action, { router })).rejects.toThrow('弹窗执行器不可用')
      expect(getActionReceipt(action.actionId!)).toMatchObject({
        actionId: action.actionId,
        status: 'FAILED',
      })
    })
  })

  describe('3. PREFILL_FORM action & frozen matrix', () => {
    it('accepts all frozen form route/formKey pairs with valid draft data', async () => {
      const cases: { routeKey: string; formKey: string; fields: Record<string, string> }[] = [
        {
          routeKey: 'LEARNING_GOALS',
          formKey: 'GOAL_FORM',
          fields: { title: '目标A', targetDate: '2026-10-01', weeklyStudyHours: '10' },
        },
        {
          routeKey: 'LEARNING_PLANS',
          formKey: 'PLAN_FORM',
          fields: { title: '计划B', goalId: 'goal-1', startDate: '2026-10-01', endDate: '2026-10-31' },
        },
        {
          routeKey: 'MATERIALS',
          formKey: 'MATERIAL_FORM',
          fields: { title: '资料C', content: '这是一篇学习资料' },
        },
      ]

      for (const { routeKey, formKey, fields } of cases) {
        const action: AssistantUiAction = {
          actionId: `act-form-${routeKey}`,
          type: 'PREFILL_FORM',
          routeKey,
          params: { formKey, ...fields },
          reason: '预填草稿',
        }

        const receipt = await dispatchUiAction(action, context)
        expect(formDraftStore.setDraft).toHaveBeenCalledWith(formKey, fields)
        expect(receipt.status).toBe('SUCCEEDED')
      }
    })

    it('strictly rejects mismatched routeKey and formKey before invoking formDraftStore', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-form-mismatch',
        type: 'PREFILL_FORM',
        routeKey: 'MATERIALS', // Expects MATERIAL_FORM
        params: { formKey: 'GOAL_FORM', title: '目标' },
        reason: '跨路由表单不合法',
      }

      await expect(dispatchUiAction(action, context)).rejects.toThrow('不受支持的表单草稿预填')
      expect(formDraftStore.setDraft).not.toHaveBeenCalled()
    })

    it('strictly rejects unsupported placeholder form keys (e.g. FEEDBACK_FORM)', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-form-feedback',
        type: 'PREFILL_FORM',
        routeKey: 'LEARNING_GOALS',
        params: { formKey: 'FEEDBACK_FORM', content: '意见', category: 'bug' },
        reason: '意见反馈不属于冻结矩阵',
      }

      await expect(dispatchUiAction(action, context)).rejects.toThrow('不受支持的表单草稿预填')
      expect(formDraftStore.setDraft).not.toHaveBeenCalled()
    })

    it('strictly rejects forbidden fields targetDate and dailyMinutes on PLAN_FORM', async () => {
      const actionWithTargetDate: AssistantUiAction = {
        actionId: 'act-plan-target-date',
        type: 'PREFILL_FORM',
        routeKey: 'LEARNING_PLANS',
        params: {
          formKey: 'PLAN_FORM',
          title: '违规目标日期计划',
          targetDate: '2026-10-01',
        },
        reason: '非法 targetDate 字段',
      }
      await expect(dispatchUiAction(actionWithTargetDate, context)).rejects.toThrow(
        '表单草稿包含未授权字段: targetDate',
      )

      const actionWithDailyMinutes: AssistantUiAction = {
        actionId: 'act-plan-daily-minutes',
        type: 'PREFILL_FORM',
        routeKey: 'LEARNING_PLANS',
        params: {
          formKey: 'PLAN_FORM',
          title: '违规每日分钟计划',
          dailyMinutes: '45',
        },
        reason: '非法 dailyMinutes 字段',
      }
      await expect(dispatchUiAction(actionWithDailyMinutes, context)).rejects.toThrow(
        '表单草稿包含未授权字段: dailyMinutes',
      )
    })

    it('strictly rejects any autoSubmit parameter', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-form-autosubmit',
        type: 'PREFILL_FORM',
        routeKey: 'LEARNING_PLANS',
        params: {
          formKey: 'PLAN_FORM',
          title: '违规自动提交计划',
          autoSubmit: 'true',
        },
        reason: '尝试自动提交',
      }

      await expect(dispatchUiAction(action, context)).rejects.toThrow('表单动作严禁自动提交')
      expect(formDraftStore.setDraft).not.toHaveBeenCalled()
    })

    it('does not report success when the registered draft executor is unavailable', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-form-missing-executor',
        type: 'PREFILL_FORM',
        routeKey: 'LEARNING_PLANS',
        params: { formKey: 'PLAN_FORM', title: '计划草稿', goalId: 'g-1', startDate: '2026-10-01', endDate: '2026-10-20' },
        reason: '预填计划草稿',
      }

      await expect(dispatchUiAction(action, { router })).rejects.toThrow('表单草稿执行器不可用')
      expect(getActionReceipt(action.actionId!)).toMatchObject({ status: 'FAILED' })
    })
  })

  describe('4. REFRESH_RESOURCE action & frozen matrix', () => {
    it('accepts all 8 frozen resource refresh routeKey/resourceKey pairs', async () => {
      const cases = [
        ['ROADMAP', 'ROADMAP'],
        ['TODAY', 'TODAY_TASKS'],
        ['LEARNING_GOALS', 'LEARNING_GOALS'],
        ['LEARNING_PLANS', 'LEARNING_PLANS'],
        ['NOTIFICATIONS', 'NOTIFICATIONS'],
        ['WRONG_QUESTIONS', 'WRONG_QUESTIONS'],
        ['MASTERY', 'MASTERY'],
        ['AGENT_ACTIVITY', 'ACTIVITY'],
      ] as const

      for (const [routeKey, resourceKey] of cases) {
        const action: AssistantUiAction = {
          actionId: `act-ref-${routeKey}`,
          type: 'REFRESH_RESOURCE',
          routeKey,
          params: { resourceKey },
          reason: '刷新资源',
        }

        const receipt = await dispatchUiAction(action, context)
        expect(resourceManager.refresh).toHaveBeenCalledWith(resourceKey)
        expect(receipt.status).toBe('SUCCEEDED')
      }
    })

    it('strictly rejects mismatched routeKey and resourceKey before invoking resourceManager', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-ref-mismatch',
        type: 'REFRESH_RESOURCE',
        routeKey: 'ROADMAP', // Expects ROADMAP
        params: { resourceKey: 'NOTIFICATIONS' },
        reason: '跨路由资源不合法',
      }

      await expect(dispatchUiAction(action, context)).rejects.toThrow('不受支持的资源刷新')
      expect(resourceManager.refresh).not.toHaveBeenCalled()
    })

    it('rejects unregistered resource key', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-ref-bad',
        type: 'REFRESH_RESOURCE',
        routeKey: 'ROADMAP',
        params: { resourceKey: 'SYSTEM_INTERNAL_DATABASE' },
        reason: '非法刷新',
      }

      await expect(dispatchUiAction(action, context)).rejects.toThrow('不受支持的资源刷新')
      expect(resourceManager.refresh).not.toHaveBeenCalled()
    })

    it('does not report success when the registered refresh executor is unavailable', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-refresh-missing-executor',
        type: 'REFRESH_RESOURCE',
        routeKey: 'ROADMAP',
        params: { resourceKey: 'ROADMAP' },
        reason: '刷新路线',
      }

      await expect(dispatchUiAction(action, { router })).rejects.toThrow('资源刷新执行器不可用')
      expect(getActionReceipt(action.actionId!)).toMatchObject({ status: 'FAILED' })
    })
  })

  describe('5. FOCUS_ELEMENT action & frozen matrix', () => {
    it('accepts all frozen focus routeKey/elementKey pairs', async () => {
      const cases = [
        ['ASSISTANT', 'MESSAGE_INPUT'],
        ['LEARNING_PLANS', 'PLAN_TITLE_INPUT'],
      ] as const

      for (const [routeKey, elementKey] of cases) {
        const action: AssistantUiAction = {
          actionId: `act-focus-${routeKey}`,
          type: 'FOCUS_ELEMENT',
          routeKey,
          params: { elementKey },
          reason: '聚焦元素',
        }

        const receipt = await dispatchUiAction(action, context)
        expect(focusManager.focus).toHaveBeenCalledWith(elementKey)
        expect(receipt.status).toBe('SUCCEEDED')
      }
    })

    it('strictly rejects mismatched routeKey and elementKey before invoking focusManager', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-focus-mismatch',
        type: 'FOCUS_ELEMENT',
        routeKey: 'LEARNING_PLANS', // Expects PLAN_TITLE_INPUT
        params: { elementKey: 'MESSAGE_INPUT' },
        reason: '跨路由聚焦不匹配',
      }

      await expect(dispatchUiAction(action, context)).rejects.toThrow('不受支持的元素聚焦')
      expect(focusManager.focus).not.toHaveBeenCalled()
    })

    it('strictly rejects unsupported placeholder element keys (e.g. STUDY_INPUT, SEARCH_INPUT)', async () => {
      const unsupported = ['STUDY_INPUT', 'SEARCH_INPUT', 'QUIZ_ANSWER_AREA', 'ARTIFACT_REVIEW_BUTTON']
      for (const elementKey of unsupported) {
        const action: AssistantUiAction = {
          actionId: `act-focus-unsupp-${elementKey}`,
          type: 'FOCUS_ELEMENT',
          routeKey: 'ASSISTANT',
          params: { elementKey },
          reason: '占位元素已剔除',
        }

        await expect(dispatchUiAction(action, context)).rejects.toThrow('不受支持的元素聚焦')
        expect(focusManager.focus).not.toHaveBeenCalled()
      }
    })

    it('strictly rejects raw CSS selectors in elementKey', async () => {
      const rawSelectors = [
        '#submit-button',
        '.quiz-option:first-child',
        'input[name="password"]',
        'div > button',
        'script',
      ]

      for (const selector of rawSelectors) {
        const action: AssistantUiAction = {
          actionId: `act-focus-bad-${selector}`,
          type: 'FOCUS_ELEMENT',
          routeKey: 'ASSISTANT',
          params: { elementKey: selector },
          reason: '尝试传入选择器',
        }

        await expect(dispatchUiAction(action, context)).rejects.toThrow('不受支持的元素聚焦')
        expect(focusManager.focus).not.toHaveBeenCalled()
      }
    })

    it('does not report success when the registered focus executor is unavailable', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-focus-missing-executor',
        type: 'FOCUS_ELEMENT',
        routeKey: 'ASSISTANT',
        params: { elementKey: 'MESSAGE_INPUT' },
        reason: '聚焦输入框',
      }

      await expect(dispatchUiAction(action, { router })).rejects.toThrow('元素聚焦执行器不可用')
      expect(getActionReceipt(action.actionId!)).toMatchObject({ status: 'FAILED' })
    })
  })

  describe('6. Pure preflight validation helper validateUiActionCapability', () => {
    it('passes for aligned frozen pairs without requiring router or context', () => {
      expect(() =>
        validateUiActionCapability({
          actionId: 'test-preflight-1',
          type: 'OPEN_MODAL',
          routeKey: 'LEARNING_GOALS',
          params: { modalKey: 'CREATE_GOAL' },
          reason: 'preflight check',
        }),
      ).not.toThrow()

      expect(() =>
        validateUiActionCapability({
          actionId: 'test-preflight-2',
          type: 'REFRESH_RESOURCE',
          routeKey: 'NOTIFICATIONS',
          params: { resourceKey: 'NOTIFICATIONS' },
          reason: 'preflight check',
        }),
      ).not.toThrow()
    })

    it('throws error for mismatched or unallowed capability before navigation or execution', () => {
      expect(() =>
        validateUiActionCapability({
          actionId: 'test-preflight-bad',
          type: 'OPEN_MODAL',
          routeKey: 'ROADMAP',
          params: { modalKey: 'CREATE_GOAL' },
          reason: 'mismatched',
        }),
      ).toThrow('不受支持的弹窗动作')
    })
  })

  describe('7. Parameter Security and Defense in Depth', () => {
    it('rejects action-specific extra fields before invoking an executor', async () => {
      const cases: Array<{ action: AssistantUiAction; executor: ReturnType<typeof vi.fn> }> = [
        {
          action: {
            actionId: 'act-modal-extra-field',
            type: 'OPEN_MODAL',
            routeKey: 'LEARNING_GOALS',
            params: { modalKey: 'CREATE_GOAL', arbitraryText: 'hidden' },
            reason: '弹窗',
          },
          executor: modalManager.open,
        },
        {
          action: {
            actionId: 'act-refresh-extra-field',
            type: 'REFRESH_RESOURCE',
            routeKey: 'ROADMAP',
            params: { resourceKey: 'ROADMAP', arbitraryText: 'hidden' },
            reason: '刷新',
          },
          executor: resourceManager.refresh,
        },
        {
          action: {
            actionId: 'act-focus-extra-field',
            type: 'FOCUS_ELEMENT',
            routeKey: 'ASSISTANT',
            params: { elementKey: 'MESSAGE_INPUT', arbitraryText: 'hidden' },
            reason: '聚焦',
          },
          executor: focusManager.focus,
        },
      ]

      for (const { action, executor } of cases) {
        await expect(dispatchUiAction(action, context)).rejects.toThrow('包含未授权字段')
        expect(executor).not.toHaveBeenCalled()
      }
    })

    it('strictly rejects any parameter containing ownerId', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-sec-ownerId',
        type: 'NAVIGATE',
        routeKey: 'LEARNING_PLAN',
        params: { planId: 'plan-1', ownerId: 'user-hacker' },
        reason: '越权注入 ownerId',
      }

      await expect(dispatchUiAction(action, context)).rejects.toThrow('严禁携带 ownerId')
      expect(router.push).not.toHaveBeenCalled()
    })

    it('strictly rejects values with HTML or JavaScript tags', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-sec-xss',
        type: 'PREFILL_FORM',
        routeKey: 'LEARNING_PLANS',
        params: {
          formKey: 'PLAN_FORM',
          title: '<script>alert("xss")</script>',
          goalId: 'g-1',
          startDate: '2026-10-01',
          endDate: '2026-10-30',
        },
        reason: 'XSS 注入',
      }

      await expect(dispatchUiAction(action, context)).rejects.toThrow('包含非法字符')
      expect(formDraftStore.setDraft).not.toHaveBeenCalled()
    })

    it('strictly rejects URL protocols in parameters', async () => {
      const evilValues = [
        'javascript:void(0)',
        'http://attacker.com',
        'https://attacker.com',
        '//attacker.com/evil',
      ]

      for (const evil of evilValues) {
        const action: AssistantUiAction = {
          actionId: `act-sec-url-${evil}`,
          type: 'NAVIGATE',
          routeKey: 'ROADMAP_NODE',
          params: { nodeId: evil },
          reason: 'URL 注入',
        }

        await expect(dispatchUiAction(action, context)).rejects.toThrow('非法')
      }
    })
  })

  describe('8. Authenticity Protection (用户专属行为严禁代办)', () => {
    it('rejects "替我答题" and only allows navigating to quiz page', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-auth-quiz',
        type: 'PREFILL_FORM',
        routeKey: 'QUIZ',
        params: {
          formKey: 'QUIZ_SUBMISSION',
          answers: 'A,B,C',
        },
        reason: '替我答题',
      }

      await expect(dispatchUiAction(action, context)).rejects.toThrow('答题操作属于用户专属行为，严禁代办')
      expect(formDraftStore.setDraft).not.toHaveBeenCalled()

      const safeNavAction: AssistantUiAction = {
        actionId: 'act-auth-quiz-nav',
        type: 'NAVIGATE',
        routeKey: 'QUIZ',
        params: { quizId: 'quiz-1' },
        reason: '导航至测验页面由用户亲自作答',
      }

      const receipt = await dispatchUiAction(safeNavAction, context)
      expect(receipt.status).toBe('SUCCEEDED')
      expect(router.push).toHaveBeenCalledWith({ name: 'quiz', params: { id: 'quiz-1' } })
    })

    it('rejects "替我写打卡总结"', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-auth-checkin',
        type: 'PREFILL_FORM',
        routeKey: 'TODAY',
        params: {
          formKey: 'CHECKIN_SUMMARY_SUBMIT',
          summary: '今天学完了 Spring Boot',
        },
        reason: '替我写打卡总结',
      }

      await expect(dispatchUiAction(action, context)).rejects.toThrow('打卡总结属于用户专属思考，严禁代办')
    })

    it('rejects "直接接受成果"', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-auth-artifact',
        type: 'PREFILL_FORM',
        routeKey: 'WORKSPACE_ARTIFACTS',
        params: {
          formKey: 'ACCEPT_ARTIFACT_SUBMISSION',
          artifactId: 'art-1',
        },
        reason: '直接接受成果',
      }

      await expect(dispatchUiAction(action, context)).rejects.toThrow('成果评审接受属于用户专属决策，严禁代办')
    })
  })

  describe('9. Action Receipt Adapter & Idempotence', () => {
    it('returns identical receipt on duplicate actionId without re-executing action', async () => {
      const action: AssistantUiAction = {
        actionId: 'act-idemp-1',
        type: 'REFRESH_RESOURCE',
        routeKey: 'ROADMAP',
        params: { resourceKey: 'ROADMAP' },
        reason: '刷新路线',
      }

      const receipt1 = await dispatchUiAction(action, context)
      expect(receipt1.status).toBe('SUCCEEDED')
      expect(resourceManager.refresh).toHaveBeenCalledTimes(1)

      const receipt2 = await dispatchUiAction(action, context)
      expect(receipt2).toEqual(receipt1)
      expect(resourceManager.refresh).toHaveBeenCalledTimes(1)
    })

    it('throws 409 Conflict if same actionId is reported with conflicting terminal status', async () => {
      recordActionReceipt({
        actionId: 'act-conflict-1',
        status: 'SUCCEEDED',
        currentRoute: 'roadmap',
        error: null,
      })

      expect(() =>
        recordActionReceipt({
          actionId: 'act-conflict-1',
          status: 'FAILED',
          currentRoute: 'roadmap',
          error: '冲突失败',
        }),
      ).toThrow('409')
    })

    it('sanitizes error field in receipt, omitting stack traces, URLs or auth credentials', async () => {
      const dirtyError = 'Error: Bearer secret-token-xyz at /app/node_modules/vue-router/index.js:123:45\n  at dispatch (http://localhost:5173/src/main.ts)'
      const action: AssistantUiAction = {
        actionId: 'act-sanitize-err',
        type: 'NAVIGATE',
        routeKey: 'LEARNING_PLAN',
        params: { planId: 'plan-1' },
        reason: '导航',
      }

      router.push.mockRejectedValueOnce(new Error(dirtyError))
      await expect(dispatchUiAction(action, context)).rejects.toThrow()

      const receipt = getActionReceipt('act-sanitize-err')
      expect(receipt?.error).not.toMatch(/Bearer/)
      expect(receipt?.error).not.toMatch(/http:\/\//)
      expect(receipt?.error).not.toMatch(/node_modules/)
      expect(receipt?.currentRoute).toBe('assistant')
    })

    describe('Scope isolation for in-memory terminal receipts', () => {
      it('deduplicates identical actionId within the same scope', async () => {
        const action: AssistantUiAction = {
          actionId: 'act-scope-dup',
          type: 'REFRESH_RESOURCE',
          routeKey: 'ROADMAP',
          params: { resourceKey: 'ROADMAP' },
          reason: '刷新路线',
        }
        const scopedContext: UiActionContext = {
          ...context,
          receiptScope: 'userA:conv1',
        }

        const receipt1 = await dispatchUiAction(action, scopedContext)
        expect(receipt1.status).toBe('SUCCEEDED')
        expect(resourceManager.refresh).toHaveBeenCalledTimes(1)

        const receipt2 = await dispatchUiAction(action, scopedContext)
        expect(receipt2).toEqual(receipt1)
        expect(resourceManager.refresh).toHaveBeenCalledTimes(1)
        expect(receipt1).not.toHaveProperty('receiptScope')
        expect(receipt1).not.toHaveProperty('scope')
      })

      it('executes separately and maintains distinct receipts for same actionId in different scopes', async () => {
        const action: AssistantUiAction = {
          actionId: 'act-shared-id',
          type: 'REFRESH_RESOURCE',
          routeKey: 'ROADMAP',
          params: { resourceKey: 'ROADMAP' },
          reason: '刷新路线',
        }
        const contextScopeA: UiActionContext = {
          ...context,
          receiptScope: 'owner-100:conv-1',
        }
        const contextScopeB: UiActionContext = {
          ...context,
          receiptScope: 'owner-200:conv-2',
        }

        const receiptA = await dispatchUiAction(action, contextScopeA)
        expect(receiptA.status).toBe('SUCCEEDED')
        expect(resourceManager.refresh).toHaveBeenCalledTimes(1)

        const receiptB = await dispatchUiAction(action, contextScopeB)
        expect(receiptB.status).toBe('SUCCEEDED')
        expect(resourceManager.refresh).toHaveBeenCalledTimes(2)

        const cachedA = getActionReceipt('act-shared-id', 'owner-100:conv-1')
        const cachedB = getActionReceipt('act-shared-id', 'owner-200:conv-2')
        expect(cachedA).toBeDefined()
        expect(cachedB).toBeDefined()
        expect(cachedA).not.toBe(cachedB)
        expect(cachedA?.actionId).toBe('act-shared-id')
        expect(cachedB?.actionId).toBe('act-shared-id')
        // Wire receipts remain unchanged and contain no scope property
        expect(cachedA).not.toHaveProperty('scope')
        expect(cachedA).not.toHaveProperty('receiptScope')
        expect(cachedB).not.toHaveProperty('scope')
        expect(cachedB).not.toHaveProperty('receiptScope')
      })

      it('rejects invalid or unsafe receiptScope (empty, whitespace, overly long, or containing token/email pattern)', async () => {
        const action: AssistantUiAction = {
          actionId: 'act-scope-invalid',
          type: 'REFRESH_RESOURCE',
          routeKey: 'ROADMAP',
          params: { resourceKey: 'ROADMAP' },
          reason: '刷新路线',
        }

        // Empty string scope
        await expect(
          dispatchUiAction(action, { ...context, receiptScope: '   ' }),
        ).rejects.toThrow('receiptScope')

        // Scope containing email
        await expect(
          dispatchUiAction(action, { ...context, receiptScope: 'user@example.com:conv1' }),
        ).rejects.toThrow('receiptScope')

        // Scope containing Bearer token
        await expect(
          dispatchUiAction(action, { ...context, receiptScope: 'Bearer eyJhbGciOi' }),
        ).rejects.toThrow('receiptScope')

        // Scope exceeding length bound
        const tooLongScope = 'a'.repeat(257)
        await expect(
          dispatchUiAction(action, { ...context, receiptScope: tooLongScope }),
        ).rejects.toThrow('receiptScope')
      })
    })

    describe('Stable actionId boundary enforcement', () => {
      it('rejects execution when actionId is undefined and performs zero side effects or receipt writes', async () => {
        const actionWithoutId: AssistantUiAction = {
          type: 'NAVIGATE',
          routeKey: 'ROADMAP_NODE',
          params: { nodeId: 'node-1' },
          reason: '缺少 actionId 的导航动作',
        }

        await expect(dispatchUiAction(actionWithoutId, context)).rejects.toThrow(
          '动作必须包含合法的 stable actionId',
        )

        expect(router.push).not.toHaveBeenCalled()
        expect(modalManager.open).not.toHaveBeenCalled()
        expect(formDraftStore.setDraft).not.toHaveBeenCalled()
        expect(resourceManager.refresh).not.toHaveBeenCalled()
        expect(focusManager.focus).not.toHaveBeenCalled()

        // Verify zero retrievable receipts under empty, undefined, or generated key patterns
        expect(getActionReceipt('')).toBeUndefined()
        expect(getActionReceipt('undefined')).toBeUndefined()
      })

      it('rejects execution when actionId is whitespace-only and performs zero side effects or receipt writes', async () => {
        const actionWithBlankId: AssistantUiAction = {
          actionId: '    \t\n   ',
          type: 'OPEN_MODAL',
          routeKey: 'LEARNING_GOALS',
          params: { modalKey: 'CREATE_GOAL' },
          reason: '空白 actionId 的动作',
        }

        await expect(dispatchUiAction(actionWithBlankId, context)).rejects.toThrow(
          '动作必须包含合法的 stable actionId',
        )

        expect(router.push).not.toHaveBeenCalled()
        expect(modalManager.open).not.toHaveBeenCalled()
        expect(formDraftStore.setDraft).not.toHaveBeenCalled()
        expect(resourceManager.refresh).not.toHaveBeenCalled()
        expect(focusManager.focus).not.toHaveBeenCalled()

        expect(getActionReceipt('    \t\n   ')).toBeUndefined()
        expect(getActionReceipt('')).toBeUndefined()
      })

      it('trims leading/trailing whitespace on valid actionId and preserves existing execution behavior', async () => {
        const actionWithPaddedId: AssistantUiAction = {
          actionId: '  act-trimmed-valid-1  ',
          type: 'REFRESH_RESOURCE',
          routeKey: 'ROADMAP',
          params: { resourceKey: 'ROADMAP' },
          reason: '刷新路线',
        }

        const receipt = await dispatchUiAction(actionWithPaddedId, context)

        expect(resourceManager.refresh).toHaveBeenCalledWith('ROADMAP')
        expect(receipt.actionId).toBe('act-trimmed-valid-1')
        expect(receipt.status).toBe('SUCCEEDED')

        const cached = getActionReceipt('act-trimmed-valid-1')
        expect(cached?.actionId).toBe('act-trimmed-valid-1')
        expect(cached?.status).toBe('SUCCEEDED')
      })
    })
  })
})
