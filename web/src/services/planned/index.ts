import type { AgentGateway } from '@/types/agent'
import { MockAgentGateway } from './mock'
import { HttpAgentGateway } from './httpGateway'

/**
 * Agent 门面选择器。
 * - mock（默认）：阶段 8 Java 门面就绪前使用，页面完整可用但数据不落库。
 * - http：门面就绪后切换，调用真实 /api/agent/**。
 * 页面只依赖 AgentGateway 接口，不感知具体实现。
 */
export const gatewayMode: 'mock' | 'http' =
  import.meta.env.VITE_AGENT_GATEWAY === 'http' ? 'http' : 'mock'

export const agentGateway: AgentGateway =
  gatewayMode === 'http' ? new HttpAgentGateway() : new MockAgentGateway()
