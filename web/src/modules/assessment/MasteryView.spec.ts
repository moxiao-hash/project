import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'

import MasteryView from './MasteryView.vue'
import { assessmentApi } from '@/services/current/assessment'
import type { Mastery } from '@/types/api'
import { useUiActionAdapterStore } from '@/stores/uiActionAdapter'

vi.mock('@/services/current/assessment', () => ({
  assessmentApi: {
    listMastery: vi.fn(),
  },
}))

const mockMasteryData: Mastery[] = [
  {
    knowledgePoint: 'Java Collections',
    score: 85,
    quizScore: 90,
    taskScore: 80,
    selfAssessmentScore: 85,
    evidenceCount: 10,
    attemptCount: 12,
    updatedAt: '2026-09-01T10:00:00Z',
  },
  {
    knowledgePoint: 'JVM Memory Model',
    score: 65,
    quizScore: 60,
    taskScore: 70,
    selfAssessmentScore: null,
    evidenceCount: 5,
    attemptCount: 8,
    updatedAt: '2026-09-02T12:00:00Z',
  },
]

async function mountView() {
  return mount(MasteryView)
}

describe('MasteryView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
    vi.mocked(assessmentApi.listMastery).mockResolvedValue(mockMasteryData)
  })

  it('renders mastery cards on initial load', async () => {
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('掌握度')
    expect(wrapper.text()).toContain('Java Collections')
    expect(wrapper.text()).toContain('JVM Memory Model')
    expect(wrapper.text()).toContain('85')
    expect(wrapper.text()).toContain('65')
  })

  it('renders empty state when mastery list is empty', async () => {
    vi.mocked(assessmentApi.listMastery).mockResolvedValue([])
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('暂无掌握度数据')
  })

  describe('UI action adapter (REFRESH_RESOURCE MASTERY)', () => {
    it('registers adapter on mount and unregisters on unmount', async () => {
      const adapterStore = useUiActionAdapterStore()
      expect(adapterStore.getAdaptersForRouteKey('MASTERY').resourceManager).toBeUndefined()

      const wrapper = await mountView()
      await flushPromises()

      const adapters = adapterStore.getAdaptersForRouteKey('MASTERY')
      expect(adapters.resourceManager).toBeDefined()

      wrapper.unmount()
      expect(adapterStore.getAdaptersForRouteKey('MASTERY').resourceManager).toBeUndefined()
    })

    it('rejects any resource key other than MASTERY', async () => {
      await mountView()
      await flushPromises()
      const adapterStore = useUiActionAdapterStore()
      const resourceManager = adapterStore.getAdaptersForRouteKey('MASTERY').resourceManager

      await expect(resourceManager?.refresh('ROADMAP')).rejects.toThrow('不支持刷新的资源: ROADMAP')
      await expect(resourceManager?.refresh('WRONG_QUESTIONS')).rejects.toThrow('不支持刷新的资源: WRONG_QUESTIONS')
      await expect(resourceManager?.refresh('UNKNOWN')).rejects.toThrow('不支持刷新的资源: UNKNOWN')
    })

    it('successfully refreshes, awaits real API, and updates rendered mastery cards', async () => {
      const wrapper = await mountView()
      await flushPromises()

      expect(wrapper.text()).toContain('Java Collections')
      expect(wrapper.text()).not.toContain('Spring Boot Starters')

      const updatedMasteryData: Mastery[] = [
        {
          knowledgePoint: 'Spring Boot Starters',
          score: 95,
          quizScore: 95,
          taskScore: 95,
          selfAssessmentScore: 95,
          evidenceCount: 15,
          attemptCount: 15,
          updatedAt: '2026-09-16T08:00:00Z',
        },
      ]
      vi.mocked(assessmentApi.listMastery).mockResolvedValue(updatedMasteryData)

      const adapterStore = useUiActionAdapterStore()
      const result = await adapterStore.getAdaptersForRouteKey('MASTERY').resourceManager?.refresh('MASTERY')

      expect(result).toBe(true)
      await flushPromises()

      expect(assessmentApi.listMastery).toHaveBeenCalledTimes(2)
      expect(wrapper.text()).toContain('Spring Boot Starters')
      expect(wrapper.text()).toContain('95')
      expect(wrapper.text()).not.toContain('Java Collections')
    })

    it('propagates failure to caller, displays ErrorState, and does not report success', async () => {
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
      vi.mocked(assessmentApi.listMastery).mockRejectedValue(axiosError)

      const adapterStore = useUiActionAdapterStore()
      await expect(
        adapterStore.getAdaptersForRouteKey('MASTERY').resourceManager?.refresh('MASTERY')
      ).rejects.toThrow(axiosError)

      await flushPromises()
      expect(wrapper.text()).toContain('服务暂不可用，请稍后重试')
    })

    it('isolates concurrent requests and does not allow older requests to overwrite newer state', async () => {
      const wrapper = await mountView()
      await flushPromises()

      let resolveFirst!: (value: Mastery[]) => void
      const firstPromise = new Promise<Mastery[]>((resolve) => {
        resolveFirst = resolve
      })

      let resolveSecond!: (value: Mastery[]) => void
      const secondPromise = new Promise<Mastery[]>((resolve) => {
        resolveSecond = resolve
      })

      vi.mocked(assessmentApi.listMastery)
        .mockImplementationOnce(() => firstPromise)
        .mockImplementationOnce(() => secondPromise)

      const adapterStore = useUiActionAdapterStore()
      const p1 = adapterStore.getAdaptersForRouteKey('MASTERY').resourceManager?.refresh('MASTERY')
      const p2 = adapterStore.getAdaptersForRouteKey('MASTERY').resourceManager?.refresh('MASTERY')

      // Second completes first with newer data
      resolveSecond([
        {
          knowledgePoint: 'Concurrency in Java',
          score: 99,
          quizScore: 99,
          taskScore: 99,
          selfAssessmentScore: 99,
          evidenceCount: 20,
          attemptCount: 20,
          updatedAt: '2026-09-16T12:00:00Z',
        },
      ])
      const r2 = await p2
      expect(r2).toBe(true)
      await flushPromises()
      expect(wrapper.text()).toContain('Concurrency in Java')
      expect(wrapper.text()).toContain('99')

      // First completes later with stale data
      resolveFirst([
        {
          knowledgePoint: 'Stale Topic',
          score: 10,
          quizScore: 10,
          taskScore: 10,
          selfAssessmentScore: 10,
          evidenceCount: 1,
          attemptCount: 2,
          updatedAt: '2026-09-01T00:00:00Z',
        },
      ])
      await expect(p1).rejects.toThrow()
      await flushPromises()

      // Rendered state must not be overwritten by stale response
      expect(wrapper.text()).toContain('Concurrency in Java')
      expect(wrapper.text()).not.toContain('Stale Topic')
    })

    it('rejects and does not update state when unmounted during in-flight refresh', async () => {
      const wrapper = await mountView()
      await flushPromises()

      let resolveSlow!: (value: Mastery[]) => void
      const slowPromise = new Promise<Mastery[]>((resolve) => {
        resolveSlow = resolve
      })
      vi.mocked(assessmentApi.listMastery).mockImplementationOnce(() => slowPromise)

      const adapterStore = useUiActionAdapterStore()
      const resourceManager = adapterStore.getAdaptersForRouteKey('MASTERY').resourceManager

      const refreshPromise = resourceManager?.refresh('MASTERY')
      wrapper.unmount()

      resolveSlow([
        {
          knowledgePoint: 'Unmounted Topic',
          score: 80,
          quizScore: 80,
          taskScore: 80,
          selfAssessmentScore: 80,
          evidenceCount: 2,
          attemptCount: 2,
          updatedAt: '2026-09-16T12:00:00Z',
        },
      ])
      await expect(refreshPromise).rejects.toThrow()
    })
  })
})
