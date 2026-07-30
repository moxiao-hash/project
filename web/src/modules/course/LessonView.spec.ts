import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import LessonView from './LessonView.vue'

const { getLesson, updateProgress } = vi.hoisted(() => ({
  getLesson: vi.fn(),
  updateProgress: vi.fn(),
}))

vi.mock('@/services/course', () => ({
  courseApi: {
    getLesson,
    updateProgress,
  },
  teachingApi: {
    createConversation: vi.fn(),
    sendMessage: vi.fn(),
  },
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { lessonId: 'lesson-rest-controller' } }),
  RouterLink: { template: '<a><slot /></a>' },
}))

vi.mock('@/stores/toast', () => ({
  useToastStore: () => ({
    success: vi.fn(),
    error: vi.fn(),
  }),
}))

const lesson = {
  id: 'lesson-rest-controller',
  moduleId: 'module-spring-rest',
  slug: 'controller-rest-api-validation',
  order: 1,
  title: 'Controller、REST API 与参数校验',
  summary: '从 StudyPilot 注册接口理解 Controller、DTO 和参数校验。',
  estimatedMinutes: 90,
  published: true,
  content: {
    blocks: [
      {
        key: 'request-flow',
        type: 'EXPLANATION',
        title: '一次请求如何流动',
        markdown: '浏览器请求先进入 **Controller**。',
      },
    ],
  },
  sources: [
    {
      type: 'VIDEO',
      title: '黑马程序员：注册接口',
      url: 'https://www.bilibili.com/video/BV14z4y1N7pg?p=15',
      locator: '实战篇-03',
      bvid: 'BV14z4y1N7pg',
      videoPage: 15,
    },
  ],
  progress: {
    status: 'NOT_STARTED',
    videoCompleted: false,
    readingCompleted: false,
    practiceCompleted: false,
    lastSectionKey: null,
  },
}

describe('LessonView', () => {
  beforeEach(() => {
    getLesson.mockReset()
    updateProgress.mockReset()
    getLesson.mockResolvedValue(structuredClone(lesson))
    updateProgress.mockImplementation(async (_id, body) => ({
      ...structuredClone(lesson),
      progress: {
        ...lesson.progress,
        ...body,
        status: 'IN_PROGRESS',
      },
    }))
  })

  it('shows the real video, sanitized lesson content and incomplete practice state', async () => {
    const wrapper = mount(LessonView, {
      global: {
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
          LessonTutorPanel: true,
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('iframe').exists()).toBe(true)
    expect(wrapper.html()).toContain('<strong>Controller</strong>')
    expect(wrapper.text()).toContain('练习尚未完成')

    await wrapper.get('[data-test="video-complete"]').trigger('click')
    await flushPromises()

    expect(updateProgress).toHaveBeenCalledWith('lesson-rest-controller', {
      videoCompleted: true,
      readingCompleted: false,
      lastSectionKey: 'video',
    })
    expect(wrapper.text()).toContain('还需完成讲义和课时练习')
  })
})
