/** 英文枚举 → 中文展示文案映射。服务端数据保留英文原值，仅在展示层转换。 */
import type {
  AgentScope,
  AttemptStatus,
  Difficulty,
  ExecutionStatus,
  ExecutionType,
  MaterialCategory,
  MaterialStatus,
  MaterialType,
  NotificationType,
  PlanStatus,
  PrivacyLevel,
  QuestionType,
  RiskLevel,
  TaskKind,
  TaskStatus,
  TriggerType,
  WeekendPreference,
} from '@/types/api'

export const taskStatusLabels: Record<TaskStatus, string> = {
  TODO: '待完成',
  COMPLETED: '已完成',
  SKIPPED: '已跳过',
  DEFERRED: '已延期',
}

export const taskStatusBadge: Record<TaskStatus, string> = {
  TODO: 'badge-info',
  COMPLETED: 'badge-success',
  SKIPPED: 'badge-warning',
  DEFERRED: 'badge-neutral',
}

export const taskKindLabels: Record<TaskKind, string> = {
  LEARNING: '学习',
  REVIEW: '复习',
  CODING_PRACTICE: '编程练习',
}

export const planStatusLabels: Record<PlanStatus, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  ARCHIVED: '已归档',
}

export const planStatusBadge: Record<PlanStatus, string> = {
  DRAFT: 'badge-neutral',
  CONFIRMED: 'badge-success',
  ARCHIVED: 'badge-neutral',
}

export const materialTypeLabels: Record<MaterialType, string> = {
  MARKDOWN: 'Markdown',
  TEXT: '纯文本',
  PDF: 'PDF',
  WORD: 'Word',
  WEB_PAGE: '网页',
  IMAGE: '图片',
  PASTED_ARTICLE: '粘贴文章',
}

export const materialCategoryLabels: Record<MaterialCategory, string> = {
  SYLLABUS: '课程大纲',
  LEARNING_MATERIAL: '学习资料',
  PERSONAL_NOTE: '个人笔记',
  REFERENCE: '参考资料',
}

export const privacyLevelLabels: Record<PrivacyLevel, string> = {
  NORMAL: '普通',
  SENSITIVE: '敏感',
  LOCAL_ONLY: '仅本地',
}

export const privacyLevelHints: Record<PrivacyLevel, string> = {
  NORMAL: '可以使用云模型分析',
  SENSITIVE: '正文不会发送到云端模型',
  LOCAL_ONLY: '仅在本地解析与索引',
}

export const materialStatusLabels: Record<MaterialStatus, string> = {
  PENDING: '排队中',
  PROCESSING: '解析中',
  READY: '已就绪',
  FAILED: '失败',
}

export const materialStatusBadge: Record<MaterialStatus, string> = {
  PENDING: 'badge-neutral',
  PROCESSING: 'badge-info',
  READY: 'badge-success',
  FAILED: 'badge-danger',
}

export const weekendPreferenceLabels: Record<WeekendPreference, string> = {
  LESS: '周末少学',
  SAME: '与平日相同',
  MORE: '周末多学',
}

export const questionTypeLabels: Record<QuestionType, string> = {
  SINGLE_CHOICE: '单选题',
  MULTIPLE_CHOICE: '多选题',
  CODING: '编程题',
}

export const difficultyLabels: Record<Difficulty, string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
}

export const attemptStatusLabels: Record<AttemptStatus, string> = {
  EVALUATING: '评分中',
  GRADED: '评分完成',
  PARTIALLY_GRADED: '部分评分',
}

export const agentScopeLabels: Record<AgentScope, string> = {
  MATERIAL_PROCESSING: '资料处理',
  QUIZ_GENERATION: '测验生成',
  PLAN_GENERATION: '计划生成',
  TASK_MANAGEMENT: '任务管理',
  SMALL_PLAN_ADJUSTMENT: '小幅计划调整',
  LARGE_PLAN_ADJUSTMENT: '大幅计划调整',
}

export const executionStatusLabels: Record<ExecutionStatus, string> = {
  WAITING_AUTHORIZATION: '等待授权',
  WAITING_CONFIRMATION: '等待确认',
  PENDING: '排队中',
  RUNNING: '执行中',
  SUCCEEDED: '已成功',
  FAILED: '已失败',
}

export const executionStatusBadge: Record<ExecutionStatus, string> = {
  WAITING_AUTHORIZATION: 'badge-warning',
  WAITING_CONFIRMATION: 'badge-warning',
  PENDING: 'badge-neutral',
  RUNNING: 'badge-info',
  SUCCEEDED: 'badge-success',
  FAILED: 'badge-danger',
}

export const executionTypeLabels: Record<ExecutionType, string> = {
  MATERIAL_PROCESSING: '资料处理',
  QUIZ_GENERATION: '测验生成',
  PLAN_GENERATION: '计划生成',
  PLAN_ADJUSTMENT: '计划调整',
  TASK_STATUS_CHANGE: '任务状态变更',
  WEB_SEARCH: '联网搜索',
  LEARNING_GOAL_CHANGE: '学习目标变更',
  USER_SETTINGS_CHANGE: '用户设置变更',
  NOTIFICATION_CHANGE: '通知设置变更',
  WORKSPACE_REGISTRATION: '工作区登记',
  ARTIFACT_SUBMISSION: '成果提交',
  ROADMAP_CHANGE: '路线切换',
  RUNNER_EXECUTION: '本地执行器执行',
}

export const riskLevelLabels: Record<RiskLevel, string> = {
  LOW: '低风险',
  HIGH: '高风险',
}

export const triggerTypeLabels: Record<TriggerType, string> = {
  USER_REQUEST: '用户请求',
  CHECK_IN: '打卡触发',
  NIGHTLY_CHECK: '夜间检查',
  MATERIAL_IMPORTED: '资料导入',
}

export const notificationTypeLabels: Record<NotificationType, string> = {
  PLAN_ADJUSTED: '计划已调整',
  PLAN_ADJUSTMENT_READY: '调整待确认',
  MATERIAL_READY: '资料就绪',
  QUIZ_READY: '测验就绪',
  TASK_OVERDUE: '任务逾期',
  AGENT_FAILED: 'Agent 失败',
}

export const dayOfWeekLabels: Record<string, string> = {
  MONDAY: '周一',
  TUESDAY: '周二',
  WEDNESDAY: '周三',
  THURSDAY: '周四',
  FRIDAY: '周五',
  SATURDAY: '周六',
  SUNDAY: '周日',
}

/** 引用来源类型（Citation.sourceType 为开放字符串） */
export function citationSourceLabel(sourceType: string): string {
  const map: Record<string, string> = {
    MATERIAL: '资料',
    WEB: '网页',
    MODEL: '模型常识',
    WEB_SEARCH: '网页',
  }
  return map[sourceType] ?? sourceType
}

export function citationSourceBadge(sourceType: string): string {
  const map: Record<string, string> = {
    MATERIAL: 'badge-primary',
    WEB: 'badge-info',
    WEB_SEARCH: 'badge-info',
    MODEL: 'badge-neutral',
  }
  return map[sourceType] ?? 'badge-neutral'
}
