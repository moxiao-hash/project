<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">学习计划</h1>
        <p class="page-subtitle">确认后的计划才能添加正式任务</p>
      </div>
      <button class="btn btn-primary" @click="openCreate">新建计划</button>
    </div>

    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <div v-else-if="plans.length === 0" class="card">
      <EmptyState icon="🗓️" title="还没有学习计划" description="手动创建计划，或通过对话让 AI 帮你起草。">
        <div class="row" style="justify-content: center">
          <button class="btn btn-primary" @click="openCreate">手动创建</button>
          <RouterLink to="/agent/plan" class="btn btn-secondary">🪄 对话生成</RouterLink>
        </div>
      </EmptyState>
    </div>

    <div v-else class="plan-list">
      <div v-for="plan in plans" :key="plan.id" class="card plan-card">
        <RouterLink :to="`/plans/${plan.id}`" class="plan-main">
          <div class="row">
            <h3 class="plan-title">{{ plan.title }}</h3>
            <StatusBadge :label="planStatusLabels[plan.status]" :badge-class="planStatusBadge[plan.status]" />
            <span class="tag">v{{ plan.version }}</span>
          </div>
          <div class="muted" style="margin-top: 6px">
            {{ plan.startDate }} ～ {{ plan.endDate }} · 目标：{{ goalTitle(plan.goalId) }}
          </div>
        </RouterLink>
        <div class="plan-actions">
          <button
            v-if="plan.status === 'DRAFT'"
            class="btn btn-primary btn-sm"
            :disabled="confirmingId === plan.id"
            @click="onConfirm(plan)"
          >
            {{ confirmingId === plan.id ? '确认中…' : '确认计划' }}
          </button>
        </div>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="dialogOpen" class="dialog-mask" @click.self="dialogOpen = false">
        <div class="dialog">
          <h3 style="margin-bottom: 16px">新建计划</h3>
          <form @submit.prevent="onSave">
            <div class="form-field">
              <label class="form-label">所属目标</label>
              <select v-model="form.goalId" class="select" required>
                <option value="" disabled>请选择目标</option>
                <option v-for="goal in goals" :key="goal.id" :value="goal.id">{{ goal.title }}</option>
              </select>
              <span v-if="goals.length === 0" class="form-hint">
                还没有目标，请先在<RouterLink to="/goals">学习目标</RouterLink>页创建。
              </span>
            </div>
            <div class="form-field">
              <label class="form-label">计划名称</label>
              <input v-model.trim="form.title" class="input" maxlength="120" placeholder="例如：第一阶段 · 基础入门" required />
            </div>
            <div class="form-field">
              <label class="form-label">开始日期</label>
              <input v-model="form.startDate" class="input" type="date" required />
            </div>
            <div class="form-field">
              <label class="form-label">结束日期</label>
              <input v-model="form.endDate" class="input" type="date" :min="form.startDate" required />
              <span v-if="formErrorField" class="field-error">{{ formErrorField }}</span>
            </div>
            <div v-if="formError" class="alert alert-danger">{{ formError }}</div>
            <div style="display: flex; justify-content: flex-end; gap: 10px">
              <button type="button" class="btn btn-secondary" :disabled="saving" @click="dialogOpen = false">取消</button>
              <button type="submit" class="btn btn-primary" :disabled="saving || goals.length === 0">
                {{ saving ? '创建中…' : '创建计划' }}
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
import { planStatusBadge, planStatusLabels } from '@/utils/labels'
import { todayString } from '@/utils/datetime'
import type { LearningGoal, LearningPlan } from '@/types/api'
import LoadingBlock from '@/components/LoadingBlock.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const toast = useToastStore()
const plans = ref<LearningPlan[]>([])
const goals = ref<LearningGoal[]>([])
const loading = ref(true)
const error = ref('')
const confirmingId = ref<string | null>(null)

const dialogOpen = ref(false)
const saving = ref(false)
const formError = ref('')
const formErrorField = ref('')
const form = reactive({ goalId: '', title: '', startDate: todayString(), endDate: '' })

function goalTitle(goalId: string): string {
  return goals.value.find((g) => g.id === goalId)?.title ?? '未知目标'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [planList, goalList] = await Promise.all([
      learningApi.listPlans(),
      learningApi.listGoals(),
    ])
    plans.value = planList
    goals.value = goalList
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function onConfirm(plan: LearningPlan) {
  if (confirmingId.value) return
  confirmingId.value = plan.id
  try {
    await learningApi.confirmPlan(plan.id)
    toast.success(`计划「${plan.title}」已确认，可以开始添加任务`)
    await load()
  } catch (e) {
    toast.error(describeError(e))
    // 409：计划可能已被修改，重新拉取最新数据
    await load()
  } finally {
    confirmingId.value = null
  }
}

function openCreate() {
  form.goalId = goals.value[0]?.id ?? ''
  form.title = ''
  form.startDate = todayString()
  form.endDate = ''
  formError.value = ''
  formErrorField.value = ''
  dialogOpen.value = true
}

async function onSave() {
  formErrorField.value = ''
  if (form.endDate < form.startDate) {
    formErrorField.value = '结束日期不能早于开始日期'
    return
  }
  if (saving.value) return
  saving.value = true
  formError.value = ''
  try {
    await learningApi.createPlan({ ...form })
    toast.success('计划已创建')
    dialogOpen.value = false
    await load()
  } catch (e) {
    const apiError = getApiError(e)
    formError.value = apiError?.fieldErrors
      ? Object.values(apiError.fieldErrors).join('；')
      : describeError(e)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.plan-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.plan-card {
  display: flex;
  align-items: center;
  gap: 16px;
}

.plan-main {
  flex: 1;
  color: var(--color-text);
  min-width: 0;
}

.plan-title {
  font-size: 16px;
}

.plan-actions {
  flex-shrink: 0;
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
