<template>
  <div class="page">
    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />

    <template v-else-if="plan">
      <div class="page-header">
        <div>
          <div class="row">
            <h1 class="page-title">{{ plan.title }}</h1>
            <StatusBadge :label="planStatusLabels[plan.status]" :badge-class="planStatusBadge[plan.status]" />
            <span class="tag">版本 v{{ plan.version }}</span>
          </div>
          <p class="page-subtitle">{{ plan.startDate }} ～ {{ plan.endDate }}</p>
        </div>
        <div class="row">
          <button
            v-if="plan.status === 'DRAFT'"
            class="btn btn-primary"
            :disabled="confirming"
            @click="confirmDialog = true"
          >
            确认计划
          </button>
          <button class="btn btn-secondary" @click="adjustmentOpen = !adjustmentOpen">
            🧭 自适应调整
          </button>
        </div>
      </div>

      <!-- 任务 -->
      <div class="card">
        <div class="row" style="margin-bottom: 14px">
          <h2 class="section-title">任务</h2>
          <span class="spacer" />
          <button
            v-if="plan.status === 'CONFIRMED'"
            class="btn btn-primary btn-sm"
            @click="taskDialog = true"
          >
            添加任务
          </button>
        </div>
        <div v-if="plan.status === 'DRAFT'" class="alert alert-info">
          草稿计划需要先「确认计划」才能添加正式任务。
        </div>
        <TaskList :tasks="planTasks" show-quiz-gen @changed="loadTasks" />
      </div>

      <!-- 版本历史 -->
      <div class="card">
        <div class="row" style="margin-bottom: 14px">
          <h2 class="section-title">版本历史</h2>
          <span class="spacer" />
          <button class="btn btn-ghost btn-sm" @click="toggleVersions">
            {{ versionsOpen ? '收起' : '展开' }}
          </button>
        </div>
        <template v-if="versionsOpen">
          <LoadingBlock v-if="versionsLoading" text="加载版本…" />
          <EmptyState v-else-if="versions.length === 0" icon="🕘" title="暂无版本记录" />
          <div v-else class="version-list">
            <details v-for="v in versions" :key="v.version" class="version-item">
              <summary>
                <span class="tag">v{{ v.version }}</span>
                <span class="version-reason">{{ v.changeReason }}</span>
                <span class="muted mono">{{ formatDateTime(v.createdAt) }}</span>
              </summary>
              <pre class="version-snapshot mono">{{ prettySnapshot(v.snapshotJson) }}</pre>
            </details>
          </div>
        </template>
      </div>

      <!-- 自适应调整 -->
      <div v-if="adjustmentOpen" class="card">
        <MockBanner />
        <PlanAdjustmentPanel :plan-id="plan.id" @applied="load" />
      </div>

      <!-- 确认计划 -->
      <ConfirmDialog
        v-model="confirmDialog"
        title="确认计划"
        :confirm-text="`确认计划「${plan.title}」`"
        :loading="confirming"
        @confirm="onConfirmPlan"
      >
        <p>
          确认后计划将进入正式状态，可以添加任务；任务的后续变更会记录版本历史。
        </p>
      </ConfirmDialog>

      <!-- 添加任务 -->
      <Teleport to="body">
        <div v-if="taskDialog" class="dialog-mask" @click.self="taskDialog = false">
          <div class="dialog">
            <h3 style="margin-bottom: 16px">添加任务</h3>
            <form @submit.prevent="onCreateTask">
              <div class="form-field">
                <label class="form-label">任务名称</label>
                <input v-model.trim="taskForm.title" class="input" maxlength="160" required />
              </div>
              <div class="form-field">
                <label class="form-label">计划日期</label>
                <input
                  v-model="taskForm.scheduledDate"
                  class="input"
                  type="date"
                  :min="plan.startDate"
                  :max="plan.endDate"
                  required
                />
              </div>
              <div class="form-field">
                <label class="form-label">预估时长（分钟，5～720）</label>
                <input v-model.number="taskForm.estimatedMinutes" class="input" type="number" min="5" max="720" required />
              </div>
              <div v-if="taskError" class="alert alert-danger">{{ taskError }}</div>
              <div style="display: flex; justify-content: flex-end; gap: 10px">
                <button type="button" class="btn btn-secondary" :disabled="creatingTask" @click="taskDialog = false">
                  取消
                </button>
                <button type="submit" class="btn btn-primary" :disabled="creatingTask">
                  {{ creatingTask ? '创建中…' : '创建任务' }}
                </button>
              </div>
            </form>
          </div>
        </div>
      </Teleport>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { learningApi } from '@/services/current/learning'
import { describeError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import { formatDateTime, todayString } from '@/utils/datetime'
import { planStatusBadge, planStatusLabels } from '@/utils/labels'
import type { LearningPlan, LearningPlanVersion, LearningTask } from '@/types/api'
import { AxiosError } from 'axios'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import MockBanner from '@/components/MockBanner.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import TaskList from '@/components/TaskList.vue'
import PlanAdjustmentPanel from '@/modules/agent/PlanAdjustmentPanel.vue'

const route = useRoute()
const toast = useToastStore()
const planId = route.params.id as string

const plan = ref<LearningPlan | null>(null)
const planTasks = ref<LearningTask[]>([])
const loading = ref(true)
const error = ref('')

const confirmDialog = ref(false)
const confirming = ref(false)

const versionsOpen = ref(false)
const versionsLoading = ref(false)
const versions = ref<LearningPlanVersion[]>([])

const adjustmentOpen = ref(false)

const taskDialog = ref(false)
const creatingTask = ref(false)
const taskError = ref('')
const taskForm = reactive({
  title: '',
  scheduledDate: todayString(),
  estimatedMinutes: 45,
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [plans, tasks] = await Promise.all([
      learningApi.listPlans(),
      learningApi.listTasks(),
    ])
    const found = plans.find((p) => p.id === planId)
    if (!found) {
      error.value = '计划不存在或不属于当前账号'
      return
    }
    plan.value = found
    planTasks.value = tasks
      .filter((t) => t.planId === planId)
      .sort((a, b) => a.scheduledDate.localeCompare(b.scheduledDate))
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function loadTasks() {
  try {
    const tasks = await learningApi.listTasks()
    planTasks.value = tasks
      .filter((t) => t.planId === planId)
      .sort((a, b) => a.scheduledDate.localeCompare(b.scheduledDate))
    // 任务变更可能影响计划版本
    const plans = await learningApi.listPlans()
    const found = plans.find((p) => p.id === planId)
    if (found) plan.value = found
    if (versionsOpen.value) await loadVersions()
  } catch (e) {
    toast.error(describeError(e))
  }
}

async function onConfirmPlan() {
  if (confirming.value || !plan.value) return
  confirming.value = true
  try {
    plan.value = await learningApi.confirmPlan(plan.value.id)
    confirmDialog.value = false
    toast.success('计划已确认')
  } catch (e) {
    if (e instanceof AxiosError && e.response?.status === 409) {
      toast.warning('计划状态已变化，已为你刷新')
      confirmDialog.value = false
      await load()
    } else {
      toast.error(describeError(e))
    }
  } finally {
    confirming.value = false
  }
}

async function toggleVersions() {
  versionsOpen.value = !versionsOpen.value
  if (versionsOpen.value && versions.value.length === 0) await loadVersions()
}

async function loadVersions() {
  versionsLoading.value = true
  try {
    versions.value = await learningApi.listPlanVersions(planId)
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    versionsLoading.value = false
  }
}

function prettySnapshot(json: string): string {
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

async function onCreateTask() {
  if (
    !Number.isFinite(taskForm.estimatedMinutes) ||
    taskForm.estimatedMinutes < 5 ||
    taskForm.estimatedMinutes > 720
  ) {
    taskError.value = '预估时长需为 5～720 分钟'
    return
  }
  if (creatingTask.value) return
  creatingTask.value = true
  taskError.value = ''
  try {
    await learningApi.createTask(planId, { ...taskForm })
    toast.success('任务已创建')
    taskDialog.value = false
    taskForm.title = ''
    await loadTasks()
  } catch (e) {
    taskError.value = describeError(e)
    if (e instanceof AxiosError && e.response?.status === 409) await load()
  } finally {
    creatingTask.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.section-title {
  font-size: 16px;
}

.version-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.version-item summary {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  padding: 8px;
  border-radius: 8px;
}

.version-item summary:hover {
  background: var(--color-bg);
}

.version-reason {
  flex: 1;
  font-size: 13px;
}

.version-snapshot {
  background: var(--color-bg);
  border-radius: 8px;
  padding: 12px;
  overflow-x: auto;
  font-size: 12px;
  margin: 4px 0 8px;
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
