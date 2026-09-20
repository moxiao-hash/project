import { describe, it, expect, beforeEach } from 'vitest'
import {
  OwnerUiActionLifecycle,
  clearOwnerUiActionLifecycle,
  clearAllOwnerUiActionLifecycles,
  clearInMemoryRuntimeClaimsForTest,
  getOwnerConversationStorageKey,
  type StorageLike,
} from './ownerUiActionLifecycle'

class MemoryStorage implements StorageLike {
  private store = new Map<string, string>()

  getItem(key: string): string | null {
    return this.store.get(key) ?? null
  }

  setItem(key: string, value: string): void {
    this.store.set(key, value)
  }

  removeItem(key: string): void {
    this.store.delete(key)
  }

  key(index: number): string | null {
    const keys = Array.from(this.store.keys())
    return keys[index] ?? null
  }

  get length(): number {
    return this.store.size
  }

  clear(): void {
    this.store.clear()
  }
}

describe('OwnerUiActionLifecycle', () => {
  let fakeStorage: MemoryStorage

  beforeEach(() => {
    fakeStorage = new MemoryStorage()
    clearInMemoryRuntimeClaimsForTest()
  })

  it('allows first claim only; subsequent claims in same instance are denied', () => {
    const lifecycle = new OwnerUiActionLifecycle({
      ownerId: 'user_1',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })

    const first = lifecycle.claim('action_100')
    expect(first.granted).toBe(true)
    expect(first.lifecycleState).toBe('EXECUTING')

    const second = lifecycle.claim('action_100')
    expect(second.granted).toBe(false)
    expect(second.lifecycleState).toBe('EXECUTING')
  })

  it('concurrent duplicate calls in the same runtime share/suppress execution', () => {
    const lifecycle = new OwnerUiActionLifecycle({
      ownerId: 'user_1',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })

    const claim1 = lifecycle.claim('action_concurrent')
    expect(claim1.granted).toBe(true)

    // Simulate concurrent call before complete
    const claim2 = lifecycle.claim('action_concurrent')
    expect(claim2.granted).toBe(false)
    expect(claim2.actionId).toBe('action_concurrent')
  })

  it('persists terminal receipt before publication and sanitizes errors/routes without leaking tokens', () => {
    const lifecycle = new OwnerUiActionLifecycle({
      ownerId: 'user_1',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })

    lifecycle.claim('action_sec')
    const receipt = lifecycle.complete('action_sec', {
      status: 'FAILED',
      currentRoute: 'goals',
      error: 'Request failed with Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9 and token=secret12345',
    })

    expect(receipt.status).toBe('FAILED')
    expect(receipt.currentRoute).toBe('goals')
    expect(receipt.error).toContain('Bearer [REDACTED]')
    expect(receipt.error).toContain('token=[REDACTED]')
    expect(receipt.error).not.toContain('secret12345')

    const storedKey = getOwnerConversationStorageKey('user_1', 'conv_1')
    const rawData = fakeStorage.getItem(storedKey)
    expect(rawData).not.toBeNull()
    expect(rawData).not.toContain('secret12345')
    expect(rawData).toContain('[REDACTED]')
  })

  it('reload returns existing terminal receipt and never grants execute again', () => {
    const lifecycle1 = new OwnerUiActionLifecycle({
      ownerId: 'user_1',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })

    lifecycle1.claim('action_terminal')
    lifecycle1.complete('action_terminal', {
      status: 'SUCCEEDED',
      currentRoute: 'roadmap',
    })

    // Simulate full page reload with new instance
    clearInMemoryRuntimeClaimsForTest()
    const lifecycle2 = new OwnerUiActionLifecycle({
      ownerId: 'user_1',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })

    const claimOnReload = lifecycle2.claim('action_terminal')
    expect(claimOnReload.granted).toBe(false)
    expect(claimOnReload.existingReceipt).toBeDefined()
    expect(claimOnReload.existingReceipt?.status).toBe('SUCCEEDED')
    expect(claimOnReload.existingReceipt?.currentRoute).toBe('roadmap')
  })

  it('pending receipts survive a reload and can be enumerated for publication retry without re-execution', () => {
    const lifecycle1 = new OwnerUiActionLifecycle({
      ownerId: 'user_1',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })

    lifecycle1.claim('action_pending')
    lifecycle1.complete('action_pending', {
      status: 'SUCCEEDED',
      currentRoute: 'today',
    })

    clearInMemoryRuntimeClaimsForTest()
    const lifecycle2 = new OwnerUiActionLifecycle({
      ownerId: 'user_1',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })

    const pending = lifecycle2.getPendingReceipts()
    expect(pending).toHaveLength(1)
    expect(pending[0].receipt.actionId).toBe('action_pending')
    expect(pending[0].receipt.status).toBe('SUCCEEDED')
    expect(pending[0].reportAttempts).toBe(0)
    expect(pending[0].manualFallbackRequired).toBe(false)

    // Ensure action cannot be re-executed
    const claimTry = lifecycle2.claim('action_pending')
    expect(claimTry.granted).toBe(false)
  })

  it('recovers storage pre-terminal EXECUTING claim on reload as terminal FAILED receipt without re-execution', () => {
    const lifecycle1 = new OwnerUiActionLifecycle({
      ownerId: 'user_1',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })

    // Claimed but app crashes or reloads before complete()
    lifecycle1.claim('action_crashed')

    clearInMemoryRuntimeClaimsForTest()
    const lifecycle2 = new OwnerUiActionLifecycle({
      ownerId: 'user_1',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })

    const record = lifecycle2.get('action_crashed')
    expect(record).toBeDefined()
    expect(record?.lifecycleState).toBe('TERMINAL')
    expect(record?.receipt?.status).toBe('FAILED')
    expect(record?.receipt?.error).toContain('Action interrupted by page reload or crash')

    // Must never re-execute
    const claimAgain = lifecycle2.claim('action_crashed')
    expect(claimAgain.granted).toBe(false)

    // Enumerated in pending publication retry
    const pending = lifecycle2.getPendingReceipts()
    expect(pending.some((p) => p.receipt.actionId === 'action_crashed')).toBe(true)
  })

  it('enforces bounded publication attempts, excludes exhausted records from retry, and exposes manualFallbackRequired', () => {
    const lifecycle = new OwnerUiActionLifecycle({
      ownerId: 'user_1',
      conversationId: 'conv_1',
      storage: fakeStorage,
      maxReportAttempts: 3,
    })

    lifecycle.claim('action_fail_pub')
    lifecycle.complete('action_fail_pub', {
      status: 'SUCCEEDED',
      currentRoute: 'goals',
    })

    expect(lifecycle.getPendingReceipts()).toHaveLength(1)

    // Attempt 1 fails
    lifecycle.markReportFailure('action_fail_pub', '503 Service Unavailable')
    expect(lifecycle.getPendingReceipts()).toHaveLength(1)
    expect(lifecycle.getPendingReceipts()[0].reportAttempts).toBe(1)

    // Attempt 2 fails
    lifecycle.markReportFailure('action_fail_pub', '503 Service Unavailable')
    expect(lifecycle.getPendingReceipts()[0].reportAttempts).toBe(2)

    // Attempt 3 fails -> exhausted
    lifecycle.markReportFailure('action_fail_pub', '504 Gateway Timeout')
    expect(lifecycle.getPendingReceipts()).toHaveLength(0)

    const exhausted = lifecycle.getExhaustedReceipts()
    expect(exhausted).toHaveLength(1)
    expect(exhausted[0].receipt.actionId).toBe('action_fail_pub')
    expect(exhausted[0].manualFallbackRequired).toBe(true)
    expect(exhausted[0].reportAttempts).toBe(3)
  })

  it('successful publication marks reported without deleting deduplication record', () => {
    const lifecycle = new OwnerUiActionLifecycle({
      ownerId: 'user_1',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })

    lifecycle.claim('action_success')
    lifecycle.complete('action_success', {
      status: 'SUCCEEDED',
      currentRoute: 'plans',
    })

    expect(lifecycle.getPendingReceipts()).toHaveLength(1)
    lifecycle.markReported('action_success')

    expect(lifecycle.getPendingReceipts()).toHaveLength(0)

    // Deduplication remains intact
    const record = lifecycle.get('action_success')
    expect(record).toBeDefined()
    expect(record?.reported).toBe(true)
    expect(record?.receipt?.status).toBe('SUCCEEDED')

    const reClaim = lifecycle.claim('action_success')
    expect(reClaim.granted).toBe(false)
  })

  it('enforces strict owner and conversation isolation', () => {
    const user1Conv1 = new OwnerUiActionLifecycle({
      ownerId: 'user_alpha',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })

    const user1Conv2 = new OwnerUiActionLifecycle({
      ownerId: 'user_alpha',
      conversationId: 'conv_2',
      storage: fakeStorage,
    })

    const user2Conv1 = new OwnerUiActionLifecycle({
      ownerId: 'user_beta',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })

    expect(user1Conv1.claim('action_x').granted).toBe(true)
    // Different conversation of same user can execute its own action_x
    expect(user1Conv2.claim('action_x').granted).toBe(true)
    // Different user in same conversation name can execute its own action_x
    expect(user2Conv1.claim('action_x').granted).toBe(true)
  })

  it('clearOwnerUiActionLifecycle clears only target owner and leaves other owners unaffected', () => {
    const userAlpha = new OwnerUiActionLifecycle({
      ownerId: 'user_alpha',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })
    userAlpha.claim('action_1')
    userAlpha.complete('action_1', { status: 'SUCCEEDED', currentRoute: 'roadmap' })

    const userBeta = new OwnerUiActionLifecycle({
      ownerId: 'user_beta',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })
    userBeta.claim('action_2')
    userBeta.complete('action_2', { status: 'SUCCEEDED', currentRoute: 'materials' })

    clearOwnerUiActionLifecycle(fakeStorage, 'user_alpha')

    // user_alpha data should be cleared
    clearInMemoryRuntimeClaimsForTest()
    const userAlphaAfter = new OwnerUiActionLifecycle({
      ownerId: 'user_alpha',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })
    expect(userAlphaAfter.get('action_1')).toBeUndefined()

    // user_beta data must remain intact
    const userBetaAfter = new OwnerUiActionLifecycle({
      ownerId: 'user_beta',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })
    expect(userBetaAfter.get('action_2')).toBeDefined()
    expect(userBetaAfter.get('action_2')?.receipt?.status).toBe('SUCCEEDED')
  })

  it('handles malformed storage payloads gracefully and resets safely', () => {
    const key = getOwnerConversationStorageKey('user_corrupt', 'conv_corrupt')
    fakeStorage.setItem(key, '{ invalid json: [')

    const lifecycle = new OwnerUiActionLifecycle({
      ownerId: 'user_corrupt',
      conversationId: 'conv_corrupt',
      storage: fakeStorage,
    })

    const claim = lifecycle.claim('action_corrupt_test')
    expect(claim.granted).toBe(true)
    expect(lifecycle.isPersistenceHealthy()).toBe(true)
  })

  it('handles storage exceptions safely, keeps in-memory dedupe, and surfaces persistence error', () => {
    const throwingStorage: StorageLike = {
      getItem() {
        throw new Error('QuotaExceededError: storage full')
      },
      setItem() {
        throw new Error('QuotaExceededError: storage full')
      },
      removeItem() {},
    }

    const lifecycle = new OwnerUiActionLifecycle({
      ownerId: 'user_err',
      conversationId: 'conv_err',
      storage: throwingStorage,
    })

    expect(lifecycle.isPersistenceHealthy()).toBe(false)
    expect(lifecycle.getPersistenceError()).toContain('QuotaExceededError')

    // In-memory claim still functions at-most-once for active runtime
    const firstClaim = lifecycle.claim('action_in_mem')
    expect(firstClaim.granted).toBe(true)
    expect(firstClaim.persistenceOk).toBe(false)

    const secondClaim = lifecycle.claim('action_in_mem')
    expect(secondClaim.granted).toBe(false)
  })

  it('bounded storage cleanup prunes old reported records without evicting pending unreported records', () => {
    let mockTime = 1000
    const lifecycle = new OwnerUiActionLifecycle({
      ownerId: 'user_bounded',
      conversationId: 'conv_bounded',
      storage: fakeStorage,
      maxStoredActions: 3,
      now: () => mockTime++,
    })

    // Add 2 pending records
    lifecycle.claim('pending_1')
    lifecycle.complete('pending_1', { status: 'SUCCEEDED', currentRoute: 'goals' })

    lifecycle.claim('pending_2')
    lifecycle.complete('pending_2', { status: 'SUCCEEDED', currentRoute: 'plans' })

    // Add reported record
    lifecycle.claim('reported_1')
    lifecycle.complete('reported_1', { status: 'SUCCEEDED', currentRoute: 'today' })
    lifecycle.markReported('reported_1')

    // Add another reported record, exceeding maxStoredActions = 3
    lifecycle.claim('reported_2')
    lifecycle.complete('reported_2', { status: 'SUCCEEDED', currentRoute: 'materials' })
    lifecycle.markReported('reported_2')

    // pending_1 and pending_2 MUST NOT be evicted
    expect(lifecycle.get('pending_1')).toBeDefined()
    expect(lifecycle.get('pending_2')).toBeDefined()

    // Oldest reported record (reported_1) should be pruned, newer reported_2 kept
    expect(lifecycle.get('reported_1')).toBeUndefined()
    expect(lifecycle.get('reported_2')).toBeDefined()
  })

  it('old claim -> owner clear -> old complete/mark cannot recreate storage and is invalid', () => {
    const lifecycle = new OwnerUiActionLifecycle({
      ownerId: 'user_target',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })

    const claimResult = lifecycle.claim('action_inval_1')
    expect(claimResult.granted).toBe(true)
    expect(lifecycle.isValid()).toBe(true)

    // Clear owner lifecycle
    clearOwnerUiActionLifecycle(fakeStorage, 'user_target')
    expect(lifecycle.isValid()).toBe(false)

    // Complete on old invalidated instance
    const receipt = lifecycle.complete('action_inval_1', {
      status: 'SUCCEEDED',
      currentRoute: 'goals',
    })
    expect(receipt.status).toBe('SUCCEEDED')

    // Cannot recreate storage
    const storageKey = getOwnerConversationStorageKey('user_target', 'conv_1')
    expect(fakeStorage.getItem(storageKey)).toBeNull()

    // Cannot mark reported to recreate storage
    const marked = lifecycle.markReported('action_inval_1')
    expect(marked).toBe(true)
    expect(fakeStorage.getItem(storageKey)).toBeNull()

    // Cannot mark report failure to recreate storage
    const markFailed = lifecycle.markReportFailure('action_inval_1', 'network error')
    expect(markFailed).toBe(true)
    expect(fakeStorage.getItem(storageKey)).toBeNull()

    // Old instance cannot grant new claim
    const newClaim = lifecycle.claim('action_inval_2')
    expect(newClaim.granted).toBe(false)
    expect(newClaim.persistenceError).toContain('invalidated')
  })

  it('new same-owner lifecycle after clear is valid and can claim', () => {
    const oldLifecycle = new OwnerUiActionLifecycle({
      ownerId: 'user_reclaim',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })
    expect(oldLifecycle.claim('action_pre_clear').granted).toBe(true)

    clearOwnerUiActionLifecycle(fakeStorage, 'user_reclaim')
    expect(oldLifecycle.isValid()).toBe(false)

    const newLifecycle = new OwnerUiActionLifecycle({
      ownerId: 'user_reclaim',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })
    expect(newLifecycle.isValid()).toBe(true)

    const newClaim = newLifecycle.claim('action_post_clear')
    expect(newClaim.granted).toBe(true)
    expect(newClaim.lifecycleState).toBe('EXECUTING')

    // Release runtime claim is safe
    expect(() => oldLifecycle.releaseRuntimeClaim('action_pre_clear')).not.toThrow()
    expect(() => newLifecycle.releaseRuntimeClaim('action_post_clear')).not.toThrow()
  })

  it('concrete-owner clear preserves another owner storage and validity', () => {
    const userA = new OwnerUiActionLifecycle({
      ownerId: 'user_a',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })
    const userB = new OwnerUiActionLifecycle({
      ownerId: 'user_b',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })

    expect(userA.claim('action_a').granted).toBe(true)
    userA.complete('action_a', { status: 'SUCCEEDED', currentRoute: 'goals' })

    expect(userB.claim('action_b').granted).toBe(true)
    userB.complete('action_b', { status: 'SUCCEEDED', currentRoute: 'plans' })

    clearOwnerUiActionLifecycle(fakeStorage, 'user_a')

    expect(userA.isValid()).toBe(false)
    expect(userB.isValid()).toBe(true)

    // user_b storage preserved
    const keyB = getOwnerConversationStorageKey('user_b', 'conv_1')
    expect(fakeStorage.getItem(keyB)).not.toBeNull()

    // user_b can continue mutating storage normally
    expect(userB.markReported('action_b')).toBe(true)
    expect(fakeStorage.getItem(keyB)).not.toBeNull()
  })

  it('clearAll removes all lifecycle keys, invalidates all old owners, preserves unrelated key', () => {
    fakeStorage.setItem('unrelated_app_setting', 'keep_me')

    const user1 = new OwnerUiActionLifecycle({
      ownerId: 'user_1',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })
    const user2 = new OwnerUiActionLifecycle({
      ownerId: 'user_2',
      conversationId: 'conv_2',
      storage: fakeStorage,
    })

    expect(user1.claim('action_1').granted).toBe(true)
    user1.complete('action_1', { status: 'SUCCEEDED', currentRoute: 'goals' })

    expect(user2.claim('action_2').granted).toBe(true)
    user2.complete('action_2', { status: 'SUCCEEDED', currentRoute: 'roadmap' })

    const key1 = getOwnerConversationStorageKey('user_1', 'conv_1')
    const key2 = getOwnerConversationStorageKey('user_2', 'conv_2')
    expect(fakeStorage.getItem(key1)).not.toBeNull()
    expect(fakeStorage.getItem(key2)).not.toBeNull()

    clearAllOwnerUiActionLifecycles(fakeStorage)

    expect(user1.isValid()).toBe(false)
    expect(user2.isValid()).toBe(false)

    // All lifecycle keys cleared
    expect(fakeStorage.getItem(key1)).toBeNull()
    expect(fakeStorage.getItem(key2)).toBeNull()

    // Unrelated storage key preserved
    expect(fakeStorage.getItem('unrelated_app_setting')).toBe('keep_me')

    // Invalidated users cannot recreate storage
    user1.complete('action_1', { status: 'SUCCEEDED', currentRoute: 'goals' })
    expect(fakeStorage.getItem(key1)).toBeNull()

    // Brand new instance created after clearAll is valid and works
    const brandNewUser = new OwnerUiActionLifecycle({
      ownerId: 'user_1',
      conversationId: 'conv_1',
      storage: fakeStorage,
    })
    expect(brandNewUser.isValid()).toBe(true)
    expect(brandNewUser.claim('action_fresh').granted).toBe(true)
  })
})
