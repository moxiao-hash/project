<template>
  <div class="page">
    <div class="page-header">
      <div><h1 class="page-title">今日任务</h1><p class="page-subtitle"><input v-model="date" type="date" class="input date-input" @change="load" /><span v-if="date === today" style="margin-left: 8px">今天</span></p></div>
      <RouterLink to="/agent/tasks" class="btn btn-secondary">🤖 用 Agent 操作任务</RouterLink>
    </div>

    <section class="card roadmap-today" aria-labelledby="roadmap-today-title">
      <div class="row"><div><h2 id="roadmap-today-title">今日路线学习</h2><p class="muted">路线节点会按每日容量滚动安排，打卡后自动生成测验。</p></div><span class="spacer" /><RouterLink to="/roadmap" class="btn btn-secondary btn-sm">查看完整路线</RouterLink></div>
      <LoadingBlock v-if="scheduleLoading" text="正在读取路线安排…" />
      <p v-else-if="scheduleError" class="field-error" role="alert">{{ scheduleError }}</p>
      <p v-else-if="roadmapItems.length === 0" class="muted roadmap-empty">该日期没有路线学习节点。</p>
      <ol v-else class="roadmap-list">
        <li v-for="item in roadmapItems" :key="item.id" class="roadmap-item">
          <div>
            <RouterLink :to="{ name: 'roadmap-node', params: { id: item.nodeId } }"><strong>{{ item.title }}</strong></RouterLink>
            <p class="muted">{{ item.nodeCode }} · {{ item.plannedMinutes }} 分钟 · {{ scheduleStatusLabel(item.status) }}</p>
          </div>
          <div class="roadmap-actions">
            <span v-if="quizBusy(item.nodeId)" role="status" aria-live="polite">生成中…</span>
            <RouterLink v-if="roadmapQuizzes[item.nodeId]?.quizId" class="btn btn-primary btn-sm" :to="{ name: 'quiz', params: { id: roadmapQuizzes[item.nodeId]!.quizId } }">{{ roadmapQuizzes[item.nodeId]?.status === 'FAILED' ? '再次作答' : '开始测验' }}</RouterLink>
            <RouterLink v-if="roadmapQuizzes[item.nodeId]?.latestAttemptId" class="btn btn-secondary btn-sm" :to="{ name: 'attempt', params: { id: roadmapQuizzes[item.nodeId]!.latestAttemptId } }">查看解析</RouterLink>
            <button v-if="roadmapQuizzes[item.nodeId]?.status === 'FAILED' || roadmapQuizzes[item.nodeId]?.generation.status === 'FAILED'" class="btn btn-secondary btn-sm" data-testid="today-retry-quiz" :disabled="retryingNodes[item.nodeId]" @click="retryQuiz(item.nodeId)">{{ retryingNodes[item.nodeId] ? '正在重试…' : '重新测验' }}</button>
          </div>
        </li>
      </ol>
    </section>

    <div v-if="summary" class="card summary-card">
      <div class="row"><div><strong>{{ summary.done }}/{{ summary.total }}</strong><span class="muted"> 已完成 · 预估总计 {{ formatMinutes(summary.estimated) }}</span></div><span class="spacer" /><span v-if="summary.total > 0 && summary.done === summary.total" class="badge badge-success">全部完成 🎉</span></div>
      <div class="progress-track" style="margin-top: 10px"><div class="progress-fill" :style="{ width: summary.progress + '%' }" /></div>
    </div>
    <div class="card" style="margin-top: 16px">
      <LoadingBlock v-if="loading" /><ErrorState v-else-if="error" :message="error" @retry="load" />
      <EmptyState v-else-if="tasks.length === 0" icon="🌤️" :title="date === today ? '今天没有传统计划任务' : '该日期没有传统计划任务'" description="路线学习仍可在上方继续；也可以到计划详情页添加任务。"><RouterLink to="/plans" class="btn btn-primary">查看学习计划</RouterLink></EmptyState>
      <TaskList v-else :tasks="tasks" show-quiz-gen @changed="load" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { learningApi } from '@/services/current/learning'
import { describeError } from '@/services/http'
import { roadmapApi } from '@/services/roadmap'
import { formatMinutes, todayString } from '@/utils/datetime'
import type { LearningTask } from '@/types/api'
import type { RoadmapNodeQuiz, RoadmapScheduleItem } from '@/types/roadmap'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import TaskList from '@/components/TaskList.vue'

const today = todayString()
const date = ref(today)
const tasks = ref<LearningTask[]>([])
const roadmapItems = ref<RoadmapScheduleItem[]>([])
const roadmapQuizzes = ref<Record<string, RoadmapNodeQuiz>>({})
const retryingNodes = ref<Record<string, boolean>>({})
const loading = ref(true)
const scheduleLoading = ref(true)
const error = ref('')
const scheduleError = ref('')
let requestSequence = 0
let active = true
let pollTimer: ReturnType<typeof setTimeout> | null = null

const summary = computed(() => {
  if (tasks.value.length === 0) return null
  const total = tasks.value.length
  const done = tasks.value.filter((task) => task.status === 'COMPLETED').length
  const estimated = tasks.value.reduce((sum, task) => sum + task.estimatedMinutes, 0)
  return { total, done, estimated, progress: Math.round((done / total) * 100) }
})

function scheduleStatusLabel(status: RoadmapScheduleItem['status']) {
  return { PLANNED: '待学习', STARTED: '学习中', COMPLETED: '已完成' }[status]
}
function quizBusy(nodeId: string) {
  const state = roadmapQuizzes.value[nodeId]
  return state?.status === 'GENERATING' || state?.status === 'EVALUATING' || state?.generation.status === 'PENDING' || state?.generation.status === 'LEASED'
}
function isNotFound(value: unknown) {
  return (value as { response?: { status?: number } })?.response?.status === 404
}
function stopPolling() {
  if (pollTimer !== null) clearTimeout(pollTimer)
  pollTimer = null
}
function scheduleQuizPoll(sequence: number) {
  stopPolling()
  if (roadmapItems.value.some((item) => quizBusy(item.nodeId))) pollTimer = setTimeout(() => void loadRoadmapQuizzes(sequence), 2500)
}
async function loadRoadmapQuizzes(sequence: number) {
  const entries = await Promise.all(roadmapItems.value.map(async (item) => {
    try { return [item.nodeId, await roadmapApi.getNodeQuiz(item.nodeId)] as const }
    catch (cause) { if (isNotFound(cause)) return null; throw cause }
  }))
  if (!active || sequence !== requestSequence) return
  roadmapQuizzes.value = Object.fromEntries(entries.filter((entry): entry is NonNullable<typeof entry> => entry !== null))
  scheduleQuizPoll(sequence)
}
async function loadSchedule(sequence: number, selectedDate: string) {
  try {
    const schedule = await roadmapApi.getSchedule(selectedDate, selectedDate)
    if (!active || sequence !== requestSequence) return
    roadmapItems.value = schedule.days.find((day) => day.date === selectedDate)?.items ?? []
    await loadRoadmapQuizzes(sequence)
  } catch (cause) {
    if (!active || sequence !== requestSequence) return
    if (isNotFound(cause)) roadmapItems.value = []
    else scheduleError.value = describeError(cause)
  } finally { if (active && sequence === requestSequence) scheduleLoading.value = false }
}
async function loadTasks(sequence: number, selectedDate: string) {
  try {
    const result = await learningApi.listTasks(selectedDate)
    if (active && sequence === requestSequence) tasks.value = result
  } catch (cause) { if (active && sequence === requestSequence) error.value = describeError(cause) }
  finally { if (active && sequence === requestSequence) loading.value = false }
}
async function retryQuiz(nodeId: string) {
  if (retryingNodes.value[nodeId]) return
  const sequence = requestSequence
  retryingNodes.value[nodeId] = true
  try {
    await roadmapApi.retryNodeQuiz(nodeId, `roadmap-quiz-retry:${nodeId}:${crypto.randomUUID()}`)
    if (active && sequence === requestSequence) await loadRoadmapQuizzes(sequence)
  } catch (cause) {
    if (active && sequence === requestSequence) scheduleError.value = describeError(cause)
  } finally {
    if (active && sequence === requestSequence) retryingNodes.value[nodeId] = false
  }
}
function load() {
  const sequence = ++requestSequence
  const selectedDate = date.value
  stopPolling()
  loading.value = true
  scheduleLoading.value = true
  error.value = ''
  scheduleError.value = ''
  tasks.value = []
  roadmapItems.value = []
  roadmapQuizzes.value = {}
  retryingNodes.value = {}
  void loadTasks(sequence, selectedDate)
  void loadSchedule(sequence, selectedDate)
}

onMounted(load)
onBeforeUnmount(() => { active = false; requestSequence += 1; stopPolling() })
</script>

<style scoped>
.summary-card { margin-top: 16px; background: linear-gradient(135deg, #eef0fe, #f8f7ff); }
.date-input { width: auto; display: inline-block; padding: 4px 8px; }
.roadmap-today { margin-bottom: 16px; }
.roadmap-today h2 { font-size: 18px; }
.roadmap-today .row > div > p { margin-top: 5px; }
.roadmap-empty { margin-top: 18px; }
.roadmap-list { margin: 18px 0 0; padding: 0; list-style: none; }
.roadmap-item { display: flex; align-items: center; gap: 18px; justify-content: space-between; padding: 14px 0; border-top: 1px solid var(--color-border); }
.roadmap-item p { margin: 5px 0 0; font-size: 12px; }
.roadmap-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
@media (max-width: 680px) { .roadmap-item { align-items: flex-start; flex-direction: column; } }
</style>
