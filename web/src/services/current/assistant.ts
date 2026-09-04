import { http } from '@/services/http'
import type {
  AssistantConversation,
  AutomationRule,
  AutomationRuleType,
  AutomationSettings,
  SendAssistantMessage,
} from '@/types/assistant'

const assistantRequest = { timeout: 120_000 } as const

export const assistantApi = {
  createConversation: () =>
    http.post<AssistantConversation>('/api/assistant/conversations', {}, assistantRequest)
      .then((response) => response.data),
  getConversation: (id: string) =>
    http.get<AssistantConversation>(`/api/assistant/conversations/${id}`, assistantRequest)
      .then((response) => response.data),
  sendMessage: (id: string, body: SendAssistantMessage) =>
    http.post<AssistantConversation>(
      `/api/assistant/conversations/${id}/messages`, body, assistantRequest,
    ).then((response) => response.data),
  confirmAction: (conversationId: string, actionId: string) =>
    http.post<AssistantConversation>(
      `/api/assistant/conversations/${conversationId}/actions/${actionId}/confirm`,
      {}, assistantRequest,
    ).then((response) => response.data),
  rejectAction: (conversationId: string, actionId: string) =>
    http.post<AssistantConversation>(
      `/api/assistant/conversations/${conversationId}/actions/${actionId}/reject`,
      {}, assistantRequest,
    ).then((response) => response.data),
  listAutomationRules: () =>
    http.get<AutomationRule[]>('/api/assistant/automation-rules')
      .then((response) => response.data),
  createAutomationRule: (body: {
    type: AutomationRuleType
    timezone: string
    localTime: string
    enabled: boolean
  }) => http.post<AutomationRule>('/api/assistant/automation-rules', body)
    .then((response) => response.data),
  updateAutomationRule: (id: string, body: {
    enabled?: boolean
    timezone?: string
    localTime?: string
  }) => http.patch<AutomationRule>(`/api/assistant/automation-rules/${id}`, body)
    .then((response) => response.data),
  deleteAutomationRule: (id: string) =>
    http.delete(`/api/assistant/automation-rules/${id}`),
  getAutomationSettings: () =>
    http.get<AutomationSettings>('/api/assistant/automation-settings')
      .then((response) => response.data),
  updateAutomationSettings: (body: { paused: boolean }) =>
    http.patch<AutomationSettings>('/api/assistant/automation-settings', body)
      .then((response) => response.data),
}
