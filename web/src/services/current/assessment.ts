import { http } from '../http'
import type {
  Mastery,
  Quiz,
  QuizAttempt,
  SelfAssessmentRequest,
  SubmitQuizAttemptRequest,
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
}
