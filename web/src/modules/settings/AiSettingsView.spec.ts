import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AiSettingsView from './AiSettingsView.vue'

const mocks = vi.hoisted(() => ({
  getAiSettings: vi.fn(),
  updateDeepseekKey: vi.fn(),
  deleteDeepseekKey: vi.fn(),
  updateTavilyKey: vi.fn(),
  deleteTavilyKey: vi.fn(),
}))

vi.mock('@/services/planned', () => ({
  gatewayMode: 'http',
  agentGateway: mocks,
}))

function settings(configured: boolean, suffix: string | null = null) {
  return {
    modelProvider: 'deepseek' as const,
    modelName: 'deepseek-v4-pro',
    deepseekConfigured: configured,
    deepseekMaskedSuffix: suffix,
    tavilyConfigured: false,
    tavilyMaskedSuffix: null,
  }
}

describe('AiSettingsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: { setItem: vi.fn(), getItem: vi.fn(), removeItem: vi.fn(), clear: vi.fn() },
    })
    Object.defineProperty(window, 'sessionStorage', {
      configurable: true,
      value: { setItem: vi.fn(), getItem: vi.fn(), removeItem: vi.fn(), clear: vi.fn() },
    })
  })

  it('clears the key and reloads safe server status without browser persistence', async () => {
    mocks.getAiSettings
      .mockResolvedValueOnce(settings(false))
      .mockResolvedValueOnce(settings(true, 'ated'))
    mocks.updateDeepseekKey.mockResolvedValue(settings(false))
    const wrapper = mount(AiSettingsView, {
      global: {
        plugins: [createPinia()],
        stubs: { RouterLink: true },
      },
    })
    await flushPromises()

    const keyInput = wrapper.findAll('input[type="password"]')[0]
    await keyInput.setValue('sk-user-key-rotated')
    await wrapper.findAll('form')[0].trigger('submit')
    await flushPromises()

    expect(mocks.updateDeepseekKey).toHaveBeenCalledWith('sk-user-key-rotated')
    expect(mocks.getAiSettings).toHaveBeenCalledTimes(2)
    expect(
      (wrapper.findAll('input[type="password"]')[0].element as HTMLInputElement).value,
    ).toBe('')
    expect(wrapper.text()).toContain('••••ated')
    expect(window.localStorage.setItem).not.toHaveBeenCalled()
    expect(window.sessionStorage.setItem).not.toHaveBeenCalled()
  })

  it('does not offer deleting a server default key', async () => {
    mocks.getAiSettings.mockResolvedValue({
      ...settings(true, '-key'),
      deepseek: {
        configured: true,
        source: 'SERVER_DEFAULT',
        maskedSuffix: '-key',
        available: true,
      },
    })
    const wrapper = mount(AiSettingsView, {
      global: {
        plugins: [createPinia()],
        stubs: { RouterLink: true },
      },
    })
    await flushPromises()

    expect(wrapper.findAll('.btn-danger')).toHaveLength(0)
    expect(wrapper.text()).toContain('开发环境默认')
  })
})
