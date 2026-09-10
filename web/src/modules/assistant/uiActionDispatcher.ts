import type { AssistantUiAction, UiActionReceipt, UiActionReceiptStatus } from '@/types/assistant'

export interface RouterLike {
  push(location: { name: string; params?: Record<string, string> }): Promise<unknown> | unknown
  currentRoute?: { value?: { name?: string | symbol | null; path?: string } } | { name?: string | symbol | null; path?: string }
}

export interface ModalManagerLike {
  open(modalKey: string, payload?: Record<string, unknown>): Promise<unknown> | unknown
}

export interface FormDraftStoreLike {
  setDraft(formKey: string, draft: Record<string, unknown>): void
}

export interface ResourceManagerLike {
  refresh(resourceKey: string): Promise<unknown> | unknown
}

export interface FocusManagerLike {
  focus(elementKey: string): boolean | void
}

export interface UiActionContext {
  router: RouterLike
  modalManager?: ModalManagerLike
  formDraftStore?: FormDraftStoreLike
  resourceManager?: ResourceManagerLike
  focusManager?: FocusManagerLike
}

interface RouteDefinition {
  name: string
  params: Record<string, string>
}

/** 31 个具名路由白名单映射 (依据 docs/agent-capability-matrix-v2.md) */
const routes: Record<string, RouteDefinition> = {
  DASHBOARD: { name: 'dashboard', params: {} },
  ASSISTANT: { name: 'assistant', params: {} },
  ASSISTANT_HEALTH: { name: 'assistant-health', params: {} },
  ROADMAP: { name: 'roadmap', params: {} },
  ROADMAP_STAGE: { name: 'roadmap-stage', params: { stageId: 'id' } },
  ROADMAP_MODULE: { name: 'roadmap-module', params: { moduleId: 'id' } },
  ROADMAP_NODE: { name: 'roadmap-node', params: { nodeId: 'id' } },
  LEARNING_GOALS: { name: 'goals', params: {} },
  LEARNING_PLANS: { name: 'plans', params: {} },
  LEARNING_PLAN: { name: 'plan-detail', params: { planId: 'id' } },
  COURSES: { name: 'courses', params: {} },
  COURSE_DETAIL: { name: 'course-detail', params: { courseSlug: 'slug' } },
  LESSON: { name: 'lesson', params: { lessonId: 'lessonId' } },
  TODAY: { name: 'today', params: {} },
  MATERIALS: { name: 'materials', params: {} },
  MATERIAL_DETAIL: { name: 'material-detail', params: { materialId: 'id' } },
  QUIZ: { name: 'quiz', params: { quizId: 'id' } },
  QUIZ_ATTEMPT: { name: 'attempt', params: { attemptId: 'id' } },
  WRONG_QUESTIONS: { name: 'wrong-questions', params: {} },
  MASTERY: { name: 'mastery', params: {} },
  KNOWLEDGE: { name: 'knowledge', params: {} },
  PLAN_ASSISTANT: { name: 'agent-plan', params: {} },
  TASK_ASSISTANT: { name: 'agent-tasks', params: {} },
  NOTIFICATIONS: { name: 'notifications', params: {} },
  AGENT_ACTIVITY: { name: 'activity', params: {} },
  LEARNING_SETTINGS: { name: 'settings', params: {} },
  AI_SETTINGS: { name: 'settings-ai', params: {} },
  WORKSPACE_ARTIFACTS: { name: 'workspace-artifacts', params: {} },
}

/** 白名单弹窗注册表 */
const ALLOWED_MODALS = new Set([
  'CONFIRM_ACTION',
  'CREATE_PLAN',
  'CREATE_GOAL',
  'IMPORT_MATERIAL',
  'REGISTER_WORKSPACE',
  'NODE_QUIZ_PREVIEW',
  'REVIEW_WRONG_QUESTION',
  'TASK_CONFIRMATION',
])

/** 白名单表单及其受控草稿字段 schema */
const ALLOWED_FORM_SCHEMAS: Record<string, string[]> = {
  PLAN_FORM: ['title', 'targetDate', 'dailyMinutes', 'goalId'],
  GOAL_FORM: ['title', 'targetDate', 'category', 'description'],
  MATERIAL_FORM: ['title', 'content', 'tags'],
  FEEDBACK_FORM: ['content', 'category'],
}

/** 白名单资源刷新注册表 */
const ALLOWED_RESOURCES = new Set([
  'ROADMAP',
  'TODAY_TASKS',
  'LEARNING_GOALS',
  'LEARNING_PLANS',
  'NOTIFICATIONS',
  'WRONG_QUESTIONS',
  'MASTERY',
  'ACTIVITY',
])

/** 白名单聚焦元素注册表（严禁传入原生选择器） */
const ALLOWED_FOCUS_ELEMENTS = new Set([
  'STUDY_INPUT',
  'MESSAGE_INPUT',
  'QUIZ_ANSWER_AREA',
  'CHECKIN_SUMMARY_INPUT',
  'ARTIFACT_REVIEW_BUTTON',
  'PLAN_TITLE_INPUT',
  'SEARCH_INPUT',
])

const safeIdPattern = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/
const urlPattern = /(https?:\/\/|\/\/|javascript:)/i
const htmlTagPattern = /<[^>]*>/
const cssSelectorCharsPattern = /[#.[\]>~:*$^\s]/

/** 本地动作回执内存缓存，用于同 actionId 的幂等校验 */
const actionReceipts = new Map<string, UiActionReceipt>()

export function clearActionReceiptsForTest(): void {
  actionReceipts.clear()
}

export function getActionReceipt(actionId: string): UiActionReceipt | undefined {
  return actionReceipts.get(actionId)
}

/**
 * 记录终态回执并保证相同 actionId 的幂等性。
 * 若已有相同 actionId 的记录且状态不同，抛出 409 Conflict 错误。
 */
export function recordActionReceipt(receipt: UiActionReceipt): UiActionReceipt {
  const existing = actionReceipts.get(receipt.actionId)
  if (existing) {
    if (existing.status !== receipt.status) {
      throw new Error(`409 Conflict: actionId '${receipt.actionId}' 已存在冲突终态 (${existing.status} vs ${receipt.status})`)
    }
    return existing
  }
  actionReceipts.set(receipt.actionId, receipt)
  return receipt
}

/**
 * 对回执中的错误信息进行脱敏裁剪：
 * 1. 过滤调用栈与本地文件路径
 * 2. 过滤 Bearer Token 与凭据
 * 3. 过滤 URL
 * 4. 截断最大 200 字符
 */
function sanitizeErrorMessage(error: unknown): string {
  if (!error) return '操作未成功'
  let msg = typeof error === 'string' ? error : (error as Error).message || String(error)
  msg = msg.replace(/\s+at\s+[\s\S]*$/, '')
  msg = msg.replace(/Bearer\s+[A-Za-z0-9._~+/-]+=*/gi, '[REDACTED_CREDENTIAL]')
  msg = msg.replace(/https?:\/\/[^\s]+/gi, '[URL]')
  msg = msg.replace(/\/[A-Za-z0-9._/-]+\/[A-Za-z0-9._-]+\.(ts|js|vue|html):\d+:\d+/g, '[PATH]')
  msg = msg.trim()
  if (msg.length > 200) {
    msg = msg.slice(0, 197) + '...'
  }
  return msg || '操作失败'
}

function resolveCurrentRouteName(context: UiActionContext | RouterLike): string {
  const router = 'router' in context ? context.router : context
  const currentRoute = router.currentRoute
  if (currentRoute) {
    if ('value' in currentRoute && currentRoute.value?.name) {
      return String(currentRoute.value.name)
    }
    if ('name' in currentRoute && currentRoute.name) {
      return String(currentRoute.name)
    }
  }
  return 'assistant'
}

function normalizeContext(routerOrContext: RouterLike | UiActionContext): UiActionContext {
  if ('router' in routerOrContext) {
    return routerOrContext as UiActionContext
  }
  return {
    router: routerOrContext,
  }
}

/** 深度防线：校验所有参数键值 */
function validateActionSecurity(action: AssistantUiAction) {
  const allEntries: Array<[string, unknown]> = Object.entries(action.params || {})

  for (const [key, val] of allEntries) {
    if (key.toLowerCase() === 'ownerid') {
      throw new Error('页面动作参数严禁携带 ownerId')
    }

    if (typeof val === 'string') {
      if (urlPattern.test(val)) {
        throw new Error('页面动作参数包含非法 URL')
      }
      if (htmlTagPattern.test(val)) {
        throw new Error('动作参数包含非法字符 (HTML/Script)')
      }
    }
  }
}

/** 学习真实性底线守护：严禁代办答题、写打卡总结与接受成果 */
function assertAuthenticityGuards(action: AssistantUiAction) {
  const formKey = (action.params?.formKey || '').toUpperCase()
  const modalKey = (action.params?.modalKey || '').toUpperCase()

  if (
    formKey === 'QUIZ_SUBMISSION' ||
    formKey === 'QUIZ_SUBMIT' ||
    modalKey === 'QUIZ_SUBMIT'
  ) {
    throw new Error('答题操作属于用户专属行为，严禁代办')
  }

  if (
    formKey === 'CHECKIN_SUMMARY_SUBMIT' ||
    formKey === 'CHECKIN_SUMMARY_SUBMISSION' ||
    formKey === 'CHECKIN_SUMMARY'
  ) {
    throw new Error('打卡总结属于用户专属思考，严禁代办')
  }

  if (
    formKey === 'ACCEPT_ARTIFACT_SUBMISSION' ||
    formKey === 'ACCEPT_ARTIFACT' ||
    modalKey === 'ACCEPT_ARTIFACT'
  ) {
    throw new Error('成果评审接受属于用户专属决策，严禁代办')
  }
}

/**
 * 调度白名单 UI Action，具有独立纵深防御、真实性守护与动作回执适配。
 */
export async function dispatchUiAction(
  action: AssistantUiAction,
  routerOrContext: RouterLike | UiActionContext,
): Promise<UiActionReceipt> {
  const context = normalizeContext(routerOrContext)
  const currentRoute = resolveCurrentRouteName(context)
  const actionId = action.actionId || `gen-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`

  // 幂等性：如果同一个 actionId 已经执行过，直接返回已有回执，避免重复副作用
  const existingReceipt = actionReceipts.get(actionId)
  if (existingReceipt) {
    return existingReceipt
  }

  let finalReceipt: UiActionReceipt

  try {
    // 1. 参数与注入防御检查
    validateActionSecurity(action)

    // 2. 真实性底线检查
    assertAuthenticityGuards(action)

    // 3. 执行五类白名单动作
    switch (action.type) {
      case 'NAVIGATE': {
        const definition = routes[action.routeKey]
        if (!definition) {
          throw new Error('不受支持的页面动作')
        }
        const inputKeys = Object.keys(action.params || {})
        const requiredKeys = Object.keys(definition.params)
        if (
          inputKeys.length !== requiredKeys.length ||
          inputKeys.some((k) => !requiredKeys.includes(k))
        ) {
          throw new Error('页面动作参数不合法')
        }
        const mappedParams: Record<string, string> = {}
        for (const [source, target] of Object.entries(definition.params)) {
          const value = action.params[source]
          if (!value || !safeIdPattern.test(value)) {
            throw new Error('页面动作参数不合法')
          }
          mappedParams[target] = value
        }

        try {
          await context.router.push(
            requiredKeys.length > 0
              ? { name: definition.name, params: mappedParams }
              : { name: definition.name },
          )
        } catch (pushErr) {
          throw new Error('导航失败')
        }

        finalReceipt = {
          actionId,
          status: 'SUCCEEDED',
          currentRoute: definition.name,
          error: null,
        }
        break
      }

      case 'OPEN_MODAL': {
        const modalKey = action.params?.modalKey
        if (!modalKey || !ALLOWED_MODALS.has(modalKey)) {
          throw new Error('不受支持的弹窗动作')
        }
        const payload: Record<string, unknown> = {}
        for (const [k, v] of Object.entries(action.params)) {
          if (k !== 'modalKey') payload[k] = v
        }
        if (context.modalManager?.open) {
          await context.modalManager.open(modalKey, payload)
        }
        finalReceipt = {
          actionId,
          status: 'SUCCEEDED',
          currentRoute,
          error: null,
        }
        break
      }

      case 'PREFILL_FORM': {
        const formKey = action.params?.formKey
        const allowedFields = formKey ? ALLOWED_FORM_SCHEMAS[formKey] : null
        if (!formKey || !allowedFields) {
          throw new Error('不受支持的表单草稿预填')
        }

        if ('autoSubmit' in action.params) {
          throw new Error('表单动作严禁自动提交，仅允许草稿预填')
        }

        const draft: Record<string, unknown> = {}
        for (const [k, v] of Object.entries(action.params)) {
          if (k === 'formKey') continue
          if (!allowedFields.includes(k)) {
            throw new Error(`表单草稿包含未授权字段: ${k}`)
          }
          draft[k] = v
        }

        if (context.formDraftStore?.setDraft) {
          context.formDraftStore.setDraft(formKey, draft)
        }

        finalReceipt = {
          actionId,
          status: 'SUCCEEDED',
          currentRoute,
          error: null,
        }
        break
      }

      case 'REFRESH_RESOURCE': {
        const resourceKey = action.params?.resourceKey
        if (!resourceKey || !ALLOWED_RESOURCES.has(resourceKey)) {
          throw new Error('不受支持的资源刷新')
        }
        if (context.resourceManager?.refresh) {
          await context.resourceManager.refresh(resourceKey)
        }
        finalReceipt = {
          actionId,
          status: 'SUCCEEDED',
          currentRoute,
          error: null,
        }
        break
      }

      case 'FOCUS_ELEMENT': {
        const elementKey = action.params?.elementKey
        if (
          !elementKey ||
          !ALLOWED_FOCUS_ELEMENTS.has(elementKey) ||
          cssSelectorCharsPattern.test(elementKey)
        ) {
          throw new Error('不受支持的元素聚焦，严禁传递 CSS 选择器')
        }
        if (context.focusManager?.focus) {
          context.focusManager.focus(elementKey)
        }
        finalReceipt = {
          actionId,
          status: 'SUCCEEDED',
          currentRoute,
          error: null,
        }
        break
      }

      default: {
        throw new Error(`暂不支持该界面动作类型: ${(action as any).type}`)
      }
    }

    recordActionReceipt(finalReceipt)
    return finalReceipt
  } catch (err: unknown) {
    const errorMsg = (err as Error).message || String(err)
    // 区分 REJECTED 与 FAILED
    const isRejected =
      errorMsg.includes('不受支持') ||
      errorMsg.includes('严禁') ||
      errorMsg.includes('非法') ||
      errorMsg.includes('专属') ||
      errorMsg.includes('未授权') ||
      errorMsg.includes('不合法')

    const status: UiActionReceiptStatus = isRejected ? 'REJECTED' : 'FAILED'
    const cleanError = sanitizeErrorMessage(err)

    finalReceipt = {
      actionId,
      status,
      currentRoute,
      error: cleanError,
    }

    try {
      recordActionReceipt(finalReceipt)
    } catch {
      // 冲突记录原样外抛
    }

    throw err
  }
}
