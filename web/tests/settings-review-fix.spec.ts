import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from 'axios'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'

const settingsApiMock = vi.hoisted(() => ({
  get: vi.fn(),
  update: vi.fn(),
}))

vi.mock('@/services/current/dashboard', () => ({
  settingsApi: settingsApiMock,
}))

import SettingsView from '@/modules/settings/SettingsView.vue'

function notFoundError() {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig
  return new AxiosError('not found', '404', config, {}, {
    status: 404,
    statusText: 'Not Found',
    headers: {},
    config,
    data: {
      timestamp: '',
      status: 404,
      error: 'Not Found',
      message: '尚未配置个人学习设置',
      path: '/api/user-settings',
    },
  })
}

describe('SettingsView 新用户初始化', () => {
  it('GET 设置返回 404 时展示可保存的默认表单', async () => {
    settingsApiMock.get.mockRejectedValueOnce(notFoundError())

    const wrapper = mount(SettingsView, {
      global: {
        plugins: [createPinia()],
        stubs: { RouterLink: true },
      },
    })
    await flushPromises()

    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.find<HTMLInputElement>('input[placeholder="Asia/Shanghai"]').element.value)
      .toBe('Asia/Shanghai')
    expect(wrapper.text()).not.toContain('尚未配置个人学习设置')
  })
})
