export type AssistantStatus =
  | 'READY'
  | 'RUNNING'
  | 'WAITING_CONFIRMATION'
  | 'COMPLETED'
  | 'FAILED'

export interface AssistantMessage {
  role: 'user' | 'assistant'
  content: string
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
  modelName: string
}

export interface SendAssistantMessage {
  message: string
  idempotencyKey: string
  clientContext: {
    routeName: string
    routeParams: Record<string, string>
    timezone: string
  }
}
