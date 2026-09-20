import type { UiActionReceipt, UiActionReceiptStatus } from '@/types/assistant'

export type ActionLifecycleState = 'EXECUTING' | 'TERMINAL'

export interface DurableUiActionRecord {
  actionId: string
  ownerId: string
  conversationId: string
  lifecycleState: ActionLifecycleState
  receipt?: UiActionReceipt
  reported: boolean
  reportAttempts: number
  lastReportError?: string | null
  createdAt: number
  updatedAt: number
}

export interface ClaimResult {
  granted: boolean
  actionId: string
  existingReceipt?: UiActionReceipt
  lifecycleState?: ActionLifecycleState
  persistenceOk: boolean
  persistenceError?: string
}

export interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
  key?(index: number): string | null
  length?: number
}

export interface PendingReceiptSummary {
  receipt: UiActionReceipt
  reportAttempts: number
  maxReportAttempts: number
  manualFallbackRequired: boolean
  lastReportError?: string | null
}

export interface OwnerUiActionLifecycleOptions {
  ownerId: string
  conversationId: string
  storage?: StorageLike | null
  maxReportAttempts?: number
  maxStoredActions?: number
  now?: () => number
}

const STORAGE_KEY_PREFIX = 'sp_ui_action_receipts:v1:'
const DEFAULT_MAX_REPORT_ATTEMPTS = 3
const DEFAULT_MAX_STORED_ACTIONS = 100

export function getOwnerConversationStorageKey(ownerId: string, conversationId: string): string {
  return `${STORAGE_KEY_PREFIX}${encodeURIComponent(ownerId)}:${encodeURIComponent(conversationId)}`
}

function sanitizeError(error?: string | null): string | null {
  if (!error) return null
  // Strip potentially sensitive credential patterns or unbounded payloads
  const cleaned = String(error)
    .replace(/(Bearer\s+)[A-Za-z0-9._~+/-]+=*/gi, '$1[REDACTED]')
    .replace(/(token|password|secret|key)=([^&\s]+)/gi, '$1=[REDACTED]')
    .trim()
  return cleaned.slice(0, 300)
}

function sanitizeRoute(route?: string | null): string {
  if (!route) return 'unknown'
  return String(route).trim().slice(0, 100)
}

/**
 * Validates and normalizes raw JSON payload into DurableUiActionRecord array.
 * Rejects/ignores any records mismatched with current ownerId and conversationId.
 */
function parseStoragePayload(
  rawJson: string,
  ownerId: string,
  conversationId: string,
): { valid: boolean; records: DurableUiActionRecord[] } {
  try {
    const parsed = JSON.parse(rawJson)
    if (!parsed || typeof parsed !== 'object') {
      return { valid: false, records: [] }
    }
    if (parsed.ownerId !== ownerId || parsed.conversationId !== conversationId) {
      return { valid: false, records: [] }
    }
    if (!Array.isArray(parsed.records)) {
      return { valid: false, records: [] }
    }

    const validRecords: DurableUiActionRecord[] = []
    for (const item of parsed.records) {
      if (!item || typeof item !== 'object' || typeof item.actionId !== 'string' || !item.actionId.trim()) {
        continue
      }
      if (item.ownerId !== ownerId || item.conversationId !== conversationId) {
        continue
      }
      validRecords.push({
        actionId: item.actionId.trim(),
        ownerId,
        conversationId,
        lifecycleState: item.lifecycleState === 'EXECUTING' ? 'EXECUTING' : 'TERMINAL',
        receipt: item.receipt
          ? {
              actionId: item.actionId.trim(),
              status: ['SUCCEEDED', 'FAILED', 'REJECTED'].includes(item.receipt.status)
                ? (item.receipt.status as UiActionReceiptStatus)
                : 'FAILED',
              currentRoute: sanitizeRoute(item.receipt.currentRoute),
              error: sanitizeError(item.receipt.error),
            }
          : undefined,
        reported: Boolean(item.reported),
        reportAttempts: typeof item.reportAttempts === 'number' ? item.reportAttempts : 0,
        lastReportError: sanitizeError(item.lastReportError),
        createdAt: typeof item.createdAt === 'number' ? item.createdAt : Date.now(),
        updatedAt: typeof item.updatedAt === 'number' ? item.updatedAt : Date.now(),
      })
    }
    return { valid: true, records: validRecords }
  } catch {
    return { valid: false, records: [] }
  }
}

/**
 * Module-level in-flight execution map to suppress concurrent executions within same runtime.
 * Keyed by: `${ownerId}::${conversationId}::${actionId}`
 */
const inMemoryRuntimeClaims = new Set<string>()

/**
 * In-memory generation counter per owner.
 * Incremented on clearOwnerUiActionLifecycle(storage, ownerId).
 * On clearAllOwnerUiActionLifecycles(storage), all existing owners are bumped
 * and a fallback baseline generation is incremented for any previously unrecorded owner.
 */
const ownerGenerations = new Map<string, number>()
let globalBaseGeneration = 0

function getOwnerCurrentGeneration(ownerId: string): number {
  const current = ownerGenerations.get(ownerId)
  if (current !== undefined) {
    return current
  }
  return globalBaseGeneration
}

export class OwnerUiActionLifecycle {
  private readonly ownerId: string
  private readonly conversationId: string
  private readonly storage: StorageLike | null
  private readonly maxReportAttempts: number
  private readonly maxStoredActions: number
  private readonly now: () => number
  private readonly storageKey: string
  private readonly generation: number

  // In-memory shadow state for active runtime & fallback if storage fails
  private memoryRecords: Map<string, DurableUiActionRecord> = new Map()
  private lastPersistenceOk: boolean = true
  private lastPersistenceError: string | undefined = undefined

  constructor(options: OwnerUiActionLifecycleOptions) {
    if (!options.ownerId || !options.ownerId.trim()) {
      throw new Error('OwnerUiActionLifecycle requires a non-empty ownerId')
    }
    if (!options.conversationId || !options.conversationId.trim()) {
      throw new Error('OwnerUiActionLifecycle requires a non-empty conversationId')
    }

    this.ownerId = options.ownerId.trim()
    this.conversationId = options.conversationId.trim()
    this.generation = getOwnerCurrentGeneration(this.ownerId)
    this.storage = options.storage ?? (typeof window !== 'undefined' && window.sessionStorage ? window.sessionStorage : null)
    this.maxReportAttempts = options.maxReportAttempts ?? DEFAULT_MAX_REPORT_ATTEMPTS
    this.maxStoredActions = options.maxStoredActions ?? DEFAULT_MAX_STORED_ACTIONS
    this.now = options.now ?? (() => Date.now())
    this.storageKey = getOwnerConversationStorageKey(this.ownerId, this.conversationId)

    this.initAndRecover()
  }

  public isValid(): boolean {
    return this.generation === getOwnerCurrentGeneration(this.ownerId)
  }

  private getRuntimeClaimKey(actionId: string): string {
    return `${this.ownerId}::${this.conversationId}::${actionId}`
  }

  /**
   * Initializes state from storage.
   * If storage contains a pre-terminal EXECUTING claim after reload/crash,
   * recover it as a terminal FAILED receipt with an actionable interruption message.
   * Never re-executes because outcome is uncertain.
   */
  private initAndRecover(): void {
    if (!this.storage) {
      this.lastPersistenceOk = false
      this.lastPersistenceError = 'Storage not available'
      return
    }

    try {
      const raw = this.storage.getItem(this.storageKey)
      if (!raw) {
        return
      }

      const { valid, records } = parseStoragePayload(raw, this.ownerId, this.conversationId)
      if (!valid) {
        // Corrupted or mismatched payload: reset safely
        this.saveRecordsToStorage([])
        return
      }

      let mutated = false
      const currentTime = this.now()

      for (const record of records) {
        if (record.lifecycleState === 'EXECUTING') {
          // Recover crashed or reloaded executing claim into terminal FAILED receipt
          record.lifecycleState = 'TERMINAL'
          record.receipt = {
            actionId: record.actionId,
            status: 'FAILED',
            currentRoute: 'unknown',
            error: 'Action interrupted by page reload or crash before completion; manual check recommended',
          }
          record.updatedAt = currentTime
          mutated = true
        }
        this.memoryRecords.set(record.actionId, record)
      }

      if (mutated) {
        this.persist()
      }
    } catch (err: unknown) {
      this.lastPersistenceOk = false
      this.lastPersistenceError = err instanceof Error ? err.message : String(err)
    }
  }

  private saveRecordsToStorage(records: DurableUiActionRecord[]): void {
    if (!this.isValid()) return
    if (!this.storage) return
    const payload = JSON.stringify({
      version: 1,
      ownerId: this.ownerId,
      conversationId: this.conversationId,
      updatedAt: this.now(),
      records,
    })
    this.storage.setItem(this.storageKey, payload)
  }

  private persist(): void {
    if (!this.isValid()) {
      return
    }
    if (!this.storage) {
      this.lastPersistenceOk = false
      this.lastPersistenceError = 'Storage not available'
      return
    }

    try {
      // Deterministic cleanup: bound stored action count without evicting pending records
      let recordList = Array.from(this.memoryRecords.values())
      if (recordList.length > this.maxStoredActions) {
        // Separate pending (unreported and not exhausted) from completed/reported or exhausted
        const pending: DurableUiActionRecord[] = []
        const eligibleForPruning: DurableUiActionRecord[] = []

        for (const rec of recordList) {
          if (!rec.reported && rec.reportAttempts < this.maxReportAttempts) {
            pending.push(rec)
          } else {
            eligibleForPruning.push(rec)
          }
        }

        // Sort eligible pruning list oldest first (updatedAt asc)
        eligibleForPruning.sort((a, b) => a.updatedAt - b.updatedAt)

        const allowedPrunedCount = Math.max(0, this.maxStoredActions - pending.length)
        const keptPruned = eligibleForPruning.slice(eligibleForPruning.length - allowedPrunedCount)

        const keptMap = new Map<string, DurableUiActionRecord>()
        for (const item of [...keptPruned, ...pending]) {
          keptMap.set(item.actionId, item)
        }
        this.memoryRecords = keptMap
        recordList = Array.from(this.memoryRecords.values())
      }

      // Format strictly without any action params, credentials, or payloads
      const minimalRecords = recordList.map((r) => ({
        actionId: r.actionId,
        ownerId: r.ownerId,
        conversationId: r.conversationId,
        lifecycleState: r.lifecycleState,
        receipt: r.receipt
          ? {
              actionId: r.receipt.actionId,
              status: r.receipt.status,
              currentRoute: sanitizeRoute(r.receipt.currentRoute),
              error: sanitizeError(r.receipt.error),
            }
          : undefined,
        reported: r.reported,
        reportAttempts: r.reportAttempts,
        lastReportError: sanitizeError(r.lastReportError),
        createdAt: r.createdAt,
        updatedAt: r.updatedAt,
      }))

      this.saveRecordsToStorage(minimalRecords)
      this.lastPersistenceOk = true
      this.lastPersistenceError = undefined
    } catch (err: unknown) {
      this.lastPersistenceOk = false
      this.lastPersistenceError = err instanceof Error ? err.message : String(err)
    }
  }

  /**
   * Atomically claims an action before any UI side effect.
   * - First claim grants execution permission.
   * - Concurrent calls in same runtime or subsequent calls across reloads are denied.
   * - Denies claim if lifecycle instance has been invalidated.
   */
  public claim(actionId: string): ClaimResult {
    const trimmedId = (actionId || '').trim()
    if (!trimmedId) {
      return {
        granted: false,
        actionId: '',
        persistenceOk: this.lastPersistenceOk,
        persistenceError: 'Invalid actionId',
      }
    }

    if (!this.isValid()) {
      return {
        granted: false,
        actionId: trimmedId,
        persistenceOk: false,
        persistenceError: 'Lifecycle instance has been invalidated',
      }
    }

    const runtimeKey = this.getRuntimeClaimKey(trimmedId)

    // Check runtime lock
    if (inMemoryRuntimeClaims.has(runtimeKey)) {
      const existing = this.memoryRecords.get(trimmedId)
      return {
        granted: false,
        actionId: trimmedId,
        existingReceipt: existing?.receipt,
        lifecycleState: existing?.lifecycleState ?? 'EXECUTING',
        persistenceOk: this.lastPersistenceOk,
        persistenceError: this.lastPersistenceError,
      }
    }

    // Check existing stored record
    const existing = this.memoryRecords.get(trimmedId)
    if (existing) {
      return {
        granted: false,
        actionId: trimmedId,
        existingReceipt: existing.receipt,
        lifecycleState: existing.lifecycleState,
        persistenceOk: this.lastPersistenceOk,
        persistenceError: this.lastPersistenceError,
      }
    }

    // Grant first claim and transition to EXECUTING
    inMemoryRuntimeClaims.add(runtimeKey)
    const currentTime = this.now()
    const newRecord: DurableUiActionRecord = {
      actionId: trimmedId,
      ownerId: this.ownerId,
      conversationId: this.conversationId,
      lifecycleState: 'EXECUTING',
      reported: false,
      reportAttempts: 0,
      createdAt: currentTime,
      updatedAt: currentTime,
    }

    this.memoryRecords.set(trimmedId, newRecord)
    this.persist()

    return {
      granted: true,
      actionId: trimmedId,
      lifecycleState: 'EXECUTING',
      persistenceOk: this.lastPersistenceOk,
      persistenceError: this.lastPersistenceError,
    }
  }

  /**
   * Completes the action with a terminal receipt before publication.
   * Stores terminal receipt immediately.
   */
  public complete(
    actionId: string,
    receiptInput: {
      status: UiActionReceiptStatus
      currentRoute: string
      error?: string | null
    },
  ): UiActionReceipt {
    const trimmedId = (actionId || '').trim()
    const runtimeKey = this.getRuntimeClaimKey(trimmedId)
    inMemoryRuntimeClaims.delete(runtimeKey)

    const sanitizedTerminalReceipt: UiActionReceipt = {
      actionId: trimmedId,
      status: receiptInput.status,
      currentRoute: sanitizeRoute(receiptInput.currentRoute),
      error: sanitizeError(receiptInput.error),
    }

    const currentTime = this.now()
    const existing = this.memoryRecords.get(trimmedId)
    const record: DurableUiActionRecord = {
      actionId: trimmedId,
      ownerId: this.ownerId,
      conversationId: this.conversationId,
      lifecycleState: 'TERMINAL',
      receipt: sanitizedTerminalReceipt,
      reported: existing ? existing.reported : false,
      reportAttempts: existing ? existing.reportAttempts : 0,
      lastReportError: existing ? existing.lastReportError : null,
      createdAt: existing ? existing.createdAt : currentTime,
      updatedAt: currentTime,
    }

    this.memoryRecords.set(trimmedId, record)
    this.persist()

    return sanitizedTerminalReceipt
  }

  /**
   * Releases runtime in-flight lock if execution was aborted before claiming or recording.
   */
  public releaseRuntimeClaim(actionId: string): void {
    const trimmedId = (actionId || '').trim()
    inMemoryRuntimeClaims.delete(this.getRuntimeClaimKey(trimmedId))
  }

  /**
   * Retrieves a record by actionId.
   */
  public get(actionId: string): DurableUiActionRecord | undefined {
    return this.memoryRecords.get((actionId || '').trim())
  }

  /**
   * Enumerates pending terminal receipts for publication retry without re-executing.
   * Excludes exhausted records (attempts >= maxReportAttempts).
   */
  public getPendingReceipts(): PendingReceiptSummary[] {
    const results: PendingReceiptSummary[] = []
    for (const record of this.memoryRecords.values()) {
      if (record.lifecycleState === 'TERMINAL' && record.receipt && !record.reported) {
        const isExhausted = record.reportAttempts >= this.maxReportAttempts
        if (!isExhausted) {
          results.push({
            receipt: record.receipt,
            reportAttempts: record.reportAttempts,
            maxReportAttempts: this.maxReportAttempts,
            manualFallbackRequired: false,
            lastReportError: record.lastReportError,
          })
        }
      }
    }
    return results
  }

  /**
   * Returns exhausted records requiring manual fallback.
   */
  public getExhaustedReceipts(): PendingReceiptSummary[] {
    const results: PendingReceiptSummary[] = []
    for (const record of this.memoryRecords.values()) {
      if (record.lifecycleState === 'TERMINAL' && record.receipt && !record.reported) {
        if (record.reportAttempts >= this.maxReportAttempts) {
          results.push({
            receipt: record.receipt,
            reportAttempts: record.reportAttempts,
            maxReportAttempts: this.maxReportAttempts,
            manualFallbackRequired: true,
            lastReportError: record.lastReportError,
          })
        }
      }
    }
    return results
  }

  /**
   * Marks a terminal receipt as successfully reported without deleting deduplication history.
   */
  public markReported(actionId: string): boolean {
    const record = this.memoryRecords.get((actionId || '').trim())
    if (!record) return false

    record.reported = true
    record.updatedAt = this.now()
    record.lastReportError = null
    this.persist()
    return true
  }

  /**
   * Increments publication failure count and updates error info.
   */
  public markReportFailure(actionId: string, error?: string | null): boolean {
    const record = this.memoryRecords.get((actionId || '').trim())
    if (!record) return false

    record.reportAttempts += 1
    record.lastReportError = sanitizeError(error)
    record.updatedAt = this.now()
    this.persist()
    return true
  }

  public isPersistenceHealthy(): boolean {
    return this.lastPersistenceOk
  }

  public getPersistenceError(): string | undefined {
    return this.lastPersistenceError
  }
}

/**
 * Clears lifecycle keys for exactly one owner on logout/account switch without touching another owner.
 * Increments only that owner's generation, removes all runtime claims for exactly that owner,
 * and removes exactly that owner's prefixed lifecycle storage keys.
 */
export function clearOwnerUiActionLifecycle(storage: StorageLike | null | undefined, ownerId: string): void {
  const trimmedOwner = (ownerId || '').trim()
  if (!trimmedOwner) return

  // Increment only this owner's generation
  const currentGen = getOwnerCurrentGeneration(trimmedOwner)
  ownerGenerations.set(trimmedOwner, currentGen + 1)

  // Remove runtime claims for exactly this owner
  const ownerRuntimePrefix = `${trimmedOwner}::`
  for (const claimKey of Array.from(inMemoryRuntimeClaims)) {
    if (claimKey.startsWith(ownerRuntimePrefix)) {
      inMemoryRuntimeClaims.delete(claimKey)
    }
  }

  // Remove exactly that owner's prefixed lifecycle storage keys
  if (storage && typeof storage.length === 'number' && typeof storage.key === 'function') {
    const encodedOwner = encodeURIComponent(trimmedOwner)
    const ownerStoragePrefix = `${STORAGE_KEY_PREFIX}${encodedOwner}:`

    const keysToRemove: string[] = []
    for (let i = 0; i < storage.length; i++) {
      const key = storage.key(i)
      if (key && key.startsWith(ownerStoragePrefix)) {
        keysToRemove.push(key)
      }
    }
    for (const key of keysToRemove) {
      storage.removeItem(key)
    }
  }
}

/**
 * Clears all owner UI action lifecycles for unknown-prior-owner cleanup.
 * Invalidates all existing instances, clears all runtime claims,
 * removes every key beginning `sp_ui_action_receipts:v1:`, and preserves unrelated storage.
 */
export function clearAllOwnerUiActionLifecycles(storage: StorageLike | null | undefined): void {
  // Bump global baseline generation and advance all known owner generations
  globalBaseGeneration += 1
  for (const owner of Array.from(ownerGenerations.keys())) {
    const current = ownerGenerations.get(owner) ?? (globalBaseGeneration - 1)
    ownerGenerations.set(owner, Math.max(current + 1, globalBaseGeneration))
  }

  // Clear all runtime claims
  inMemoryRuntimeClaims.clear()

  // Remove every key beginning with STORAGE_KEY_PREFIX, preserve unrelated keys
  if (storage && typeof storage.length === 'number' && typeof storage.key === 'function') {
    const keysToRemove: string[] = []
    for (let i = 0; i < storage.length; i++) {
      const key = storage.key(i)
      if (key && key.startsWith(STORAGE_KEY_PREFIX)) {
        keysToRemove.push(key)
      }
    }
    for (const key of keysToRemove) {
      storage.removeItem(key)
    }
  }
}

/**
 * Test helper to clear module-level in-flight runtime claims.
 */
export function clearInMemoryRuntimeClaimsForTest(): void {
  inMemoryRuntimeClaims.clear()
}
