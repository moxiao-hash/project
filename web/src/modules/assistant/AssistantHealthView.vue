<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">AGENT OBSERVABILITY</p>
        <h1 class="page-title">运行健康</h1>
        <p class="page-subtitle">查看你的受治理执行记录、模型消耗明细与可观测性指标。</p>
      </div>
      <button class="btn btn-secondary" type="button" :disabled="loading" @click="load">
        刷新
      </button>
    </header>

    <LoadingBlock v-if="loading" text="正在汇总 Agent 指标…" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else-if="health">
      <!-- 分区 1：真实模型遥测与用量可观测性 -->
      <section class="section-container" data-testid="model-telemetry-section">
        <div class="section-header">
          <h2 class="section-title">模型调用与用量可观测性</h2>
          <span v-if="health.priceVersion" class="price-version-tag">定价版本 {{ health.priceVersion }}</span>
        </div>

        <div class="metric-grid">
          <!-- 1. 模型调用次数 -->
          <article class="card metric-card accent">
            <span class="metric-label">模型调用</span>
            <strong>{{ health.modelCalls !== undefined ? health.modelCalls.toLocaleString() : '暂无数据' }}</strong>
            <small v-if="health.failedModelCalls">
              失败 {{ health.failedModelCalls }} 次 ({{ Math.round((health.modelFailureRate ?? 0) * 100) }}%)
            </small>
            <small v-else-if="health.modelCalls">全部调用成功</small>
            <small v-else>暂无调用记录</small>
          </article>

          <!-- 2. Token 用量：直接取后端 modelTotalTokens，绝不二次相加 -->
          <article class="card metric-card">
            <span class="metric-label">Token 用量</span>
            <strong>{{ health.modelTotalTokens !== undefined ? health.modelTotalTokens.toLocaleString() : '暂无数据' }}</strong>
            <small>
              缓存命中 {{ health.modelCachedPromptTokens?.toLocaleString() ?? 0 }} · 未缓存 {{ health.modelUncachedPromptTokens?.toLocaleString() ?? 0 }}
            </small>
            <small>
              输出 {{ health.modelCompletionTokens?.toLocaleString() ?? 0 }} · 思考 {{ health.modelReasoningTokens?.toLocaleString() ?? 0 }}
            </small>
          </article>

          <!-- 3. 估算费用：动态货币符号，priceStatus 为 UNKNOWN 或 cost 为 null 时显示价格未知；无调用或 NONE 时显示暂无数据 -->
          <article class="card metric-card" data-testid="model-cost-card">
            <span class="metric-label">估算费用</span>
            <div class="cost-value-wrapper">
              <strong v-if="health.priceStatus === 'NONE' || !health.modelCalls" data-testid="model-cost-empty">
                暂无数据
              </strong>
              <span
                v-else-if="health.priceStatus === 'UNKNOWN' || health.usageEstimatedCost === null || health.usageEstimatedCost === undefined"
                class="badge-unknown"
                data-testid="cost-unknown-badge"
              >
                价格未知
              </span>
              <strong v-else data-testid="model-cost-value">
                {{ formatCost(health.usageEstimatedCost, health.currency) }}
              </strong>
            </div>
            <small v-if="health.priceStatus === 'NONE' || !health.modelCalls">
              暂无已计价调用
            </small>
            <small v-else-if="health.unknownPriceCalls && health.unknownPriceCalls > 0">
              含 {{ health.unknownPriceCalls }} 次未计价调用
            </small>
            <small v-else-if="health.priceVersion">
              定价版本 {{ health.priceVersion }}
            </small>
            <small v-else>按官方目录估算核算</small>
          </article>

          <!-- 4. 延迟分位 P50 / P95：无调用或无样本时显示明确暂无数据，绝不记为 0 ms -->
          <article class="card metric-card" data-testid="model-latency-card">
            <span class="metric-label">模型延迟分位</span>
            <strong v-if="!health.modelCalls || !health.latencySamples" data-testid="model-latency-empty">
              暂无数据
            </strong>
            <strong v-else-if="health.p50LatencyMs !== null && health.p50LatencyMs !== undefined">
              {{ health.p50LatencyMs.toLocaleString() }} ms
            </strong>
            <strong v-else>暂无数据</strong>
            <small v-if="!health.modelCalls || !health.latencySamples">
              暂无延迟采样数据
            </small>
            <small v-else>
              P50 {{ health.p50LatencyMs?.toLocaleString() ?? '-' }} ms · P95 {{ health.p95LatencyMs?.toLocaleString() ?? '-' }} ms
            </small>
          </article>
        </div>

        <!-- 模型维度明细看板 -->
        <div class="card models-card">
          <div class="table-header">
            <h3>模型维度明细</h3>
            <span class="table-subtitle">按实际调用的模型底层 ID 与提供商独立统计</span>
          </div>

          <div v-if="!health.models || health.models.length === 0" class="empty-state" data-testid="models-empty-state">
            暂无模型调用数据
          </div>

          <div v-else class="table-responsive">
            <table class="models-table" data-testid="models-table">
              <thead>
                <tr>
                  <th>模型名称</th>
                  <th>提供商</th>
                  <th>调用次数</th>
                  <th>失败率</th>
                  <th>Prompt (缓存/未缓存)</th>
                  <th>输出 (输出/思考)</th>
                  <th>总 Token</th>
                  <th>延迟 (P50/P95)</th>
                  <th>定价版本</th>
                  <th>估算费用</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="m in health.models"
                  :key="m.modelName"
                  :data-testid="`model-row-${m.modelName}`"
                >
                  <td class="model-name-cell">
                    <code>{{ m.modelName }}</code>
                  </td>
                  <td>{{ m.provider }}</td>
                  <td>
                    {{ m.calls }} 次
                    <span v-if="m.failedCalls" class="text-danger">({{ m.failedCalls }} 失败)</span>
                  </td>
                  <td>{{ Math.round(m.failureRate * 100) }}%</td>
                  <td>
                    {{ m.promptTokens.toLocaleString() }}
                    <small class="text-muted">({{ m.cachedPromptTokens.toLocaleString() }} / {{ m.uncachedPromptTokens.toLocaleString() }})</small>
                  </td>
                  <td>
                    {{ m.completionTokens.toLocaleString() }}
                    <small class="text-muted">({{ m.completionTokens.toLocaleString() }} / {{ m.reasoningTokens.toLocaleString() }})</small>
                  </td>
                  <td><strong>{{ m.totalTokens.toLocaleString() }}</strong></td>
                  <td>{{ m.p50LatencyMs !== null && m.p50LatencyMs !== undefined ? `${m.p50LatencyMs} ms` : '-' }} / {{ m.p95LatencyMs !== null && m.p95LatencyMs !== undefined ? `${m.p95LatencyMs} ms` : '-' }}</td>
                  <td>
                    <span class="version-badge">{{ m.priceVersion || '-' }}</span>
                  </td>
                  <td>
                    <span
                      v-if="m.priceStatus === 'UNKNOWN' || m.estimatedCost === null || m.estimatedCost === undefined"
                      class="badge-unknown-small"
                    >
                      价格未知
                    </span>
                    <span v-else class="cost-text">
                      {{ formatCost(m.estimatedCost, m.currency) }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <!-- 分区 2：历史执行记录与治理统计 (Legacy Execution Metrics) -->
      <section class="section-container" data-testid="legacy-execution-section">
        <div class="section-header">
          <h2 class="section-title">执行记录与治理统计</h2>
          <span class="section-subtitle">受治理的业务操作、安全决策与自动化执行</span>
        </div>

        <div class="metric-grid">
          <article class="card metric-card">
            <span class="metric-label">执行成功率</span>
            <strong>{{ health.successfulExecutions + health.failedExecutions ? `${(health.successRate * 100).toFixed(1)}%` : '暂无数据' }}</strong>
            <small>{{ health.successfulExecutions }} 成功 / {{ health.failedExecutions }} 失败</small>
          </article>

          <article class="card metric-card">
            <span class="metric-label">累计执行</span>
            <strong>{{ health.totalExecutions.toLocaleString() }}</strong>
            <small>受治理的业务与自动化操作</small>
          </article>

          <article class="card metric-card">
            <span class="metric-label">平均耗时</span>
            <strong>{{ health.latencySamples ? `${health.averageLatencyMs} ms` : '暂无数据' }}</strong>
            <small>仅统计已上报延迟的执行</small>
          </article>

          <article class="card metric-card">
            <span class="metric-label">历史执行 Token</span>
            <strong>{{ (health.promptTokens + health.completionTokens).toLocaleString() }}</strong>
            <small>输入 {{ health.promptTokens.toLocaleString() }} · 输出 {{ health.completionTokens.toLocaleString() }}</small>
          </article>

          <article class="card metric-card">
            <span class="metric-label">历史估算成本</span>
            <strong>{{ health.costSamples ? formatCost(health.estimatedCost, 'USD') : '暂无数据' }}</strong>
            <small>按历史执行记录累计</small>
          </article>

          <article class="card metric-card" :class="{ warning: health.pendingConfirmations > 0 }">
            <span class="metric-label">需要你的决定</span>
            <strong>{{ health.pendingConfirmations }}</strong>
            <small>{{ health.pendingConfirmations }} 个操作等待确认</small>
          </article>
        </div>
      </section>

      <section class="card boundary-card">
        <h2>如何理解这些数据</h2>
        <p>模型遥测展示底层大语言模型调用的实际用量、延迟分位和基于官方目录估算的费用；执行记录展示被系统治理的业务写操作、工具执行和待确认事件。系统遵循隐私安全底线，绝不记录或持久化任何 Prompt 明文、请求 Header 或 API Key。未知价格模型明确标注为价格未知，绝不伪记为零成本。</p>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import { assistantApi } from '@/services/current/assistant'
import { describeError } from '@/services/http'
import type { AssistantHealth } from '@/types/assistant'

const health = ref<AssistantHealth | null>(null)
const loading = ref(true)
const error = ref('')

function formatCost(cost: number | null | undefined, currency: string | null | undefined): string {
  if (cost === null || cost === undefined) return '价格未知'
  const unit = currency || 'USD'
  return `${cost} ${unit}`
}

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
.page-title { margin-top: 4px; font-size: 26px; }
.page-subtitle { color: var(--color-text-secondary); margin-top: 4px; font-size: 14px; }

.section-container { margin-bottom: 32px; }
.section-header { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 14px; }
.section-title { font-size: 18px; font-weight: 700; color: var(--color-text); }
.section-subtitle { font-size: 13px; color: var(--color-text-muted); }
.price-version-tag { font-size: 12px; color: var(--color-text-secondary); background: #f3f4f6; padding: 2px 8px; border-radius: 4px; font-family: monospace; }

.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; margin-bottom: 16px; }
[data-testid="legacy-execution-section"] .metric-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }

.metric-card { min-height: 140px; display: flex; flex-direction: column; gap: 8px; padding: 18px; }
.metric-card strong { font-size: 28px; line-height: 1.1; font-weight: 750; }
.metric-card small { color: var(--color-text-muted); font-size: 12px; line-height: 1.4; }
.metric-label { color: var(--color-text-muted); font-size: 13px; font-weight: 600; }
.metric-card.accent { border-top: 3px solid var(--color-primary); }
.metric-card.warning { border-color: #f59e0b; background: #fffbeb; }

.cost-value-wrapper { display: flex; align-items: center; min-height: 32px; }
.badge-unknown {
  display: inline-block;
  padding: 4px 10px;
  font-size: 14px;
  font-weight: 600;
  color: #b45309;
  background: #fef3c7;
  border: 1px solid #fde68a;
  border-radius: 6px;
}
.badge-unknown-small {
  display: inline-block;
  padding: 2px 6px;
  font-size: 11px;
  font-weight: 600;
  color: #b45309;
  background: #fef3c7;
  border-radius: 4px;
}

.models-card { margin-top: 16px; padding: 20px; }
.table-header { margin-bottom: 14px; }
.table-header h3 { font-size: 16px; font-weight: 700; margin: 0 0 4px; }
.table-subtitle { font-size: 12px; color: var(--color-text-muted); }
.empty-state { padding: 32px; text-align: center; color: var(--color-text-muted); font-size: 14px; }

.table-responsive { overflow-x: auto; }
.models-table { width: 100%; border-collapse: collapse; text-align: left; font-size: 13px; }
.models-table th, .models-table td { padding: 10px 12px; border-bottom: 1px solid #f1f3f8; }
.models-table th { color: var(--color-text-muted); font-weight: 600; background: #fafbfc; white-space: nowrap; }
.model-name-cell code { background: #f3f4f6; padding: 2px 6px; border-radius: 4px; font-size: 12px; }
.cost-text { font-family: monospace; font-weight: 600; }
.text-muted { color: var(--color-text-muted); }
.text-danger { color: #ef4444; }

.boundary-card { margin-top: 24px; padding: 20px; }
.boundary-card h2 { margin-bottom: 8px; font-size: 16px; }
.boundary-card p { color: var(--color-text-muted); line-height: 1.7; font-size: 13px; margin: 0; }

@media (max-width: 1024px) {
  .metric-grid { grid-template-columns: repeat(2, 1fr) !important; }
}
@media (max-width: 640px) {
  .metric-grid { grid-template-columns: 1fr !important; }
  .page-header { flex-direction: column; }
}
</style>
