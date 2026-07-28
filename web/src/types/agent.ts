/**
 * 阶段 8 规划中的 Java Agent 门面契约（🟨 尚未实现）。
 * 页面只通过 services/planned 的 AgentGateway 访问这些能力，
 * 当前默认由 MockAgentGateway 提供，禁止向真实地址发请求。
 */
import type { LearningTask, RiskLevel, TaskKind, TaskStatus } from './api'

export type PlanConversationStatus =
  | 'COLLECTING' | 'DRAFT_READY' | 'SAVING' | 'COMPLETED' | 'FAILED'

export interface PlanDraft {
  title: string
  startDate: string
  endDate: string
  tasks: Array<{
    title: string
    scheduledDate: string
    estimatedMinutes: number
  }>
}

export interface Citation {
  sourceType: string
  title: string
  snippet: string
  materialId: string | null
  locator: string | null
  resultId: string | null
  url: string | null
}

export interface PlanConversation {
  conversationId: string
  goalId: string
  status: PlanConversationStatus
  reply: string
  draft: PlanDraft | null
  savedPlanId: string | null
  error: string | null
  citations: Citation[]
  warnings: string[]
}

export type TaskConversationStatus =
  | 'COLLECTING' | 'PREVIEW_READY' | 'EXECUTING' | 'COMPLETED' | 'FAILED'

export interface TaskCandidate {
  id: string
  title: string
  status: TaskStatus
  version: number
}

export interface TaskActionDraft {
  targetStatus: TaskStatus
  taskId: string
  taskTitle: string
  expectedVersion: number
  reason: string | null
  deferredTo: string | null
  actualMinutes: number | null
}

export interface TaskConversation {
  conversationId: string
  targetDate: string
  status: TaskConversationStatus
  reply: string
  candidateTasks: TaskCandidate[]
  actionDraft: TaskActionDraft | null
  executionId: string | null
  updatedTask: LearningTask | null
  error: string | null
}

export type KnowledgeMode = 'AUTO' | 'LOCAL_ONLY'
export type WebSearchPreference = 'AUTO' | 'ENABLED' | 'DISABLED'

export interface KnowledgeConversation {
  conversationId: string
  mode: KnowledgeMode
  status: 'ACTIVE'
  answer: string
  retrievalMode: string
  citations: Citation[]
  warnings: string[]
}

export type AdjustmentOperationType =
  | 'RESCHEDULE_TASK' | 'UPDATE_ESTIMATE' | 'SPLIT_TASK' | 'INSERT_REVIEW_TASK'

export interface AdjustmentOperation {
  type: AdjustmentOperationType
  taskId: string | null
  expectedVersion: number | null
  scheduledDate: string | null
  estimatedMinutes: number | null
  firstTitle: string | null
  firstEstimatedMinutes: number | null
  secondTitle: string | null
  secondScheduledDate: string | null
  secondEstimatedMinutes: number | null
  title?: string | null
  taskKind?: TaskKind | null
  knowledgePoint?: string | null
  sourceAttemptId?: string | null
}

export type PlanAdjustmentStatus =
  | 'ANALYZING' | 'NO_CHANGE' | 'DRAFT_READY' | 'EXECUTING' | 'COMPLETED' | 'FAILED'

export type AdjustmentSignal =
  | 'OVERDUE_TASKS' | 'CONSECUTIVE_SKIPS' | 'TIME_ESTIMATE_BIAS'

export interface PlanAdjustment {
  id: string
  planId: string
  analysisDate: string
  triggerType: string
  signals: AdjustmentSignal[]
  summary: string
  operations: AdjustmentOperation[]
  riskLevel: RiskLevel
  status: PlanAdjustmentStatus
  executionId: string | null
  beforePlanVersion: number
  afterPlanVersion: number | null
  error: string | null
  createdAt: string
  updatedAt: string
}

export interface AiSettings {
  modelProvider: 'deepseek'
  modelName: string
  deepseekConfigured: boolean
  deepseekMaskedSuffix: string | null
  tavilyConfigured: boolean
  tavilyMaskedSuffix: string | null
}

/**
 * 阶段 8 Java 门面统一接口。页面不得绕过该接口访问 Agent 能力。
 * Mock 与未来的 Http 实现可互换，切换不影响页面。
 */
export interface AgentGateway {
  createPlanConversation(goalId: string): Promise<PlanConversation>
  sendPlanMessage(id: string, message: string): Promise<PlanConversation>
  confirmPlan(id: string): Promise<PlanConversation>

  createTaskConversation(targetDate: string): Promise<TaskConversation>
  sendTaskMessage(id: string, message: string): Promise<TaskConversation>
  confirmTaskAction(id: string): Promise<TaskConversation>

  createKnowledgeConversation(mode: KnowledgeMode): Promise<KnowledgeConversation>
  sendKnowledgeMessage(
    id: string,
    message: string,
    webSearch: WebSearchPreference,
  ): Promise<KnowledgeConversation>
  importWebResult(
    resultId: string,
    category: string,
    privacyLevel: string,
  ): Promise<{ materialId: string }>

  analyzePlanAdjustment(analysisDate: string): Promise<PlanAdjustment>
  getPlanAdjustment(id: string): Promise<PlanAdjustment>
  confirmPlanAdjustment(id: string): Promise<PlanAdjustment>

  generateQuiz(
    taskId: string,
    webSearch: WebSearchPreference,
  ): Promise<{ quizId: string }>

  getAiSettings(): Promise<AiSettings>
  updateDeepseekKey(apiKey: string): Promise<AiSettings>
  deleteDeepseekKey(): Promise<AiSettings>
  updateTavilyKey(apiKey: string): Promise<AiSettings>
  deleteTavilyKey(): Promise<AiSettings>
}
