import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import { useUiActionAdapterStore } from './uiActionAdapter'
import {
  getOwnerConversationStorageKey,
} from '@/modules/assistant/ownerUiActionLifecycle'
import { TOKEN_EXPIRES_KEY, TOKEN_STORAGE_KEY } from '@/services/http'
import { authApi } from '@/services/current/auth'
import type { User } from '@/types/api'

vi.mock('@/services/current/auth', () => ({
  authApi: {
    login: vi.fn(),
    register: vi.fn(),
    me: vi.fn(),
    logout: vi.fn(),
  },
}))

describe('useAuthStore owner isolation and cleanup', () => {
  const userA: User = {
    id: 'user-a',
    email: 'usera@example.com',
    displayName: 'User A',
    createdAt: '2026-01-01',
  }

  const userB: User = {
    id: 'user-b',
    email: 'userb@example.com',
    displayName: 'User B',
    createdAt: '2026-01-01',
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
    vi.clearAllMocks()
  })

  it('logout clears token/expiry, old owner lifecycle, conversation id, lastSeq, and live adapters while preserving unrelated storage and another owner lifecycle', async () => {
    const auth = useAuthStore()
    const adapterStore = useUiActionAdapterStore()

    // Setup active session for user A
    auth.applySession('token-a', '2026-09-17T00:00:00Z', userA)

    // Setup owner A & owner B lifecycles, cursors, unrelated storage, and live adapters
    const keyUserA = getOwnerConversationStorageKey('user-a', 'conv-1')
    const keyUserB = getOwnerConversationStorageKey('user-b', 'conv-2')
    sessionStorage.setItem(keyUserA, JSON.stringify({ ownerId: 'user-a', records: [] }))
    sessionStorage.setItem(keyUserB, JSON.stringify({ ownerId: 'user-b', records: [] }))
    sessionStorage.setItem('studypilot.assistantConversationId', 'conv-1')
    sessionStorage.setItem('studypilot.lastSeq.conv-1', '42')
    sessionStorage.setItem('studypilot.lastSeq.conv-other', '100')
    sessionStorage.setItem('unrelated_app_setting', 'preserve-me')

    adapterStore.register({
      routeKey: 'LEARNING_GOALS',
      modalManager: { open: vi.fn() },
    })
    expect(adapterStore.getAdaptersForRouteKey('LEARNING_GOALS').modalManager).toBeDefined()

    vi.mocked(authApi.logout).mockResolvedValueOnce(undefined as never)

    await auth.logout()

    // Token and user cleared
    expect(auth.accessToken).toBeNull()
    expect(auth.user).toBeNull()
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(sessionStorage.getItem(TOKEN_EXPIRES_KEY)).toBeNull()

    // Owner A lifecycle removed, owner B lifecycle preserved
    expect(sessionStorage.getItem(keyUserA)).toBeNull()
    expect(sessionStorage.getItem(keyUserB)).not.toBeNull()

    // Assistant conversation id and sequence cursors removed
    expect(sessionStorage.getItem('studypilot.assistantConversationId')).toBeNull()
    expect(sessionStorage.getItem('studypilot.lastSeq.conv-1')).toBeNull()
    expect(sessionStorage.getItem('studypilot.lastSeq.conv-other')).toBeNull()

    // Unrelated storage preserved
    expect(sessionStorage.getItem('unrelated_app_setting')).toBe('preserve-me')

    // Live adapter registrations cleared
    expect(adapterStore.getAdaptersForRouteKey('LEARNING_GOALS').modalManager).toBeUndefined()
  })

  it('switching owner A -> B clears A lifecycle/cursors/adapters before applying B, but preserves B preexisting lifecycle key', () => {
    const auth = useAuthStore()
    const adapterStore = useUiActionAdapterStore()

    auth.applySession('token-a', '2026-09-17T00:00:00Z', userA)

    const keyUserA = getOwnerConversationStorageKey('user-a', 'conv-1')
    const keyUserB = getOwnerConversationStorageKey('user-b', 'conv-2')
    sessionStorage.setItem(keyUserA, JSON.stringify({ ownerId: 'user-a', records: [] }))
    sessionStorage.setItem(keyUserB, JSON.stringify({ ownerId: 'user-b', records: [{ actionId: 'b-action' }] }))
    sessionStorage.setItem('studypilot.assistantConversationId', 'conv-1')
    sessionStorage.setItem('studypilot.lastSeq.conv-1', '5')
    sessionStorage.setItem('unrelated_theme', 'dark')

    adapterStore.register({
      routeKey: 'LEARNING_PLANS',
      formDraftStore: { setDraft: vi.fn() },
    })

    // Switch to user B
    auth.applySession('token-b', '2026-09-17T01:00:00Z', userB)

    // User A wiped, User B preserved
    expect(sessionStorage.getItem(keyUserA)).toBeNull()
    expect(sessionStorage.getItem(keyUserB)).toBe(JSON.stringify({ ownerId: 'user-b', records: [{ actionId: 'b-action' }] }))

    // Cursors wiped
    expect(sessionStorage.getItem('studypilot.assistantConversationId')).toBeNull()
    expect(sessionStorage.getItem('studypilot.lastSeq.conv-1')).toBeNull()

    // Unrelated storage kept
    expect(sessionStorage.getItem('unrelated_theme')).toBe('dark')

    // Adapter store reset
    expect(adapterStore.getAdaptersForRouteKey('LEARNING_PLANS').formDraftStore).toBeUndefined()

    // Auth reflects user B
    expect(auth.accessToken).toBe('token-b')
    expect(auth.user?.id).toBe('user-b')
  })

  it('restore failure / clearSession gets the same isolation cleanup', async () => {
    const auth = useAuthStore()
    const adapterStore = useUiActionAdapterStore()

    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'invalid-token')
    auth.applySession('invalid-token', '2026-09-17T00:00:00Z', userA)

    const keyUserA = getOwnerConversationStorageKey('user-a', 'conv-1')
    sessionStorage.setItem(keyUserA, JSON.stringify({ ownerId: 'user-a' }))
    sessionStorage.setItem('studypilot.assistantConversationId', 'conv-1')
    sessionStorage.setItem('studypilot.lastSeq.conv-1', '10')

    adapterStore.register({
      routeKey: 'MATERIALS',
      resourceManager: { refresh: vi.fn() },
    })

    vi.mocked(authApi.me).mockRejectedValueOnce(new Error('Unauthorized'))

    const restored = await auth.restore()
    expect(restored).toBe(false)
    expect(auth.accessToken).toBeNull()
    expect(auth.user).toBeNull()

    expect(sessionStorage.getItem(keyUserA)).toBeNull()
    expect(sessionStorage.getItem('studypilot.assistantConversationId')).toBeNull()
    expect(sessionStorage.getItem('studypilot.lastSeq.conv-1')).toBeNull()
    expect(adapterStore.getAdaptersForRouteKey('MATERIALS').resourceManager).toBeUndefined()
  })

  it('storage exceptions still result in user/token cleared', () => {
    const auth = useAuthStore()

    auth.applySession('token-a', '2026-09-17T00:00:00Z', userA)

    // Simulate sessionStorage failure on inspection/removal
    const originalRemoveItem = sessionStorage.removeItem.bind(sessionStorage)
    const spy = vi.spyOn(sessionStorage, 'removeItem').mockImplementation((key: string) => {
      if (key.includes('studypilot.')) {
        throw new Error('QuotaExceeded or SecurityError')
      }
      return originalRemoveItem(key)
    })

    expect(() => auth.clearSession()).not.toThrow()

    expect(auth.accessToken).toBeNull()
    expect(auth.user).toBeNull()

    spy.mockRestore()
  })

  it('normal same-owner session refresh does not clear that owner lifecycle', () => {
    const auth = useAuthStore()
    const adapterStore = useUiActionAdapterStore()

    auth.applySession('token-a1', '2026-09-17T00:00:00Z', userA)

    const keyUserA = getOwnerConversationStorageKey('user-a', 'conv-1')
    sessionStorage.setItem(keyUserA, JSON.stringify({ ownerId: 'user-a', records: [{ actionId: 'act-1' }] }))
    sessionStorage.setItem('studypilot.assistantConversationId', 'conv-1')
    sessionStorage.setItem('studypilot.lastSeq.conv-1', '12')

    adapterStore.register({
      routeKey: 'LEARNING_GOALS',
      focusManager: { focus: vi.fn() },
    })

    // Refresh token / session for same owner
    const updatedUserA: User = { ...userA, displayName: 'User A Updated' }
    auth.applySession('token-a2', '2026-09-17T02:00:00Z', updatedUserA)

    // Lifecycle, cursors, and adapters must NOT be cleared on same-owner refresh
    expect(sessionStorage.getItem(keyUserA)).toBe(
      JSON.stringify({ ownerId: 'user-a', records: [{ actionId: 'act-1' }] }),
    )
    expect(sessionStorage.getItem('studypilot.assistantConversationId')).toBe('conv-1')
    expect(sessionStorage.getItem('studypilot.lastSeq.conv-1')).toBe('12')
    expect(adapterStore.getAdaptersForRouteKey('LEARNING_GOALS').focusManager).toBeDefined()
    expect(auth.user?.displayName).toBe('User A Updated')
  })

  it('clearSession with no current user clears all owner lifecycles, conversation, lastSeq, and tokens while preserving unrelated storage', () => {
    const auth = useAuthStore()

    expect(auth.user).toBeNull()

    const keyUserA = getOwnerConversationStorageKey('user-a', 'conv-1')
    const keyUserB = getOwnerConversationStorageKey('user-b', 'conv-2')
    sessionStorage.setItem(keyUserA, JSON.stringify({ ownerId: 'user-a', records: [{ actionId: 'act-a' }] }))
    sessionStorage.setItem(keyUserB, JSON.stringify({ ownerId: 'user-b', records: [{ actionId: 'act-b' }] }))
    sessionStorage.setItem('studypilot.assistantConversationId', 'conv-orphan')
    sessionStorage.setItem('studypilot.lastSeq.conv-orphan', '99')
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'stale-token')
    sessionStorage.setItem(TOKEN_EXPIRES_KEY, '2026-09-17T00:00:00Z')
    sessionStorage.setItem('unrelated_app_setting', 'keep-this-value')

    auth.clearSession()

    // All lifecycle keys for both owners must be removed
    expect(sessionStorage.getItem(keyUserA)).toBeNull()
    expect(sessionStorage.getItem(keyUserB)).toBeNull()

    // Conversation key, sequence cursor, and auth tokens removed
    expect(sessionStorage.getItem('studypilot.assistantConversationId')).toBeNull()
    expect(sessionStorage.getItem('studypilot.lastSeq.conv-orphan')).toBeNull()
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(sessionStorage.getItem(TOKEN_EXPIRES_KEY)).toBeNull()

    // Unrelated sessionStorage preserved
    expect(sessionStorage.getItem('unrelated_app_setting')).toBe('keep-this-value')
  })
})
