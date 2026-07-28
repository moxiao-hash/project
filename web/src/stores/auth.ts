import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/services/current/auth'
import { TOKEN_EXPIRES_KEY, TOKEN_STORAGE_KEY } from '@/services/http'
import type { User } from '@/types/api'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(sessionStorage.getItem(TOKEN_STORAGE_KEY))
  const user = ref<User | null>(null)
  /** 应用启动时是否已用 /api/auth/me 验证过 token */
  const restored = ref(false)

  const isAuthenticated = computed(() => accessToken.value !== null)

  function applySession(token: string, expiresAt: string, u: User) {
    accessToken.value = token
    user.value = u
    sessionStorage.setItem(TOKEN_STORAGE_KEY, token)
    sessionStorage.setItem(TOKEN_EXPIRES_KEY, expiresAt)
  }

  async function login(email: string, password: string) {
    const res = await authApi.login({ email, password })
    applySession(res.accessToken, res.expiresAt, res.user)
  }

  async function register(email: string, password: string, displayName: string) {
    const res = await authApi.register({ email, password, displayName })
    applySession(res.accessToken, res.expiresAt, res.user)
  }

  /** 恢复会话：不能只信本地缓存，必须调用 /api/auth/me 验证。 */
  async function restore(): Promise<boolean> {
    if (!accessToken.value) {
      restored.value = true
      return false
    }
    try {
      user.value = await authApi.me()
      restored.value = true
      return true
    } catch {
      // 401 由拦截器统一清理；其它错误也视为未登录
      clearSession()
      restored.value = true
      return false
    }
  }

  function clearSession() {
    accessToken.value = null
    user.value = null
    sessionStorage.removeItem(TOKEN_STORAGE_KEY)
    sessionStorage.removeItem(TOKEN_EXPIRES_KEY)
  }

  async function logout() {
    try {
      await authApi.logout()
    } catch {
      // 退出请求失败也强制清理本地会话
    }
    clearSession()
  }

  return {
    accessToken,
    user,
    restored,
    isAuthenticated,
    login,
    register,
    restore,
    logout,
    clearSession,
  }
})
