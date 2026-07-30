import type { Citation } from './agent'

export type LessonProgressStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED'
export type LessonSourceType = 'VIDEO' | 'OFFICIAL_DOC' | 'MATERIAL' | 'PROJECT_CODE'
export type LessonBlockType =
  | 'OBJECTIVES'
  | 'EXPLANATION'
  | 'PROJECT_CODE'
  | 'CHECKPOINT'
  | 'SUMMARY'

export interface LessonProgress {
  status: LessonProgressStatus
  videoCompleted: boolean
  readingCompleted: boolean
  practiceCompleted: boolean
  checkpointPassed: boolean
  quizPassed: boolean
  lastSectionKey: string | null
  startedAt?: string | null
  completedAt?: string | null
  updatedAt?: string | null
}

export interface LessonSource {
  type: LessonSourceType
  title: string
  url: string
  locator: string | null
  bvid: string | null
  videoPage: number | null
}

export interface LessonBlock {
  key: string
  type: LessonBlockType
  title: string
  markdown?: string
  projectPath?: string
  question?: string
  options?: string[]
}

export interface Lesson {
  id: string
  moduleId: string
  slug: string
  order: number
  title: string
  summary: string
  estimatedMinutes: number
  content: { blocks: LessonBlock[] }
  published: boolean
  sources: LessonSource[]
  progress: LessonProgress
}

export interface CourseSummary {
  id: string
  slug: string
  title: string
  description: string
  techStack: string
  publicationStatus: 'DRAFT' | 'PUBLISHED'
  version: number
  moduleCount: number
  lessonCount: number
  completedLessonCount: number
  progressPercent: number
}

export interface CourseLessonItem {
  id: string
  slug: string
  order: number
  title: string
  summary: string
  estimatedMinutes: number
  published: boolean
  progress: LessonProgress
}

export interface CourseModule {
  id: string
  order: number
  title: string
  description: string
  lessons: CourseLessonItem[]
}

export interface CourseDetail {
  course: CourseSummary
  modules: CourseModule[]
}

export interface TeachingConversation {
  conversationId: string
  lessonId: string
  status: 'ACTIVE'
  answer: string
  citations: Citation[]
  suggestedActions: Array<
    'CHECK_UNDERSTANDING' | 'SHOW_EXAMPLE' | 'GIVE_HINT' | 'CONTINUE_LESSON'
  >
  modelProvider: string
  modelName: string
}

export interface LessonCheckpointResult {
  correct: boolean
  explanation: string
  progress: LessonProgress
}
