<template>
  <div class="page">
    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />

    <template v-else-if="quiz">
      <div class="page-header">
        <div>
          <h1 class="page-title">{{ quiz.title }}</h1>
          <p class="page-subtitle">
            <template v-if="quiz.kind === 'WRONG_QUESTION_REVIEW'">
              原题复习 · 不会重新消耗 AI 出题或联网额度 · 共 {{ quiz.questions.length }} 题
            </template>
            <template v-else>由 {{ quiz.modelName }} 生成 · 共 {{ quiz.questions.length }} 题</template>
          </p>
        </div>
      </div>

      <div v-for="(question, index) in quiz.questions" :key="question.id" class="card question-card">
        <div class="row" style="margin-bottom: 10px">
          <span class="question-index">第 {{ index + 1 }} 题</span>
          <span class="badge badge-neutral">{{ questionTypeLabels[question.type] }}</span>
          <span class="badge" :class="difficultyBadge(question.difficulty)">
            {{ difficultyLabels[question.difficulty] }}
          </span>
          <span class="tag">{{ question.knowledgePoint }}</span>
          <span v-if="question.language" class="tag mono">{{ question.language }}</span>
        </div>

        <p class="question-text">{{ question.questionText }}</p>

        <!-- 选择题 -->
        <div v-if="question.type !== 'CODING'" class="options">
          <label
            v-for="(option, oi) in question.options"
            :key="oi"
            class="option"
            :class="{ selected: isSelected(question.id, option) }"
          >
            <input
              v-if="question.type === 'SINGLE_CHOICE'"
              type="radio"
              :name="question.id"
              :checked="isSelected(question.id, option)"
              @change="selectSingle(question.id, option)"
            />
            <input
              v-else
              type="checkbox"
              :checked="isSelected(question.id, option)"
              @change="toggleMultiple(question.id, option)"
            />
            <span>{{ option }}</span>
          </label>
        </div>

        <!-- 编程题 -->
        <div v-else class="coding">
          <div class="alert alert-info" style="margin-bottom: 10px">
            编程题仅做 AI 文本评价，不会编译或运行代码。
          </div>
          <pre v-if="question.starterCode" class="starter-code mono">{{ question.starterCode }}</pre>
          <textarea
            class="textarea code-input mono"
            rows="10"
            placeholder="在此编写你的代码…"
            :value="answers.get(question.id)?.codeAnswer ?? question.starterCode ?? ''"
            @input="setCode(question.id, ($event.target as HTMLTextAreaElement).value)"
          />
        </div>

        <!-- 题目来源 -->
        <div v-if="question.sources.length > 0" class="question-sources">
          <div class="muted" style="font-size: 12px; margin-bottom: 6px">题目来源：</div>
          <div v-for="(s, si) in question.sources" :key="si" class="source-line">
            <span class="badge badge-primary" style="font-size: 11px">{{ s.sourceType }}</span>
            <span>{{ s.title }}</span>
            <span v-if="s.locator" class="muted">{{ s.locator }}</span>
          </div>
        </div>
      </div>

      <div class="submit-bar">
        <div class="muted">
          已作答 {{ answeredCount }}/{{ quiz.questions.length }} 题
        </div>
        <button class="btn btn-primary" :disabled="submitting || answeredCount === 0" @click="confirmSubmit = true">
          {{ submitting ? '提交中…' : '提交答案' }}
        </button>
      </div>

      <ConfirmDialog
        v-model="confirmSubmit"
        title="提交答案"
        :confirm-text="`确认提交 ${answeredCount} 题答案`"
        :loading="submitting"
        @confirm="onSubmit"
      >
        <p v-if="answeredCount < quiz.questions.length" class="alert alert-warning">
          还有 {{ quiz.questions.length - answeredCount }} 题未作答，未作答的题目将按空答案提交。
        </p>
        <p v-else>提交后将立即判分；含编程题时会进入异步评分。</p>
      </ConfirmDialog>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { assessmentApi } from '@/services/current/assessment'
import { describeError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import { difficultyLabels, questionTypeLabels } from '@/utils/labels'
import type { Difficulty, Quiz } from '@/types/api'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'

interface AnswerDraft {
  selectedAnswers: string[] | null
  codeAnswer: string | null
}

const route = useRoute()
const router = useRouter()
const toast = useToastStore()
const quizId = route.params.id as string

const quiz = ref<Quiz | null>(null)
const loading = ref(true)
const error = ref('')
const submitting = ref(false)
const confirmSubmit = ref(false)
const answers = reactive(new Map<string, AnswerDraft>())

/** 幂等键：每次点击提交生成一次，网络重试复用同一键 */
let idempotencyKey: string | null = null

const answeredCount = computed(() => {
  let count = 0
  for (const q of quiz.value?.questions ?? []) {
    const a = answers.get(q.id)
    if (!a) continue
    if (q.type === 'CODING' ? (a.codeAnswer ?? '').trim() : (a.selectedAnswers?.length ?? 0) > 0) {
      count++
    }
  }
  return count
})

function difficultyBadge(d: Difficulty): string {
  return d === 'EASY' ? 'badge-success' : d === 'MEDIUM' ? 'badge-warning' : 'badge-danger'
}

function isSelected(questionId: string, option: string): boolean {
  return answers.get(questionId)?.selectedAnswers?.includes(option) ?? false
}

function selectSingle(questionId: string, option: string) {
  answers.set(questionId, { selectedAnswers: [option], codeAnswer: null })
}

function toggleMultiple(questionId: string, option: string) {
  const current = answers.get(questionId)?.selectedAnswers ?? []
  const next = current.includes(option)
    ? current.filter((o) => o !== option)
    : [...current, option]
  answers.set(questionId, { selectedAnswers: next, codeAnswer: null })
}

function setCode(questionId: string, code: string) {
  const existing = answers.get(questionId)
  answers.set(questionId, { selectedAnswers: existing?.selectedAnswers ?? null, codeAnswer: code })
}

async function onSubmit() {
  if (!quiz.value || submitting.value) return
  submitting.value = true
  try {
    // 同一轮提交复用幂等键；新一轮提交才生成新键
    if (!idempotencyKey) {
      idempotencyKey = `quiz-attempt:${quizId}:${crypto.randomUUID()}`
    }
    const attempt = await assessmentApi.submitAttempt(quizId, {
      idempotencyKey,
      answers: quiz.value.questions.map((q) => {
        const a = answers.get(q.id)
        return {
          questionId: q.id,
          selectedAnswers: a?.selectedAnswers ?? null,
          codeAnswer: a?.codeAnswer ?? null,
        }
      }),
    })
    confirmSubmit.value = false
    void router.push(`/attempts/${attempt.id}`)
  } catch (e) {
    // 网络层失败（超时等）可能实际已提交成功，不能盲目用新键重试；
    // 提示用户并由用户决定是否再次提交（复用原幂等键）。
    toast.error(describeError(e))
  } finally {
    submitting.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    quiz.value = await assessmentApi.getQuiz(quizId)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.question-card {
  margin-bottom: 14px;
}

.question-index {
  font-weight: 700;
}

.question-text {
  font-size: 15px;
  margin: 0 0 14px;
  white-space: pre-wrap;
}

.options {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.option {
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.option:hover {
  border-color: var(--color-primary);
}

.option.selected {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}

.starter-code {
  background: #14171f;
  color: #d5daf0;
  border-radius: 8px;
  padding: 12px;
  overflow-x: auto;
  font-size: 13px;
}

.code-input {
  font-size: 13px;
  min-height: 180px;
}

.question-sources {
  margin-top: 14px;
  border-top: 1px dashed var(--color-border);
  padding-top: 10px;
}

.source-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  margin-bottom: 4px;
}

.submit-bar {
  position: sticky;
  bottom: 0;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  padding: 12px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16px;
}
</style>
