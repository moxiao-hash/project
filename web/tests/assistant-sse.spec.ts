import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  assistantApi,
  type AssistantEvent,
  type EventStreamStatus,
} from '@/services/current/assistant'
import { setUnauthorizedHandler, TOKEN_STORAGE_KEY } from '@/services/http'

function createMockStream(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  let index = 0
  return new ReadableStream({
    pull(controller) {
      if (index < chunks.length) {
        controller.enqueue(encoder.encode(chunks[index++]))
      } else {
        controller.close()
      }
    },
  })
}

describe('Assistant SSE 事件流解析 (assistantApi.subscribeEvents)', () => {
  let originalFetch: typeof globalThis.fetch

  beforeEach(() => {
    sessionStorage.clear()
    originalFetch = globalThis.fetch
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
    setUnauthorizedHandler(null)
    vi.restoreAllMocks()
  })

  it('发送正确的 Accept 与 Authorization 认证头', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'test-jwt-token')
    let capturedUrl = ''
    let capturedHeaders: Record<string, string> = {}

    globalThis.fetch = vi.fn().mockImplementation(async (url: string | URL | Request, init?: RequestInit) => {
      capturedUrl = String(url)
      capturedHeaders = (init?.headers as Record<string, string>) || {}
      return {
        ok: true,
        status: 200,
        headers: new Headers({ 'Content-Type': 'text/event-stream' }),
        body: createMockStream([': heartbeat\n\n']),
      } as unknown as Response
    })

    const onHeartbeat = vi.fn()
    const controller = assistantApi.subscribeEvents('conv-1', {
      onHeartbeat,
      autoReconnect: false,
    })

    await new Promise((resolve) => setTimeout(resolve, 50))
    controller.close()

    expect(capturedUrl).toContain('/api/assistant/conversations/conv-1/events')
    expect(capturedHeaders['Accept']).toBe('text/event-stream')
    expect(capturedHeaders['Authorization']).toBe('Bearer test-jwt-token')
    expect(onHeartbeat).toHaveBeenCalled()
  })

  it('指定 lastEventId 时，请求头附带 Last-Event-ID', async () => {
    let capturedHeaders: Record<string, string> = {}
    globalThis.fetch = vi.fn().mockImplementation(async (_url: unknown, init?: RequestInit) => {
      capturedHeaders = (init?.headers as Record<string, string>) || {}
      return {
        ok: true,
        status: 200,
        headers: new Headers({ 'Content-Type': 'text/event-stream' }),
        body: createMockStream(['']),
      } as unknown as Response
    })

    const controller = assistantApi.subscribeEvents('conv-1', {
      lastEventId: 42,
      autoReconnect: false,
    })

    await new Promise((resolve) => setTimeout(resolve, 50))
    controller.close()

    expect(capturedHeaders['Last-Event-ID']).toBe('42')
  })

  it('正确解析分片传输（跨 chunk 边界）与真实后端帧格式', async () => {
    const chunks = [
      'id: 1\nevent: TURN_STARTED\ndata: {"conversationId":"conv-1","sequence":1,"type":"TURN_STARTED","payload":{"turnId":"t-101"}}\n\n',
      'id: 2\nevent: ASSISTANT_DELTA\nda',
      'ta: {"conversationId":"conv-1","sequence":2,"type":"ASSISTANT_DELTA","payload":{"turnId":"t-101","index":0,"delta":"你好，"}}\n\n',
      ': heartbeat\n\n',
      'id: 3\nevent: ASSISTANT_DELTA\ndata: {"conversationId":"conv-1","sequence":3,"type":"ASSISTANT_DELTA","payload":{"turnId":"t-101","index":1,"delta":"我是 StudyPilot"}}\n\n',
      'id: 4\nevent: TURN_COMPLETED\ndata: {"conversationId":"conv-1","sequence":4,"type":"TURN_COMPLETED","payload":{"turnId":"t-101","reply":"你好，我是 StudyPilot"}}\n\n',
    ]

    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Type': 'text/event-stream' }),
      body: createMockStream(chunks),
    } as unknown as Response)

    const receivedEvents: AssistantEvent[] = []
    const onHeartbeat = vi.fn()
    const controller = assistantApi.subscribeEvents('conv-1', {
      onEvent: (event) => receivedEvents.push(event),
      onHeartbeat,
      autoReconnect: false,
    })

    await new Promise((resolve) => setTimeout(resolve, 100))
    controller.close()

    expect(receivedEvents).toHaveLength(4)
    expect(onHeartbeat).toHaveBeenCalled()
    expect(receivedEvents[0].sequence).toBe(1)
    expect(receivedEvents[1].sequence).toBe(2)
    expect(receivedEvents[2].sequence).toBe(3)
    expect(receivedEvents[3].sequence).toBe(4)
    expect(controller.getLastEventId()).toBe(4)
  })

  it('严格拒绝非法的帧：格式错误的 JSON、未知事件类型、类型不匹配、会话 ID 不匹配或 ID 与 sequence 不一致', async () => {
    const corruptedChunks = [
      // 1. 合法事件 1
      'id: 1\nevent: TURN_STARTED\ndata: {"conversationId":"conv-1","sequence":1,"type":"TURN_STARTED","payload":{"turnId":"t-1"}}\n\n',
      // 2. 格式错误的 JSON（应拒绝且游标不前进至 2）
      'id: 2\nevent: ASSISTANT_DELTA\ndata: {bad json\n\n',
      // 3. 未知事件类型（应拒绝且游标不前进至 3）
      'id: 3\nevent: UNKNOWN_MALICIOUS_EVENT\ndata: {"conversationId":"conv-1","sequence":3,"type":"UNKNOWN_MALICIOUS_EVENT","payload":{}}\n\n',
      // 4. event 声明与 payload.type 不匹配（应拒绝且游标不前进至 4）
      'id: 4\nevent: TURN_COMPLETED\ndata: {"conversationId":"conv-1","sequence":4,"type":"ASSISTANT_DELTA","payload":{}}\n\n',
      // 5. conversationId 与当前会话不匹配（应拒绝且游标不前进至 5）
      'id: 5\nevent: TURN_COMPLETED\ndata: {"conversationId":"conv-other","sequence":5,"type":"TURN_COMPLETED","payload":{}}\n\n',
      // 6. frame id 与 data.sequence 不一致（应拒绝且游标不前进至 6）
      'id: 6\nevent: TURN_COMPLETED\ndata: {"conversationId":"conv-1","sequence":999,"type":"TURN_COMPLETED","payload":{}}\n\n',
      // 7. 合法事件 7（验证合法事件仍能正常接收，游标推进至 7）
      'id: 7\nevent: TURN_COMPLETED\ndata: {"conversationId":"conv-1","sequence":7,"type":"TURN_COMPLETED","payload":{"turnId":"t-1","reply":"已完成"}}\n\n',
    ]

    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Type': 'text/event-stream' }),
      body: createMockStream(corruptedChunks),
    } as unknown as Response)

    const events: AssistantEvent[] = []
    const controller = assistantApi.subscribeEvents('conv-1', {
      onEvent: (event) => events.push(event),
      autoReconnect: false,
    })

    await new Promise((resolve) => setTimeout(resolve, 100))
    controller.close()

    expect(events.map((e) => e.sequence)).toEqual([1, 7])
    expect(controller.getLastEventId()).toBe(7)
  })

  it('支持有界退避重连与连接状态转换回调 (connecting -> connected -> reconnecting -> disconnected)', async () => {
    let callCount = 0
    const statuses: EventStreamStatus[] = []

    globalThis.fetch = vi.fn().mockImplementation(async () => {
      callCount++
      if (callCount === 1) {
        return {
          ok: true,
          status: 200,
          headers: new Headers({ 'Content-Type': 'text/event-stream' }),
          body: createMockStream([
            'id: 1\nevent: TURN_STARTED\ndata: {"conversationId":"conv-1","sequence":1,"type":"TURN_STARTED","payload":{}}\n\n',
          ]),
        } as unknown as Response
      } else {
        throw new Error('网络暂时中断')
      }
    })

    const controller = assistantApi.subscribeEvents('conv-1', {
      autoReconnect: true,
      reconnectIntervalMs: 20,
      maxReconnectIntervalMs: 50,
      maxReconnectAttempts: 2,
      onStatusChange: (status) => statuses.push(status),
    })

    await new Promise((resolve) => setTimeout(resolve, 150))
    controller.close()

    expect(statuses).toContain('connecting')
    expect(statuses).toContain('connected')
    expect(statuses).toContain('reconnecting')
    expect(statuses).toContain('disconnected')
  })

  it('401 响应时触发共享 auth 清理回调并立即断开连接，不进行无谓重连', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'expired-token')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)

    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      statusText: 'Unauthorized',
      headers: new Headers(),
      body: null,
    } as unknown as Response)

    const onError = vi.fn()
    const controller = assistantApi.subscribeEvents('conv-1', {
      onError,
      autoReconnect: true,
      reconnectIntervalMs: 20,
    })

    await new Promise((resolve) => setTimeout(resolve, 60))
    controller.close()

    expect(onUnauthorized).toHaveBeenCalled()
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(globalThis.fetch).toHaveBeenCalledTimes(1)
  })
})
