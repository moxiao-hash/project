<template>
  <div>
    <EmptyState
      v-if="tasks.length === 0"
      icon="📝"
      title="暂无任务"
      description="任务会显示在这里。"
    />
    <div v-else class="task-list">
      <div v-for="task in tasks" :key="task.id" class="task-row">
        <div class="task-info">
          <div class="row">
            <span class="task-title" :class="{ done: task.status === 'COMPLETED' }">
              {{ task.title }}
            </span>
            <StatusBadge
              :label="taskStatusLabels[task.status]"
              :badge-class="taskStatusBadge[task.status]"
            />
            <span class="tag">{{ taskKindLabels[task.taskKind] }}</span>
          </div>
          <div class="task-meta muted">
            📅 {{ task.scheduledDate }} · 预估 {{ formatMinutes(task.estimatedMinutes) }}
            <template v-if="task.actualMinutes !== null">
              · 实际 {{ formatMinutes(task.actualMinutes) }}
            </template>
            <template v-if="task.knowledgePoint"> · 知识点：{{ task.knowledgePoint }}</template>
          </div>
        </div>
        <div class="task-actions">
          <template v-if="task.status === 'TODO'">
            <button class="btn btn-primary btn-sm" @click="openAction(task, 'COMPLETED')">完成</button>
            <button class="btn btn-secondary btn-sm" @click="openAction(task, 'SKIPPED')">跳过</button>
            <button class="btn btn-secondary btn-sm" @click="openAction(task, 'DEFERRED')">延期</button>
          </template>
          <button
            v-if="showQuizGen"
            class="btn btn-ghost btn-sm"
            :disabled="quizGenTaskId === task.id"
            @click="onGenerateQuiz(task)"
          >
            {{ quizGenTaskId === task.id ? '生成中…' : '🧪 生成测验' }}
          </button>
          <button class="btn btn-ghost btn-sm" @click="toggleHistory(task.id)">
            {{ expandedHistoryId === task.id ? '收起历史' : '历史' }}
          </button>
        </div>
        <div v-if="expandedHistoryId === task.id" class="task-history">
          <LoadingBlock v-if="historyLoading" text="加载历史…" />
          <template v-else-if="history.length > 0">
            <div v-for="(change, i) in history" :key="i" class="history-item">
              <span class="mono">{{ formatDateTime(change.createdAt) }}</span>
              <StatusBadge :label="taskStatusLabels[change.fromStatus]" badge-class="badge-neutral" />
              →
              <StatusBadge
                :label="taskStatusLabels[change.toStatus]"
                :badge-class="taskStatusBadge[change.toStatus]"
              />
              <span v-if="change.fromScheduledDate !== change.toScheduledDate" class="muted">
                {{ change.fromScheduledDate }} → {{ change.toScheduledDate }}
              </span>
              <span v-if="change.reason" class="muted">原因：{{ change.reason }}</span>
              <span v-if="change.actualMinutes !== null" class="muted">
                实际 {{ formatMinutes(change.actualMinutes) }}
              </span>
            </div>
          </template>
          <div v-else class="muted" style="font-size: 13px">暂无变更记录</div>
        </div>
      </div>
    </div>

    <!-- 完成 -->
    <ConfirmDialog
      v-model="completeDialog"
      title="完成任务"
      :confirm-text="`确认完成「${activeTask?.title ?? ''}」`"
      :loading="submitting"
      @confirm="submitComplete"
    >
      <div class="form-field">
        <label class="form-label">实际用时（分钟，可选，1～720）</label>
        <input v-model.number="actualMinutes" class="input" type="number" min="1" max="720" placeholder="留空表示不记录" />
      </div>
    </ConfirmDialog>

    <!-- 跳过 -->
    <ConfirmDialog
      v-model="skipDialog"
      title="跳过任务"
      :confirm-text="`确认跳过「${activeTask?.title ?? ''}」`"
      :loading="submitting"
      @confirm="submitSkip"
    >
      <div class="form-field">
        <label class="form-label">跳过原因（建议填写）</label>
        <textarea v-model.trim="skipReason" class="textarea" placeholder="例如：今天加班，没时间学习" />
      </div>
    </ConfirmDialog>

    <!-- 延期 -->
    <ConfirmDialog
      v-model="deferDialog"
      title="延期任务"
      :confirm-text="`确认延期「${activeTask?.title ?? ''}」`"
      :loading="submitting"
      @confirm="submitDefer"
    >
      <div class="form-field">
        <label class="form-label">延期到</label>
        <input v-model="deferredDate" class="input" type="date" :min="minDeferDate" required />
        <span v-if="deferError" class="field-error">{{ deferError }}</span>
      </div>
    </ConfirmDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { learningApi } from '@/services/current/learning'
import { agentGateway, gatewayMode } from '@/services/planned'
import { describeError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import { formatDateTime, formatMinutes, todayString, addDays } from '@/utils/datetime'
import { taskKindLabels, taskStatusBadge, taskStatusLabels } from '@/utils/labels'
import type { LearningTask, TaskChange, TaskStatus } from '@/types/api'
import { AxiosError } from 'axios'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'

withDefaults(defineProps<{ tasks: LearningTask[]; showQuizGen?: boolean }>(), {
  showQuizGen: false,
})

const emit = defineEmits<{ changed: [] }>()

const router = useRouter()
const toast = useToastStore()

const activeTask = ref<LearningTask | null>(null)
const submitting = ref(false)

const completeDialog = ref(false)
const skipDialog = ref(false)
const deferDialog = ref(false)
const actualMinutes = ref<number | null>(null)
const skipReason = ref('')
const deferredDate = ref('')
const deferError = ref('')

const minDeferDate = computed(() =>
  activeTask.value ? addDays(activeTask.value.scheduledDate, 1) : todayString(),
)

const expandedHistoryId = ref<string | null>(null)
const history = ref<TaskChange[]>([])
const historyLoading = ref(false)
const quizGenTaskId = ref<string | null>(null)

function openAction(task: LearningTask, action: TaskStatus) {
  activeTask.value = task
  actualMinutes.value = null
  skipReason.value = ''
  deferredDate.value = addDays(task.scheduledDate, 1)
  deferError.value = ''
  if (action === 'COMPLETED') completeDialog.value = true
  else if (action === 'SKIPPED') skipDialog.value = true
  else deferDialog.value = true
}

async function submitChange(
  body: Parameters<typeof learningApi.changeTaskStatus>[1],
  dialog: typeof completeDialog,
  successText: string,
) {
  if (!activeTask.value || submitting.value) return
  submitting.value = true
  try {
    await learningApi.changeTaskStatus(activeTask.value.id, body)
    toast.success(successText)
    dialog.value = false
    emit('changed')
  } catch (e) {
    if (e instanceof AxiosError && e.response?.status === 409) {
      toast.warning('任务状态或版本已变化，已为你刷新最新数据')
      dialog.value = false
      emit('changed')
    } else {
      toast.error(describeError(e))
    }
  } finally {
    submitting.value = false
  }
}

function submitComplete() {
  const minutes = actualMinutes.value
  if (minutes !== null && (minutes < 1 || minutes > 720 || !Number.isFinite(minutes))) {
    toast.warning('实际用时需为 1～720 分钟')
    return
  }
  void submitChange(
    { status: 'COMPLETED', actualMinutes: minutes ?? null },
    completeDialog,
    '任务已完成，干得漂亮！',
  )
}

function submitSkip() {
  void submitChange(
    { status: 'SKIPPED', reason: skipReason.value || null },
    skipDialog,
    '任务已跳过',
  )
}

function submitDefer() {
  if (!deferredDate.value) {
    deferError.value = '延期必须选择新的日期'
    return
  }
  void submitChange(
    { status: 'DEFERRED', scheduledDate: deferredDate.value },
    deferDialog,
    '任务已延期',
  )
}

async function toggleHistory(taskId: string) {
  if (expandedHistoryId.value === taskId) {
    expandedHistoryId.value = null
    return
  }
  expandedHistoryId.value = taskId
  historyLoading.value = true
  try {
    history.value = await learningApi.taskHistory(taskId)
  } catch (e) {
    toast.error(describeError(e))
    expandedHistoryId.value = null
  } finally {
    historyLoading.value = false
  }
}

async function onGenerateQuiz(task: LearningTask) {
  if (quizGenTaskId.value) return
  quizGenTaskId.value = task.id
  try {
    const { quizId } = await agentGateway.generateQuiz(task.id, 'AUTO')
    if (gatewayMode === 'mock') {
      toast.info(`Mock 模式：测验已模拟生成（${quizId}），联调后将自动跳转作答页`)
    } else {
      void router.push(`/quizzes/${quizId}`)
    }
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    quizGenTaskId.value = null
  }
}
</script>

<style scoped>
.task-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.task-row {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
  background: var(--color-surface);
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.task-info {
  flex: 1;
  min-width: 220px;
}

.task-title {
  font-weight: 600;
}

.task-title.done {
  text-decoration: line-through;
  color: var(--color-text-secondary);
}

.task-meta {
  font-size: 12px;
  margin-top: 4px;
}

.task-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.task-history {
  flex-basis: 100%;
  border-top: 1px dashed var(--color-border);
  padding-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
}
</style>
