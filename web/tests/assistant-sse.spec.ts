import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  assistantApi,
  type AssistantEvent,
  type AssistantEventStreamController,
} from '@/services/current/assistant'
import { TOKEN_STORAGE_KEY } from '@/services/http'

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

  it('正确解析分片传输（跨 chunk 边界）与包含标准 id/event/data 的事件流', async () => {
    // 模拟 chunk 分割：第二个事件在 "da" 和 "ta: ..." 之间被拆断
    const chunks = [
      'id: 1\nevent: TURN_STARTED\ndata: {"conversationId":"conv-1","sequence":1,"type":"TURN_STARTED","payload":{"turnId":"t-101"}}\n\n',
      'id: 2\nevent: ASSISTANT_DELTA\nda',
      'ta: {"conversationId":"conv-1","sequence":2,"type":"ASSISTANT_DELTA","payload":{"turnId":"t-101","index":0,"delta":"你好，"}}\n\n',
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
    const controller = assistantApi.subscribeEvents('conv-1', {
      onEvent: (event) => receivedEvents.push(event),
      autoReconnect: false,
    })

    await new Promise((resolve) => setTimeout(resolve, 100))
    controller.close()

    expect(receivedEvents).toHaveLength(4)
    expect(receivedEvents[0]).toEqual({
      sequence: 1,
      type: 'TURN_STARTED',
      conversationId: 'conv-1',
      payload: { turnId: 't-101' },
    })
    expect(receivedEvents[1]).toEqual({
      sequence: 2,
      type: 'ASSISTANT_DELTA',
      conversationId: 'conv-1',
      payload: { turnId: 't-101', index: 0, delta: '你好，' },
    })
    expect(receivedEvents[2]).toEqual({
      sequence: 3,
      type: 'ASSISTANT_DELTA',
      conversationId: 'conv-1',
      payload: { turnId: 't-101', index: 1, delta: '我是 StudyPilot' },
    })
    expect(receivedEvents[3]).toEqual({
      sequence: 4,
      type: 'TURN_COMPLETED',
      conversationId: 'conv-1',
      payload: { turnId: 't-101', reply: '你好，我是 StudyPilot' },
    })
    expect(controller.getLastEventId()).toBe(4)
  })

  it('支持结构化 TOOL_*、ACTION_PREVIEW、UI_ACTION 与 TURN_CANCELLED 事件解析', async () => {
    const streamContent = [
      'id: 10\nevent: TOOL_STARTED\ndata: {"conversationId":"conv-1","sequence":10,"type":"TOOL_STARTED","payload":{"toolName":"learning.context.get"}}\n\n',
      'id: 11\nevent: TOOL_SUCCEEDED\ndata: {"conversationId":"conv-1","sequence":11,"type":"TOOL_SUCCEEDED","payload":{"toolName":"learning.context.get","summary":"已获取学习上下文"}}\n\n',
      'id: 12\nevent: ACTION_PREVIEW\ndata: {"conversationId":"conv-1","sequence":12,"type":"ACTION_PREVIEW","payload":{"turnId":"t-1","actionId":"act-99","summary":"调整每日时长","riskLevel":"HIGH"}}\n\n',
      'id: 13\nevent: UI_ACTION\ndata: {"conversationId":"conv-1","sequence":13,"type":"UI_ACTION","payload":{"turnId":"t-1","type":"NAVIGATE","routeKey":"ROADMAP","params":{},"reason":"查看路线"}}\n\n',
      'id: 14\nevent: TURN_CANCELLED\ndata: {"conversationId":"conv-1","sequence":14,"type":"TURN_CANCELLED","payload":{"turnId":"t-1","reason":"CANCEL_REQUESTED"}}\n\n',
    ].join('')

    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Type': 'text/event-stream' }),
      body: createMockStream([streamContent]),
    } as unknown as Response)

    const events: AssistantEvent[] = []
    const controller = assistantApi.subscribeEvents('conv-1', {
      onEvent: (event) => events.push(event),
      autoReconnect: false,
    })

    await new Promise((resolve) => setTimeout(resolve, 80))
    controller.close()

    expect(events.map((e) => e.type)).toEqual([
      'TOOL_STARTED',
      'TOOL_SUCCEEDED',
      'ACTION_PREVIEW',
      'UI_ACTION',
      'TURN_CANCELLED',
    ])
    expect((events[2].payload as { actionId: string }).actionId).toBe('act-99')
    expect((events[3].payload as { routeKey: string }).routeKey).toBe('ROADMAP')
    expect((events[4].payload as { reason: string }).reason).toBe('CANCEL_REQUESTED')
  })

  it('网络中断时支持使用最后接收到的 sequence 发起自动重连', async () => {
    let callCount = 0
    const capturedLastEventIds: Array<string | undefined> = []

    globalThis.fetch = vi.fn().mockImplementation(async (_url: unknown, init?: RequestInit) => {
      callCount++
      const headers = (init?.headers as Record<string, string>) || {}
      capturedLastEventIds.push(headers['Last-Event-ID'])

      if (callCount === 1) {
        // 第一次连接返回 1 个事件后流中断
        return {
          ok: true,
          status: 200,
          headers: new Headers({ 'Content-Type': 'text/event-stream' }),
          body: createMockStream([
            'id: 15\nevent: TURN_STARTED\ndata: {"sequence":15,"type":"TURN_STARTED","payload":{"turnId":"t-reconnect"}}\n\n',
          ]),
        } as unknown as Response
      } else {
        // 第二次连接成功并返回后续事件
        return {
          ok: true,
          status: 200,
          headers: new Headers({ 'Content-Type': 'text/event-stream' }),
          body: createMockStream([
            'id: 16\nevent: TURN_COMPLETED\ndata: {"sequence":16,"type":"TURN_COMPLETED","payload":{"turnId":"t-reconnect","reply":"已恢复"}}\n\n',
          ]),
        } as unknown as Response
      }
    })

    const events: AssistantEvent[] = []
    let controller: AssistantEventStreamController | null = null

    controller = assistantApi.subscribeEvents('conv-1', {
      lastEventId: 14,
      autoReconnect: true,
      reconnectIntervalMs: 20,
      onEvent: (event) => events.push(event),
    })

    // 等待初次连接与自动重连完成
    await new Promise((resolve) => setTimeout(resolve, 150))
    controller.close()

    expect(callCount).toBeGreaterThanOrEqual(2)
    expect(capturedLastEventIds[0]).toBe('14')
    expect(capturedLastEventIds[1]).toBe('15')
    expect(events.map((e) => e.sequence)).toEqual([15, 16])
  })

  it('401 未认证响应时清除 Token 并通知错误，不触发无谓重连', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'invalid-token')
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

    expect(onError).toHaveBeenCalled()
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    // 401 停止重连，只调用一次 fetch
    expect(globalThis.fetch).toHaveBeenCalledTimes(1)
  })
})
