import { http } from './http'
import type {
  CourseDetail,
  CourseSummary,
  Lesson,
  LessonCheckpointResult,
  TeachingConversation,
} from '@/types/course'

const agentRequest = { timeout: 120_000 } as const

export interface UpdateLessonProgress {
  videoCompleted: boolean
  readingCompleted: boolean
  lastSectionKey: string | null
}

export const courseApi = {
  listCourses() {
    return http.get<CourseSummary[]>('/api/courses').then((response) => response.data)
  },
  getCourse(slug: string) {
    return http.get<CourseDetail>(`/api/courses/${slug}`).then((response) => response.data)
  },
  getContinueLesson() {
    return http
      .get<Lesson | null>('/api/courses/continue')
      .then((response) => (response.status === 204 ? null : response.data))
  },
  getLesson(lessonId: string) {
    return http.get<Lesson>(`/api/lessons/${lessonId}`).then((response) => response.data)
  },
  updateProgress(lessonId: string, body: UpdateLessonProgress) {
    return http
      .put<Lesson>(`/api/lessons/${lessonId}/progress`, body)
      .then((response) => response.data)
  },
  submitCheckpoint(lessonId: string, blockKey: string, selectedOption: number) {
    return http
      .post<LessonCheckpointResult>(
        `/api/lessons/${lessonId}/checkpoints/${blockKey}/attempts`,
        { selectedOption },
      )
      .then((response) => response.data)
  },
  generateLessonQuiz(lessonId: string) {
    return http
      .post<{ quizId: string }>(
        '/api/agent/quizzes/generate',
        { lessonId, webSearch: 'AUTO' },
        agentRequest,
      )
      .then((response) => response.data)
  },
}

export const teachingApi = {
  createConversation(lessonId: string) {
    return http
      .post<TeachingConversation>(
        '/api/agent/teaching-conversations',
        { lessonId },
        agentRequest,
      )
      .then((response) => response.data)
  },
  sendMessage(conversationId: string, message: string) {
    return http
      .post<TeachingConversation>(
        `/api/agent/teaching-conversations/${conversationId}/messages`,
        { message },
        agentRequest,
      )
      .then((response) => response.data)
  },
}
