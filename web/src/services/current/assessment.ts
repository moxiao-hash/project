import { http } from '../http'
import type {
  Mastery,
  Quiz,
  QuizAttempt,
  SelfAssessmentRequest,
  SubmitQuizAttemptRequest,
  WrongQuestionPage,
  WrongQuestionReview,
  WrongQuestionStatus,
  WrongQuestionSummary,
} from '@/types/api'

export const assessmentApi = {
  getQuiz(quizId: string) {
    return http.get<Quiz>(`/api/quizzes/${quizId}`).then((r) => r.data)
  },
  submitAttempt(quizId: string, body: SubmitQuizAttemptRequest) {
    return http
      .post<QuizAttempt>(`/api/quizzes/${quizId}/attempts`, body)
      .then((r) => r.data)
  },
  getAttempt(attemptId: string) {
    return http.get<QuizAttempt>(`/api/quiz-attempts/${attemptId}`).then((r) => r.data)
  },
  selfAssess(attemptId: string, body: SelfAssessmentRequest) {
    return http
      .post<Mastery[]>(`/api/quiz-attempts/${attemptId}/self-assessments`, body)
      .then((r) => r.data)
  },
  listMastery() {
    return http.get<Mastery[]>('/api/mastery').then((r) => r.data)
  },
  getWrongQuestionSummary() {
    return http.get<WrongQuestionSummary>('/api/wrong-questions/summary').then((r) => r.data)
  },
  listWrongQuestions(params: {
    status?: WrongQuestionStatus
    chapterKey?: string
    page?: number
    size?: number
  } = {}) {
    return http.get<WrongQuestionPage>('/api/wrong-questions', { params }).then((r) => r.data)
  },
  getCurrentWrongQuestionReview() {
    return http.get<WrongQuestionReview | ''>('/api/wrong-question-reviews/current')
      .then((r) => r.data || null)
  },
  createWrongQuestionReview(body: { chapterKey: string | null; idempotencyKey: string }) {
    return http.post<WrongQuestionReview>('/api/wrong-question-reviews', body)
      .then((r) => r.data)
  },
}
