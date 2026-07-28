import { http } from '../http'
import type { Dashboard, UserSettings } from '@/types/api'

export const settingsApi = {
  get() {
    return http.get<UserSettings>('/api/user-settings').then((r) => r.data)
  },
  update(body: UserSettings) {
    return http.put<UserSettings>('/api/user-settings', body).then((r) => r.data)
  },
}

export const dashboardApi = {
  get() {
    return http.get<Dashboard>('/api/dashboard').then((r) => r.data)
  },
}
