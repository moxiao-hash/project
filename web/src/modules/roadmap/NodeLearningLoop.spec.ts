import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import NodeView from './NodeView.vue'
import { roadmapApi } from '@/services/roadmap'
import type { RoadmapNode } from '@/types/roadmap'

vi.mock('@/services/roadmap', () => ({
  roadmapApi: {
    getNode: vi.fn(), getCurrentMap: vi.fn(), getNodeQuiz: vi.fn(),
    checkIn: vi.fn(), retryNodeQuiz: vi.fn(), quickVerification: vi.fn(),
  },
}))

const node: RoadmapNode = {
  id: 'node-1', code: 'java-first', order: 1, title: '第一个 Java 程序',
  objectives: ['理解编译与运行'], highFrequency: ['javac 与 java'],
  commonMistakes: ['文件名与 public 类名不一致'], searchKeywords: ['Java HelloWorld'],
  estimatedMinutes: 40, practiceMinutes: 20, difficulty: 'EASY', required: true,
  prerequisiteCodes: [], availabilityStatus: 'AVAILABLE', learningStatus: 'NOT_STARTED',
  checkInStatus: 'MISSING', quizStatus: 'NOT_GENERATED', artifactStatus: 'NOT_REQUIRED',
  completionStatus: 'INCOMPLETE', diagnosticMastered: false,
  displayStatus: 'AVAILABLE', version: 0,
}

async function mountNode() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/roadmap', name: 'roadmap', component: { template: '<div />' } },
      { path: '/roadmap/modules/:id', name: 'roadmap-module', component: { template: '<div />' } },
      { path: '/roadmap/nodes/:id', name: 'roadmap-node', component: NodeView },
      { path: '/quizzes/:id', name: 'quiz', component: { template: '<div />' } },
      { path: '/attempts/:id', name: 'attempt', component: { template: '<div />' } },
    ],
  })
  await router.push('/roadmap/nodes/node-1')
  await router.isReady()
  return mount(NodeView, { global: { plugins: [router] } })
}

describe('roadmap node learning loop', () => {
  beforeEach(() => vi.resetAllMocks())

  it('shows the visible learning sequence and generation state', async () => {
    vi.mocked(roadmapApi.getNode).mockResolvedValue(node)
    vi.mocked(roadmapApi.getNodeQuiz).mockResolvedValue({
      nodeId: node.id, status: 'GENERATING', quizId: null, latestAttemptId: null,
      generation: { jobId: 'job-1', purpose: 'NODE', status: 'PENDING',
        retrySequence: 0, attemptCount: 0, quizId: null, lastError: null,
        leaseUntil: null, updatedAt: new Date().toISOString() },
    })
    const wrapper = await mountNode()
    await flushPromises()

    const text = wrapper.text()
    expect(text.indexOf('学习目标')).toBeLessThan(text.indexOf('自主学习'))
    expect(text.indexOf('自主学习')).toBeLessThan(text.indexOf('总结打卡'))
    expect(text.indexOf('总结打卡')).toBeLessThan(text.indexOf('节点测验'))
    expect(text.indexOf('节点测验')).toBeLessThan(text.indexOf('实践成果'))
    expect(wrapper.get('[role="status"]').text()).toContain('生成中')
  })

  it('uses persisted quiz and attempt ids for action links', async () => {
    vi.mocked(roadmapApi.getNode).mockResolvedValue({ ...node, quizStatus: 'FAILED' })
    vi.mocked(roadmapApi.getNodeQuiz).mockResolvedValue({
      nodeId: node.id, status: 'FAILED', quizId: 'quiz-real', latestAttemptId: 'attempt-real',
      generation: { jobId: 'job-1', purpose: 'NODE', status: 'COMPLETED',
        retrySequence: 0, attemptCount: 1, quizId: 'quiz-real', lastError: null,
        leaseUntil: null, updatedAt: new Date().toISOString() },
    })
    const wrapper = await mountNode()
    await flushPromises()

    expect(wrapper.get('[data-testid="start-quiz"]').attributes('href')).toBe('/quizzes/quiz-real')
    expect(wrapper.get('[data-testid="view-analysis"]').attributes('href'))
      .toBe('/attempts/attempt-real')
    expect(wrapper.get('[data-testid="retry-quiz"]').text()).toContain('重新测验')
  })
})
