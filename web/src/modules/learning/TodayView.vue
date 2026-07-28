<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">今日任务</h1>
        <p class="page-subtitle">
          <input v-model="date" type="date" class="input date-input" @change="load" />
          <span v-if="date === today" style="margin-left: 8px">今天</span>
        </p>
      </div>
      <RouterLink to="/agent/tasks" class="btn btn-secondary">🤖 用 Agent 操作任务</RouterLink>
    </div>

    <div v-if="summary" class="card summary-card">
      <div class="row">
        <div>
          <strong>{{ summary.done }}/{{ summary.total }}</strong>
          <span class="muted"> 已完成 · 预估总计 {{ formatMinutes(summary.estimated) }}</span>
        </div>
        <span class="spacer" />
        <span v-if="summary.total > 0 && summary.done === summary.total" class="badge badge-success">
          全部完成 🎉
        </span>
      </div>
      <div class="progress-track" style="margin-top: 10px">
        <div class="progress-fill" :style="{ width: summary.progress + '%' }" />
      </div>
    </div>

    <div class="card" style="margin-top: 16px">
      <LoadingBlock v-if="loading" />
      <ErrorState v-else-if="error" :message="error" @retry="load" />
      <EmptyState
        v-else-if="tasks.length === 0"
        icon="🌤️"
        :title="date === today ? '今天没有安排任务' : '该日期没有任务'"
        description="到计划详情页添加任务，或让 AI 帮你生成计划。"
      >
        <RouterLink to="/plans" class="btn btn-primary">查看学习计划</RouterLink>
      </EmptyState>
      <TaskList v-else :tasks="tasks" show-quiz-gen @changed="load" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { learningApi } from '@/services/current/learning'
import { describeError } from '@/services/http'
import { formatMinutes, todayString } from '@/utils/datetime'
import type { LearningTask } from '@/types/api'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import TaskList from '@/components/TaskList.vue'

const today = todayString()
const date = ref(today)
const tasks = ref<LearningTask[]>([])
const loading = ref(true)
const error = ref('')

const summary = computed(() => {
  if (tasks.value.length === 0) return null
  const total = tasks.value.length
  const done = tasks.value.filter((t) => t.status === 'COMPLETED').length
  const estimated = tasks.value.reduce((sum, t) => sum + t.estimatedMinutes, 0)
  return { total, done, estimated, progress: Math.round((done / total) * 100) }
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    tasks.value = await learningApi.listTasks(date.value)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.summary-card {
  background: linear-gradient(135deg, #eef0fe, #f8f7ff);
}

.date-input {
  width: auto;
  display: inline-block;
  padding: 4px 8px;
}
</style>
