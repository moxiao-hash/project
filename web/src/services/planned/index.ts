import type { AgentGateway } from '@/types/agent'
import { MockAgentGateway } from './mock'
import { HttpAgentGateway } from './httpGateway'

/**
 * Agent 门面选择器。
 * - http（默认）：调用已完成的 Java /api/agent/** 门面。
 * - mock：只供显式离线演示或测试注入，数据不落库。
 * 页面只依赖 AgentGateway 接口，不感知具体实现。
 */
export const gatewayMode: 'mock' | 'http' =
  import.meta.env.VITE_AGENT_GATEWAY === 'mock' ? 'mock' : 'http'

export const agentGateway: AgentGateway =
  gatewayMode === 'http' ? new HttpAgentGateway() : new MockAgentGateway()
