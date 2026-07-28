import { http } from '../http'
import type {
  ChangeTaskStatusRequest,
  LearningGoal,
  LearningPlan,
  LearningPlanVersion,
  LearningTask,
  TaskChange,
} from '@/types/api'

export interface CreateGoalRequest {
  title: string
  targetDate: string
  weeklyStudyHours: number
}

export interface CreatePlanRequest {
  goalId: string
  title: string
  startDate: string
  endDate: string
}

export interface CreateTaskRequest {
  title: string
  scheduledDate: string
  estimatedMinutes: number
}

export const learningApi = {
  // 目标
  createGoal(body: CreateGoalRequest) {
    return http.post<LearningGoal>('/api/learning-goals', body).then((r) => r.data)
  },
  listGoals() {
    return http.get<LearningGoal[]>('/api/learning-goals').then((r) => r.data)
  },
  updateGoal(goalId: string, body: CreateGoalRequest) {
    return http
      .patch<LearningGoal>(`/api/learning-goals/${goalId}`, body)
      .then((r) => r.data)
  },

  // 计划
  createPlan(body: CreatePlanRequest) {
    return http.post<LearningPlan>('/api/learning-plans', body).then((r) => r.data)
  },
  listPlans() {
    return http.get<LearningPlan[]>('/api/learning-plans').then((r) => r.data)
  },
  confirmPlan(planId: string) {
    return http
      .post<LearningPlan>(`/api/learning-plans/${planId}/confirm`)
      .then((r) => r.data)
  },
  listPlanVersions(planId: string) {
    return http
      .get<LearningPlanVersion[]>(`/api/learning-plans/${planId}/versions`)
      .then((r) => r.data)
  },

  // 任务
  createTask(planId: string, body: CreateTaskRequest) {
    return http
      .post<LearningTask>(`/api/learning-plans/${planId}/tasks`, body)
      .then((r) => r.data)
  },
  listTasks(date?: string) {
    return http
      .get<LearningTask[]>('/api/learning-tasks', { params: date ? { date } : {} })
      .then((r) => r.data)
  },
  changeTaskStatus(taskId: string, body: ChangeTaskStatusRequest) {
    return http
      .patch<LearningTask>(`/api/learning-tasks/${taskId}/status`, body)
      .then((r) => r.data)
  },
  taskHistory(taskId: string) {
    return http
      .get<TaskChange[]>(`/api/learning-tasks/${taskId}/history`)
      .then((r) => r.data)
  },
}
