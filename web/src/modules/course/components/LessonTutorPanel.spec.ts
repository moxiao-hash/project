import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'

import LessonTutorPanel from './LessonTutorPanel.vue'

const teachingApi = vi.hoisted(() => ({
  createConversation: vi.fn(),
  sendMessage: vi.fn(),
}))

vi.mock('@/services/course', () => ({ teachingApi }))

beforeEach(() => {
  vi.clearAllMocks()
  teachingApi.createConversation.mockResolvedValue({ conversationId: 'teaching-1' })
  teachingApi.sendMessage.mockResolvedValue({
    answer: '## DTO\n\n使用 **请求对象** 隔离输入。',
  })
})

it('把课内导师回答渲染为 Markdown', async () => {
  const wrapper = mount(LessonTutorPanel, {
    props: { lessonId: 'lesson-rest-controller' },
  })

  await wrapper.get('textarea').setValue('DTO 有什么作用？')
  await wrapper.get('form').trigger('submit.prevent')
  await flushPromises()

  expect(wrapper.get('.assistant h2').text()).toBe('DTO')
  expect(wrapper.get('.assistant strong').text()).toBe('请求对象')
  expect(wrapper.get('.student').text()).toBe('DTO 有什么作用？')
})
