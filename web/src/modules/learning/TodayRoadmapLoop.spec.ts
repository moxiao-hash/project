import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, expect, it, vi } from 'vitest'

import TodayView from './TodayView.vue'
import { roadmapApi } from '@/services/roadmap'
import { learningApi } from '@/services/current/learning'
import { todayString } from '@/utils/datetime'

vi.mock('@/services/roadmap', () => ({ roadmapApi: {
  getSchedule: vi.fn(), getNodeQuiz: vi.fn(), retryNodeQuiz: vi.fn(),
} }))
vi.mock('@/services/current/learning', () => ({ learningApi: { listTasks: vi.fn() } }))

beforeEach(() => vi.resetAllMocks())

it('shows route-projected nodes and a real quiz entry on Today', async () => {
  const today = todayString()
  vi.mocked(learningApi.listTasks).mockResolvedValue([])
  vi.mocked(roadmapApi.getSchedule).mockResolvedValue({
    scheduleId: 'schedule-1', timeZone: 'Asia/Shanghai', dailyCapacityMinutes: 60,
    weekendsEnabled: true, days: [{ date: today, plannedMinutes: 40, items: [{
      id: 'item-1', nodeId: 'node-1', nodeCode: 'java-first', title: '第一个 Java 程序',
      plannedMinutes: 40, status: 'STARTED',
    }] }],
  })
  vi.mocked(roadmapApi.getNodeQuiz).mockResolvedValue({
    nodeId: 'node-1', status: 'READY', quizId: 'quiz-real', latestAttemptId: null,
    generation: { jobId: 'job-1', purpose: 'NODE', status: 'COMPLETED', retrySequence: 0,
      attemptCount: 1, quizId: 'quiz-real', lastError: null, leaseUntil: null,
      updatedAt: new Date().toISOString() },
  })
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/today', component: TodayView },
    { path: '/roadmap', name: 'roadmap', component: { template: '<div />' } },
    { path: '/roadmap/nodes/:id', name: 'roadmap-node', component: { template: '<div />' } },
    { path: '/quizzes/:id', name: 'quiz', component: { template: '<div />' } },
    { path: '/plans', component: { template: '<div />' } },
    { path: '/agent/tasks', component: { template: '<div />' } },
  ] })
  await router.push('/today')
  await router.isReady()
  const wrapper = mount(TodayView, { global: { plugins: [router] } })
  await flushPromises()

  expect(wrapper.text()).toContain('今日路线学习')
  expect(wrapper.get('a[href="/roadmap/nodes/node-1"]').text()).toContain('第一个 Java 程序')
  expect(wrapper.get('a[href="/quizzes/quiz-real"]').text()).toContain('开始测验')
})

it('retries a failed node quiz from Today without inventing a quiz id', async () => {
  const today = todayString()
  vi.mocked(learningApi.listTasks).mockResolvedValue([])
  vi.mocked(roadmapApi.getSchedule).mockResolvedValue({
    scheduleId: 'schedule-1', timeZone: 'Asia/Shanghai', dailyCapacityMinutes: 60,
    weekendsEnabled: true, days: [{ date: today, plannedMinutes: 40, items: [{
      id: 'item-1', nodeId: 'node-1', nodeCode: 'java-first', title: '第一个 Java 程序',
      plannedMinutes: 40, status: 'STARTED',
    }] }],
  })
  vi.mocked(roadmapApi.getNodeQuiz).mockResolvedValue({
    nodeId: 'node-1', status: 'FAILED', quizId: 'quiz-real', latestAttemptId: 'attempt-real',
    generation: { jobId: 'job-1', purpose: 'NODE', status: 'COMPLETED', retrySequence: 0,
      attemptCount: 1, quizId: 'quiz-real', lastError: null, leaseUntil: null,
      updatedAt: new Date().toISOString() },
  })
  vi.mocked(roadmapApi.retryNodeQuiz).mockResolvedValue({
    jobId: 'job-2', purpose: 'NODE', status: 'PENDING', retrySequence: 1,
    attemptCount: 0, quizId: null, lastError: null, leaseUntil: null,
    updatedAt: new Date().toISOString(),
  })
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/today', component: TodayView },
    { path: '/roadmap', name: 'roadmap', component: { template: '<div />' } },
    { path: '/roadmap/nodes/:id', name: 'roadmap-node', component: { template: '<div />' } },
    { path: '/quizzes/:id', name: 'quiz', component: { template: '<div />' } },
    { path: '/attempts/:id', name: 'attempt', component: { template: '<div />' } },
    { path: '/plans', component: { template: '<div />' } },
    { path: '/agent/tasks', component: { template: '<div />' } },
  ] })
  await router.push('/today')
  await router.isReady()
  const wrapper = mount(TodayView, { global: { plugins: [router] } })
  await flushPromises()

  await wrapper.get('[data-testid="today-retry-quiz"]').trigger('click')
  await flushPromises()
  expect(roadmapApi.retryNodeQuiz).toHaveBeenCalledWith(
    'node-1', expect.stringMatching(/^roadmap-quiz-retry:node-1:/),
  )
})
