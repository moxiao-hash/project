<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">掌握度</h1>
        <p class="page-subtitle">综合测验、任务和自评三类证据计算；缺失的分量不参与显示为 0</p>
      </div>
    </div>

    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <div v-else-if="mastery.length === 0" class="card">
      <EmptyState
        icon="📈"
        title="暂无掌握度数据"
        description="完成测验或自评后，这里会展示各知识点的掌握情况。"
      />
    </div>

    <div v-else class="mastery-list">
      <div v-for="m in mastery" :key="m.knowledgePoint" class="card mastery-card">
        <div class="row">
          <h3 class="kp-name">{{ m.knowledgePoint }}</h3>
          <span class="spacer" />
          <span class="score" :class="scoreClass(m.score)">{{ m.score }}</span>
        </div>
        <div class="progress-track" style="margin: 10px 0">
          <div
            class="progress-fill"
            :class="scoreClass(m.score)"
            :style="{ width: m.score + '%' }"
          />
        </div>
        <div class="components">
          <div class="component">
            <div class="component-label">测验</div>
            <div class="component-value">{{ m.quizScore !== null ? m.quizScore : '—' }}</div>
          </div>
          <div class="component">
            <div class="component-label">任务</div>
            <div class="component-value">{{ m.taskScore !== null ? m.taskScore : '—' }}</div>
          </div>
          <div class="component">
            <div class="component-label">自评</div>
            <div class="component-value">
              {{ m.selfAssessmentScore !== null ? m.selfAssessmentScore : '—' }}
            </div>
          </div>
          <div class="component">
            <div class="component-label">证据 / 作答</div>
            <div class="component-value">{{ m.evidenceCount }} / {{ m.attemptCount }}</div>
          </div>
        </div>
        <div class="muted" style="font-size: 12px; margin-top: 10px">
          更新于 {{ formatDateTime(m.updatedAt) }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { assessmentApi } from '@/services/current/assessment'
import { describeError } from '@/services/http'
import { formatDateTime } from '@/utils/datetime'
import type { Mastery } from '@/types/api'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'

const mastery = ref<Mastery[]>([])
const loading = ref(true)
const error = ref('')

function scoreClass(score: number): string {
  if (score >= 80) return 'score-high'
  if (score >= 60) return 'score-mid'
  return 'score-low'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    mastery.value = await assessmentApi.listMastery()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.mastery-list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
}

@media (max-width: 720px) {
  .mastery-list {
    grid-template-columns: 1fr;
  }
}

.kp-name {
  font-size: 15px;
}

.score {
  font-size: 24px;
  font-weight: 800;
}

.score-high { color: var(--color-success); }
.score-mid { color: var(--color-warning); }
.score-low { color: var(--color-danger); }

.progress-fill.score-high { background: var(--color-success); }
.progress-fill.score-mid { background: var(--color-warning); }
.progress-fill.score-low { background: var(--color-danger); }

.components {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  text-align: center;
}

.component-label {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.component-value {
  font-weight: 700;
  font-size: 15px;
  margin-top: 2px;
}
</style>
