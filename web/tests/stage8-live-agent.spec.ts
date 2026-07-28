import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { http, describeError } from '@/services/http'
import { HttpAgentGateway } from '@/services/planned/httpGateway'
import { buildPlanRevisionMessage } from '@/services/planned/planRevision'
import type { PlanDraft } from '@/types/agent'
import gatewaySelectorSource from '@/services/planned/index.ts?raw'
import appShellSource from '@/components/AppShell.vue?raw'

function config(): InternalAxiosRequestConfig {
  return { headers: new AxiosHeaders() } as InternalAxiosRequestConfig
}

describe('阶段 8 真实 Agent HTTP 门面', () => {
  it('未显式指定 mock 时默认使用真实 HTTP 门面', () => {
    expect(gatewaySelectorSource).toContain(
      "import.meta.env.VITE_AGENT_GATEWAY === 'mock' ? 'mock' : 'http'",
    )
  })

  it('真实门面模式不会在导航中误标 Mock', () => {
    expect(appShellSource).toContain("item.mock && gatewayMode === 'mock'")
  })

  it('三类会话都能通过 GET 恢复，并使用 120 秒超时', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: { conversationId: 'c-1' } })
    const gateway = new HttpAgentGateway()

    await gateway.getPlanConversation('plan-1')
    await gateway.getTaskConversation('task-1')
    await gateway.getKnowledgeConversation('knowledge-1')

    expect(get).toHaveBeenNthCalledWith(
      1,
      '/api/agent/plan-conversations/plan-1',
      { timeout: 120_000 },
    )
    expect(get).toHaveBeenNthCalledWith(
      2,
      '/api/agent/task-conversations/task-1',
      { timeout: 120_000 },
    )
    expect(get).toHaveBeenNthCalledWith(
      3,
      '/api/agent/knowledge-conversations/knowledge-1',
      { timeout: 120_000 },
    )
  })

  it('生成请求使用 Agent 专属 120 秒超时', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: { conversationId: 'c-1' } })

    await new HttpAgentGateway().createKnowledgeConversation('AUTO')

    expect(post).toHaveBeenCalledWith(
      '/api/agent/knowledge-conversations',
      { mode: 'AUTO' },
      { timeout: 120_000 },
    )
  })

  it('兼容 Python 计划草稿中的 snake_case 字段', async () => {
    vi.spyOn(http, 'get').mockResolvedValueOnce({
      data: {
        conversationId: 'plan-1',
        status: 'DRAFT_READY',
        draft: {
          title: '学习计划',
          start_date: '2026-07-29',
          end_date: '2026-08-04',
          tasks: [{
            title: '学习 IoC',
            scheduled_date: '2026-07-29',
            estimated_minutes: 60,
          }],
        },
      },
    })

    const result = await new HttpAgentGateway().getPlanConversation('plan-1')

    expect(result.draft).toMatchObject({
      startDate: '2026-07-29',
      endDate: '2026-08-04',
      tasks: [{ scheduledDate: '2026-07-29', estimatedMinutes: 60 }],
    })
  })
})

describe('Agent 错误呈现', () => {
  it('保留 Java 门面返回的 detail', () => {
    const error = new AxiosError('failed', '503', config(), {}, {
      status: 503,
      statusText: 'Service Unavailable',
      headers: {},
      config: config(),
      data: { detail: 'DeepSeek Key 尚未配置' },
    })

    expect(describeError(error)).toBe('DeepSeek Key 尚未配置')
  })

  it('429 显示 Retry-After', () => {
    const error = new AxiosError('limited', '429', config(), {}, {
      status: 429,
      statusText: 'Too Many Requests',
      headers: { 'retry-after': '12' },
      config: config(),
      data: { detail: '请求过于频繁' },
    })

    expect(describeError(error)).toBe('请求过于频繁（请在 12 秒后重试）')
  })

  it('标准 ApiError 的 429 同样显示 Retry-After', () => {
    const error = new AxiosError('limited', '429', config(), {}, {
      status: 429,
      statusText: 'Too Many Requests',
      headers: { 'retry-after': '8' },
      config: config(),
      data: {
        status: 429,
        error: 'Too Many Requests',
        message: '模型请求达到限额',
        path: '/api/agent/knowledge-conversations/c/messages',
      },
    })

    expect(describeError(error)).toBe('模型请求达到限额（请在 8 秒后重试）')
  })
})

describe('计划草稿结构化修订', () => {
  const original: PlanDraft = {
    title: 'Java 学习计划',
    startDate: '2026-07-29',
    endDate: '2026-08-04',
    tasks: [
      {
        title: '学习依赖注入',
        scheduledDate: '2026-07-29',
        estimatedMinutes: 60,
      },
    ],
  }

  it('只把真实变更生成为确定性的自然语言消息', () => {
    const edited: PlanDraft = structuredClone(original)
    edited.title = 'Spring AI 学习计划'
    edited.tasks[0]!.estimatedMinutes = 90

    expect(buildPlanRevisionMessage(original, edited)).toBe(
      '请按以下明确修改重新生成完整计划草案：\n' +
      '1. 将计划标题从“Java 学习计划”改为“Spring AI 学习计划”。\n' +
      '2. 将第 1 个任务“学习依赖注入”的预计时长从 60 分钟改为 90 分钟。',
    )
  })

  it('没有修改时不生成修订消息', () => {
    expect(buildPlanRevisionMessage(original, structuredClone(original))).toBeNull()
  })
})
