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
  focus(elementKey: string): Promise<boolean | void> | boolean | void
}

export interface UiActionContext {
  router: RouterLike
  modalManager?: ModalManagerLike
  formDraftStore?: FormDraftStoreLike
  resourceManager?: ResourceManagerLike
  focusManager?: FocusManagerLike
  receiptScope?: string
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

export interface ResolvedNamedRoute {
  name: string
  params: Record<string, string>
}

/**
 * 依据 routeKey 与 action.params 解析对应的目标具名路由与参数映射。
 * 共享导出，杜绝第二张分叉路由表。
 */
export function resolveNamedRouteFromUiAction(
  action: Pick<AssistantUiAction, 'routeKey' | 'params'>,
): ResolvedNamedRoute | null {
  const definition = routes[action.routeKey]
  if (!definition) return null
  const mappedParams: Record<string, string> = {}
  for (const [source, target] of Object.entries(definition.params)) {
    if (action.params && source in action.params) {
      mappedParams[target] = String(action.params[source])
    }
  }
  return {
    name: definition.name,
    params: mappedParams,
  }
}

/**
 * Task 30 冻结能力矩阵定义：
 * OPEN_MODAL: LEARNING_GOALS/CREATE_GOAL, LEARNING_PLANS/CREATE_PLAN, MATERIALS/IMPORT_MATERIAL.
 * PREFILL_FORM: LEARNING_GOALS/GOAL_FORM, LEARNING_PLANS/PLAN_FORM, MATERIALS/MATERIAL_FORM.
 * REFRESH_RESOURCE: ROADMAP/ROADMAP, TODAY/TODAY_TASKS, LEARNING_GOALS/LEARNING_GOALS, LEARNING_PLANS/LEARNING_PLANS, NOTIFICATIONS/NOTIFICATIONS, WRONG_QUESTIONS/WRONG_QUESTIONS, MASTERY/MASTERY, AGENT_ACTIVITY/ACTIVITY.
 * FOCUS_ELEMENT: ASSISTANT/MESSAGE_INPUT, LEARNING_PLANS/PLAN_TITLE_INPUT.
 */
export const FROZEN_MODAL_MATRIX: Record<string, string> = {
  LEARNING_GOALS: 'CREATE_GOAL',
  LEARNING_PLANS: 'CREATE_PLAN',
  MATERIALS: 'IMPORT_MATERIAL',
}

export const FROZEN_FORM_MATRIX: Record<string, string> = {
  LEARNING_GOALS: 'GOAL_FORM',
  LEARNING_PLANS: 'PLAN_FORM',
  MATERIALS: 'MATERIAL_FORM',
}

export const FROZEN_RESOURCE_MATRIX: Record<string, string> = {
  ROADMAP: 'ROADMAP',
  TODAY: 'TODAY_TASKS',
  LEARNING_GOALS: 'LEARNING_GOALS',
  LEARNING_PLANS: 'LEARNING_PLANS',
  NOTIFICATIONS: 'NOTIFICATIONS',
  WRONG_QUESTIONS: 'WRONG_QUESTIONS',
  MASTERY: 'MASTERY',
  AGENT_ACTIVITY: 'ACTIVITY',
}

export const FROZEN_FOCUS_MATRIX: Record<string, string> = {
  ASSISTANT: 'MESSAGE_INPUT',
  LEARNING_PLANS: 'PLAN_TITLE_INPUT',
}

/** 白名单弹窗及其允许透传的业务标识字段 */
const ALLOWED_MODAL_SCHEMAS: Record<string, string[]> = {
  CREATE_GOAL: [],
  CREATE_PLAN: [],
  IMPORT_MATERIAL: [],
}

/**
 * 白名单表单及其受控草稿字段 schema。
 * 严格对齐 Task-30 frozen contract:
 *   GOAL_FORM   : title(1-100), targetDate(ISO date), weeklyStudyHours(integer string 1-40)
 *   PLAN_FORM   : title(1-120), goalId(safe-id), startDate(ISO date), endDate(ISO date)
 *   MATERIAL_FORM: title(1-180), content(1-2000)
 */
const ALLOWED_FORM_SCHEMAS: Record<string, string[]> = {
  GOAL_FORM: ['title', 'targetDate', 'weeklyStudyHours'],
  PLAN_FORM: ['title', 'goalId', 'startDate', 'endDate'],
  MATERIAL_FORM: ['title', 'content'],
}

/** 各表单字段的长度与格式约束 (frozen contract) */
const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

function validateFormDraftField(formKey: string, field: string, value: string): void {
  if (formKey === 'GOAL_FORM') {
    if (field === 'title') {
      if (value.length < 1 || value.length > 100) {
        throw new Error('目标标题长度不合法 (1-100)')
      }
    }
    if (field === 'targetDate' && !ISO_DATE_PATTERN.test(value)) {
      throw new Error('targetDate 必须为 ISO 日期格式 (YYYY-MM-DD)')
    }
    if (field === 'weeklyStudyHours') {
      const n = parseInt(value, 10)
      if (isNaN(n) || n < 1 || n > 40 || String(n) !== value) {
        throw new Error('weeklyStudyHours 必须为 1-40 的整数字符串')
      }
    }
  }
  if (formKey === 'PLAN_FORM') {
    if (field === 'title') {
      if (value.length < 1 || value.length > 120) {
        throw new Error('计划标题长度不合法 (1-120)')
      }
    }
    if ((field === 'startDate' || field === 'endDate') && !ISO_DATE_PATTERN.test(value)) {
      throw new Error(`${field} 必须为 ISO 日期格式 (YYYY-MM-DD)`)
    }
    if (field === 'goalId' && !safeIdPattern.test(value)) {
      throw new Error('goalId 包含非法字符')
    }
  }
  if (formKey === 'MATERIAL_FORM') {
    if (field === 'title') {
      if (value.length < 1 || value.length > 180) {
        throw new Error('资料标题长度不合法 (1-180)')
      }
    }
    if (field === 'content') {
      if (value.length < 1 || value.length > 2000) {
        throw new Error('资料内容长度不合法 (1-2000)')
      }
    }
  }
}

const safeIdPattern = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/
const urlPattern = /(https?:\/\/|\/\/|javascript:)/i
const htmlTagPattern = /<[^>]*>/
const cssSelectorCharsPattern = /[#.[\]>~:*$^\s]/

const DEFAULT_RECEIPT_SCOPE = '__default_scope__'

/**
 * 校验并规范化 receiptScope:
 * 1. 若未指定，则回退到默认 scope
 * 2. 若指定，必须为非空有界字符串 (1-256 字符)
 * 3. 不得包含凭据模式 (Bearer) 或邮箱模式
 */
export function normalizeReceiptScope(scope?: string): string {
  if (scope === undefined || scope === null) {
    return DEFAULT_RECEIPT_SCOPE
  }
  if (typeof scope !== 'string') {
    throw new Error('receiptScope 必须为字符串')
  }
  const trimmed = scope.trim()
  if (trimmed.length === 0 || trimmed.length > 256) {
    throw new Error('receiptScope 必须为 1-256 字符的非空字符串')
  }
  if (/@/.test(trimmed)) {
    throw new Error('receiptScope 严禁包含邮箱格式')
  }
  if (/Bearer\s+/i.test(trimmed)) {
    throw new Error('receiptScope 严禁包含凭据 Token')
  }
  return trimmed
}

function buildReceiptKey(actionId: string, scope?: string): string {
  const normScope = normalizeReceiptScope(scope)
  return `${normScope}::${actionId}`
}

/** 本地动作回执内存缓存，按 (scope, actionId) 进行隔离幂等存储 */
const actionReceipts = new Map<string, UiActionReceipt>()

export function clearActionReceiptsForTest(): void {
  actionReceipts.clear()
}

export function getActionReceipt(actionId: string, scope?: string): UiActionReceipt | undefined {
  const key = buildReceiptKey(actionId, scope)
  return actionReceipts.get(key)
}

/**
 * 记录终态回执并保证相同 (scope, actionId) 的幂等性。
 * 若已有相同 scope 与 actionId 的记录且状态不同，抛出 409 Conflict 错误。
 * 外部 Wire UiActionReceipt 结构保持纯净，不泄露内部 scope 属性。
 */
export function recordActionReceipt(receipt: UiActionReceipt, scope?: string): UiActionReceipt {
  const key = buildReceiptKey(receipt.actionId, scope)
  const existing = actionReceipts.get(key)
  if (existing) {
    if (existing.status !== receipt.status) {
      throw new Error(`409 Conflict: actionId '${receipt.actionId}' 在该 scope 下已存在冲突终态 (${existing.status} vs ${receipt.status})`)
    }
    return existing
  }
  // 确保 wire receipt 纯净
  const wireReceipt: UiActionReceipt = {
    actionId: receipt.actionId,
    status: receipt.status,
    currentRoute: receipt.currentRoute,
    error: receipt.error ?? null,
  }
  actionReceipts.set(key, wireReceipt)
  return wireReceipt
}

/**
 * 对回执中的错误信息进行脱敏裁剪：
 * 1. 过滤调用栈与本地文件路径
2. 过滤 Bearer Token 与凭据
3. 过滤 URL
4. 截断最大 200 字符
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

function assertAllowedParameterKeys(
  params: Record<string, string>,
  allowedKeys: readonly string[],
): void {
  const unknown = Object.keys(params).filter((key) => !allowedKeys.includes(key))
  if (unknown.length > 0) {
    throw new Error(`动作参数包含未授权字段: ${unknown.join(',')}`)
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
 * 纯预检与能力对齐校验器。
 * 在执行任何副作用前检验 routeKey 与能力 key 的对齐以及参数字段合规性。
 */
export function validateUiActionCapability(action: AssistantUiAction): void {
  // 1. 安全性检查
  validateActionSecurity(action)

  // 2. 真实性底线
  assertAuthenticityGuards(action)

  // 3. 校验冻结矩阵与具体能力契约
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
      for (const source of Object.keys(definition.params)) {
        const value = action.params[source]
        if (!value || !safeIdPattern.test(value)) {
          throw new Error('页面动作参数不合法')
        }
      }
      break
    }

    case 'OPEN_MODAL': {
      const modalKey = action.params?.modalKey
      const expectedModalKey = FROZEN_MODAL_MATRIX[action.routeKey]
      if (!expectedModalKey || modalKey !== expectedModalKey) {
        throw new Error('不受支持的弹窗动作')
      }
      const allowedFields = ALLOWED_MODAL_SCHEMAS[modalKey]
      if (!allowedFields) {
        throw new Error('不受支持的弹窗动作')
      }
      assertAllowedParameterKeys(action.params, ['modalKey', ...allowedFields])
      break
    }

    case 'PREFILL_FORM': {
      const formKey = action.params?.formKey
      const expectedFormKey = FROZEN_FORM_MATRIX[action.routeKey]
      if (!expectedFormKey || formKey !== expectedFormKey) {
        throw new Error('不受支持的表单草稿预填')
      }
      const allowedFields = ALLOWED_FORM_SCHEMAS[formKey]
      if (!allowedFields) {
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

      for (const [field, value] of Object.entries(draft)) {
        validateFormDraftField(formKey, field, String(value))
      }
      break
    }

    case 'REFRESH_RESOURCE': {
      assertAllowedParameterKeys(action.params, ['resourceKey'])
      const resourceKey = action.params?.resourceKey
      const expectedResourceKey = FROZEN_RESOURCE_MATRIX[action.routeKey]
      if (!expectedResourceKey || resourceKey !== expectedResourceKey) {
        throw new Error('不受支持的资源刷新')
      }
      break
    }

    case 'FOCUS_ELEMENT': {
      assertAllowedParameterKeys(action.params, ['elementKey'])
      const elementKey = action.params?.elementKey
      const expectedElementKey = FROZEN_FOCUS_MATRIX[action.routeKey]
      if (
        !expectedElementKey ||
        elementKey !== expectedElementKey ||
        cssSelectorCharsPattern.test(elementKey)
      ) {
        throw new Error('不受支持的元素聚焦，严禁传递 CSS 选择器')
      }
      break
    }

    default: {
      throw new Error(`暂不支持该界面动作类型: ${(action as any).type}`)
    }
  }
}

/**
 * 调度白名单 UI Action，具有独立纵深防御、真实性守护与动作回执适配。
 */
export async function dispatchUiAction(
  action: AssistantUiAction,
  routerOrContext: RouterLike | UiActionContext,
): Promise<UiActionReceipt> {
  const rawActionId = action?.actionId
  if (typeof rawActionId !== 'string' || rawActionId.trim().length === 0) {
    throw new Error('动作必须包含合法的 stable actionId')
  }
  const actionId = rawActionId.trim()

  const context = normalizeContext(routerOrContext)
  const currentRoute = resolveCurrentRouteName(context)
  const receiptScope = normalizeReceiptScope(context.receiptScope)

  // 幂等性：如果同一个 (scope, actionId) 已经执行过，直接返回已有回执，避免重复副作用
  const existingReceipt = getActionReceipt(actionId, receiptScope)
  if (existingReceipt) {
    return existingReceipt
  }

  let finalReceipt: UiActionReceipt

  try {
    // 纯校验先行：参数、注入、真实性底线与严格对齐矩阵
    validateUiActionCapability(action)

    // 执行五类白名单动作
    switch (action.type) {
      case 'NAVIGATE': {
        const definition = routes[action.routeKey]
        const mappedParams: Record<string, string> = {}
        for (const [source, target] of Object.entries(definition.params)) {
          mappedParams[target] = action.params[source]
        }

        try {
          await context.router.push(
            Object.keys(definition.params).length > 0
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
        const modalKey = action.params.modalKey
        const payload: Record<string, unknown> = {}
        for (const [k, v] of Object.entries(action.params)) {
          if (k !== 'modalKey') payload[k] = v
        }
        if (!context.modalManager?.open) {
          throw new Error('弹窗执行器不可用')
        }
        await context.modalManager.open(modalKey, payload)
        finalReceipt = {
          actionId,
          status: 'SUCCEEDED',
          currentRoute,
          error: null,
        }
        break
      }

      case 'PREFILL_FORM': {
        const formKey = action.params.formKey
        const draft: Record<string, unknown> = {}
        for (const [k, v] of Object.entries(action.params)) {
          if (k !== 'formKey') draft[k] = v
        }

        if (!context.formDraftStore?.setDraft) {
          throw new Error('表单草稿执行器不可用')
        }
        context.formDraftStore.setDraft(formKey, draft)

        finalReceipt = {
          actionId,
          status: 'SUCCEEDED',
          currentRoute,
          error: null,
        }
        break
      }

      case 'REFRESH_RESOURCE': {
        const resourceKey = action.params.resourceKey
        if (!context.resourceManager?.refresh) {
          throw new Error('资源刷新执行器不可用')
        }
        await context.resourceManager.refresh(resourceKey)
        finalReceipt = {
          actionId,
          status: 'SUCCEEDED',
          currentRoute,
          error: null,
        }
        break
      }

      case 'FOCUS_ELEMENT': {
        const elementKey = action.params.elementKey
        if (!context.focusManager?.focus) {
          throw new Error('元素聚焦执行器不可用')
        }
        const focusResult = await context.focusManager.focus(elementKey)
        if (focusResult === false) {
          throw new Error('元素聚焦执行失败或未生效')
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

    return recordActionReceipt(finalReceipt, receiptScope)
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
      recordActionReceipt(finalReceipt, receiptScope)
    } catch {
      // 冲突记录原样外抛
    }

    throw err
  }
}
