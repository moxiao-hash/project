import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'

import WrongQuestionsView from './WrongQuestionsView.vue'
import { assessmentApi } from '@/services/current/assessment'
import type { WrongQuestion } from '@/types/api'
import { useUiActionAdapterStore } from '@/stores/uiActionAdapter'

vi.mock('@/services/current/assessment', () => ({
  assessmentApi: {
    getWrongQuestionSummary: vi.fn(),
    listWrongQuestions: vi.fn(),
    getCurrentWrongQuestionReview: vi.fn(),
    createWrongQuestionReview: vi.fn(),
  },
}))

const summary = {
  activeCount: 1,
  masteredCount: 2,
  chapters: [{ chapterKey: 'java-basics', chapterTitle: 'Java 基础', activeCount: 1, masteredCount: 2 }],
  currentReview: null,
}

const wrong: WrongQuestion = {
  id: 'wrong-1', status: 'ACTIVE', chapterKey: 'java-basics', chapterTitle: 'Java 基础',
  type: 'SINGLE_CHOICE', difficulty: 'EASY', codingKind: null, language: null,
  knowledgePoint: 'String 比较', questionText: '字符串内容应该如何比较？',
  options: ['==', 'equals'], latestSelectedAnswers: ['=='], latestCodeAnswer: null,
  correctAnswers: ['equals'], referenceAnswer: null, explanation: 'equals 比较字符串内容。',
  sources: [], wrongCount: 2, redoCount: 0,
  firstWrongAt: '2026-09-01T00:00:00Z', lastWrongAt: '2026-09-02T00:00:00Z', masteredAt: null,
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/wrong-questions', component: WrongQuestionsView },
      { path: '/quizzes/:id', component: { template: '<div />' } },
    ],
  })
  await router.push('/wrong-questions')
  await router.isReady()
  return mount(WrongQuestionsView, { global: { plugins: [router] } })
}

describe('wrong question book', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
    vi.mocked(assessmentApi.getWrongQuestionSummary).mockResolvedValue(summary)
    vi.mocked(assessmentApi.listWrongQuestions).mockResolvedValue({
      items: [wrong], totalElements: 1, page: 0, size: 20,
    })
    vi.mocked(assessmentApi.getCurrentWrongQuestionReview).mockResolvedValue(null)
  })

  it('shows the question, submitted answer, correct answer and explanation together', async () => {
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('字符串内容应该如何比较？')
    expect(wrapper.text()).toContain('你的答案')
    expect(wrapper.text()).toContain('==')
    expect(wrapper.text()).toContain('正确答案')
    expect(wrapper.text()).toContain('equals')
    expect(wrapper.text()).toContain('equals 比较字符串内容。')
  })

  it('creates a review for the selected chapter and opens the reused quiz', async () => {
    vi.mocked(assessmentApi.createWrongQuestionReview).mockResolvedValue({
      id: 'review-1', quizId: 'quiz-review', status: 'OPEN', questionCount: 1, remainingCount: 1,
    })
    const wrapper = await mountView()
    await flushPromises()

    await wrapper.get('[data-testid="redo-tab"]').trigger('click')
    await wrapper.get('select').setValue('java-basics')
    await wrapper.get('[data-testid="start-redo"]').trigger('click')
    await flushPromises()

    expect(assessmentApi.createWrongQuestionReview).toHaveBeenCalledWith(expect.objectContaining({
      chapterKey: 'java-basics',
    }))
  })

  it('shows the completion state when no active wrong questions remain', async () => {
    vi.mocked(assessmentApi.getWrongQuestionSummary).mockResolvedValue({
      ...summary, activeCount: 0, currentReview: null,
    })
    vi.mocked(assessmentApi.listWrongQuestions).mockResolvedValue({
      items: [], totalElements: 0, page: 0, size: 20,
    })
    const wrapper = await mountView()
    await flushPromises()
    await wrapper.get('[data-testid="redo-tab"]').trigger('click')

    expect(wrapper.text()).toContain('错题已全部清空')
  })

  describe('UI action adapter (REFRESH_RESOURCE WRONG_QUESTIONS)', () => {
    it('registers adapter on mount and unregisters on unmount', async () => {
      const adapterStore = useUiActionAdapterStore()
      expect(adapterStore.getAdaptersForRouteKey('WRONG_QUESTIONS').resourceManager).toBeUndefined()

      const wrapper = await mountView()
      await flushPromises()

      const adapters = adapterStore.getAdaptersForRouteKey('WRONG_QUESTIONS')
      expect(adapters.resourceManager).toBeDefined()

      wrapper.unmount()
      expect(adapterStore.getAdaptersForRouteKey('WRONG_QUESTIONS').resourceManager).toBeUndefined()
    })

    it('rejects unsupported resource keys', async () => {
      await mountView()
      await flushPromises()
      const adapterStore = useUiActionAdapterStore()
      const resourceManager = adapterStore.getAdaptersForRouteKey('WRONG_QUESTIONS').resourceManager

      await expect(resourceManager?.refresh('ROADMAP')).rejects.toThrow('不支持刷新的资源: ROADMAP')
      await expect(resourceManager?.refresh('UNKNOWN')).rejects.toThrow('不支持刷新的资源: UNKNOWN')
    })

    it('successfully refreshes and updates view state preserving filters', async () => {
      const wrapper = await mountView()
      await flushPromises()

      // Switch to MASTERED
      const statusButtons = wrapper.findAll('.status-switch button')
      await statusButtons[1].trigger('click')
      await flushPromises()

      expect(assessmentApi.listWrongQuestions).toHaveBeenLastCalledWith(expect.objectContaining({
        status: 'MASTERED',
        page: 0,
      }))

      // Prepare updated data on refresh
      const updatedSummary = {
        activeCount: 0,
        masteredCount: 3,
        chapters: [{ chapterKey: 'java-basics', chapterTitle: 'Java 基础', activeCount: 0, masteredCount: 3 }],
        currentReview: null,
      }
      const updatedWrong: WrongQuestion = {
        ...wrong,
        id: 'wrong-2',
        status: 'MASTERED',
        questionText: '更新后的已掌握错题',
      }

      vi.mocked(assessmentApi.getWrongQuestionSummary).mockResolvedValue(updatedSummary)
      vi.mocked(assessmentApi.listWrongQuestions).mockResolvedValue({
        items: [updatedWrong], totalElements: 1, page: 0, size: 20,
      })

      const adapterStore = useUiActionAdapterStore()
      const result = await adapterStore.getAdaptersForRouteKey('WRONG_QUESTIONS').resourceManager?.refresh('WRONG_QUESTIONS')

      expect(result).toBe(true)
      await flushPromises()

      expect(assessmentApi.getWrongQuestionSummary).toHaveBeenCalledTimes(2)
      expect(assessmentApi.getCurrentWrongQuestionReview).toHaveBeenCalledTimes(2)
      expect(assessmentApi.listWrongQuestions).toHaveBeenLastCalledWith({
        status: 'MASTERED',
        chapterKey: undefined,
        page: 0,
        size: 20,
      })
      expect(wrapper.text()).toContain('更新后的已掌握错题')
    })

    it('propagates refresh failure, updates UI with error, and does not claim success', async () => {
      const wrapper = await mountView()
      await flushPromises()

      const axiosError = new AxiosError(
        'Request failed with status code 503',
        'ERR_BAD_RESPONSE',
        undefined,
        undefined,
        {
          status: 503,
          statusText: 'Service Unavailable',
          data: {},
          headers: {},
          config: {} as any,
        }
      )
      vi.mocked(assessmentApi.getWrongQuestionSummary).mockRejectedValue(axiosError)

      const adapterStore = useUiActionAdapterStore()
      await expect(
        adapterStore.getAdaptersForRouteKey('WRONG_QUESTIONS').resourceManager?.refresh('WRONG_QUESTIONS')
      ).rejects.toThrow(axiosError)

      await flushPromises()
      expect(wrapper.text()).toContain('服务暂不可用，请稍后重试')
    })

    it('isolates concurrent requests and does not allow older requests to overwrite newer state', async () => {
      const wrapper = await mountView()
      await flushPromises()

      let resolveFirst!: (value: any) => void
      const firstPromise = new Promise((resolve) => { resolveFirst = resolve })

      let resolveSecond!: (value: any) => void
      const secondPromise = new Promise((resolve) => { resolveSecond = resolve })

      // First call is slow
      vi.mocked(assessmentApi.getWrongQuestionSummary)
        .mockImplementationOnce(() => firstPromise as any)
        .mockImplementationOnce(() => secondPromise as any)

      const adapterStore = useUiActionAdapterStore()
      const p1 = adapterStore.getAdaptersForRouteKey('WRONG_QUESTIONS').resourceManager?.refresh('WRONG_QUESTIONS')
      const p2 = adapterStore.getAdaptersForRouteKey('WRONG_QUESTIONS').resourceManager?.refresh('WRONG_QUESTIONS')

      // Second completes first with new data
      resolveSecond({
        activeCount: 99,
        masteredCount: 99,
        chapters: [],
        currentReview: null,
      })
      const r2 = await p2
      expect(r2).toBe(true)
      await flushPromises()
      expect(wrapper.text()).toContain('99')

      // First completes later with stale data
      resolveFirst({
        activeCount: 1,
        masteredCount: 1,
        chapters: [],
        currentReview: null,
      })
      await expect(p1).rejects.toThrow()
      await flushPromises()
      // UI state should NOT have been overwritten with 1
      expect(wrapper.text()).toContain('99')
    })

    it('rejects and does not update state when unmounted during refresh', async () => {
      const wrapper = await mountView()
      await flushPromises()

      let resolveSlow!: (value: any) => void
      const slowPromise = new Promise((resolve) => { resolveSlow = resolve })
      vi.mocked(assessmentApi.getWrongQuestionSummary).mockImplementationOnce(() => slowPromise as any)

      const adapterStore = useUiActionAdapterStore()
      const resourceManager = adapterStore.getAdaptersForRouteKey('WRONG_QUESTIONS').resourceManager

      const refreshPromise = resourceManager?.refresh('WRONG_QUESTIONS')
      wrapper.unmount()

      resolveSlow(summary)
      await expect(refreshPromise).rejects.toThrow()
    })
  })
})
