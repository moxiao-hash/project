import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'

const settingsApiMock = vi.hoisted(() => ({
  get: vi.fn().mockResolvedValue({
    timeZone: 'Asia/Shanghai', dailyStudyLimitMinutes: 60,
    weekendPreference: 'NORMAL', defaultPrivacyLevel: 'NORMAL', weeklyAvailability: [],
  }),
  update: vi.fn(),
}))
const assistantApiMock = vi.hoisted(() => ({
  listAutomationRules: vi.fn().mockResolvedValue([{
    id: 'rule-1', type: 'AUTHORIZED_PLAN_ADJUSTMENT', status: 'ACTIVE',
    timezone: 'Asia/Shanghai', localTime: '00:15:00', riskLevel: 'LOW',
    requiredScope: 'SMALL_PLAN_ADJUSTMENT',
  }]),
  getAutomationSettings: vi.fn().mockResolvedValue({ paused: false, updatedAt: '' }),
  updateAutomationSettings: vi.fn().mockResolvedValue({ paused: true, updatedAt: '' }),
}))

vi.mock('@/services/current/dashboard', () => ({ settingsApi: settingsApiMock }))
vi.mock('@/services/current/assistant', () => ({ assistantApi: assistantApiMock }))

import SettingsView from '@/modules/settings/SettingsView.vue'

describe('主动 Agent 设置', () => {
  it('展示规则并允许用户一键暂停全部主动自动化', async () => {
    const wrapper = mount(SettingsView, {
      global: { plugins: [createPinia()], stubs: { RouterLink: true } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('主动 Agent')
    expect(wrapper.text()).toContain('夜间计划整理')
    await wrapper.get('[data-testid="automation-master-toggle"]').trigger('click')
    await flushPromises()

    expect(assistantApiMock.updateAutomationSettings).toHaveBeenCalledWith({ paused: true })
    expect(wrapper.text()).toContain('全部主动自动化已暂停')
  })
})
