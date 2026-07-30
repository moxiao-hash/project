<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ greeting }}，{{ auth.user?.displayName }}</h1>
        <p class="page-subtitle">{{ todayText }}</p>
      </div>
      <div class="row">
        <RouterLink to="/today" class="btn btn-primary">去打卡</RouterLink>
      </div>
    </div>

    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />

    <template v-else-if="dashboard">
      <section v-if="continueLesson" class="continue-card">
        <div>
          <span class="continue-kicker">CONTINUE LEARNING</span>
          <h2>{{ continueLesson.title }}</h2>
          <p>{{ continueLesson.summary }}</p>
          <div class="continue-meta">
            <span>{{ continueLesson.estimatedMinutes }} 分钟</span>
            <span>·</span>
            <span>{{ continueLesson.progress.status === 'NOT_STARTED' ? '尚未开始' : '学习中' }}</span>
          </div>
        </div>
        <RouterLink :to="`/lessons/${continueLesson.id}`" class="btn btn-primary">
          {{ continueLesson.progress.status === 'NOT_STARTED' ? '开始第一课' : '继续学习' }}
        </RouterLink>
      </section>
      <section v-else class="continue-card completed">
        <div>
          <span class="continue-kicker">COURSE COMPLETE</span>
          <h2>当前开放课时已完成</h2>
          <p>可以回到课程路线查看即将开放的 Java + AI 内容。</p>
        </div>
        <RouterLink to="/courses" class="btn btn-secondary">查看课程路线</RouterLink>
      </section>

      <div class="grid cols-3 stat-grid">
        <div class="card stat">
          <div class="stat-value">{{ dashboard.activeGoalCount }}</div>
          <div class="stat-label">进行中的目标</div>
        </div>
        <div class="card stat">
          <div class="stat-value">
            {{ dashboard.completedTodayTaskCount }}/{{ dashboard.todayTaskCount }}
          </div>
          <div class="stat-label">今日任务完成</div>
          <div class="progress-track" style="margin-top: 8px">
            <div class="progress-fill" :style="{ width: todayProgress + '%' }" />
          </div>
        </div>
        <div class="card stat">
          <div class="stat-value">{{ dashboard.pendingMaterialCount }}</div>
          <div class="stat-label">待处理资料</div>
        </div>
        <div class="card stat">
          <div class="stat-value">{{ dashboard.lowMasteryCount }}</div>
          <div class="stat-label">薄弱知识点</div>
        </div>
        <div class="card stat">
          <div class="stat-value">{{ dashboard.unreadNotificationCount }}</div>
          <div class="stat-label">未读通知</div>
        </div>
      </div>

      <div
        v-if="
          dashboard.activeGoalCount === 0 &&
          dashboard.todayTaskCount === 0 &&
          dashboard.pendingMaterialCount === 0
        "
        class="card"
      >
        <EmptyState
          icon="🌱"
          title="从这里开始你的学习计划"
          description="创建第一个学习目标，或导入一份学习资料。"
        >
          <div class="row" style="justify-content: center">
            <RouterLink to="/goals" class="btn btn-primary">创建学习目标</RouterLink>
            <RouterLink to="/materials" class="btn btn-secondary">导入资料</RouterLink>
          </div>
        </EmptyState>
      </div>

      <div class="grid cols-3 quick-grid">
        <RouterLink to="/today" class="card quick">
          <div class="quick-icon">✅</div>
          <div>
            <div class="quick-title">今日任务</div>
            <div class="muted">完成、跳过或延期今天的任务</div>
          </div>
        </RouterLink>
        <RouterLink to="/knowledge" class="card quick">
          <div class="quick-icon">💬</div>
          <div>
            <div class="quick-title">知识问答</div>
            <div class="muted">基于资料的可溯源问答</div>
          </div>
        </RouterLink>
        <RouterLink to="/mastery" class="card quick">
          <div class="quick-icon">📈</div>
          <div>
            <div class="quick-title">掌握度</div>
            <div class="muted">查看知识点掌握情况</div>
          </div>
        </RouterLink>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { dashboardApi } from '@/services/current/dashboard'
import { courseApi } from '@/services/course'
import { describeError } from '@/services/http'
import { useAuthStore } from '@/stores/auth'
import type { Dashboard } from '@/types/api'
import type { Lesson } from '@/types/course'
import LoadingBlock from '@/components/LoadingBlock.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const auth = useAuthStore()
const dashboard = ref<Dashboard | null>(null)
const continueLesson = ref<Lesson | null>(null)
const loading = ref(true)
const error = ref('')

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const todayText = new Date().toLocaleDateString('zh-CN', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  weekday: 'long',
})

const todayProgress = computed(() => {
  if (!dashboard.value || dashboard.value.todayTaskCount === 0) return 0
  return Math.round(
    (dashboard.value.completedTodayTaskCount / dashboard.value.todayTaskCount) * 100,
  )
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [dashboardResult, lessonResult] = await Promise.all([
      dashboardApi.get(),
      courseApi.getContinueLesson(),
    ])
    dashboard.value = dashboardResult
    continueLesson.value = lessonResult
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.continue-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 16px;
  padding: 24px;
  border: 1px solid #30374d;
  border-radius: 14px;
  background:
    radial-gradient(circle at 90% 10%, rgba(99, 102, 241, .3), transparent 35%),
    #191d2b;
  color: white;
}

.continue-card.completed {
  border-color: var(--color-border);
  background: white;
  color: var(--color-text);
}

.continue-card h2 {
  margin-top: 5px;
  font-size: 20px;
}

.continue-card p {
  margin: 5px 0;
  color: #b5bbcf;
}

.continue-card.completed p {
  color: var(--color-text-secondary);
}

.continue-kicker {
  color: #a5b4fc;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .12em;
}

.continue-meta {
  color: #969db4;
  font-size: 12px;
}

@media (max-width: 640px) {
  .continue-card {
    align-items: flex-start;
    flex-direction: column;
  }
}

.stat-grid {
  margin-bottom: 16px;
}

.stat {
  text-align: left;
}

.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: var(--color-primary);
}

.stat-label {
  color: var(--color-text-secondary);
  font-size: 13px;
  margin-top: 2px;
}

.quick-grid {
  margin-top: 16px;
}

.quick {
  display: flex;
  gap: 12px;
  align-items: center;
  color: var(--color-text);
  transition: border-color 0.15s, transform 0.15s;
}

.quick:hover {
  border-color: var(--color-primary);
  transform: translateY(-1px);
}

.quick-icon {
  font-size: 26px;
}

.quick-title {
  font-weight: 600;
  margin-bottom: 2px;
}
</style>
