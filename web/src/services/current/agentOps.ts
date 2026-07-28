import { http } from '../http'
import type {
  AgentExecution,
  AgentGrant,
  AgentScope,
  AuditLog,
  Notification,
} from '@/types/api'

export const agentOpsApi = {
  createGrant(body: { scopes: AgentScope[]; expiresAt: string }) {
    return http.post<AgentGrant>('/api/agent-grants', body).then((r) => r.data)
  },
  listGrants() {
    return http.get<AgentGrant[]>('/api/agent-grants').then((r) => r.data)
  },
  listExecutions() {
    return http.get<AgentExecution[]>('/api/agent-executions').then((r) => r.data)
  },
  confirmExecution(executionId: string) {
    return http
      .post<AgentExecution>(`/api/agent-executions/${executionId}/confirm`)
      .then((r) => r.data)
  },
  listNotifications() {
    return http.get<Notification[]>('/api/notifications').then((r) => r.data)
  },
  markNotificationRead(notificationId: string) {
    return http
      .patch<Notification>(`/api/notifications/${notificationId}/read`)
      .then((r) => r.data)
  },
  listAuditLogs() {
    return http.get<AuditLog[]>('/api/audit-logs').then((r) => r.data)
  },
}
