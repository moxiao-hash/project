<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import { courseApi } from '@/services/course'
import { describeError } from '@/services/http'
import type { CourseDetail } from '@/types/course'

const route = useRoute()
const course = ref<CourseDetail | null>(null)
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    course.value = await courseApi.getCourse(String(route.params.slug))
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <aside class="legacy-roadmap-banner" role="status">
      <div>
        <strong>旧版课程入口</strong>
        <span>现有课程与学习记录仍可使用；新的主学习流程已迁移到路线图。</span>
      </div>
      <RouterLink to="/roadmap">前往 Java + AI 学习路线 →</RouterLink>
    </aside>
    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else-if="course">
      <div class="page-header detail-header">
        <div>
          <RouterLink to="/courses" class="back-link">← 返回课程中心</RouterLink>
          <h1 class="page-title">{{ course.course.title }}</h1>
          <p class="page-subtitle">{{ course.course.description }}</p>
        </div>
        <div class="progress-badge">{{ course.course.progressPercent }}%</div>
      </div>

      <div class="roadmap">
        <section v-for="module in course.modules" :key="module.id" class="module-card">
          <div class="module-index">{{ String(module.order).padStart(2, '0') }}</div>
          <div class="module-body">
            <h2>{{ module.title }}</h2>
            <p>{{ module.description }}</p>
            <div class="lesson-list">
              <template v-for="lesson in module.lessons" :key="lesson.id">
                <RouterLink
                  v-if="lesson.published"
                  :to="`/lessons/${lesson.id}`"
                  class="lesson-row published"
                >
                  <span class="lesson-state">{{ lesson.progress.status === 'COMPLETED' ? '✓' : '▶' }}</span>
                  <span>
                    <strong>{{ lesson.title }}</strong>
                    <small>{{ lesson.summary }} · {{ lesson.estimatedMinutes }} 分钟</small>
                  </span>
                  <span class="badge badge-primary">开始学习</span>
                </RouterLink>
                <div v-else class="lesson-row">
                  <span class="lesson-state">○</span>
                  <span>
                    <strong>{{ lesson.title }}</strong>
                    <small>{{ lesson.summary }}</small>
                  </span>
                  <span class="badge badge-neutral">即将开放</span>
                </div>
              </template>
            </div>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.legacy-roadmap-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
  padding: 14px 16px;
  border: 1px solid #c7d2fe;
  border-radius: 12px;
  background: #eef2ff;
}

.legacy-roadmap-banner div,
.legacy-roadmap-banner span {
  display: grid;
  gap: 3px;
}

.legacy-roadmap-banner span {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.legacy-roadmap-banner a {
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 700;
}

.back-link {
  display: inline-block;
  margin-bottom: 12px;
  font-size: 12px;
}

.detail-header {
  padding: 24px;
  border-radius: 15px;
  background: #171b29;
  color: white;
}

.detail-header .page-subtitle {
  color: #b5bbcf;
}

.progress-badge {
  display: grid;
  width: 66px;
  height: 66px;
  place-items: center;
  border: 5px solid #626bde;
  border-radius: 50%;
  font-weight: 800;
}

.roadmap {
  display: grid;
  gap: 12px;
}

.module-card {
  display: grid;
  grid-template-columns: 64px 1fr;
  padding: 20px;
  border: 1px solid var(--color-border);
  border-radius: 14px;
  background: white;
}

.module-index {
  color: #c5c9d5;
  font-size: 28px;
  font-weight: 800;
}

.module-body > p {
  margin: 5px 0 14px;
  color: var(--color-text-secondary);
}

.lesson-list {
  display: grid;
  gap: 7px;
}

.lesson-row {
  display: grid;
  grid-template-columns: 26px 1fr auto;
  gap: 8px;
  align-items: center;
  padding: 11px;
  border-radius: 9px;
  background: #f7f8fb;
  color: #7a8090;
}

.lesson-row.published {
  color: var(--color-text);
  background: var(--color-primary-soft);
}

.lesson-row small {
  display: block;
  color: var(--color-text-secondary);
}

@media (max-width: 720px) {
  .legacy-roadmap-banner { align-items: flex-start; flex-direction: column; }
}
</style>
