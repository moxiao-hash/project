/**
 * 当前 Java API（/api/**）契约类型。
 * 基线：阶段 7 完成版本（2026-07-28，迁移 V1～V20）。
 * 服务端枚举保留英文原值，中文文案仅在展示层映射。
 */

export type GoalStatus = 'DRAFT'
export type PlanStatus = 'DRAFT' | 'CONFIRMED' | 'ARCHIVED'
export type TaskStatus = 'TODO' | 'COMPLETED' | 'SKIPPED' | 'DEFERRED'
export type TaskKind = 'LEARNING' | 'REVIEW' | 'CODING_PRACTICE'
export type MaterialType =
  | 'MARKDOWN' | 'TEXT' | 'PDF' | 'WORD'
  | 'WEB_PAGE' | 'IMAGE' | 'PASTED_ARTICLE'
export type MaterialCategory =
  | 'SYLLABUS' | 'LEARNING_MATERIAL' | 'PERSONAL_NOTE' | 'REFERENCE'
export type PrivacyLevel = 'NORMAL' | 'SENSITIVE' | 'LOCAL_ONLY'
export type MaterialStatus = 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED'
export type WeekendPreference = 'LESS' | 'SAME' | 'MORE'
export type QuestionType = 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'CODING'
export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD'
export type CodingKind =
  | 'CODE_COMPLETION' | 'DEBUGGING' | 'METHOD_IMPLEMENTATION' | 'MINI_MODULE'
export type AttemptStatus = 'EVALUATING' | 'GRADED' | 'PARTIALLY_GRADED'
export type AgentScope =
  | 'MATERIAL_PROCESSING' | 'QUIZ_GENERATION' | 'PLAN_GENERATION'
  | 'TASK_MANAGEMENT' | 'SMALL_PLAN_ADJUSTMENT' | 'LARGE_PLAN_ADJUSTMENT'
export type ExecutionStatus =
  | 'WAITING_AUTHORIZATION' | 'WAITING_CONFIRMATION'
  | 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
export type ExecutionType =
  | 'MATERIAL_PROCESSING' | 'QUIZ_GENERATION' | 'PLAN_GENERATION'
  | 'PLAN_ADJUSTMENT' | 'TASK_STATUS_CHANGE' | 'WEB_SEARCH'
export type RiskLevel = 'LOW' | 'HIGH'
export type TriggerType =
  | 'USER_REQUEST' | 'CHECK_IN' | 'NIGHTLY_CHECK' | 'MATERIAL_IMPORTED'
export type NotificationType =
  | 'PLAN_ADJUSTED' | 'PLAN_ADJUSTMENT_READY' | 'MATERIAL_READY'
  | 'QUIZ_READY' | 'TASK_OVERDUE' | 'AGENT_FAILED'

/** Java 统一错误响应 */
export interface ApiError {
  timestamp: string
  status: number
  error: string
  message: string
  path: string
  fieldErrors?: Record<string, string> | null
}

export interface User {
  id: string
  email: string
  displayName: string
  createdAt: string | null
}

export interface AuthResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresAt: string
  user: User
}

export interface AvailabilitySlot {
  dayOfWeek:
    | 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY'
    | 'FRIDAY' | 'SATURDAY' | 'SUNDAY'
  /** HH:mm:ss */
  startTime: string
  /** HH:mm:ss */
  endTime: string
}

export interface UserSettings {
  timeZone: string
  dailyStudyLimitMinutes: number
  weekendPreference: WeekendPreference
  defaultPrivacyLevel: PrivacyLevel
  weeklyAvailability: AvailabilitySlot[]
}

export interface Dashboard {
  activeGoalCount: number
  todayTaskCount: number
  completedTodayTaskCount: number
  pendingMaterialCount: number
  lowMasteryCount: number
  unreadNotificationCount: number
}

export interface LearningGoal {
  id: string
  title: string
  /** YYYY-MM-DD */
  targetDate: string
  weeklyStudyHours: number
  status: GoalStatus
}

export interface LearningPlan {
  id: string
  goalId: string
  title: string
  startDate: string
  endDate: string
  status: PlanStatus
  version: number
}

export interface LearningPlanVersion {
  version: number
  snapshotJson: string
  changeReason: string
  createdAt: string
}

export interface LearningTask {
  id: string
  planId: string
  title: string
  /** YYYY-MM-DD */
  scheduledDate: string
  estimatedMinutes: number
  status: TaskStatus
  version: number
  completedAt: string | null
  actualMinutes: number | null
  taskKind: TaskKind
  knowledgePoint: string | null
  sourceAttemptId: string | null
}

export interface TaskChange {
  fromStatus: TaskStatus
  toStatus: TaskStatus
  fromScheduledDate: string
  toScheduledDate: string
  reason: string | null
  actualMinutes: number | null
  createdAt: string
}

export interface ChangeTaskStatusRequest {
  status: TaskStatus
  scheduledDate?: string | null
  reason?: string | null
  actualMinutes?: number | null
}

export interface Material {
  id: string
  title: string
  materialType: MaterialType
  category: MaterialCategory
  privacyLevel: PrivacyLevel
  sourceUrl: string | null
  originalFilename: string | null
  mediaType: string | null
  contentLength: number | null
  processingStatus: MaterialStatus
  summary: string | null
  tags: string[]
  knowledgePoints: string[]
  processingWarnings: string[]
  contentReference: string | null
  failureReason: string | null
}

export interface QuizSource {
  sourceType: string
  materialId: string | null
  webResultId: string | null
  title: string
  locator: string | null
  snippet: string
}

export interface QuizQuestion {
  id: string
  type: QuestionType
  difficulty: Difficulty
  codingKind: CodingKind | null
  language: string | null
  knowledgePoint: string
  questionText: string
  options: string[]
  starterCode: string | null
  sources: QuizSource[]
}

export interface Quiz {
  id: string
  materialId: string | null
  taskId: string | null
  title: string
  modelName: string
  questions: QuizQuestion[]
}

export interface QuestionResult {
  questionId: string
  correct: boolean
  knowledgePoint: string
  explanation: string | null
  evaluationMethod: string
  score: number | null
  evaluation: unknown | null
}

export interface QuizAttempt {
  id: string
  score: number
  status: AttemptStatus
  warning: string | null
  results: QuestionResult[]
}

export interface SubmitQuizAttemptRequest {
  idempotencyKey: string
  answers: Array<{
    questionId: string
    selectedAnswers?: string[] | null
    codeAnswer?: string | null
  }>
}

export interface SelfAssessmentRequest {
  ratings: Array<{ knowledgePoint: string; score: number }>
}

export interface Mastery {
  knowledgePoint: string
  score: number
  quizScore: number | null
  taskScore: number | null
  selfAssessmentScore: number | null
  evidenceCount: number
  attemptCount: number
  updatedAt: string
}

export interface AgentGrant {
  id: string
  scopes: AgentScope[]
  expiresAt: string
  active: boolean
}

export interface AgentExecution {
  id: string
  idempotencyKey: string
  executionType: ExecutionType
  triggerType: TriggerType
  riskLevel: RiskLevel
  requiredScope: AgentScope
  status: ExecutionStatus
  summary: string
  resultSummary: string | null
  errorMessage: string | null
  modelName: string | null
  promptTokens: number | null
  completionTokens: number | null
  latencyMs: number | null
  estimatedCost: number | null
  createdAt: string
}

export interface Notification {
  id: string
  type: NotificationType
  title: string
  content: string
  read: boolean
  createdAt: string
  readAt: string | null
}

export interface AuditLog {
  id: number
  action: string
  targetType: string
  targetId: string
  details: string | null
  createdAt: string
}
