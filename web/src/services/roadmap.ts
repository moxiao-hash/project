import { http } from './http'
import type {
  RoadmapEnrollment,
  RoadmapMap,
  RoadmapModule,
  RoadmapNode,
  RoadmapNodeCheckIn,
  RoadmapNodeQuiz,
  RoadmapQuizGeneration,
  RoadmapSchedule,
  RoadmapStage,
  RoadmapUpgrade,
} from '@/types/roadmap'

export const roadmapApi = {
  enroll(roadmapCode = 'studypilot-java-ai', templateVersion = 2) {
    return http
      .post<RoadmapEnrollment>('/api/roadmap-enrollments', {
        roadmapCode,
        templateVersion,
      })
      .then((response) => response.data)
  },
  getCurrentMap() {
    return http
      .get<RoadmapMap>('/api/roadmaps/current/map')
      .then((response) => response.data)
  },
  getUpgrades() {
    return http
      .get<RoadmapUpgrade[]>('/api/roadmaps/current/upgrades')
      .then((response) => response.data)
  },
  confirmUpgrade(upgradeId: string) {
    return http
      .post<RoadmapUpgrade>(
        `/api/roadmaps/current/upgrades/${encodeURIComponent(upgradeId)}/confirm`,
      )
      .then((response) => response.data)
  },
  getStage(stageId: string) {
    return http
      .get<RoadmapStage>(`/api/roadmaps/current/stages/${encodeURIComponent(stageId)}`)
      .then((response) => response.data)
  },
  getModule(moduleId: string) {
    return http
      .get<RoadmapModule>(`/api/roadmaps/current/modules/${encodeURIComponent(moduleId)}`)
      .then((response) => response.data)
  },
  getNode(nodeId: string) {
    return http
      .get<RoadmapNode>(`/api/roadmaps/current/nodes/${encodeURIComponent(nodeId)}`)
      .then((response) => response.data)
  },
  checkIn(nodeId: string, summary: string, idempotencyKey: string) {
    return http.post<RoadmapNodeCheckIn>(
      `/api/roadmap-nodes/${encodeURIComponent(nodeId)}/check-ins`,
      { summary, idempotencyKey },
    ).then((response) => response.data)
  },
  getNodeQuiz(nodeId: string) {
    return http.get<RoadmapNodeQuiz>(
      `/api/roadmap-nodes/${encodeURIComponent(nodeId)}/quiz`,
    ).then((response) => response.data)
  },
  retryNodeQuiz(nodeId: string, idempotencyKey: string) {
    return http.post<RoadmapQuizGeneration>(
      `/api/roadmap-nodes/${encodeURIComponent(nodeId)}/quiz-retries`,
      { idempotencyKey },
    ).then((response) => response.data)
  },
  quickVerification(nodeId: string, idempotencyKey: string) {
    return http.post<RoadmapQuizGeneration>(
      `/api/roadmap-nodes/${encodeURIComponent(nodeId)}/quick-verification`,
      { idempotencyKey },
    ).then((response) => response.data)
  },
  getSchedule(from: string, to: string) {
    return http.get<RoadmapSchedule>('/api/roadmaps/current/schedule', {
      params: { from, to },
    }).then((response) => response.data)
  },
  refreshSchedule() {
    return http.post<RoadmapSchedule>('/api/roadmaps/current/schedule/refresh')
      .then((response) => response.data)
  },
}
