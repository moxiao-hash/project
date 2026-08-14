import { http } from './http'
import type {
  RoadmapEnrollment,
  RoadmapMap,
  RoadmapModule,
  RoadmapNode,
  RoadmapStage,
} from '@/types/roadmap'

export const roadmapApi = {
  enroll(roadmapCode = 'studypilot-java-ai', templateVersion = 1) {
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
}
