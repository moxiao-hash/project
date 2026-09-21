<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">AGENT OBSERVABILITY</p>
        <h1 class="page-title">运行健康</h1>
        <p class="page-subtitle">查看你的受治理执行记录、模型消耗明细与每日用量预算。</p>
      </div>
      <button class="btn btn-secondary" type="button" :disabled="loading" @click="load">
        刷新
      </button>
    </header>

    <LoadingBlock v-if="loading" text="正在汇总 Agent 指标…" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else-if="health">
      <!-- 每日用量硬预算告警横幅 -->
      <section
        v-if="health.budget?.budgetExhausted"
        class="card budget-alert-card"
        data-testid="budget-alert"
      >
        <div class="alert-icon">⚠️</div>
        <div class="alert-content">
          <strong>今日模型调用额度已用尽</strong>
          <p>{{ health.budget.exhaustedReason || '今日调用次数或费用已达到上限，纯 Java 查阅与导航仍可使用，次日 0 点重置。' }}</p>
        </div>
      </section>

      <!-- 核心指标卡片网格 -->
      <section class="metric-grid">
        <article class="card metric-card accent">
          <span class="metric-label">执行成功率</span>
          <strong>{{ health.successfulExecutions + health.failedExecutions ? `${Math.round(health.successRate * 100)}%` : '暂无数据' }}</strong>
          <small>{{ health.successfulExecutions }} 成功 / {{ health.failedExecutions }} 失败</small>
        </article>
        <article class="card metric-card">
          <span class="metric-label">累计执行</span>
          <strong>{{ health.totalExecutions }}</strong>
          <small>受治理的业务与自动化操作</small>
        </article>
        <article class="card metric-card">
          <span class="metric-label">平均耗时</span>
          <strong>{{ health.latencySamples ? `${health.averageLatencyMs} ms` : '暂无数据' }}</strong>
          <small v-if="health.p50LatencyMs !== undefined && health.p95LatencyMs !== undefined">
            P50 延迟 {{ health.p50LatencyMs.toLocaleString() }} ms · P95 延迟 {{ health.p95LatencyMs.toLocaleString() }} ms
          </small>
          <small v-else>仅统计已上报延迟的执行</small>
        </article>
        <article class="card metric-card">
          <span class="metric-label">Token 用量</span>
          <strong>{{ health.tokenSamples ? totalTokens.toLocaleString() : '暂无数据' }}</strong>
          <small>输入 {{ health.promptTokens.toLocaleString() }} · 输出 {{ health.completionTokens.toLocaleString() }}<template v-if="health.reasoningTokens"> · 思考 {{ health.reasoningTokens.toLocaleString() }}</template></small>
        </article>
        <article class="card metric-card">
          <span class="metric-label">估算成本</span>
          <strong>{{ health.costSamples ? (health.isCostEstimated === false ? '不可估算' : health.estimatedCost) : '暂无数据' }}</strong>
          <small>按官方目录精确核算，未知价格模型不记零</small>
        </article>
        <article class="card metric-card" :class="{ warning: health.pendingConfirmations > 0 }">
          <span class="metric-label">需要你的决定</span>
          <strong>{{ health.pendingConfirmations }}</strong>
          <small>{{ health.pendingConfirmations }} 个操作等待确认</small>
        </article>
      </section>

      <!-- 每日用量预算进度面板 -->
      <section v-if="health.budget" class="card budget-card">
        <h2>用户每日预算与硬限制</h2>
        <div class="budget-items">
          <div class="budget-item">
            <span class="budget-title">今日调用次数配额</span>
            <div class="budget-bar-wrapper">
              <div
                class="budget-bar-fill"
                :style="{ width: `${Math.min(100, Math.round((health.budget.dailyCallsUsed / (health.budget.dailyCallsLimit || 1)) * 100))}%` }"
                :class="{ exceeded: health.budget.dailyCallsUsed >= health.budget.dailyCallsLimit }"
              />
            </div>
            <span class="budget-stat">{{ health.budget.dailyCallsUsed }} / {{ health.budget.dailyCallsLimit }} 次</span>
          </div>
          <div class="budget-item">
            <span class="budget-title">今日预计花费限额</span>
            <div class="budget-bar-wrapper">
              <div
                class="budget-bar-fill"
                :style="{ width: `${Math.min(100, Math.round((health.budget.dailyCostUsed / (health.budget.dailyCostLimit || 1)) * 100))}%` }"
                :class="{ exceeded: health.budget.dailyCostUsed >= health.budget.dailyCostLimit }"
              />
            </div>
            <span class="budget-stat">¥{{ health.budget.dailyCostUsed.toFixed(2) }} / ¥{{ health.budget.dailyCostLimit.toFixed(2) }}</span>
          </div>
        </div>
      </section>

      <!-- 分模型用量与明细看板 -->
      <section v-if="health.models && health.models.length > 0" class="card models-card">
        <h2>模型维度用量与费用核算</h2>
        <div class="table-responsive">
          <table class="models-table">
            <thead>
              <tr>
                <th>模型 ID</th>
                <th>调用次数</th>
                <th>Prompt Tokens</th>
                <th>Completion Tokens</th>
                <th>Reasoning Tokens</th>
                <th>估算费用</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in health.models" :key="m.modelId">
                <td class="model-id-cell">
                  <code>{{ m.modelId }}</code>
                </td>
                <td>{{ m.callCount }}</td>
                <td>{{ m.promptTokens.toLocaleString() }}</td>
                <td>{{ m.completionTokens.toLocaleString() }}</td>
                <td>{{ (m.reasoningTokens || 0).toLocaleString() }}</td>
                <td>
                  <span v-if="m.isEstimated">¥{{ m.estimatedCost }}</span>
                  <span v-else class="badge-unestimated" data-testid="unestimated-badge">不可估算</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="card boundary-card">
        <h2>如何理解这些数据</h2>
        <p>成功率仅计算已成功或已失败的执行。用量与耗时按每次执行的底层模型返回严格计量。系统遵循隐私安全底线，绝不记录或持久化任何 Prompt 明文、请求 Header 或 API Key。估算成本严格依据官方目录版本核算，未知价格明确标注为不可估算，不记为零成本。</p>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import { assistantApi } from '@/services/current/assistant'
import { describeError } from '@/services/http'
import type { AssistantHealth } from '@/types/assistant'

const health = ref<AssistantHealth | null>(null)
const loading = ref(true)
const error = ref('')
const totalTokens = computed(() =>
  (health.value?.promptTokens ?? 0) +
  (health.value?.completionTokens ?? 0) +
  (health.value?.reasoningTokens ?? 0),
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    health.value = await assistantApi.getAssistantHealth()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 24px; }
.eyebrow { color: var(--color-primary); font-size: 12px; font-weight: 800; letter-spacing: .12em; }
.metric-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.metric-card { min-height: 150px; display: flex; flex-direction: column; gap: 10px; }
.metric-card strong { font-size: 34px; line-height: 1; }
.metric-card small, .metric-label { color: var(--color-text-muted); }
.metric-card.accent { border-top: 3px solid var(--color-primary); }
.metric-card.warning { border-color: #f59e0b; background: #fffbeb; }

/* 预算告警横幅 */
.budget-alert-card {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
  background: #fffbeb;
  border: 1px solid #f59e0b;
  color: #92400e;
}
.alert-icon { font-size: 24px; }
.alert-content strong { display: block; font-size: 15px; margin-bottom: 4px; color: #b45309; }
.alert-content p { margin: 0; font-size: 13px; color: #78350f; }

/* 预算进度看板 */
.budget-card, .models-card, .boundary-card { margin-top: 20px; }
.budget-card h2, .models-card h2, .boundary-card h2 { margin-bottom: 12px; font-size: 17px; }
.budget-items { display: grid; grid-template-columns: repeat(2, 1fr); gap: 20px; margin-top: 12px; }
.budget-item { display: flex; flex-direction: column; gap: 6px; }
.budget-title { font-size: 13px; font-weight: 600; color: var(--color-text-secondary); }
.budget-bar-wrapper { height: 8px; background: #e5e7eb; border-radius: 4px; overflow: hidden; }
.budget-bar-fill { height: 100%; background: var(--color-primary); border-radius: 4px; transition: width .3s ease; }
.budget-bar-fill.exceeded { background: #ef4444; }
.budget-stat { font-size: 12px; color: var(--color-text-muted); text-align: right; }

/* 模型明细表格 */
.table-responsive { overflow-x: auto; margin-top: 8px; }
.models-table { width: 100%; border-collapse: collapse; text-align: left; font-size: 13px; }
.models-table th, .models-table td { padding: 10px 12px; border-bottom: 1px solid #f1f3f8; }
.models-table th { color: var(--color-text-muted); font-weight: 600; background: #fafbfc; }
.model-id-cell code { background: #f3f4f6; padding: 2px 6px; border-radius: 4px; font-size: 12px; }
.badge-unestimated {
  display: inline-block;
  padding: 2px 6px;
  font-size: 11px;
  font-weight: 600;
  color: #b45309;
  background: #fef3c7;
  border-radius: 4px;
}

.boundary-card p { color: var(--color-text-muted); line-height: 1.7; font-size: 13px; margin: 0; }
@media (max-width: 900px) {
  .metric-grid { grid-template-columns: repeat(2, 1fr); }
  .budget-items { grid-template-columns: 1fr; }
}
@media (max-width: 620px) {
  .metric-grid { grid-template-columns: 1fr; }
  .page-header { flex-direction: column; }
}
</style>
