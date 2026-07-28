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

/** Agent 请求允许模型在 Java 门面规定的 120 秒内完成。 */
const agentRequest = { timeout: 120_000 } as const

interface RawPlanTask {
  title: string
  scheduledDate?: string
  scheduled_date?: string
  estimatedMinutes?: number
  estimated_minutes?: number
}

interface RawPlanDraft {
  title: string
  startDate?: string
  start_date?: string
  endDate?: string
  end_date?: string
  tasks: RawPlanTask[]
}

type RawPlanConversation = Omit<PlanConversation, 'draft'> & {
  draft: RawPlanDraft | null
}

function normalizePlanConversation(raw: RawPlanConversation): PlanConversation {
  if (!raw.draft) return { ...raw, draft: null }
  return {
    ...raw,
    draft: {
      title: raw.draft.title,
      startDate: raw.draft.startDate ?? raw.draft.start_date ?? '',
      endDate: raw.draft.endDate ?? raw.draft.end_date ?? '',
      tasks: raw.draft.tasks.map((task) => ({
        title: task.title,
        scheduledDate: task.scheduledDate ?? task.scheduled_date ?? '',
        estimatedMinutes: task.estimatedMinutes ?? task.estimated_minutes ?? 0,
      })),
    },
  }
}

/** 已认证 Java Agent 门面的 HTTP 实现。 */
export class HttpAgentGateway implements AgentGateway {
  createPlanConversation(goalId: string) {
    return http
      .post<PlanConversation>('/api/agent/plan-conversations', { goalId }, agentRequest)
      .then((r) => normalizePlanConversation(r.data as RawPlanConversation))
  }
  getPlanConversation(id: string) {
    return http
      .get<PlanConversation>(`/api/agent/plan-conversations/${id}`, agentRequest)
      .then((r) => normalizePlanConversation(r.data as RawPlanConversation))
  }
  sendPlanMessage(id: string, message: string) {
    return http
      .post<PlanConversation>(
        `/api/agent/plan-conversations/${id}/messages`,
        { message },
        agentRequest,
      )
      .then((r) => normalizePlanConversation(r.data as RawPlanConversation))
  }
  confirmPlan(id: string) {
    return http
      .post<PlanConversation>(`/api/agent/plan-conversations/${id}/confirm`, null, agentRequest)
      .then((r) => normalizePlanConversation(r.data as RawPlanConversation))
  }

  createTaskConversation(targetDate: string) {
    return http
      .post<TaskConversation>('/api/agent/task-conversations', { targetDate }, agentRequest)
      .then((r) => r.data)
  }
  getTaskConversation(id: string) {
    return http
      .get<TaskConversation>(`/api/agent/task-conversations/${id}`, agentRequest)
      .then((r) => r.data)
  }
  sendTaskMessage(id: string, message: string) {
    return http
      .post<TaskConversation>(
        `/api/agent/task-conversations/${id}/messages`,
        { message },
        agentRequest,
      )
      .then((r) => r.data)
  }
  confirmTaskAction(id: string) {
    return http
      .post<TaskConversation>(`/api/agent/task-conversations/${id}/confirm`, null, agentRequest)
      .then((r) => r.data)
  }

  createKnowledgeConversation(mode: KnowledgeMode) {
    return http
      .post<KnowledgeConversation>('/api/agent/knowledge-conversations', { mode }, agentRequest)
      .then((r) => r.data)
  }
  getKnowledgeConversation(id: string) {
    return http
      .get<KnowledgeConversation>(`/api/agent/knowledge-conversations/${id}`, agentRequest)
      .then((r) => r.data)
  }
  sendKnowledgeMessage(id: string, message: string, webSearch: WebSearchPreference) {
    return http
      .post<KnowledgeConversation>(`/api/agent/knowledge-conversations/${id}/messages`, {
        message,
        webSearch,
      }, agentRequest)
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
      .post<PlanAdjustment>(
        '/api/agent/plan-adjustments/analyze',
        { analysisDate },
        agentRequest,
      )
      .then((r) => r.data)
  }
  getPlanAdjustment(id: string) {
    return http
      .get<PlanAdjustment>(`/api/agent/plan-adjustments/${id}`, agentRequest)
      .then((r) => r.data)
  }
  confirmPlanAdjustment(id: string) {
    return http
      .post<PlanAdjustment>(`/api/agent/plan-adjustments/${id}/confirm`, null, agentRequest)
      .then((r) => r.data)
  }

  generateQuiz(taskId: string, webSearch: WebSearchPreference) {
    return http
      .post<{ quizId: string }>(
        '/api/agent/quizzes/generate',
        { taskId, webSearch },
        agentRequest,
      )
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
