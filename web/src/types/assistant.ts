export type AssistantStatus =
  | 'READY'
  | 'RUNNING'
  | 'WAITING_CONFIRMATION'
  | 'COMPLETED'
  | 'FAILED'

export interface AssistantMessage {
  role: 'user' | 'assistant'
  content: string
  turnId?: string
  status?: 'streaming' | 'completed' | 'failed' | 'cancelled'
}

export interface AssistantToolStep {
  toolName: string
  status: string
  summary: string
}

export interface AssistantPendingAction {
  actionId: string
  executionId: string
  toolName: string
  riskLevel: 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH'
  status: string
  summary: string
  arguments: Record<string, unknown>
  expiresAt: string
}

export interface AssistantUiAction {
  type: 'NAVIGATE' | 'OPEN_MODAL' | 'PREFILL_FORM' | 'REFRESH_RESOURCE' | 'FOCUS_ELEMENT'
  routeKey: string
  params: Record<string, string>
  reason: string
}

export interface AssistantConversation {
  conversationId: string
  status: AssistantStatus
  reply: string
  messages: AssistantMessage[]
  intent: string | null
  toolSteps: AssistantToolStep[]
  pendingAction: AssistantPendingAction | null
  uiActions: AssistantUiAction[]
  warnings: string[]
  citations: Array<{
    sourceType: string
    title: string
    snippet: string
    materialId?: string | null
    locator?: string | null
    resultId?: string | null
    url?: string | null
  }>
  modelName: string
  lastEventSequence?: number | null
  activeTurnId?: string | null
}

export type EventStreamStatus = 'connecting' | 'connected' | 'reconnecting' | 'disconnected'

export interface SendAssistantMessage {
  message: string
  idempotencyKey: string
  clientContext: {
    routeName: string
    routeParams: Record<string, string>
    timezone: string
  }
}

export type AutomationRuleType =
  | 'AUTHORIZED_PLAN_ADJUSTMENT'
  | 'OVERDUE_NODE_ROLLOVER'
  | 'QUIZ_GENERATION_RETRY'
  | 'WEAKNESS_REVIEW_REMINDER'
  | 'ARTIFACT_REVIEW_REMINDER'

export interface AutomationRule {
  id: string
  type: AutomationRuleType
  status: 'ACTIVE' | 'PAUSED'
  timezone: string
  localTime: string
  riskLevel: 'NONE' | 'LOW' | 'HIGH'
  requiredScope: string
  createdAt?: string
  updatedAt?: string
}

export interface AutomationSettings {
  paused: boolean
  updatedAt: string
}

export interface AssistantHealth {
  costSamples: number
  tokenSamples: number
  latencySamples: number
  totalExecutions: number
  successfulExecutions: number
  failedExecutions: number
  successRate: number
  promptTokens: number
  completionTokens: number
  estimatedCost: number
  averageLatencyMs: number
  pendingConfirmations: number
}

export type AssistantEventType =
  | 'HEARTBEAT'
  | 'TURN_STARTED'
  | 'CONTEXT_LOADED'
  | 'PLAN_GENERATED'
  | 'TOOL_STARTED'
  | 'TOOL_SUCCEEDED'
  | 'TOOL_FAILED'
  | 'ACTION_PREVIEW'
  | 'ASSISTANT_DELTA'
  | 'UI_ACTION'
  | 'TURN_COMPLETED'
  | 'TURN_FAILED'
  | 'TURN_CANCELLED'

export interface AssistantEvent<T = Record<string, unknown>> {
  sequence: number
  type: AssistantEventType
  conversationId: string
  payload: T
}

export interface AssistantEventStreamOptions {
  lastEventId?: string | number | null
  signal?: AbortSignal
  onEvent?: (event: AssistantEvent) => void
  onHeartbeat?: () => void
  onError?: (error: unknown) => void
  onClose?: () => void
  onStatusChange?: (status: EventStreamStatus) => void
  autoReconnect?: boolean
  reconnectIntervalMs?: number
  maxReconnectIntervalMs?: number
  maxReconnectAttempts?: number
}

export interface AssistantEventStreamController {
  close: () => void
  getLastEventId: () => number
  getStatus: () => EventStreamStatus
}
