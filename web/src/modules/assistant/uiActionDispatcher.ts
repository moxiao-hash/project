import type { AssistantUiAction } from '@/types/assistant'

interface RouterLike {
  push(location: { name: string; params?: Record<string, string> }): Promise<unknown> | unknown
}

interface RouteDefinition {
  name: string
  params: Record<string, string>
}

const routes: Record<string, RouteDefinition> = {
  DASHBOARD: { name: 'dashboard', params: {} },
  ROADMAP: { name: 'roadmap', params: {} },
  ROADMAP_STAGE: { name: 'roadmap-stage', params: { stageId: 'id' } },
  ROADMAP_MODULE: { name: 'roadmap-module', params: { moduleId: 'id' } },
  ROADMAP_NODE: { name: 'roadmap-node', params: { nodeId: 'id' } },
  LEARNING_GOALS: { name: 'goals', params: {} },
  LEARNING_PLANS: { name: 'plans', params: {} },
  LEARNING_PLAN: { name: 'plan-detail', params: { planId: 'id' } },
  TODAY: { name: 'today', params: {} },
  MATERIALS: { name: 'materials', params: {} },
  MATERIAL_DETAIL: { name: 'material-detail', params: { materialId: 'id' } },
  QUIZ: { name: 'quiz', params: { quizId: 'id' } },
  QUIZ_ATTEMPT: { name: 'attempt', params: { attemptId: 'id' } },
  WRONG_QUESTIONS: { name: 'wrong-questions', params: {} },
  MASTERY: { name: 'mastery', params: {} },
  KNOWLEDGE: { name: 'knowledge', params: {} },
  PLAN_ASSISTANT: { name: 'agent-plan', params: {} },
  TASK_ASSISTANT: { name: 'agent-tasks', params: {} },
  NOTIFICATIONS: { name: 'notifications', params: {} },
  AGENT_ACTIVITY: { name: 'activity', params: {} },
  LEARNING_SETTINGS: { name: 'settings', params: {} },
  AI_SETTINGS: { name: 'settings-ai', params: {} },
  WORKSPACE_ARTIFACTS: { name: 'workspace-artifacts', params: {} },
}

const safeId = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/

/** 执行后端已经校验过的动作，但前端仍使用独立白名单进行纵深防御。 */
export async function dispatchUiAction(action: AssistantUiAction, router: RouterLike) {
  if (action.type !== 'NAVIGATE') throw new Error('暂不支持该界面动作')
  const definition = routes[action.routeKey]
  if (!definition) throw new Error('不受支持的页面动作')
  const inputKeys = Object.keys(action.params)
  const requiredKeys = Object.keys(definition.params)
  if (inputKeys.length !== requiredKeys.length || inputKeys.some((key) => !requiredKeys.includes(key))) {
    throw new Error('页面动作参数不合法')
  }
  const params: Record<string, string> = {}
  for (const [source, target] of Object.entries(definition.params)) {
    const value = action.params[source]
    if (!value || !safeId.test(value)) throw new Error('页面动作参数不合法')
    params[target] = value
  }
  await router.push(requiredKeys.length > 0
    ? { name: definition.name, params }
    : { name: definition.name })
}
