import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import WrongQuestionsView from './WrongQuestionsView.vue'
import { assessmentApi } from '@/services/current/assessment'
import type { WrongQuestion } from '@/types/api'

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
  return mount(WrongQuestionsView, { global: { plugins: [createPinia(), router] } })
}

describe('wrong question book', () => {
  beforeEach(() => {
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
})
