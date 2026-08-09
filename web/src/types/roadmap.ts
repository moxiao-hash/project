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
  displayStatus: RoadmapDisplayStatus
  version: number
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
