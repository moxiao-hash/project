<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">学习目标</h1>
        <p class="page-subtitle">目标日期必须在未来，每周学习时长 1～40 小时</p>
      </div>
      <button class="btn btn-primary" @click="openCreate">新建目标</button>
    </div>

    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <div v-else-if="goals.length === 0" class="card">
      <EmptyState icon="🎯" title="还没有学习目标" description="创建一个目标，StudyPilot 会帮你拆解成可执行的计划。">
        <button class="btn btn-primary" @click="openCreate">创建第一个目标</button>
      </EmptyState>
    </div>

    <div v-else class="grid cols-2">
      <div v-for="goal in goals" :key="goal.id" class="card goal-card">
        <div class="row">
          <h3 class="goal-title">{{ goal.title }}</h3>
          <span class="spacer" />
          <StatusBadge :label="goalStatusLabel(goal.status)" badge-class="badge-primary" />
        </div>
        <div class="goal-meta">
          <div>📅 目标日期：<strong>{{ goal.targetDate }}</strong>（{{ daysLeft(goal.targetDate) }}）</div>
          <div>⏱️ 每周投入：<strong>{{ goal.weeklyStudyHours }} 小时</strong></div>
        </div>
        <div class="row" style="margin-top: 12px">
          <button class="btn btn-secondary btn-sm" @click="openEdit(goal)">编辑</button>
          <span class="spacer" />
          <RouterLink :to="{ name: 'agent-plan', query: { goalId: goal.id } }" class="btn btn-ghost btn-sm">
            🪄 对话生成计划
          </RouterLink>
        </div>
      </div>
    </div>

    <!-- 创建/编辑弹窗 -->
    <Teleport to="body">
      <div v-if="dialogOpen" class="dialog-mask" @click.self="dialogOpen = false">
        <div class="dialog">
          <h3 style="margin-bottom: 16px">{{ editingGoal ? '编辑目标' : '新建目标' }}</h3>
          <form @submit.prevent="onSave">
            <div class="form-field">
              <label class="form-label">目标名称</label>
              <input v-model.trim="form.title" class="input" maxlength="100" placeholder="例如：三个月掌握 Spring Boot" required />
              <span v-if="formErrors.title" class="field-error">{{ formErrors.title }}</span>
            </div>
            <div class="form-field">
              <label class="form-label">目标日期</label>
              <input v-model="form.targetDate" class="input" type="date" :min="minDate" required />
              <span v-if="formErrors.targetDate" class="field-error">{{ formErrors.targetDate }}</span>
            </div>
            <div class="form-field">
              <label class="form-label">每周学习时长（小时）</label>
              <input v-model.number="form.weeklyStudyHours" class="input" type="number" min="1" max="40" required />
              <span v-if="formErrors.weeklyStudyHours" class="field-error">{{ formErrors.weeklyStudyHours }}</span>
            </div>
            <div v-if="formError" class="alert alert-danger">{{ formError }}</div>
            <div style="display: flex; justify-content: flex-end; gap: 10px">
              <button type="button" class="btn btn-secondary" :disabled="saving" @click="dialogOpen = false">取消</button>
              <button type="submit" class="btn btn-primary" :disabled="saving">
                {{ saving ? '保存中…' : '保存目标' }}
              </button>
            </div>
          </form>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { learningApi } from '@/services/current/learning'
import { describeError, getApiError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import { addDays, todayString } from '@/utils/datetime'
import type { GoalStatus, LearningGoal } from '@/types/api'
import LoadingBlock from '@/components/LoadingBlock.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const toast = useToastStore()
const goals = ref<LearningGoal[]>([])
const loading = ref(true)
const error = ref('')

const dialogOpen = ref(false)
const editingGoal = ref<LearningGoal | null>(null)
const saving = ref(false)
const formError = ref('')
const formErrors = reactive<Record<string, string>>({})
const form = reactive({ title: '', targetDate: '', weeklyStudyHours: 10 })

const minDate = addDays(todayString(), 1)

function goalStatusLabel(_status: GoalStatus): string {
  return '进行中'
}

function daysLeft(targetDate: string): string {
  const diff = Math.ceil(
    (new Date(`${targetDate}T00:00:00`).getTime() - Date.now()) / 86_400_000,
  )
  if (diff < 0) return '已过期'
  if (diff === 0) return '今天到期'
  return `剩余 ${diff} 天`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    goals.value = await learningApi.listGoals()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingGoal.value = null
  form.title = ''
  form.targetDate = ''
  form.weeklyStudyHours = 10
  formError.value = ''
  Object.keys(formErrors).forEach((k) => delete formErrors[k])
  dialogOpen.value = true
}

function openEdit(goal: LearningGoal) {
  editingGoal.value = goal
  form.title = goal.title
  form.targetDate = goal.targetDate
  form.weeklyStudyHours = goal.weeklyStudyHours
  formError.value = ''
  Object.keys(formErrors).forEach((k) => delete formErrors[k])
  dialogOpen.value = true
}

function validate(): boolean {
  Object.keys(formErrors).forEach((k) => delete formErrors[k])
  let ok = true
  if (!form.title) {
    formErrors.title = '请输入目标名称'
    ok = false
  }
  if (!form.targetDate || form.targetDate <= todayString()) {
    formErrors.targetDate = '目标日期必须在未来'
    ok = false
  }
  if (
    !Number.isFinite(form.weeklyStudyHours) ||
    form.weeklyStudyHours < 1 ||
    form.weeklyStudyHours > 40
  ) {
    formErrors.weeklyStudyHours = '每周学习时长需为 1～40 小时'
    ok = false
  }
  return ok
}

async function onSave() {
  if (saving.value || !validate()) return
  saving.value = true
  formError.value = ''
  try {
    const body = {
      title: form.title,
      targetDate: form.targetDate,
      weeklyStudyHours: form.weeklyStudyHours,
    }
    if (editingGoal.value) {
      await learningApi.updateGoal(editingGoal.value.id, body)
      toast.success('目标已更新')
    } else {
      await learningApi.createGoal(body)
      toast.success('目标已创建')
    }
    dialogOpen.value = false
    await load()
  } catch (e) {
    const apiError = getApiError(e)
    if (apiError?.fieldErrors) Object.assign(formErrors, apiError.fieldErrors)
    else formError.value = describeError(e)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.goal-card {
  display: flex;
  flex-direction: column;
}

.goal-title {
  font-size: 16px;
}

.goal-meta {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 18, 30, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 500;
  padding: 20px;
}

.dialog {
  background: var(--color-surface);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  width: 100%;
  max-width: 440px;
  padding: 22px;
}
</style>
