import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { TOKEN_STORAGE_KEY } from '@/services/http'
import { authApi } from '@/services/current/auth'
import type { AuthResponse, User } from '@/types/api'

vi.mock('@/services/current/auth', () => ({
  authApi: {
    login: vi.fn(),
    register: vi.fn(),
    me: vi.fn(),
    logout: vi.fn(),
  },
}))

const mockUser: User = {
  id: 'u1',
  email: 'learner@example.com',
  displayName: '学习者',
  createdAt: null,
}

const mockAuthResponse: AuthResponse = {
  accessToken: 'token-abc',
  tokenType: 'Bearer',
  expiresAt: '2026-07-29T00:00:00Z',
  user: mockUser,
}

describe('auth store', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('登录成功后把 token 存入 sessionStorage 并更新用户', async () => {
    vi.mocked(authApi.login).mockResolvedValue(mockAuthResponse)
    const auth = useAuthStore()

    await auth.login('learner@example.com', 'StudyPilot123!')

    expect(auth.isAuthenticated).toBe(true)
    expect(auth.user?.email).toBe('learner@example.com')
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBe('token-abc')
  })

  it('restore 通过 /api/auth/me 验证 token，不信任本地缓存', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-abc')
    vi.mocked(authApi.me).mockResolvedValue(mockUser)
    const auth = useAuthStore()

    const ok = await auth.restore()

    expect(ok).toBe(true)
    expect(authApi.me).toHaveBeenCalledOnce()
    expect(auth.user?.id).toBe('u1')
  })

  it('restore 失败（如 401）时清理会话', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'expired-token')
    vi.mocked(authApi.me).mockRejectedValue(new Error('401'))
    const auth = useAuthStore()

    const ok = await auth.restore()

    expect(ok).toBe(false)
    expect(auth.isAuthenticated).toBe(false)
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
  })

  it('退出时即使接口失败也清理本地会话', async () => {
    vi.mocked(authApi.login).mockResolvedValue(mockAuthResponse)
    vi.mocked(authApi.logout).mockRejectedValue(new Error('network'))
    const auth = useAuthStore()
    await auth.login('learner@example.com', 'StudyPilot123!')

    await auth.logout()

    expect(auth.isAuthenticated).toBe(false)
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
  })
})
