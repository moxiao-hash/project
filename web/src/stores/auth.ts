import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/services/current/auth'
import { TOKEN_EXPIRES_KEY, TOKEN_STORAGE_KEY } from '@/services/http'
import type { User } from '@/types/api'
import { clearAllOwnerUiActionLifecycles, clearOwnerUiActionLifecycle } from '@/modules/assistant/ownerUiActionLifecycle'
import { useUiActionAdapterStore } from './uiActionAdapter'

const ASSISTANT_CONVERSATION_KEY = 'studypilot.assistantConversationId'
const LAST_SEQ_PREFIX = 'studypilot.lastSeq.'

function clearOwnerIsolationState(previousOwnerId?: string | null): void {
  // 1. Reset live adapter store registrations so no page executor/draft adapter survives
  try {
    const adapterStore = useUiActionAdapterStore()
    adapterStore.clearAll()
  } catch {
    // Suppress store resolution or reset errors
  }

  if (typeof window === 'undefined' || !window.sessionStorage) {
    return
  }

  const storage = window.sessionStorage

  // 2. Clear exact previous owner's lifecycle records if known, otherwise clear all owner lifecycles
  if (previousOwnerId) {
    try {
      clearOwnerUiActionLifecycle(storage, previousOwnerId)
    } catch {
      // Storage enumeration/removal exceptions must not prevent auth cleanup
    }
  } else {
    try {
      clearAllOwnerUiActionLifecycles(storage)
    } catch {
      // Storage enumeration/removal exceptions must not prevent auth cleanup
    }
  }

  // 3. Remove unscoped conversation ID and all sequence cursors (studypilot.lastSeq.*)
  try {
    storage.removeItem(ASSISTANT_CONVERSATION_KEY)
  } catch {
    // Ignore storage errors
  }

  try {
    if (typeof storage.length === 'number' && typeof storage.key === 'function') {
      const keysToRemove: string[] = []
      for (let i = 0; i < storage.length; i++) {
        const key = storage.key(i)
        if (key && key.startsWith(LAST_SEQ_PREFIX)) {
          keysToRemove.push(key)
        }
      }
      for (const key of keysToRemove) {
        storage.removeItem(key)
      }
    }
  } catch {
    // Ignore storage enumeration/removal errors
  }
}

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(sessionStorage.getItem(TOKEN_STORAGE_KEY))
  const user = ref<User | null>(null)
  /** 应用启动时是否已用 /api/auth/me 验证过 token */
  const restored = ref(false)

  const isAuthenticated = computed(() => accessToken.value !== null)

  function applySession(token: string, expiresAt: string, u: User) {
    const previousOwnerId = user.value?.id
    const isDifferentOwner = previousOwnerId && previousOwnerId !== u.id

    if (isDifferentOwner) {
      clearOwnerIsolationState(previousOwnerId)
    }

    accessToken.value = token
    user.value = u
    try {
      sessionStorage.setItem(TOKEN_STORAGE_KEY, token)
      sessionStorage.setItem(TOKEN_EXPIRES_KEY, expiresAt)
    } catch {
      // Storage failure must not prevent in-memory auth state update
    }
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
    const previousOwnerId = user.value?.id
    clearOwnerIsolationState(previousOwnerId)

    accessToken.value = null
    user.value = null
    try {
      sessionStorage.removeItem(TOKEN_STORAGE_KEY)
      sessionStorage.removeItem(TOKEN_EXPIRES_KEY)
    } catch {
      // Storage removal failure must not prevent state nulling
    }
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
    applySession,
    login,
    register,
    restore,
    logout,
    clearSession,
  }
})
