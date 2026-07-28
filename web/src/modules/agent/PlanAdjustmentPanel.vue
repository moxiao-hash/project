<template>
  <div>
    <div class="row" style="margin-bottom: 14px">
      <h2 class="section-title">计划自适应调整</h2>
      <span class="spacer" />
      <input v-model="analysisDate" class="input date-input" type="date" />
      <button class="btn btn-primary btn-sm" :disabled="analyzing" @click="onAnalyze">
        {{ analyzing ? '分析中…' : '开始分析' }}
      </button>
    </div>

    <LoadingBlock v-if="adjustment?.status === 'ANALYZING'" text="正在分析学习偏差信号…" />

    <template v-else-if="adjustment">
      <div class="row" style="margin-bottom: 10px">
        <StatusBadge
          :label="statusLabel(adjustment.status)"
          :badge-class="statusBadge(adjustment.status)"
        />
        <span class="badge" :class="adjustment.riskLevel === 'HIGH' ? 'badge-danger' : 'badge-success'">
          {{ riskLevelLabels[adjustment.riskLevel] }}
        </span>
        <span class="muted" style="font-size: 12px">
          分析日期 {{ adjustment.analysisDate }} · 计划版本 v{{ adjustment.beforePlanVersion }}
          <template v-if="adjustment.afterPlanVersion !== null">
            → v{{ adjustment.afterPlanVersion }}
          </template>
        </span>
      </div>

      <!-- 偏差信号 -->
      <div v-if="adjustment.signals.length > 0" class="row" style="margin-bottom: 10px">
        <span v-for="signal in adjustment.signals" :key="signal" class="badge badge-warning">
          {{ signalLabels[signal] }}
        </span>
      </div>

      <p class="summary-text">{{ adjustment.summary }}</p>

      <div v-if="adjustment.error" class="alert alert-danger">{{ adjustment.error }}</div>

      <div v-if="adjustment.status === 'NO_CHANGE'" class="alert alert-info">
        未发现需要调整的偏差，计划保持不变。
      </div>

      <!-- 操作列表 -->
      <template v-if="adjustment.operations.length > 0">
        <h3 class="sub-title">调整操作（{{ adjustment.operations.length }}）</h3>
        <div class="op-list">
          <div v-for="(op, i) in adjustment.operations" :key="i" class="op-item">
            <div class="row">
              <span class="badge badge-primary">{{ operationLabels[op.type] }}</span>
              <span class="mono muted" style="font-size: 12px">
                任务 {{ op.taskId }} · 版本 v{{ op.expectedVersion }}
              </span>
            </div>
            <div class="op-detail">
              <template v-if="op.type === 'RESCHEDULE_TASK'">
                重新排期到 <strong>{{ op.scheduledDate }}</strong>
              </template>
              <template v-else-if="op.type === 'UPDATE_ESTIMATE'">
                预估时长调整为 <strong>{{ op.estimatedMinutes }} 分钟</strong>
              </template>
              <template v-else-if="op.type === 'SPLIT_TASK'">
                拆分为「{{ op.firstTitle }}」（{{ op.firstEstimatedMinutes }} 分钟）和
                「{{ op.secondTitle }}」（{{ op.secondScheduledDate }}，
                {{ op.secondEstimatedMinutes }} 分钟）
              </template>
              <template v-else-if="op.type === 'INSERT_REVIEW_TASK'">
                插入复习任务「{{ op.title }}」（{{ op.estimatedMinutes }} 分钟，
                {{ op.scheduledDate }}）
                <template v-if="op.knowledgePoint"> · 知识点：{{ op.knowledgePoint }}</template>
              </template>
            </div>
          </div>
        </div>
      </template>

      <!-- 确认区 -->
      <div v-if="adjustment.status === 'DRAFT_READY'" class="confirm-area">
        <div class="alert" :class="adjustment.riskLevel === 'HIGH' ? 'alert-danger' : 'alert-warning'">
          {{ adjustment.riskLevel === 'HIGH'
            ? '高风险调整：将直接修改计划任务，必须人工确认后执行。'
            : '低风险调整：确认后自动执行。' }}
        </div>
        <button
          class="btn"
          :class="adjustment.riskLevel === 'HIGH' ? 'btn-danger' : 'btn-primary'"
          :disabled="confirming"
          @click="confirmDialog = true"
        >
          确认执行 {{ adjustment.operations.length }} 项调整
        </button>
      </div>

      <div v-if="adjustment.status === 'EXECUTING'" class="alert alert-info">
        <span class="spinner" /> 正在执行调整…
      </div>

      <div v-if="adjustment.status === 'COMPLETED'" class="alert alert-info">
        调整已完成，计划已更新到 v{{ adjustment.afterPlanVersion }}。
      </div>
    </template>

    <EmptyState v-else icon="🧭" title="尚未分析" description="选择分析日期后开始，系统会检查逾期、连续跳过和预估偏差。" />

    <ConfirmDialog
      v-model="confirmDialog"
      title="确认计划调整"
      :confirm-text="`确认执行 ${adjustment?.operations.length ?? 0} 项计划调整`"
      :danger="adjustment?.riskLevel === 'HIGH'"
      :loading="confirming"
      @confirm="onConfirm"
    >
      <p>{{ adjustment?.summary }}</p>
      <p class="muted" style="font-size: 13px">
        执行基于计划版本 v{{ adjustment?.beforePlanVersion }}；若计划已被修改，将刷新后重新分析。
      </p>
    </ConfirmDialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { agentGateway } from '@/services/planned'
import { describeError } from '@/services/http'
import { usePolling } from '@/composables/usePolling'
import { useToastStore } from '@/stores/toast'
import { todayString } from '@/utils/datetime'
import { riskLevelLabels } from '@/utils/labels'
import type {
  AdjustmentOperationType,
  AdjustmentSignal,
  PlanAdjustment,
  PlanAdjustmentStatus,
} from '@/types/agent'
import { AxiosError } from 'axios'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'

defineProps<{ planId: string }>()
const emit = defineEmits<{ applied: [] }>()

const toast = useToastStore()
const analysisDate = ref(todayString())
const adjustment = ref<PlanAdjustment | null>(null)
const analyzing = ref(false)
const confirming = ref(false)
const confirmDialog = ref(false)

const signalLabels: Record<AdjustmentSignal, string> = {
  OVERDUE_TASKS: '存在逾期任务',
  CONSECUTIVE_SKIPS: '连续跳过',
  TIME_ESTIMATE_BIAS: '预估时长偏差',
}

const operationLabels: Record<AdjustmentOperationType, string> = {
  RESCHEDULE_TASK: '重新排期',
  UPDATE_ESTIMATE: '调整预估',
  SPLIT_TASK: '拆分任务',
  INSERT_REVIEW_TASK: '插入复习',
}

function statusLabel(status: PlanAdjustmentStatus): string {
  const map: Record<PlanAdjustmentStatus, string> = {
    ANALYZING: '分析中',
    NO_CHANGE: '无需调整',
    DRAFT_READY: '待确认',
    EXECUTING: '执行中',
    COMPLETED: '已完成',
    FAILED: '失败',
  }
  return map[status]
}

function statusBadge(status: PlanAdjustmentStatus): string {
  const map: Record<PlanAdjustmentStatus, string> = {
    ANALYZING: 'badge-info',
    NO_CHANGE: 'badge-neutral',
    DRAFT_READY: 'badge-warning',
    EXECUTING: 'badge-info',
    COMPLETED: 'badge-success',
    FAILED: 'badge-danger',
  }
  return map[status]
}

// ANALYZING / EXECUTING 期间轮询
const polling = usePolling<PlanAdjustment>({
  fetcher: () => {
    if (!adjustment.value) throw new Error('无分析记录')
    return agentGateway.getPlanAdjustment(adjustment.value.id)
  },
  shouldContinue: (a) => a.status === 'ANALYZING' || a.status === 'EXECUTING',
  interval: 2000,
  onData: (a) => {
    adjustment.value = a
    if (a.status === 'COMPLETED') emit('applied')
  },
  onFailed: (e) => toast.error(describeError(e)),
})

async function onAnalyze() {
  if (analyzing.value) return
  analyzing.value = true
  try {
    adjustment.value = await agentGateway.analyzePlanAdjustment(analysisDate.value)
    if (adjustment.value.status === 'ANALYZING') polling.start()
    else if (adjustment.value.status === 'NO_CHANGE') toast.info('未发现需要调整的偏差')
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    analyzing.value = false
  }
}

async function onConfirm() {
  if (!adjustment.value || confirming.value) return
  confirming.value = true
  try {
    adjustment.value = await agentGateway.confirmPlanAdjustment(adjustment.value.id)
    confirmDialog.value = false
    if (adjustment.value.status === 'EXECUTING') polling.start()
    else if (adjustment.value.status === 'COMPLETED') emit('applied')
  } catch (e) {
    if (e instanceof AxiosError && e.response?.status === 409) {
      toast.warning('计划已被修改，请重新分析')
      confirmDialog.value = false
      adjustment.value = null
      emit('applied')
    } else {
      toast.error(describeError(e))
    }
  } finally {
    confirming.value = false
  }
}
</script>

<style scoped>
.section-title {
  font-size: 16px;
}

.sub-title {
  font-size: 14px;
  margin: 14px 0 8px;
}

.date-input {
  width: auto;
  padding: 4px 8px;
}

.summary-text {
  font-size: 14px;
  margin: 8px 0;
}

.op-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.op-item {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 10px 12px;
}

.op-detail {
  font-size: 13px;
  margin-top: 6px;
}

.confirm-area {
  margin-top: 14px;
}
</style>
