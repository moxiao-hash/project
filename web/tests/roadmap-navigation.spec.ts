import { afterEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/services/http'
import { roadmapApi } from '@/services/roadmap'
import type {
  RoadmapEnrollment,
  RoadmapMap,
  RoadmapNode,
  RoadmapStage,
} from '@/types/roadmap'

const node: RoadmapNode = {
  id: 'node/1',
  code: 'java-syntax-oop',
  order: 1,
  title: 'Java 语法与面向对象',
  objectives: ['掌握 Java 基础语法'],
  highFrequency: ['集合与异常处理'],
  commonMistakes: ['混淆 == 与 equals'],
  searchKeywords: ['Java OOP'],
  estimatedMinutes: 180,
  practiceMinutes: 90,
  difficulty: 'EASY',
  required: true,
  prerequisiteCodes: [],
  availabilityStatus: 'AVAILABLE',
  learningStatus: 'NOT_STARTED',
  checkInStatus: 'MISSING',
  quizStatus: 'NOT_GENERATED',
  artifactStatus: 'NOT_REQUIRED',
  completionStatus: 'INCOMPLETE',
  displayStatus: 'AVAILABLE',
  version: 0,
}

const stage: RoadmapStage = {
  id: 'stage/1',
  code: 'java-core',
  order: 1,
  title: 'Java 核心基础',
  description: '建立传统 Java 后端开发基础',
  graduationProjectTitle: '命令行学习记录器',
  completedRequiredNodes: 0,
  totalRequiredNodes: 1,
  nodes: [node],
}

const map: RoadmapMap = {
  enrollmentId: 'enrollment-1',
  roadmapCode: 'studypilot-java-ai',
  templateVersion: 1,
  title: 'Java + AI 全栈学习路线',
  description: '从 Java 后端基础走向可治理的 Python Agent',
  completedRequiredNodes: 0,
  totalRequiredNodes: 64,
  stages: [stage],
}

const enrollment: RoadmapEnrollment = {
  id: 'enrollment-1',
  roadmapCode: 'studypilot-java-ai',
  templateVersion: 1,
  title: 'Java + AI 全栈学习路线',
  status: 'ACTIVE',
  enrolledAt: '2026-08-09T08:00:00Z',
}

describe('roadmapApi', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('enrolls through the Java public API with only the roadmap identity', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValueOnce({ data: enrollment })

    await expect(roadmapApi.enroll()).resolves.toEqual(enrollment)

    expect(post).toHaveBeenCalledExactlyOnceWith('/api/roadmap-enrollments', {
      roadmapCode: 'studypilot-java-ai',
      templateVersion: 1,
    })
  })

  it('loads the current map only through the Java public API', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValueOnce({ data: map })

    await expect(roadmapApi.getCurrentMap()).resolves.toEqual(map)

    expect(get).toHaveBeenCalledExactlyOnceWith('/api/roadmaps/current/map')
  })

  it('encodes the stage ID and returns the response data', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValueOnce({ data: stage })

    await expect(roadmapApi.getStage('stage/1 with space')).resolves.toEqual(stage)

    expect(get).toHaveBeenCalledExactlyOnceWith(
      '/api/roadmaps/current/stages/stage%2F1%20with%20space',
    )
  })

  it('encodes the node ID and returns the response data', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValueOnce({ data: node })

    await expect(roadmapApi.getNode('node/1 with space')).resolves.toEqual(node)

    expect(get).toHaveBeenCalledExactlyOnceWith(
      '/api/roadmaps/current/nodes/node%2F1%20with%20space',
    )
  })

  it('propagates a rejected HTTP promise unchanged', async () => {
    const failure = new Error('roadmap unavailable')
    vi.spyOn(http, 'get').mockRejectedValueOnce(failure)

    await expect(roadmapApi.getCurrentMap()).rejects.toBe(failure)
  })
})
