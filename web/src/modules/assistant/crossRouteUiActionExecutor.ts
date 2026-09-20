import type { AssistantUiAction, UiActionReceipt } from '@/types/assistant'
import {
  dispatchUiAction,
  getActionReceipt,
  normalizeReceiptScope,
  resolveNamedRouteFromUiAction,
  validateUiActionCapability,
  type RouterLike,
  type ModalManagerLike,
  type FormDraftStoreLike,
  type ResourceManagerLike,
  type FocusManagerLike,
  type UiActionContext,
} from './uiActionDispatcher'

export interface ExactRouteAdapters {
  modalManager?: ModalManagerLike
  formDraftStore?: FormDraftStoreLike
  resourceManager?: ResourceManagerLike
  focusManager?: FocusManagerLike
}

export interface AdapterRegistryLike {
  getAdaptersForRouteKey(routeKey: string): ExactRouteAdapters
  awaitAdaptersForRouteKey(routeKey: string, timeoutMs?: number): Promise<ExactRouteAdapters>
}

export interface ExecuteCrossRouteUiActionOptions {
  action: AssistantUiAction
  router: RouterLike
  registry: AdapterRegistryLike
  adapterTimeoutMs?: number
  receiptScope?: string
}

/**
 * Module-level in-flight execution promise cache, keyed by scope + actionId.
 * Concurrent duplicate invocations with the same scope and actionId share the exact same execution promise.
 */
const inFlightActionPromises = new Map<string, Promise<UiActionReceipt>>()

function buildInFlightKey(actionId: string, scope?: string): string {
  const normScope = normalizeReceiptScope(scope)
  return `${normScope}::${actionId}`
}

/**
 * Test helper to inspect or clear module-level in-flight executions.
 */
export function clearInFlightActionsForTest(): void {
  inFlightActionPromises.clear()
}

export function getInFlightActionCountForTest(): number {
  return inFlightActionPromises.size
}

function resolveCurrentRouteName(router: RouterLike): string | null {
  const currentRoute = router.currentRoute
  if (currentRoute) {
    if ('value' in currentRoute && currentRoute.value?.name != null) {
      return String(currentRoute.value.name)
    }
    if ('name' in currentRoute && currentRoute.name != null) {
      return String(currentRoute.name)
    }
  }
  return null
}

function hasRequiredCapability(
  action: AssistantUiAction,
  adapters: ExactRouteAdapters,
): boolean {
  switch (action.type) {
    case 'OPEN_MODAL':
      return typeof adapters.modalManager?.open === 'function'
    case 'PREFILL_FORM':
      return typeof adapters.formDraftStore?.setDraft === 'function'
    case 'REFRESH_RESOURCE':
      return typeof adapters.resourceManager?.refresh === 'function'
    case 'FOCUS_ELEMENT':
      return typeof adapters.focusManager?.focus === 'function'
    default:
      return true
  }
}

/**
 * Framework-independent cross-route UI action executor.
 *
 * Guarantees:
 * 1. Strict server actionId requirement (nonempty string; rejects rather than inventing one).
 * 2. Terminal receipt check: if actionId is already in terminal state, returns immediately with no navigation/side-effects.
 * 3. In-flight deduplication: concurrent duplicate calls with the same actionId share a single in-flight promise.
 * 4. Preflight capability and schema validation before any router navigation.
 * 5. NAVIGATE type executes directly without awaiting adapters.
 * 6. Other types resolve named route, check current route, navigate if route differs, await exact adapter registration with timeout.
 * 7. Enforces exact target adapter capability check (fails immediately if required capability is missing).
 * 8. Cleans up in-flight promise map in `finally`, keeping completed receipts deduplicated in terminal storage.
 * 9. Rejections (preflight, navigation, timeout, missing capability, executor errors) propagate directly and never return fake SUCCEEDED receipts.
 * 10. No component lifecycle dependency (caller unmount or scope exit will not cancel or corrupt in-flight action).
 */
export function executeCrossRouteUiAction(
  options: ExecuteCrossRouteUiActionOptions,
): Promise<UiActionReceipt> {
  const { action, router, registry, adapterTimeoutMs = 2000, receiptScope } = options

  // 1. Strict requirement: nonempty server actionId
  if (!action || typeof action.actionId !== 'string' || action.actionId.trim() === '') {
    return Promise.reject(new Error('UI 动作缺少合法的 actionId，严禁自动生成客户端 ID 执行'))
  }

  const normalizedActionId = action.actionId.trim()
  const normalizedAction: AssistantUiAction = action.actionId === normalizedActionId
    ? action
    : { ...action, actionId: normalizedActionId }

  // 2. Terminal receipt check: already completed/failed terminal receipt is returned immediately
  const existingReceipt = getActionReceipt(normalizedActionId, receiptScope)
  if (existingReceipt) {
    return Promise.resolve(existingReceipt)
  }

  // 3. In-flight deduplication: share existing running promise scoped by receiptScope + actionId
  const inFlightKey = buildInFlightKey(normalizedActionId, receiptScope)
  const inFlight = inFlightActionPromises.get(inFlightKey)
  if (inFlight) {
    return inFlight
  }

  const executionPromise = (async (): Promise<UiActionReceipt> => {
    // 4. Preflight validation before any router navigation
    validateUiActionCapability(normalizedAction)

    // 5. NAVIGATE type: dispatch directly without awaiting page adapters
    if (normalizedAction.type === 'NAVIGATE') {
      const navContext: UiActionContext = {
        router,
        receiptScope,
      }
      return await dispatchUiAction(normalizedAction, navContext)
    }

    // 6. Cross-route action: resolve named route and compare
    const resolvedRoute = resolveNamedRouteFromUiAction(normalizedAction)
    if (!resolvedRoute) {
      throw new Error(`无法为动作路由 ${normalizedAction.routeKey} 解析目标命名路由`)
    }

    const currentRouteName = resolveCurrentRouteName(router)
    if (currentRouteName !== resolvedRoute.name) {
      const location = Object.keys(resolvedRoute.params).length > 0
        ? { name: resolvedRoute.name, params: resolvedRoute.params }
        : { name: resolvedRoute.name }

      try {
        await router.push(location)
      } catch (routerError) {
        throw new Error(`导航到目标页面失败: ${(routerError as Error)?.message || routerError}`)
      }
    }

    // 7. Fetch exact target adapter (await registration if not yet mounted)
    let adapters = registry.getAdaptersForRouteKey(normalizedAction.routeKey)
    const hasAdapter = hasRequiredCapability(normalizedAction, adapters)

    if (!hasAdapter) {
      try {
        adapters = await registry.awaitAdaptersForRouteKey(normalizedAction.routeKey, adapterTimeoutMs)
      } catch (awaitError) {
        throw new Error(`等待目标页面能力适配器就绪超时: ${(awaitError as Error)?.message || awaitError}`)
      }
    }

    // 8. Enforce required capability existence on exact adapter
    if (!hasRequiredCapability(normalizedAction, adapters)) {
      throw new Error(`目标页面未注册该动作所需的执行器能力: ${normalizedAction.type}`)
    }

    // 9. Dispatch action with ONLY the exact target adapters and supplied receiptScope
    const context: UiActionContext = {
      router,
      modalManager: adapters.modalManager,
      formDraftStore: adapters.formDraftStore,
      resourceManager: adapters.resourceManager,
      focusManager: adapters.focusManager,
      receiptScope,
    }

    return await dispatchUiAction(normalizedAction, context)
  })()

  // Track in-flight execution promise
  inFlightActionPromises.set(inFlightKey, executionPromise)

  return executionPromise.finally(() => {
    inFlightActionPromises.delete(inFlightKey)
  })
}
