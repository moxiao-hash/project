import { http } from '../http'
import type {
  AgentGateway,
  AiSettings,
  KnowledgeConversation,
  KnowledgeMode,
  PlanAdjustment,
  PlanConversation,
  TaskConversation,
  WebSearchPreference,
} from '@/types/agent'
import type { Material } from '@/types/api'

/**
 * 阶段 8 Java Agent 门面的 HTTP 实现。
 * 这些端点当前尚未在 Java 中实现，仅在 VITE_AGENT_GATEWAY=http 时启用，
 * 供门面就绪后无缝切换联调。所有路径都在 /api/** 之下。
 */
export class HttpAgentGateway implements AgentGateway {
  createPlanConversation(goalId: string) {
    return http
      .post<PlanConversation>('/api/agent/plan-conversations', { goalId })
      .then((r) => r.data)
  }
  sendPlanMessage(id: string, message: string) {
    return http
      .post<PlanConversation>(`/api/agent/plan-conversations/${id}/messages`, { message })
      .then((r) => r.data)
  }
  confirmPlan(id: string) {
    return http
      .post<PlanConversation>(`/api/agent/plan-conversations/${id}/confirm`)
      .then((r) => r.data)
  }

  createTaskConversation(targetDate: string) {
    return http
      .post<TaskConversation>('/api/agent/task-conversations', { targetDate })
      .then((r) => r.data)
  }
  sendTaskMessage(id: string, message: string) {
    return http
      .post<TaskConversation>(`/api/agent/task-conversations/${id}/messages`, { message })
      .then((r) => r.data)
  }
  confirmTaskAction(id: string) {
    return http
      .post<TaskConversation>(`/api/agent/task-conversations/${id}/confirm`)
      .then((r) => r.data)
  }

  createKnowledgeConversation(mode: KnowledgeMode) {
    return http
      .post<KnowledgeConversation>('/api/agent/knowledge-conversations', { mode })
      .then((r) => r.data)
  }
  sendKnowledgeMessage(id: string, message: string, webSearch: WebSearchPreference) {
    return http
      .post<KnowledgeConversation>(`/api/agent/knowledge-conversations/${id}/messages`, {
        message,
        webSearch,
      })
      .then((r) => r.data)
  }
  importWebResult(resultId: string, category: string, privacyLevel: string) {
    return http
      .post<Material>(`/api/web-search-results/${resultId}/import`, {
        category,
        privacyLevel,
      })
      .then((r) => ({ materialId: r.data.id }))
  }

  analyzePlanAdjustment(analysisDate: string) {
    return http
      .post<PlanAdjustment>('/api/agent/plan-adjustments/analyze', { analysisDate })
      .then((r) => r.data)
  }
  getPlanAdjustment(id: string) {
    return http
      .get<PlanAdjustment>(`/api/agent/plan-adjustments/${id}`)
      .then((r) => r.data)
  }
  confirmPlanAdjustment(id: string) {
    return http
      .post<PlanAdjustment>(`/api/agent/plan-adjustments/${id}/confirm`)
      .then((r) => r.data)
  }

  generateQuiz(taskId: string, webSearch: WebSearchPreference) {
    return http
      .post<{ quizId: string }>('/api/agent/quizzes/generate', { taskId, webSearch })
      .then((r) => r.data)
  }

  getAiSettings() {
    return http.get<AiSettings>('/api/ai-settings').then((r) => r.data)
  }
  updateDeepseekKey(apiKey: string) {
    return http
      .put<AiSettings>('/api/ai-settings/deepseek-key', { apiKey })
      .then((r) => r.data)
  }
  deleteDeepseekKey() {
    return http
      .delete<AiSettings>('/api/ai-settings/deepseek-key')
      .then((r) => r.data)
  }
  updateTavilyKey(apiKey: string) {
    return http
      .put<AiSettings>('/api/ai-settings/tavily-key', { apiKey })
      .then((r) => r.data)
  }
  deleteTavilyKey() {
    return http.delete<AiSettings>('/api/ai-settings/tavily-key').then((r) => r.data)
  }
}
