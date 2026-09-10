import { handleUnauthorized, http, TOKEN_STORAGE_KEY } from '@/services/http'
import type {
  AssistantConversation,
  AssistantEvent,
  AssistantEventType,
  AssistantEventStreamController,
  AssistantEventStreamOptions,
  AssistantHealth,
  AutomationRule,
  AutomationRuleType,
  AutomationSettings,
  EventStreamStatus,
  SendAssistantMessage,
} from '@/types/assistant'

export type {
  AssistantEvent,
  AssistantEventType,
  AssistantEventStreamController,
  AssistantEventStreamOptions,
  EventStreamStatus,
}

const assistantRequest = { timeout: 120_000 } as const

export const VALID_ASSISTANT_EVENT_TYPES: ReadonlySet<AssistantEventType> = new Set([
  'HEARTBEAT',
  'TURN_STARTED',
  'CONTEXT_LOADED',
  'PLAN_GENERATED',
  'TOOL_STARTED',
  'TOOL_SUCCEEDED',
  'TOOL_FAILED',
  'ACTION_PREVIEW',
  'ASSISTANT_DELTA',
  'UI_ACTION',
  'TURN_COMPLETED',
  'TURN_FAILED',
  'TURN_CANCELLED',
])

export function subscribeAssistantEvents(
  conversationId: string,
  options: AssistantEventStreamOptions = {},
): AssistantEventStreamController {
  const {
    lastEventId,
    signal,
    onEvent,
    onHeartbeat,
    onError,
    onClose,
    onStatusChange,
    autoReconnect = true,
    reconnectIntervalMs = 1000,
    maxReconnectIntervalMs = 10_000,
    maxReconnectAttempts = 10,
  } = options

  let currentLastSequence = typeof lastEventId === 'number'
    ? lastEventId
    : (lastEventId ? parseInt(String(lastEventId), 10) || 0 : 0)
  let isClosed = false
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let activeAbortController: AbortController | null = null
  let currentReconnectDelay = reconnectIntervalMs
  let reconnectAttempts = 0
  let status: EventStreamStatus = 'disconnected'

  function setStatus(next: EventStreamStatus) {
    if (status !== next) {
      status = next
      onStatusChange?.(next)
    }
  }

  const baseUrl = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/+$/, '')
  const endpoint = `${baseUrl}/api/assistant/conversations/${encodeURIComponent(conversationId)}/events`

  async function connect() {
    if (isClosed) return

    activeAbortController = new AbortController()
    if (signal) {
      signal.addEventListener('abort', () => close(), { once: true })
      if (signal.aborted) {
        close()
        return
      }
    }

    setStatus(reconnectAttempts > 0 ? 'reconnecting' : 'connecting')

    const headers: Record<string, string> = {
      Accept: 'text/event-stream',
    }
    const token = sessionStorage.getItem(TOKEN_STORAGE_KEY)
    if (token) {
      headers['Authorization'] = `Bearer ${token}`
    }
    if (currentLastSequence > 0) {
      headers['Last-Event-ID'] = String(currentLastSequence)
    }

    try {
      const response = await fetch(endpoint, {
        method: 'GET',
        headers,
        signal: activeAbortController.signal,
      })

      if (response.status === 401) {
        handleUnauthorized()
        setStatus('disconnected')
        const err = new Error('登录已过期，请重新登录')
        onError?.(err)
        close()
        return
      }

      if (!response.ok) {
        throw new Error(`SSE 连接失败: HTTP ${response.status} ${response.statusText}`)
      }

      if (!response.body) {
        throw new Error('响应体为空，无法建立流式连接')
      }

      setStatus('connected')
      reconnectAttempts = 0
      currentReconnectDelay = reconnectIntervalMs

      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      while (!isClosed) {
        const { done, value } = await reader.read()
        if (done) {
          break
        }
        buffer += decoder.decode(value, { stream: true })

        while (true) {
          const rn = buffer.indexOf('\r\n\r\n')
          const nn = buffer.indexOf('\n\n')
          if (rn === -1 && nn === -1) break

          let frame = ''
          if (rn !== -1 && (nn === -1 || rn < nn)) {
            frame = buffer.slice(0, rn)
            buffer = buffer.slice(rn + 4)
          } else {
            frame = buffer.slice(0, nn)
            buffer = buffer.slice(nn + 2)
          }
          dispatchFrame(frame)
        }
      }

      if (!isClosed) {
        onClose?.()
        scheduleReconnect()
      }
    } catch (err: unknown) {
      if (isClosed || (err instanceof DOMException && err.name === 'AbortError')) {
        return
      }
      onError?.(err)
      if (!isClosed) {
        scheduleReconnect()
      }
    }
  }

  function dispatchFrame(frame: string) {
    if (!frame.trim()) return

    const lines = frame.split(/\r?\n/)
    let eventName: string | null = null
    let frameId: string | null = null
    const dataLines: string[] = []

    for (const line of lines) {
      if (!line) continue
      if (line.startsWith(':')) {
        const comment = line.slice(1).trim()
        if (comment === 'heartbeat' || comment === '') {
          onHeartbeat?.()
        }
        continue
      }
      const colonIndex = line.indexOf(':')
      let field = line
      let val = ''
      if (colonIndex !== -1) {
        field = line.slice(0, colonIndex)
        val = line.slice(colonIndex + 1)
        if (val.startsWith(' ')) {
          val = val.slice(1)
        }
      }
      if (field === 'id') {
        frameId = val
      } else if (field === 'event') {
        eventName = val
      } else if (field === 'data') {
        dataLines.push(val)
      }
    }

    if (dataLines.length === 0) {
      return
    }

    const rawData = dataLines.join('\n')
    let parsed: any
    try {
      parsed = JSON.parse(rawData)
    } catch {
      // 拒绝格式非法的 JSON 数据，绝不提前推进游标
      return
    }

    if (!parsed || typeof parsed !== 'object') {
      return
    }

    // 事件类型白名单校验
    const eventType = (eventName || parsed.type) as AssistantEventType
    if (!eventType || !VALID_ASSISTANT_EVENT_TYPES.has(eventType)) {
      return
    }
    // event 声明与 payload.type 必须一致
    if (eventName && parsed.type && eventName !== parsed.type) {
      return
    }

    // 会话归属一致性校验
    if (parsed.conversationId && parsed.conversationId !== conversationId) {
      return
    }

    // 序列号与 frame ID 一致性校验
    let frameSeq: number | null = null
    if (frameId !== null) {
      frameSeq = parseInt(frameId, 10)
      if (isNaN(frameSeq) || frameSeq <= 0) {
        return
      }
    }
    let payloadSeq: number | null = null
    if (typeof parsed.sequence === 'number') {
      const parsedNum = parsed.sequence as number
      if (parsedNum <= 0) {
        return
      }
      payloadSeq = parsedNum
    }
    if (frameSeq !== null && payloadSeq !== null && frameSeq !== payloadSeq) {
      return
    }

    const seq: number | null = payloadSeq !== null ? payloadSeq : frameSeq
    if (seq === null || seq <= 0) {
      return
    }

    // 幂等去重：序列号小于等于已消费序号时直接丢弃
    if (seq <= currentLastSequence) {
      return
    }

    // 全部严格校验通过后，才正式推进序号游标
    currentLastSequence = seq

    const event: AssistantEvent = {
      sequence: seq,
      type: eventType,
      conversationId: parsed.conversationId || conversationId,
      payload: (parsed.payload !== undefined && typeof parsed.payload === 'object' && parsed.payload !== null)
        ? parsed.payload
        : parsed,
    }
    onEvent?.(event)
  }

  function scheduleReconnect() {
    if (isClosed || !autoReconnect) {
      setStatus('disconnected')
      return
    }
    if (reconnectAttempts >= maxReconnectAttempts) {
      setStatus('disconnected')
      onError?.(new Error(`超出最大重连尝试次数 (${maxReconnectAttempts})，连接已断开`))
      return
    }
    reconnectAttempts++
    setStatus('reconnecting')
    if (reconnectTimer) clearTimeout(reconnectTimer)
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      void connect()
    }, currentReconnectDelay)
    currentReconnectDelay = Math.min(currentReconnectDelay * 1.5, maxReconnectIntervalMs)
  }

  function close() {
    isClosed = true
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (activeAbortController) {
      activeAbortController.abort()
      activeAbortController = null
    }
    setStatus('disconnected')
    onClose?.()
  }

  void connect()

  return {
    close,
    getLastEventId: () => currentLastSequence,
    getStatus: () => status,
  }
}

export const assistantApi = {
  createConversation: () =>
    http.post<AssistantConversation>('/api/assistant/conversations', {}, assistantRequest)
      .then((response) => response.data),
  getConversation: (id: string) =>
    http.get<AssistantConversation>(`/api/assistant/conversations/${id}`, assistantRequest)
      .then((response) => response.data),
  sendMessage: (id: string, body: SendAssistantMessage) =>
    http.post<AssistantConversation>(
      `/api/assistant/conversations/${id}/messages`, body, assistantRequest,
    ).then((response) => response.data),
  confirmAction: (conversationId: string, actionId: string) =>
    http.post<AssistantConversation>(
      `/api/assistant/conversations/${conversationId}/actions/${actionId}/confirm`,
      {}, assistantRequest,
    ).then((response) => response.data),
  rejectAction: (conversationId: string, actionId: string) =>
    http.post<AssistantConversation>(
      `/api/assistant/conversations/${conversationId}/actions/${actionId}/reject`,
      {}, assistantRequest,
    ).then((response) => response.data),
  reportActionReceipt: (conversationId: string, receipt: import('@/types/assistant').UiActionReceipt) =>
    http.post<import('@/types/assistant').UiActionReceipt>(
      `/api/assistant/conversations/${conversationId}/actions/receipt`,
      receipt,
      assistantRequest,
    ).then((response) => response.data),
  cancelTurn: (conversationId: string, turnId: string) =>
    http.post<AssistantConversation>(
      `/api/assistant/conversations/${conversationId}/turns/${turnId}/cancel`,
      {}, assistantRequest,
    ).then((response) => response.data),
  subscribeEvents: (conversationId: string, options?: AssistantEventStreamOptions) =>
    subscribeAssistantEvents(conversationId, options),
  listAutomationRules: () =>
    http.get<AutomationRule[]>('/api/assistant/automation-rules')
      .then((response) => response.data),
  createAutomationRule: (body: {
    type: AutomationRuleType
    timezone: string
    localTime: string
    enabled: boolean
  }) => http.post<AutomationRule>('/api/assistant/automation-rules', body)
    .then((response) => response.data),
  updateAutomationRule: (id: string, body: {
    enabled?: boolean
    timezone?: string
    localTime?: string
  }) => http.patch<AutomationRule>(`/api/assistant/automation-rules/${id}`, body)
    .then((response) => response.data),
  deleteAutomationRule: (id: string) =>
    http.delete(`/api/assistant/automation-rules/${id}`),
  getAutomationSettings: () =>
    http.get<AutomationSettings>('/api/assistant/automation-settings')
      .then((response) => response.data),
  updateAutomationSettings: (body: { paused: boolean }) =>
    http.patch<AutomationSettings>('/api/assistant/automation-settings', body)
      .then((response) => response.data),
  getAssistantHealth: () =>
    http.get<AssistantHealth>('/api/assistant/health')
      .then((response) => response.data),
}
