<template>
  <main class="page node-page">
    <LoadingBlock v-if="loading" text="正在加载学习节点…" />
    <section v-else-if="notFound" class="card node-state">
      <h1>未找到该学习节点</h1>
      <p>它可能已经调整，或不属于当前学习路线。</p>
      <RouterLink class="btn btn-secondary" to="/roadmap">返回路线</RouterLink>
    </section>
    <section v-else-if="error" class="card node-state" role="alert">
      <h1>节点加载失败</h1>
      <p>请检查网络后重试。</p>
      <button class="btn btn-secondary" :disabled="loading" @click="loadNode">重试</button>
    </section>
    <template v-else-if="node">
      <RouterLink class="node-back" data-testid="node-back" :to="moduleId
        ? { name: 'roadmap-module', params: { id: moduleId } }
        : { name: 'roadmap' }"
      >{{ moduleId ? '← 返回所属模块' : '← 返回学习路线' }}</RouterLink>
      <aside v-if="isLegacyNode" class="legacy-roadmap-warning" data-testid="legacy-roadmap-warning">
        <div>
          <strong>这是 V1 历史课程</strong>
          <p>该版本保留用于查看历史记录，不再推荐零基础学习者从这里开始。</p>
        </div>
        <RouterLink class="btn btn-secondary" to="/roadmap">返回路线并升级 V2</RouterLink>
      </aside>
      <header class="node-hero">
        <div class="node-meta">
          <span>{{ statusLabel }}</span><span>{{ node.required ? '必修' : '选修' }}</span>
          <span>{{ node.difficulty }}</span>
          <span>{{ node.estimatedMinutes + node.practiceMinutes }} 分钟</span>
        </div>
        <h1>{{ node.title }}</h1>
        <p class="node-gate">打卡、测验和必交成果全部满足后才完成节点</p>
      </header>

      <div class="node-content">
        <section aria-labelledby="objectives-title">
          <p class="node-section-index">01</p><h2 id="objectives-title">学习目标</h2>
          <ul><li v-for="item in node.objectives" :key="item">{{ item }}</li></ul>
        </section>
        <section aria-labelledby="self-study-title">
          <p class="node-section-index">02</p><h2 id="self-study-title">自主学习</h2>
          <h3>开发高频点</h3>
          <ul><li v-for="item in node.highFrequency" :key="item">{{ item }}</li></ul>
          <h3>常见误区</h3>
          <ul><li v-for="item in node.commonMistakes" :key="item">{{ item }}</li></ul>
          <h3>资源搜索关键词</h3>
          <ul class="node-keywords"><li v-for="item in node.searchKeywords" :key="item">{{ item }}</li></ul>
        </section>
        <section id="node-prerequisites" data-testid="node-prerequisites" aria-labelledby="prerequisites-title">
          <p class="node-section-index">03</p><h2 id="prerequisites-title">前置节点</h2>
          <p v-if="node.prerequisiteCodes.length === 0" class="muted">无，可直接开始。</p>
          <ul v-else>
            <li v-for="code in node.prerequisiteCodes" :key="code">
              <RouterLink v-if="prerequisiteIds[code]" :to="{ name: 'roadmap-node', params: { id: prerequisiteIds[code] } }">{{ code }}</RouterLink>
              <template v-else>
                <span>{{ code }}</span><span class="prerequisite-note">— {{ prerequisiteResolution === 'unavailable' ? '前置节点链接暂时无法解析' : '当前路线中未找到该前置节点' }}</span>
              </template>
            </li>
          </ul>
        </section>
        <section aria-labelledby="check-in-title">
          <p class="node-section-index">04</p><h2 id="check-in-title">总结打卡</h2>
          <p v-if="node.checkInStatus === 'SUBMITTED'" class="status-success">已提交学习总结。</p>
          <template v-else-if="node.diagnosticMastered">
            <p class="muted">诊断已掌握，可跳过总结，但仍需完成一次快速验证。</p>
            <button class="btn btn-primary" :disabled="submittingVerification || quizBusy" @click="startQuickVerification">
              {{ submittingVerification ? '正在创建…' : '开始快速验证' }}
            </button>
          </template>
          <form v-else class="check-in-form" @submit.prevent="submitCheckIn">
            <label for="check-in-summary">写下至少一个收获、疑问或实践结果</label>
            <textarea id="check-in-summary" v-model="summary" class="textarea" rows="4" maxlength="1000" placeholder="例如：我理解了 public 类名必须和文件名一致，并独立运行了 HelloWorld。" />
            <p v-if="checkInError" class="field-error" role="alert">{{ checkInError }}</p>
            <button class="btn btn-primary" type="submit" :disabled="submittingCheckIn">{{ submittingCheckIn ? '正在打卡…' : '提交总结并打卡' }}</button>
          </form>
        </section>
        <section aria-labelledby="quiz-title">
          <p class="node-section-index">05</p><h2 id="quiz-title">节点测验</h2>
          <p v-if="quizLoading" class="muted">正在查询测验状态…</p>
          <p v-else-if="quizBusy" role="status" aria-live="polite">测验生成中，请稍候…</p>
          <p v-else-if="quizError" class="field-error" role="alert">{{ quizError }}</p>
          <p v-else-if="!quiz" class="muted">完成总结打卡后，将自动生成 5 道测验题。</p>
          <div v-if="quiz" class="quiz-actions">
            <RouterLink v-if="quiz.quizId" class="btn btn-primary" data-testid="start-quiz" :to="{ name: 'quiz', params: { id: quiz.quizId } }">{{ quiz.status === 'FAILED' ? '再次作答' : '开始测验' }}</RouterLink>
            <RouterLink v-if="quiz.latestAttemptId" class="btn btn-secondary" data-testid="view-analysis" :to="{ name: 'attempt', params: { id: quiz.latestAttemptId } }">查看解析</RouterLink>
            <button v-if="quiz.status === 'FAILED' || quiz.generation.status === 'FAILED'" class="btn btn-secondary" data-testid="retry-quiz" :disabled="retryingQuiz" @click="retryQuiz">{{ retryingQuiz ? '正在重试…' : '重新测验' }}</button>
          </div>
        </section>
        <section aria-labelledby="artifact-title">
          <p class="node-section-index">06</p><h2 id="artifact-title">实践成果</h2>
          <p v-if="node.artifactStatus === 'NOT_REQUIRED'" class="muted">本节点无需单独提交实践成果。</p>
          <p v-else>当前状态：{{ artifactLabel }}</p>
          <p v-if="node.artifactStatus !== 'NOT_REQUIRED'" class="muted">安全 Runner 与成果验收将在下一批接入。</p>
        </section>
      </div>
    </template>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import LoadingBlock from '@/components/LoadingBlock.vue'
import { describeError } from '@/services/http'
import { roadmapApi } from '@/services/roadmap'
import type { RoadmapMap, RoadmapNode, RoadmapNodeQuiz } from '@/types/roadmap'

const route = useRoute()
const moduleId = computed(() => typeof route.query.moduleId === 'string' ? route.query.moduleId : '')
const node = ref<RoadmapNode | null>(null)
const quiz = ref<RoadmapNodeQuiz | null>(null)
const loading = ref(false)
const quizLoading = ref(false)
const error = ref(false)
const notFound = ref(false)
const summary = ref('')
const checkInError = ref('')
const quizError = ref('')
const submittingCheckIn = ref(false)
const submittingVerification = ref(false)
const retryingQuiz = ref(false)
const prerequisiteIds = ref<Record<string, string>>({})
const prerequisiteResolution = ref<'resolved' | 'unavailable'>('unavailable')
let requestSequence = 0
let quizRequestSequence = 0
let pollTimer: ReturnType<typeof setTimeout> | null = null
let active = true

const labels: Record<RoadmapNode['displayStatus'], string> = {
  LOCKED: '已锁定', AVAILABLE: '可开始', SCHEDULED: '已排期', IN_PROGRESS: '学习中',
  QUIZ_PENDING: '待测验', REVIEW_REQUIRED: '待复习', COMPLETED: '已完成',
}
const artifactLabels: Record<RoadmapNode['artifactStatus'], string> = {
  NOT_REQUIRED: '无需提交', MISSING: '待提交', SUBMITTED: '待验收', ACCEPTED: '已通过', REJECTED: '需修改',
}
const statusLabel = computed(() => node.value ? labels[node.value.displayStatus] : '')
const artifactLabel = computed(() => node.value ? artifactLabels[node.value.artifactStatus] : '')
const isLegacyNode = computed(() => node.value?.id.includes('studypilot-java-ai-v1-') ?? false)
const quizBusy = computed(() => quiz.value?.status === 'GENERATING' || quiz.value?.status === 'EVALUATING'
  || quiz.value?.generation.status === 'PENDING' || quiz.value?.generation.status === 'LEASED')

function isNotFound(value: unknown) {
  return (value as { response?: { status?: number } })?.response?.status === 404
}

function operationKey(prefix: string, nodeId: string) {
  return `${prefix}:${nodeId}:${crypto.randomUUID()}`
}

function stopPolling() {
  if (pollTimer !== null) clearTimeout(pollTimer)
  pollTimer = null
}

async function loadNode() {
  if (!active) return
  const id = String(route.params.id ?? '')
  const sequence = ++requestSequence
  stopPolling()
  loading.value = true
  error.value = false
  notFound.value = false
  node.value = null
  quiz.value = null
  summary.value = ''
  checkInError.value = ''
  quizError.value = ''
  prerequisiteIds.value = {}
  prerequisiteResolution.value = 'unavailable'
  try {
    const result = await roadmapApi.getNode(id)
    if (!active || sequence !== requestSequence) return
    node.value = result
    loading.value = false
    void loadQuiz(id, sequence, true)
    if (result.prerequisiteCodes.length > 0) void resolvePrerequisites(sequence)
  } catch (cause) {
    if (!active || sequence !== requestSequence) return
    if (isNotFound(cause)) notFound.value = true
    else error.value = true
    loading.value = false
  }
}

async function loadQuiz(nodeId: string, nodeSequence: number, initial = false, fromPoll = false) {
  if (!active || nodeSequence !== requestSequence) return
  const quizSequence = ++quizRequestSequence
  if (initial) quizLoading.value = true
  try {
    const result = await roadmapApi.getNodeQuiz(nodeId)
    if (!active || nodeSequence !== requestSequence || quizSequence !== quizRequestSequence) return
    quiz.value = result
    quizError.value = ''
    if (quizBusy.value) schedulePoll(nodeId, nodeSequence)
    else if (fromPoll && node.value) void refreshNodeSnapshot(nodeId, nodeSequence)
  } catch (cause) {
    if (!active || nodeSequence !== requestSequence || quizSequence !== quizRequestSequence) return
    if (isNotFound(cause)) quiz.value = null
    else quizError.value = describeError(cause)
  } finally {
    if (active && nodeSequence === requestSequence && quizSequence === quizRequestSequence) quizLoading.value = false
  }
}

function schedulePoll(nodeId: string, sequence: number) {
  stopPolling()
  pollTimer = setTimeout(() => void loadQuiz(nodeId, sequence, false, true), 2000)
}

async function refreshNodeSnapshot(nodeId: string, sequence: number) {
  try {
    const result = await roadmapApi.getNode(nodeId)
    if (active && sequence === requestSequence) node.value = result
  } catch {
    // Quiz actions remain usable if this optional progress refresh fails.
  }
}

async function submitCheckIn() {
  if (!node.value || submittingCheckIn.value) return
  const trimmed = summary.value.trim()
  if (trimmed.length < 10) {
    checkInError.value = '请至少写 10 个字，记录一个收获、疑问或实践结果。'
    return
  }
  const nodeId = node.value.id
  const sequence = requestSequence
  submittingCheckIn.value = true
  checkInError.value = ''
  try {
    await roadmapApi.checkIn(nodeId, trimmed, operationKey('roadmap-check-in', nodeId))
    if (!active || sequence !== requestSequence) return
    await Promise.all([refreshNodeSnapshot(nodeId, sequence), loadQuiz(nodeId, sequence)])
  } catch (cause) {
    if (active && sequence === requestSequence) checkInError.value = describeError(cause)
  } finally {
    if (active && sequence === requestSequence) submittingCheckIn.value = false
  }
}

async function retryQuiz() {
  if (!node.value || retryingQuiz.value) return
  const nodeId = node.value.id
  const sequence = requestSequence
  retryingQuiz.value = true
  quizError.value = ''
  try {
    await roadmapApi.retryNodeQuiz(nodeId, operationKey('roadmap-quiz-retry', nodeId))
    if (active && sequence === requestSequence) await loadQuiz(nodeId, sequence)
  } catch (cause) {
    if (active && sequence === requestSequence) quizError.value = describeError(cause)
  } finally {
    if (active && sequence === requestSequence) retryingQuiz.value = false
  }
}

async function startQuickVerification() {
  if (!node.value || submittingVerification.value) return
  const nodeId = node.value.id
  const sequence = requestSequence
  submittingVerification.value = true
  quizError.value = ''
  try {
    await roadmapApi.quickVerification(nodeId, operationKey('roadmap-quick-verify', nodeId))
    if (active && sequence === requestSequence) await loadQuiz(nodeId, sequence)
  } catch (cause) {
    if (active && sequence === requestSequence) quizError.value = describeError(cause)
  } finally {
    if (active && sequence === requestSequence) submittingVerification.value = false
  }
}

async function resolvePrerequisites(sequence: number) {
  try {
    const map = await roadmapApi.getCurrentMap()
    if (!active || sequence !== requestSequence || !isRoadmapMap(map)) return
    prerequisiteIds.value = Object.fromEntries(map.stages.flatMap((stage) => stage.nodes.map((item) => [item.code, item.id])))
    prerequisiteResolution.value = 'resolved'
  } catch {
    // Prerequisite links are optional; the primary node remains readable.
  }
}

function isRoadmapMap(value: unknown): value is RoadmapMap {
  return typeof value === 'object' && value !== null && Array.isArray((value as RoadmapMap).stages)
}

watch(() => route.params.id, loadNode, { immediate: true })
onBeforeUnmount(() => {
  active = false
  requestSequence += 1
  quizRequestSequence += 1
  stopPolling()
})
</script>

<style scoped>
.node-page { max-width: 930px; }
.node-back { display: inline-block; margin-bottom: 20px; font-size: 13px; }
.legacy-roadmap-warning {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 22px;
  margin-bottom: 22px;
  padding: 17px 19px;
  border: 1px solid #e8c781;
  border-left: 4px solid var(--color-warning);
  border-radius: var(--radius-sm);
  background: var(--color-warning-soft);
}
.legacy-roadmap-warning strong { color: #6d4909; }
.legacy-roadmap-warning p { margin: 4px 0 0; color: #75510f; font-size: 13px; line-height: 1.55; }
.legacy-roadmap-warning .btn { flex: 0 0 auto; }
.node-hero { padding: 8px 0 28px; border-bottom: 1px solid var(--color-border); }
.node-meta { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.node-meta span { padding: 2px 9px; border: 1px solid var(--color-border); border-radius: 999px; color: var(--color-text-secondary); font-size: 11px; font-weight: 650; }
.node-hero h1 { max-width: 770px; font-size: clamp(28px, 4vw, 42px); letter-spacing: -0.035em; }
.node-gate { margin: 18px 0 0; padding: 12px 14px; border-left: 3px solid var(--color-warning); background: var(--color-warning-soft); color: #75510f; }
.node-content { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 34px; }
.node-content section { padding: 25px 0; border-bottom: 1px solid var(--color-border); }
.node-section-index { margin: 0 0 5px; color: var(--color-primary); font-size: 11px; font-weight: 750; }
.node-content h2 { font-size: 17px; }
.node-content h3 { margin-top: 16px; font-size: 14px; }
.node-content ul { margin: 12px 0 0; padding-left: 20px; }
.node-content li + li { margin-top: 6px; }
.prerequisite-note { margin-left: 5px; color: var(--color-text-secondary); font-size: 12px; }
.node-keywords { display: flex; flex-wrap: wrap; gap: 7px; padding: 0 !important; list-style: none; }
.node-keywords li { margin: 0 !important; padding: 3px 9px; border-radius: 6px; background: #eef0f4; color: #4b5563; font-size: 12px; }
.check-in-form { display: grid; gap: 10px; margin-top: 12px; }
.check-in-form label { font-size: 13px; font-weight: 650; }
.check-in-form .btn { justify-self: start; }
.quiz-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.status-success { color: var(--color-success); }
.node-state { padding: 40px; }
.node-state p { margin: 8px 0 18px; color: var(--color-text-secondary); }
@media (max-width: 680px) {
  .node-content { grid-template-columns: 1fr; }
  .legacy-roadmap-warning { align-items: stretch; flex-direction: column; }
  .legacy-roadmap-warning .btn { width: 100%; }
}
</style>
