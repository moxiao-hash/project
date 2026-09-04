import { http } from '@/services/http'
import type { AssistantConversation, SendAssistantMessage } from '@/types/assistant'

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
}
