<template>
  <div class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">AGENT OBSERVABILITY</p>
        <h1 class="page-title">运行健康</h1>
        <p class="page-subtitle">查看你的受治理执行记录、结果和已上报用量。</p>
      </div>
      <button class="btn btn-secondary" type="button" :disabled="loading" @click="load">
        刷新
      </button>
    </header>

    <LoadingBlock v-if="loading" text="正在汇总 Agent 指标…" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else-if="health">
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
          <small>仅统计已上报延迟的执行</small>
        </article>
        <article class="card metric-card">
          <span class="metric-label">Token 用量</span>
          <strong>{{ health.tokenSamples ? totalTokens.toLocaleString() : '暂无数据' }}</strong>
          <small>输入 {{ health.promptTokens.toLocaleString() }} · 输出 {{ health.completionTokens.toLocaleString() }}</small>
        </article>
        <article class="card metric-card">
          <span class="metric-label">估算成本</span>
          <strong>{{ health.costSamples ? health.estimatedCost : '暂无数据' }}</strong>
          <small>按执行记录累计，仅供趋势参考</small>
        </article>
        <article class="card metric-card" :class="{ warning: health.pendingConfirmations > 0 }">
          <span class="metric-label">需要你的决定</span>
          <strong>{{ health.pendingConfirmations }}</strong>
          <small>{{ health.pendingConfirmations }} 个操作等待确认</small>
        </article>
      </section>

      <section class="card boundary-card">
        <h2>如何理解这些数据</h2>
        <p>成功率仅计算已成功或已失败的执行。用量与耗时只累计已上报的数据，可能不包含全部对话调用；估算成本请以模型服务商账单为准。</p>
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
  (health.value?.promptTokens ?? 0) + (health.value?.completionTokens ?? 0),
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
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.eyebrow { color: var(--color-primary); font-size: 12px; font-weight: 800; letter-spacing: .12em; }
.metric-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.metric-card { min-height: 150px; display: flex; flex-direction: column; gap: 10px; }
.metric-card strong { font-size: 34px; line-height: 1; }
.metric-card small, .metric-label { color: var(--color-text-muted); }
.metric-card.accent { border-top: 3px solid var(--color-primary); }
.metric-card.warning { border-color: #f59e0b; background: #fffbeb; }
.boundary-card { margin-top: 20px; }
.boundary-card h2 { margin-bottom: 8px; font-size: 18px; }
.boundary-card p { color: var(--color-text-muted); line-height: 1.7; }
@media (max-width: 900px) { .metric-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 620px) { .metric-grid { grid-template-columns: 1fr; } .page-header { flex-direction: column; } }
</style>
