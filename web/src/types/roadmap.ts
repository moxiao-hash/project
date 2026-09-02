export type RoadmapDisplayStatus =
  | 'LOCKED'
  | 'AVAILABLE'
  | 'SCHEDULED'
  | 'IN_PROGRESS'
  | 'QUIZ_PENDING'
  | 'REVIEW_REQUIRED'
  | 'COMPLETED'

export interface RoadmapNode {
  id: string
  code: string
  order: number
  title: string
  objectives: string[]
  highFrequency: string[]
  commonMistakes: string[]
  searchKeywords: string[]
  estimatedMinutes: number
  practiceMinutes: number
  difficulty: 'EASY' | 'MEDIUM' | 'HARD'
  required: boolean
  prerequisiteCodes: string[]
  availabilityStatus: 'LOCKED' | 'AVAILABLE'
  learningStatus: 'NOT_STARTED' | 'SCHEDULED' | 'IN_PROGRESS'
  checkInStatus: 'MISSING' | 'SUBMITTED'
  quizStatus:
    | 'NOT_GENERATED'
    | 'GENERATING'
    | 'READY'
    | 'EVALUATING'
    | 'PASSED'
    | 'FAILED'
    | 'PARTIALLY_GRADED'
  artifactStatus: 'NOT_REQUIRED' | 'MISSING' | 'SUBMITTED' | 'ACCEPTED' | 'REJECTED'
  completionStatus: 'INCOMPLETE' | 'COMPLETED'
  diagnosticMastered: boolean
  displayStatus: RoadmapDisplayStatus
  version: number
}

export interface RoadmapQuizGeneration {
  jobId: string
  purpose: 'NODE' | 'DIAGNOSTIC' | 'STAGE_GRADUATION'
  status: 'PENDING' | 'LEASED' | 'COMPLETED' | 'FAILED'
  retrySequence: number
  attemptCount: number
  quizId: string | null
  lastError: string | null
  leaseUntil: string | null
  updatedAt: string
}

export interface RoadmapNodeQuiz {
  nodeId: string
  status: RoadmapNode['quizStatus']
  quizId: string | null
  latestAttemptId: string | null
  generation: RoadmapQuizGeneration
}

export interface RoadmapNodeCheckIn {
  id: string
  nodeId: string
  summary: string
  idempotencyKey: string
  createdAt: string
  quizGeneration: RoadmapQuizGeneration
}

export interface RoadmapScheduleItem {
  id: string
  nodeId: string
  nodeCode: string
  title: string
  plannedMinutes: number
  status: 'PLANNED' | 'STARTED' | 'COMPLETED'
}

export interface RoadmapSchedule {
  scheduleId: string
  timeZone: string
  dailyCapacityMinutes: number
  weekendsEnabled: boolean
  days: Array<{
    date: string
    plannedMinutes: number
    items: RoadmapScheduleItem[]
  }>
}

export interface RoadmapModuleSummary {
  id: string
  code: string
  order: number
  title: string
  description: string
  completedRequiredNodes: number
  totalRequiredNodes: number
  milestoneNodeId: string
  milestoneNodeCode: string
  displayStatus: RoadmapDisplayStatus
}

export interface RoadmapModule {
  id: string
  stageId: string
  code: string
  order: number
  title: string
  description: string
  completedRequiredNodes: number
  totalRequiredNodes: number
  displayStatus: RoadmapDisplayStatus
  milestoneNode: RoadmapNode
  nodes: RoadmapNode[]
}

export interface RoadmapStage {
  id: string
  code: string
  order: number
  title: string
  description: string
  graduationProjectTitle: string
  completedRequiredNodes: number
  totalRequiredNodes: number
  modules: RoadmapModuleSummary[]
  nodes: RoadmapNode[]
}

export interface RoadmapMap {
  enrollmentId: string
  roadmapCode: string
  templateVersion: number
  title: string
  description: string
  completedRequiredNodes: number
  totalRequiredNodes: number
  stages: RoadmapStage[]
}

export interface RoadmapEnrollment {
  id: string
  roadmapCode: string
  templateVersion: number
  title: string
  status: 'ACTIVE' | 'SUPERSEDED' | 'ARCHIVED'
  enrolledAt: string
}
