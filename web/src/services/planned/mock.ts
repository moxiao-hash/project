import type {
  AdjustmentOperation,
  AgentGateway,
  AiSettings,
  Citation,
  KnowledgeConversation,
  KnowledgeMode,
  PlanAdjustment,
  PlanConversation,
  PlanDraft,
  TaskCandidate,
  TaskConversation,
  WebSearchPreference,
} from '@/types/agent'

const delay = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

function uuid(): string {
  return typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `mock-${Math.random().toString(36).slice(2)}`
}

function plusDays(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return d.toISOString().slice(0, 10)
}

interface MockPlanState extends PlanConversation {
  messageCount: number
}

interface MockTaskState extends TaskConversation {
  confirmed: boolean
}

interface MockAdjustmentState extends PlanAdjustment {
  analyzedTicks: number
  confirmed: boolean
}

/**
 * Agent 门面的离线 Mock 实现，仅在显式配置时使用。
 * 严格遵守说明书状态机：草稿必须先 DRAFT_READY / PREVIEW_READY，
 * 只有专用 confirm 方法能推进到保存/执行；自然语言消息永远不会触发写操作。
 */
export class MockAgentGateway implements AgentGateway {
  private planConversations = new Map<string, MockPlanState>()
  private taskConversations = new Map<string, MockTaskState>()
  private knowledgeConversations = new Map<string, KnowledgeConversation>()
  private adjustments = new Map<string, MockAdjustmentState>()
  private aiSettings: AiSettings = {
    modelProvider: 'deepseek',
    modelName: 'deepseek-chat',
    deepseek: {
      configured: true,
      source: 'SERVER_DEFAULT',
      maskedSuffix: 'a1b2',
      available: true,
    },
    tavily: {
      configured: false,
      source: 'NONE',
      maskedSuffix: null,
      available: false,
    },
    deepseekConfigured: true,
    deepseekMaskedSuffix: 'a1b2',
    tavilyConfigured: false,
    tavilyMaskedSuffix: null,
  }

  // ---------- 学习计划对话 ----------

  async createPlanConversation(goalId: string): Promise<PlanConversation> {
    await delay(500)
    const state: MockPlanState = {
      conversationId: uuid(),
      goalId,
      status: 'COLLECTING',
      reply:
        '你好，我是计划助手。请告诉我你的目标背景、当前基础和偏好的学习节奏，' +
        '我会据此起草一份学习计划草案。',
      draft: null,
      savedPlanId: null,
      error: null,
      citations: [],
      warnings: [],
      messageCount: 0,
    }
    this.planConversations.set(state.conversationId, state)
    return { ...state }
  }

  async sendPlanMessage(id: string, message: string): Promise<PlanConversation> {
    await delay(800)
    const state = this.require(this.planConversations, id, '计划对话不存在')
    if (state.status === 'COMPLETED') {
      return { ...state, reply: '该计划已经保存，如需调整请前往计划详情页。' }
    }
    state.messageCount += 1
    if (state.messageCount === 1) {
      state.status = 'COLLECTING'
      state.reply =
        '了解了。我还需要确认：你希望计划覆盖多长时间？每天大概能投入多少分钟？' +
        '回复后我会生成完整的计划草案。'
    } else {
      state.status = 'DRAFT_READY'
      state.draft = this.buildDraft(message)
      state.reply =
        '已根据你的描述生成计划草案。你可以在右侧表单中检查任务安排；' +
        '需要调整时直接告诉我（例如「把周三的任务改到周四」），我会返回新的完整草案。' +
        '确认无误后，请点击「保存计划」按钮正式写入。'
      state.citations = []
      state.warnings = []
    }
    return { ...state }
  }

  async getPlanConversation(id: string): Promise<PlanConversation> {
    return { ...this.require(this.planConversations, id, '计划对话不存在') }
  }

  async confirmPlan(id: string): Promise<PlanConversation> {
    await delay(700)
    const state = this.require(this.planConversations, id, '计划对话不存在')
    if (state.status !== 'DRAFT_READY' || !state.draft) {
      return { ...state, error: '当前没有可保存的计划草案' }
    }
    state.status = 'SAVING'
    // Mock：模拟保存过程；真实实现中 Java 会写入业务库并返回 savedPlanId。
    state.status = 'COMPLETED'
    state.savedPlanId = `mock-plan-${state.conversationId.slice(0, 8)}`
    state.reply = '计划已保存（Mock）。联调后此处会跳转到你真实的计划详情页。'
    return { ...state }
  }

  private buildDraft(message: string): PlanDraft {
    const start = plusDays(1)
    return {
      title: '自动生成的学习计划（Mock）',
      startDate: start,
      endDate: plusDays(28),
      tasks: [
        { title: '基础概念学习：' + message.slice(0, 12), scheduledDate: start, estimatedMinutes: 45 },
        { title: '配套练习与笔记整理', scheduledDate: plusDays(2), estimatedMinutes: 60 },
        { title: '阶段复习与自测', scheduledDate: plusDays(4), estimatedMinutes: 30 },
      ],
    }
  }

  // ---------- 每日任务 Agent ----------

  async createTaskConversation(targetDate: string): Promise<TaskConversation> {
    await delay(500)
    const candidates: TaskCandidate[] = [
      { id: 'mock-task-1', title: '阅读第 3 章并做笔记', status: 'TODO', version: 1 },
      { id: 'mock-task-2', title: '完成 10 道练习题', status: 'TODO', version: 2 },
    ]
    const state: MockTaskState = {
      conversationId: uuid(),
      targetDate,
      status: 'COLLECTING',
      reply:
        `这是 ${targetDate} 的候选任务。你可以对我说「完成第一个任务」「跳过练习」` +
        '或「把任务延期到明天」，我会生成操作预览，确认后才会真正执行。',
      candidateTasks: candidates,
      actionDraft: null,
      executionId: null,
      updatedTask: null,
      error: null,
      confirmed: false,
    }
    this.taskConversations.set(state.conversationId, state)
    return { ...state }
  }

  async sendTaskMessage(id: string, message: string): Promise<TaskConversation> {
    await delay(800)
    const state = this.require(this.taskConversations, id, '任务对话不存在')
    if (state.status === 'COMPLETED') {
      return { ...state, reply: '该对话的操作已执行完毕。' }
    }
    const target = state.candidateTasks[0]
    if (!target) {
      return { ...state, reply: '当前没有可操作的候选任务。' }
    }
    const wantsDefer = /延期|推迟|明天/.test(message)
    const wantsSkip = /跳过|skip/i.test(message)
    state.status = 'PREVIEW_READY'
    state.actionDraft = {
      targetStatus: wantsDefer ? 'DEFERRED' : wantsSkip ? 'SKIPPED' : 'COMPLETED',
      taskId: target.id,
      taskTitle: target.title,
      expectedVersion: target.version,
      reason: wantsSkip ? message.slice(0, 50) : null,
      deferredTo: wantsDefer ? plusDays(1) : null,
      actualMinutes: wantsDefer || wantsSkip ? null : 45,
    }
    state.reply =
      '我理解了你的意图，操作预览如下。请核对任务、动作和影响；' +
      '只有点击下方「确认执行」按钮才会真正修改任务。'
    return { ...state }
  }

  async getTaskConversation(id: string): Promise<TaskConversation> {
    return { ...this.require(this.taskConversations, id, '任务对话不存在') }
  }

  async confirmTaskAction(id: string): Promise<TaskConversation> {
    await delay(700)
    const state = this.require(this.taskConversations, id, '任务对话不存在')
    if (state.status !== 'PREVIEW_READY' || !state.actionDraft) {
      return { ...state, error: '当前没有待确认的操作预览' }
    }
    const draft = state.actionDraft
    state.status = 'EXECUTING'
    state.executionId = `mock-exec-${uuid().slice(0, 8)}`
    state.updatedTask = {
      id: draft.taskId,
      planId: 'mock-plan',
      title: draft.taskTitle,
      scheduledDate: draft.deferredTo ?? state.targetDate,
      estimatedMinutes: 45,
      status: draft.targetStatus,
      version: draft.expectedVersion + 1,
      completedAt:
        draft.targetStatus === 'COMPLETED' ? new Date().toISOString() : null,
      actualMinutes: draft.actualMinutes,
      taskKind: 'LEARNING',
      knowledgePoint: null,
      sourceAttemptId: null,
    }
    state.status = 'COMPLETED'
    state.confirmed = true
    state.reply = '操作已执行（Mock）。联调后任务的真实状态会以 Java 返回为准。'
    return { ...state }
  }

  // ---------- 知识问答 ----------

  async createKnowledgeConversation(mode: KnowledgeMode): Promise<KnowledgeConversation> {
    await delay(400)
    const conv: KnowledgeConversation = {
      conversationId: uuid(),
      mode,
      status: 'ACTIVE',
      answer: '',
      retrievalMode: mode === 'LOCAL_ONLY' ? 'LOCAL' : 'HYBRID',
      citations: [],
      warnings:
        mode === 'AUTO'
          ? ['联网搜索服务（Tavily）未配置，当前仅能检索本地资料库。']
          : [],
    }
    this.knowledgeConversations.set(conv.conversationId, conv)
    return { ...conv }
  }

  async sendKnowledgeMessage(
    id: string,
    message: string,
    webSearch: WebSearchPreference,
  ): Promise<KnowledgeConversation> {
    await delay(900)
    const conv = this.require(this.knowledgeConversations, id, '知识问答会话不存在')
    const localCitation: Citation = {
      sourceType: 'MATERIAL',
      title: 'Spring Boot 官方笔记（本地资料）',
      snippet: 'Spring Boot 3.x 要求 Java 17 及以上版本……',
      materialId: 'mock-material-1',
      locator: '第 2 节 · 段落 3',
      resultId: null,
      url: null,
    }
    const webCitation: Citation = {
      sourceType: 'WEB',
      title: 'Spring Boot 官方文档 - System Requirements',
      snippet: 'Spring Boot 3.5.x requires Java 17 and is compatible up to Java 24.',
      materialId: null,
      locator: null,
      resultId: `mock-web-result-${uuid().slice(0, 8)}`,
      url: 'https://docs.spring.io/spring-boot/system-requirements.html',
    }

    const useWeb =
      conv.mode === 'AUTO' && webSearch !== 'DISABLED'
    conv.citations = useWeb ? [localCitation, webCitation] : [localCitation]
    conv.warnings = []
    if (conv.mode === 'LOCAL_ONLY' && webSearch === 'ENABLED') {
      conv.warnings.push('当前会话为仅本地模式，已忽略联网搜索请求。')
    }
    if (conv.mode === 'AUTO' && useWeb) {
      conv.warnings.push('联网搜索服务（Tavily）未配置，以下来源为本地检索结果（Mock 演示）。')
    }
    conv.retrievalMode = useWeb ? 'HYBRID' : 'LOCAL'
    conv.answer =
      `关于「${message.slice(0, 40)}」：根据检索到的来源，Spring Boot 3.x 推荐使用 ` +
      'Java 17 或更高版本。请注意这是 Mock 回答，联调后将由 RAG 管线基于你的真实资料生成。'
    return { ...conv }
  }

  async getKnowledgeConversation(id: string): Promise<KnowledgeConversation> {
    return { ...this.require(this.knowledgeConversations, id, '知识会话不存在') }
  }

  async importWebResult(
    resultId: string,
    _category: string,
    _privacyLevel: string,
  ): Promise<{ materialId: string }> {
    await delay(600)
    return { materialId: `mock-material-from-${resultId}` }
  }

  // ---------- 计划调整 ----------

  async analyzePlanAdjustment(analysisDate: string): Promise<PlanAdjustment> {
    await delay(600)
    const state: MockAdjustmentState = {
      id: uuid(),
      planId: 'mock-plan-1',
      analysisDate,
      triggerType: 'USER_REQUEST',
      signals: ['OVERDUE_TASKS', 'TIME_ESTIMATE_BIAS'],
      summary:
        '检测到 2 个逾期任务，且近期任务实际用时普遍超出预估约 40%。' +
        '建议重新排期并上调后续任务的预估时长。',
      operations: this.buildOperations(),
      riskLevel: 'HIGH',
      status: 'ANALYZING',
      executionId: null,
      beforePlanVersion: 3,
      afterPlanVersion: null,
      error: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      analyzedTicks: 0,
      confirmed: false,
    }
    this.adjustments.set(state.id, state)
    return { ...state }
  }

  async getPlanAdjustment(id: string): Promise<PlanAdjustment> {
    await delay(300)
    const state = this.require(this.adjustments, id, '调整分析不存在')
    if (state.status === 'ANALYZING') {
      state.analyzedTicks += 1
      if (state.analyzedTicks >= 1) {
        state.status = state.operations.length > 0 ? 'DRAFT_READY' : 'NO_CHANGE'
        state.updatedAt = new Date().toISOString()
      }
    }
    if (state.status === 'EXECUTING') {
      state.status = 'COMPLETED'
      state.afterPlanVersion = state.beforePlanVersion + 1
      state.executionId = `mock-exec-${uuid().slice(0, 8)}`
      state.updatedAt = new Date().toISOString()
    }
    return { ...state }
  }

  async confirmPlanAdjustment(id: string): Promise<PlanAdjustment> {
    await delay(600)
    const state = this.require(this.adjustments, id, '调整分析不存在')
    if (state.status !== 'DRAFT_READY') {
      return { ...state, error: '当前没有可确认的调整草案' }
    }
    state.status = 'EXECUTING'
    state.confirmed = true
    state.updatedAt = new Date().toISOString()
    return { ...state }
  }

  private buildOperations(): AdjustmentOperation[] {
    return [
      {
        type: 'RESCHEDULE_TASK',
        taskId: 'mock-task-1',
        expectedVersion: 1,
        scheduledDate: plusDays(1),
        estimatedMinutes: null,
        firstTitle: null,
        firstEstimatedMinutes: null,
        secondTitle: null,
        secondScheduledDate: null,
        secondEstimatedMinutes: null,
      },
      {
        type: 'UPDATE_ESTIMATE',
        taskId: 'mock-task-2',
        expectedVersion: 2,
        scheduledDate: null,
        estimatedMinutes: 90,
        firstTitle: null,
        firstEstimatedMinutes: null,
        secondTitle: null,
        secondScheduledDate: null,
        secondEstimatedMinutes: null,
      },
      {
        type: 'INSERT_REVIEW_TASK',
        taskId: null,
        expectedVersion: null,
        scheduledDate: plusDays(2),
        estimatedMinutes: 30,
        title: '复习：依赖注入（测验正确率偏低）',
        taskKind: 'REVIEW',
        firstTitle: null,
        firstEstimatedMinutes: null,
        secondTitle: null,
        secondScheduledDate: null,
        secondEstimatedMinutes: null,
        knowledgePoint: '依赖注入',
        sourceAttemptId: 'mock-attempt-1',
      },
    ]
  }

  // ---------- 测验生成 ----------

  async generateQuiz(
    _taskId: string,
    _webSearch: WebSearchPreference,
  ): Promise<{ quizId: string }> {
    await delay(1200)
    // Mock 无法生成真实测验（题目由 Java/Python 联调后持久化），
    // 返回占位 ID，页面据此提示 Mock 模式而非跳转。
    return { quizId: `mock-quiz-${uuid().slice(0, 8)}` }
  }

  // ---------- AI 设置 ----------

  async getAiSettings(): Promise<AiSettings> {
    await delay(300)
    return { ...this.aiSettings }
  }

  async updateDeepseekKey(apiKey: string): Promise<AiSettings> {
    await delay(500)
    this.aiSettings = {
      ...this.aiSettings,
      deepseekConfigured: true,
      deepseekMaskedSuffix: apiKey.slice(-4),
      deepseek: {
        configured: true,
        source: 'USER',
        maskedSuffix: apiKey.slice(-4),
        available: true,
      },
    }
    return { ...this.aiSettings }
  }

  async deleteDeepseekKey(): Promise<AiSettings> {
    await delay(400)
    this.aiSettings = {
      ...this.aiSettings,
      deepseekConfigured: false,
      deepseekMaskedSuffix: null,
      deepseek: {
        configured: false,
        source: 'NONE',
        maskedSuffix: null,
        available: false,
      },
    }
    return { ...this.aiSettings }
  }

  async updateTavilyKey(apiKey: string): Promise<AiSettings> {
    await delay(500)
    this.aiSettings = {
      ...this.aiSettings,
      tavilyConfigured: true,
      tavilyMaskedSuffix: apiKey.slice(-4),
      tavily: {
        configured: true,
        source: 'USER',
        maskedSuffix: apiKey.slice(-4),
        available: true,
      },
    }
    return { ...this.aiSettings }
  }

  async deleteTavilyKey(): Promise<AiSettings> {
    await delay(400)
    this.aiSettings = {
      ...this.aiSettings,
      tavilyConfigured: false,
      tavilyMaskedSuffix: null,
      tavily: {
        configured: false,
        source: 'NONE',
        maskedSuffix: null,
        available: false,
      },
    }
    return { ...this.aiSettings }
  }

  private require<T>(map: Map<string, T>, id: string, message: string): T {
    const value = map.get(id)
    if (!value) throw new Error(message)
    return value
  }
}
