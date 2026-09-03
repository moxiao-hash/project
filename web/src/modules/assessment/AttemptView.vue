<template>
  <div class="page">
    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />

    <template v-else-if="attempt">
      <div class="page-header">
        <div>
          <h1 class="page-title">测验结果</h1>
          <p class="page-subtitle">
            <StatusBadge
              :label="attemptStatusLabels[attempt.status]"
              :badge-class="attempt.status === 'EVALUATING' ? 'badge-info' : 'badge-success'"
            />
          </p>
        </div>
        <RouterLink to="/mastery" class="btn btn-secondary">查看掌握度</RouterLink>
      </div>

      <!-- 异步评分中 -->
      <div v-if="attempt.status === 'EVALUATING'" class="card processing-card">
        <div class="row">
          <span class="spinner" />
          <div>
            <strong>编程题正在由 AI 评分…</strong>
            <div class="muted" style="font-size: 13px">每 2 秒自动刷新，评分完成后自动停止。</div>
          </div>
        </div>
      </div>

      <div v-if="pollingFailed" class="alert alert-warning">
        状态刷新多次失败，已停止自动轮询。
        <button class="btn btn-secondary btn-sm" style="margin-left: 8px" @click="resumePolling">
          恢复轮询
        </button>
      </div>

      <!-- 服务端警告（如部分编程题未评分）必须展示 -->
      <div v-if="attempt.warning" class="alert alert-warning">⚠️ {{ attempt.warning }}</div>

      <div v-if="attempt.status !== 'EVALUATING'" class="card score-card">
        <div class="score-value">{{ attempt.score }}</div>
        <div class="muted">总分</div>
      </div>

      <div v-if="attempt.reviewProgress" class="card review-progress">
        <div>
          <strong>本次掌握 {{ attempt.reviewProgress.clearedCount }} 题</strong>
          <p class="muted">仍有 {{ attempt.reviewProgress.remainingCount }} 题待重做</p>
        </div>
        <div class="row">
          <RouterLink to="/wrong-questions" class="btn btn-secondary">返回错题集</RouterLink>
          <RouterLink v-if="attempt.reviewProgress.remainingCount > 0"
                      to="/wrong-questions?mode=redo" class="btn btn-primary">继续重做</RouterLink>
        </div>
      </div>

      <div v-for="(result, index) in attempt.results" :key="result.questionId" class="card result-card">
        <div class="row" style="margin-bottom: 8px">
          <span style="font-weight: 700">第 {{ index + 1 }} 题</span>
          <span class="badge" :class="result.correct ? 'badge-success' : 'badge-danger'">
            {{ result.correct ? '正确' : '错误' }}
          </span>
          <span class="tag">{{ result.knowledgePoint }}</span>
          <span class="muted" style="font-size: 12px">评分方式：{{ result.evaluationMethod }}</span>
          <span v-if="result.score !== null" class="spacer" />
          <span v-if="result.score !== null" class="mono">{{ result.score }} 分</span>
        </div>
        <h2 v-if="result.questionText" class="result-question">{{ result.questionText }}</h2>
        <div v-if="result.type !== 'CODING'" class="answer-comparison">
          <div class="answer-box submitted">
            <span>你的答案</span>
            <strong>{{ formatAnswers(result.selectedAnswers) }}</strong>
          </div>
          <div class="answer-box expected">
            <span>正确答案</span>
            <strong>{{ formatAnswers(result.correctAnswers) }}</strong>
          </div>
        </div>
        <div v-else class="answer-comparison">
          <div class="answer-box submitted"><span>你的代码</span><pre class="mono">{{ result.codeAnswer }}</pre></div>
          <div class="answer-box expected"><span>参考实现</span><pre class="mono">{{ result.referenceAnswer }}</pre></div>
        </div>
        <p v-if="result.explanation" class="explanation">{{ result.explanation }}</p>
        <details v-if="result.evaluation" class="evaluation-detail">
          <summary class="muted" style="cursor: pointer; font-size: 13px">查看 AI 评价详情</summary>
          <pre class="mono evaluation-json">{{ prettyJson(result.evaluation) }}</pre>
        </details>
      </div>

      <!-- 自评 -->
      <div v-if="attempt.status !== 'EVALUATING' && attempt.results.length > 0" class="card">
        <h2 class="section-title">知识点自评</h2>
        <p class="muted" style="font-size: 13px; margin: 6px 0 14px">
          根据本次作答感受，为每个知识点打分（0～100），会纳入掌握度计算。
        </p>
        <div v-for="kp in knowledgePoints" :key="kp" class="self-assess-row">
          <span class="kp-name">{{ kp }}</span>
          <input
            v-model.number="selfAssessScores[kp]"
            class="input score-input"
            type="number"
            min="0"
            max="100"
            placeholder="0-100"
          />
        </div>
        <div class="row" style="margin-top: 14px; justify-content: flex-end">
          <button class="btn btn-primary" :disabled="selfAssessing" @click="onSelfAssess">
            {{ selfAssessing ? '提交中…' : '提交自评' }}
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { assessmentApi } from '@/services/current/assessment'
import { describeError } from '@/services/http'
import { usePolling } from '@/composables/usePolling'
import { useToastStore } from '@/stores/toast'
import { attemptStatusLabels } from '@/utils/labels'
import type { QuizAttempt } from '@/types/api'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const route = useRoute()
const toast = useToastStore()
const attemptId = route.params.id as string

const attempt = ref<QuizAttempt | null>(null)
const loading = ref(true)
const error = ref('')
const pollingFailed = ref(false)
const selfAssessing = ref(false)
const selfAssessScores = reactive<Record<string, number | null>>({})

const knowledgePoints = computed(() => {
  const set = new Set<string>()
  for (const r of attempt.value?.results ?? []) set.add(r.knowledgePoint)
  return [...set]
})

const polling = usePolling<QuizAttempt>({
  fetcher: () => assessmentApi.getAttempt(attemptId),
  shouldContinue: (a) => a.status === 'EVALUATING',
  interval: 2000,
  maxConsecutiveErrors: 5,
  onData: (a) => {
    attempt.value = a
    if (a.status !== 'EVALUATING') initSelfAssess()
  },
  onFailed: () => {
    pollingFailed.value = true
  },
})

function resumePolling() {
  pollingFailed.value = false
  polling.start()
}

function initSelfAssess() {
  for (const kp of knowledgePoints.value) {
    if (!(kp in selfAssessScores)) selfAssessScores[kp] = null
  }
}

function prettyJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function formatAnswers(answers: string[] | undefined): string {
  return answers?.length ? answers.join('、') : '未作答'
}

async function onSelfAssess() {
  const ratings = knowledgePoints.value
    .map((kp) => ({ knowledgePoint: kp, score: selfAssessScores[kp] }))
    .filter((r): r is { knowledgePoint: string; score: number } => r.score !== null)
  if (ratings.length === 0) {
    toast.warning('请至少为一个知识点打分')
    return
  }
  for (const r of ratings) {
    if (r.score < 0 || r.score > 100 || !Number.isFinite(r.score)) {
      toast.warning(`「${r.knowledgePoint}」的分数需在 0～100 之间`)
      return
    }
  }
  if (selfAssessing.value) return
  selfAssessing.value = true
  try {
    await assessmentApi.selfAssess(attemptId, { ratings })
    toast.success('自评已提交，掌握度已更新')
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    selfAssessing.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    attempt.value = await assessmentApi.getAttempt(attemptId)
    if (attempt.value.status === 'EVALUATING') polling.start()
    else initSelfAssess()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.processing-card {
  background: var(--color-info-soft);
  border-color: #b6ddef;
}

.score-card {
  text-align: center;
  padding: 28px;
}

.score-value {
  font-size: 44px;
  font-weight: 800;
  color: var(--color-primary);
}

.result-card {
  margin-top: 14px;
}

.review-progress { display: flex; align-items: center; justify-content: space-between; }
.review-progress p { margin: 5px 0 0; }
.result-question { font-size: 17px; line-height: 1.55; margin: 14px 0; }
.answer-comparison { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 12px; }
.answer-box { border-radius: 8px; padding: 12px; }
.answer-box span { display: block; font-size: 12px; font-weight: 700; margin-bottom: 6px; }
.answer-box pre { margin: 0; white-space: pre-wrap; }
.answer-box.submitted { background: var(--color-danger-soft, #fff1f2); }
.answer-box.expected { background: var(--color-success-soft, #ecfdf5); }
@media (max-width: 720px) {
  .review-progress { align-items: stretch; flex-direction: column; gap: 12px; }
  .answer-comparison { grid-template-columns: 1fr; }
}

.explanation {
  font-size: 13px;
  color: var(--color-text-secondary);
  margin: 0;
  white-space: pre-wrap;
}

.evaluation-detail {
  margin-top: 8px;
}

.evaluation-json {
  background: var(--color-bg);
  border-radius: 8px;
  padding: 10px;
  overflow-x: auto;
  font-size: 12px;
}

.section-title {
  font-size: 16px;
}

.self-assess-row {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 10px;
}

.kp-name {
  flex: 1;
  font-size: 14px;
}

.score-input {
  width: 110px;
}
</style>
