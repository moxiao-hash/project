<template>
  <div class="page wrong-book">
    <div class="page-header">
      <div>
        <p class="eyebrow">LEARNING REVIEW</p>
        <h1 class="page-title">错题集</h1>
        <p class="page-subtitle">看清错误，再用一组短测把它真正掌握。</p>
      </div>
      <div v-if="summary" class="stats">
        <div><strong>{{ summary.activeCount }}</strong><span>待重做</span></div>
        <div><strong>{{ summary.masteredCount }}</strong><span>已掌握</span></div>
      </div>
    </div>

    <div class="mode-tabs" role="tablist">
      <button :class="{ active: mode === 'review' }" @click="mode = 'review'">查看错题</button>
      <button data-testid="redo-tab" :class="{ active: mode === 'redo' }" @click="mode = 'redo'">
        重做错题
      </button>
    </div>

    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />

    <template v-else-if="summary && mode === 'review'">
      <div class="toolbar card">
        <div class="status-switch">
          <button :class="{ active: status === 'ACTIVE' }" @click="changeStatus('ACTIVE')">
            待重做 {{ summary.activeCount }}
          </button>
          <button :class="{ active: status === 'MASTERED' }" @click="changeStatus('MASTERED')">
            已掌握 {{ summary.masteredCount }}
          </button>
        </div>
        <select v-model="chapterKey" class="select" @change="loadQuestions">
          <option value="">全部章节</option>
          <option v-for="chapter in summary.chapters" :key="chapter.chapterKey" :value="chapter.chapterKey">
            {{ chapter.chapterTitle }}
          </option>
        </select>
      </div>

      <div v-if="questions.length === 0" class="card empty-state">
        <div class="empty-icon">✓</div>
        <h2>{{ status === 'ACTIVE' ? '当前没有待复习错题' : '还没有已掌握记录' }}</h2>
        <p class="muted">完成路线测验后，错误题目会自动归档到这里。</p>
      </div>

      <article v-for="(question, index) in questions" :key="question.id" class="card wrong-card">
        <div class="question-meta">
          <span class="index">{{ String(index + 1).padStart(2, '0') }}</span>
          <span class="badge" :class="question.status === 'ACTIVE' ? 'badge-danger' : 'badge-success'">
            {{ question.status === 'ACTIVE' ? '待重做' : '已掌握' }}
          </span>
          <span class="tag">{{ question.chapterTitle }}</span>
          <span class="tag">{{ question.knowledgePoint }}</span>
          <span class="muted count">累计错 {{ question.wrongCount }} 次</span>
        </div>
        <h2>{{ question.questionText }}</h2>
        <div class="answer-grid">
          <section class="answer wrong-answer">
            <span>你的答案</span>
            <pre v-if="question.latestCodeAnswer" class="mono">{{ question.latestCodeAnswer }}</pre>
            <strong v-else>{{ formatAnswers(question.latestSelectedAnswers) }}</strong>
          </section>
          <section class="answer correct-answer">
            <span>正确答案</span>
            <pre v-if="question.referenceAnswer" class="mono">{{ question.referenceAnswer }}</pre>
            <strong v-else>{{ formatAnswers(question.correctAnswers) }}</strong>
          </section>
        </div>
        <section class="explanation">
          <span>答案解析</span>
          <p>{{ question.explanation }}</p>
        </section>
      </article>
      <div v-if="totalElements > pageSize" class="pagination">
        <button class="btn btn-secondary" :disabled="pageIndex === 0" @click="changePage(pageIndex - 1)">
          上一页
        </button>
        <span class="muted">第 {{ pageIndex + 1 }} / {{ Math.ceil(totalElements / pageSize) }} 页</span>
        <button class="btn btn-secondary" :disabled="(pageIndex + 1) * pageSize >= totalElements"
                @click="changePage(pageIndex + 1)">下一页</button>
      </div>
    </template>

    <template v-else-if="summary && mode === 'redo'">
      <div v-if="summary.activeCount === 0" class="card empty-state completion">
        <div class="empty-icon">✓</div>
        <h2>错题已全部清空</h2>
        <p class="muted">这一轮薄弱点已经完成复习，继续保持！</p>
      </div>
      <div v-else class="card redo-panel">
        <p class="eyebrow">FOCUSED RETRY</p>
        <h2>开始一组错题重做</h2>
        <p class="muted">每组最多 5 题，直接复用原题，不会重新消耗 AI 出题或联网额度。</p>
        <label>
          <span>重做范围</span>
          <select v-model="redoChapterKey" class="select">
            <option value="">全部待重做错题</option>
            <option v-for="chapter in activeChapters" :key="chapter.chapterKey" :value="chapter.chapterKey">
              {{ chapter.chapterTitle }}（{{ chapter.activeCount }} 题）
            </option>
          </select>
        </label>
        <div v-if="currentReview" class="resume-box">
          <div><strong>你有一组未完成的错题</strong><p class="muted">共 {{ currentReview.questionCount }} 题</p></div>
          <button class="btn btn-secondary" @click="openQuiz(currentReview.quizId)">继续作答</button>
        </div>
        <button data-testid="start-redo" class="btn btn-primary start-button"
                :disabled="starting || Boolean(currentReview)" @click="startRedo">
          {{ starting ? '正在准备…' : `开始重做（最多 ${Math.min(5, selectedActiveCount)} 题）` }}
        </button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { assessmentApi } from '@/services/current/assessment'
import { describeError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import type { WrongQuestion, WrongQuestionReview, WrongQuestionStatus, WrongQuestionSummary } from '@/types/api'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'

const router = useRouter()
const route = useRoute()
const toast = useToastStore()
const mode = ref<'review' | 'redo'>(route.query.mode === 'redo' ? 'redo' : 'review')
const status = ref<WrongQuestionStatus>('ACTIVE')
const chapterKey = ref('')
const redoChapterKey = ref('')
const summary = ref<WrongQuestionSummary | null>(null)
const questions = ref<WrongQuestion[]>([])
const currentReview = ref<WrongQuestionReview | null>(null)
const loading = ref(true)
const starting = ref(false)
const error = ref('')
const pageIndex = ref(0)
const pageSize = 20
const totalElements = ref(0)

const activeChapters = computed(() => summary.value?.chapters.filter((c) => c.activeCount > 0) ?? [])
const selectedActiveCount = computed(() => {
  if (!redoChapterKey.value) return summary.value?.activeCount ?? 0
  return activeChapters.value.find((c) => c.chapterKey === redoChapterKey.value)?.activeCount ?? 0
})

function formatAnswers(answers: string[]): string {
  return answers.length ? answers.join('、') : '未作答'
}

function changeStatus(next: WrongQuestionStatus) {
  status.value = next
  pageIndex.value = 0
  void loadQuestions()
}

function changePage(next: number) {
  pageIndex.value = next
  void loadQuestions()
}

async function loadQuestions() {
  const page = await assessmentApi.listWrongQuestions({
    status: status.value,
    chapterKey: chapterKey.value || undefined,
    page: pageIndex.value,
    size: pageSize,
  })
  questions.value = page.items
  totalElements.value = page.totalElements
}

function openQuiz(quizId: string) {
  void router.push(`/quizzes/${quizId}`)
}

async function startRedo() {
  if (starting.value) return
  starting.value = true
  try {
    const review = await assessmentApi.createWrongQuestionReview({
      chapterKey: redoChapterKey.value || null,
      idempotencyKey: `wrong-review:${crypto.randomUUID()}`,
    })
    openQuiz(review.quizId)
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    starting.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [nextSummary, review] = await Promise.all([
      assessmentApi.getWrongQuestionSummary(),
      assessmentApi.getCurrentWrongQuestionReview(),
    ])
    summary.value = nextSummary
    currentReview.value = review
    await loadQuestions()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

watch(mode, (next) => {
  if (next === 'review') void loadQuestions()
})
onMounted(load)
</script>

<style scoped>
.wrong-book { max-width: 1120px; margin: 0 auto; }
.eyebrow { color: var(--color-primary); font-size: 11px; font-weight: 800; letter-spacing: .14em; margin: 0 0 8px; }
.stats { display: flex; gap: 10px; }
.stats div { min-width: 92px; padding: 12px 16px; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius); }
.stats strong, .stats span { display: block; }
.stats strong { font-size: 24px; }
.stats span { color: var(--color-text-secondary); font-size: 12px; }
.mode-tabs { display: inline-flex; padding: 4px; margin-bottom: 18px; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 12px; }
.mode-tabs button, .status-switch button { border: 0; background: transparent; color: var(--color-text-secondary); cursor: pointer; border-radius: 8px; padding: 9px 18px; }
.mode-tabs button.active, .status-switch button.active { color: var(--color-primary); background: var(--color-primary-soft); font-weight: 700; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; padding: 12px; }
.status-switch { display: flex; gap: 4px; }
.select { min-height: 40px; border: 1px solid var(--color-border); border-radius: 8px; background: var(--color-surface); color: var(--color-text); padding: 0 34px 0 12px; }
.wrong-card { margin-bottom: 14px; }
.question-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.index { color: var(--color-primary); font-weight: 800; letter-spacing: .1em; }
.count { margin-left: auto; font-size: 12px; }
.wrong-card h2 { font-size: 18px; line-height: 1.55; margin: 18px 0; }
.answer-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.answer { border-radius: 10px; padding: 14px; }
.answer span, .explanation span { display: block; font-size: 12px; font-weight: 700; margin-bottom: 8px; }
.answer strong { font-size: 14px; }
.answer pre { margin: 0; white-space: pre-wrap; }
.wrong-answer { background: var(--color-danger-soft, #fff1f2); color: #9f1239; }
.correct-answer { background: var(--color-success-soft, #ecfdf5); color: #166534; }
.explanation { margin-top: 12px; padding: 14px; border-left: 3px solid var(--color-primary); background: var(--color-primary-soft); }
.explanation p { margin: 0; line-height: 1.65; }
.empty-state { text-align: center; padding: 64px 24px; }
.empty-icon { width: 54px; height: 54px; margin: 0 auto 14px; display: grid; place-items: center; color: #fff; background: #16a34a; border-radius: 50%; font-size: 26px; }
.redo-panel { max-width: 680px; padding: 30px; }
.redo-panel label { display: grid; gap: 8px; margin: 24px 0; font-size: 13px; font-weight: 700; }
.resume-box { display: flex; align-items: center; justify-content: space-between; background: var(--color-primary-soft); border-radius: 10px; padding: 14px; margin-bottom: 14px; }
.resume-box p { margin: 4px 0 0; }
.start-button { width: 100%; justify-content: center; }
.pagination { display: flex; justify-content: center; align-items: center; gap: 14px; margin-top: 18px; }
@media (max-width: 720px) {
  .page-header, .toolbar { align-items: stretch; flex-direction: column; }
  .answer-grid { grid-template-columns: 1fr; }
  .stats { width: 100%; }
  .stats div { flex: 1; }
  .count { width: 100%; margin-left: 0; }
}
</style>
