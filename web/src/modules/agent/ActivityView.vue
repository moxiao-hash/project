<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">执行与审计</h1>
        <p class="page-subtitle">Agent 授权、执行状态与操作审计</p>
      </div>
    </div>

    <div class="tabs">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="tab"
        :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
        <span v-if="tab.key === 'executions' && waitingConfirmCount > 0" class="tab-badge">
          {{ waitingConfirmCount }}
        </span>
      </button>
    </div>

    <!-- 执行记录 -->
    <div v-show="activeTab === 'executions'" class="card">
      <LoadingBlock v-if="executionsLoading" />
      <ErrorState v-else-if="executionsError" :message="executionsError" @retry="loadExecutions" />
      <EmptyState v-else-if="executions.length === 0" icon="🧾" title="暂无执行记录" />
      <div v-else class="exec-list">
        <div v-for="exec in executions" :key="exec.id" class="exec-item">
          <div class="exec-head">
            <div class="row">
              <span class="badge badge-primary">{{ executionTypeLabels[exec.executionType] }}</span>
              <StatusBadge
                :label="executionStatusLabels[exec.status]"
                :badge-class="executionStatusBadge[exec.status]"
              />
              <span class="badge" :class="exec.riskLevel === 'HIGH' ? 'badge-danger' : 'badge-success'">
                {{ riskLevelLabels[exec.riskLevel] }}
              </span>
              <span class="muted" style="font-size: 12px">
                {{ triggerTypeLabels[exec.triggerType] }} · 需要授权：{{ agentScopeLabels[exec.requiredScope] }}
              </span>
            </div>
            <span class="muted mono" style="font-size: 12px">{{ formatDateTime(exec.createdAt) }}</span>
          </div>
          <p class="exec-summary">{{ exec.summary }}</p>
          <p v-if="exec.resultSummary" class="muted" style="font-size: 13px; margin: 4px 0">
            结果：{{ exec.resultSummary }}
          </p>
          <p v-if="exec.errorMessage" class="exec-error">错误：{{ exec.errorMessage }}</p>
          <div class="exec-meta muted">
            <template v-if="exec.modelName">模型 {{ exec.modelName }} · </template>
            <template v-if="exec.promptTokens !== null && exec.completionTokens !== null">
              tokens {{ exec.promptTokens }} + {{ exec.completionTokens }} ·
            </template>
            <template v-if="exec.latencyMs !== null">耗时 {{ exec.latencyMs }} ms · </template>
            <template v-if="exec.estimatedCost !== null">
              预估费用 ¥{{ exec.estimatedCost.toFixed(4) }} ·
            </template>
            <span class="mono">幂等键 {{ exec.idempotencyKey }}</span>
          </div>
          <div v-if="exec.status === 'WAITING_CONFIRMATION'" class="exec-actions">
            <span class="alert alert-warning execution-guidance">
              请返回发起该操作的 Agent 页面核对预览并确认。
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- 授权 -->
    <div v-show="activeTab === 'grants'" class="card">
      <div class="row" style="margin-bottom: 14px">
        <h2 class="section-title">Agent 授权</h2>
        <span class="spacer" />
        <button class="btn btn-primary btn-sm" @click="grantDialog = true">新建授权</button>
      </div>
      <LoadingBlock v-if="grantsLoading" />
      <ErrorState v-else-if="grantsError" :message="grantsError" @retry="loadGrants" />
      <EmptyState v-else-if="grants.length === 0" icon="🔑" title="暂无授权" description="授权后 Agent 才能在对应范围内执行操作。" />
      <div v-else class="grant-list">
        <div v-for="grant in grants" :key="grant.id" class="grant-item">
          <div class="row">
            <StatusBadge
              :label="grant.active ? '生效中' : '已失效'"
              :badge-class="grant.active ? 'badge-success' : 'badge-neutral'"
            />
            <span class="muted mono" style="font-size: 12px">至 {{ formatDateTime(grant.expiresAt) }}</span>
          </div>
          <div class="row" style="margin-top: 8px">
            <span v-for="scope in grant.scopes" :key="scope" class="tag">
              {{ agentScopeLabels[scope] }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- 审计日志 -->
    <div v-show="activeTab === 'audit'" class="card">
      <LoadingBlock v-if="auditLoading" />
      <ErrorState v-else-if="auditError" :message="auditError" @retry="loadAudit" />
      <EmptyState v-else-if="auditLogs.length === 0" icon="📜" title="暂无审计日志" />
      <table v-else class="audit-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>动作</th>
            <th>对象</th>
            <th>详情</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="log in auditLogs" :key="log.id">
            <td class="mono">{{ formatDateTime(log.createdAt) }}</td>
            <td><span class="tag">{{ log.action }}</span></td>
            <td class="mono">{{ log.targetType }} / {{ log.targetId.slice(0, 8) }}</td>
            <td class="muted">{{ log.details ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 新建授权 -->
    <Teleport to="body">
      <div v-if="grantDialog" class="dialog-mask" @click.self="grantDialog = false">
        <div class="dialog">
          <h3 style="margin-bottom: 16px">新建 Agent 授权</h3>
          <div class="form-field">
            <label class="form-label">授权范围（可多选）</label>
            <label v-for="(label, scope) in agentScopeLabels" :key="scope" class="scope-option">
              <input v-model="grantForm.scopes" type="checkbox" :value="scope" />
              <span>{{ label }}</span>
            </label>
          </div>
          <div class="form-field">
            <label class="form-label">有效期至</label>
            <input v-model="grantForm.expiresAt" class="input" type="datetime-local" :min="minExpiry" />
          </div>
          <div v-if="grantError" class="alert alert-danger">{{ grantError }}</div>
          <div style="display: flex; justify-content: flex-end; gap: 10px">
            <button class="btn btn-secondary" :disabled="creatingGrant" @click="grantDialog = false">取消</button>
            <button class="btn btn-primary" :disabled="creatingGrant" @click="onCreateGrant">
              {{ creatingGrant ? '创建中…' : '创建授权' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { agentOpsApi } from '@/services/current/agentOps'
import { describeError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import { formatDateTime } from '@/utils/datetime'
import {
  agentScopeLabels,
  executionStatusBadge,
  executionStatusLabels,
  executionTypeLabels,
  riskLevelLabels,
  triggerTypeLabels,
} from '@/utils/labels'
import type { AgentExecution, AgentGrant, AgentScope, AuditLog } from '@/types/api'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const toast = useToastStore()

const tabs = [
  { key: 'executions' as const, label: '执行记录' },
  { key: 'grants' as const, label: '授权' },
  { key: 'audit' as const, label: '审计日志' },
]
const activeTab = ref<'executions' | 'grants' | 'audit'>('executions')

const executions = ref<AgentExecution[]>([])
const executionsLoading = ref(true)
const executionsError = ref('')

const grants = ref<AgentGrant[]>([])
const grantsLoading = ref(true)
const grantsError = ref('')

const auditLogs = ref<AuditLog[]>([])
const auditLoading = ref(true)
const auditError = ref('')

const grantDialog = ref(false)
const creatingGrant = ref(false)
const grantError = ref('')
const grantForm = reactive<{ scopes: AgentScope[]; expiresAt: string }>({
  scopes: [],
  expiresAt: '',
})

const waitingConfirmCount = computed(
  () => executions.value.filter((e) => e.status === 'WAITING_CONFIRMATION').length,
)

const minExpiry = computed(() => {
  const d = new Date(Date.now() + 60_000)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
})

async function loadExecutions() {
  executionsLoading.value = true
  executionsError.value = ''
  try {
    executions.value = await agentOpsApi.listExecutions()
  } catch (e) {
    executionsError.value = describeError(e)
  } finally {
    executionsLoading.value = false
  }
}

async function loadGrants() {
  grantsLoading.value = true
  grantsError.value = ''
  try {
    grants.value = await agentOpsApi.listGrants()
  } catch (e) {
    grantsError.value = describeError(e)
  } finally {
    grantsLoading.value = false
  }
}

async function loadAudit() {
  auditLoading.value = true
  auditError.value = ''
  try {
    auditLogs.value = await agentOpsApi.listAuditLogs()
  } catch (e) {
    auditError.value = describeError(e)
  } finally {
    auditLoading.value = false
  }
}

async function onCreateGrant() {
  grantError.value = ''
  if (grantForm.scopes.length === 0) {
    grantError.value = '请至少选择一个授权范围'
    return
  }
  if (!grantForm.expiresAt) {
    grantError.value = '请选择有效期'
    return
  }
  if (creatingGrant.value) return
  creatingGrant.value = true
  try {
    await agentOpsApi.createGrant({
      scopes: grantForm.scopes,
      expiresAt: new Date(grantForm.expiresAt).toISOString(),
    })
    toast.success('授权已创建')
    grantDialog.value = false
    grantForm.scopes = []
    await loadGrants()
  } catch (e) {
    grantError.value = describeError(e)
  } finally {
    creatingGrant.value = false
  }
}

onMounted(() => {
  void loadExecutions()
  void loadGrants()
  void loadAudit()
})
</script>

<style scoped>
.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.tab {
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  border-radius: 999px;
  padding: 6px 16px;
  font-size: 13px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
}

.tab.active {
  background: var(--color-primary-soft);
  border-color: var(--color-primary);
  color: var(--color-primary);
  font-weight: 600;
}

.tab-badge {
  background: var(--color-danger);
  color: #fff;
  border-radius: 999px;
  font-size: 11px;
  padding: 0 6px;
  line-height: 16px;
}

.section-title {
  font-size: 16px;
}

.exec-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.exec-item {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 14px;
}

.exec-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.exec-summary {
  margin: 10px 0 4px;
  font-size: 14px;
}

.exec-error {
  color: var(--color-danger);
  font-size: 13px;
  margin: 4px 0;
}

.exec-meta {
  font-size: 12px;
  margin-top: 6px;
}

.exec-actions {
  margin-top: 10px;
}

.execution-guidance {
  display: inline-block;
  margin: 0;
  padding: 7px 10px;
  font-size: 12px;
}

.grant-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.grant-item {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
}

.audit-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.audit-table th,
.audit-table td {
  text-align: left;
  padding: 8px 10px;
  border-bottom: 1px solid var(--color-border);
  vertical-align: top;
}

.audit-table th {
  color: var(--color-text-secondary);
  font-weight: 600;
  font-size: 12px;
}

.scope-option {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 14px;
  cursor: pointer;
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
