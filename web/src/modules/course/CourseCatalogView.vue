<script setup lang="ts">
import { onMounted, ref } from 'vue'

import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import { courseApi } from '@/services/course'
import { describeError } from '@/services/http'
import type { CourseSummary } from '@/types/course'

const courses = ref<CourseSummary[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    courses.value = await courseApi.listCourses()
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
    <div class="course-hero">
      <div>
        <span class="eyebrow">PROJECT-BASED LEARNING</span>
        <h1>从 StudyPilot 本身，学会 Java + AI</h1>
        <p>跟着黑马课程主线学习，再回到真实项目代码中验证。视频、讲义、AI 答疑和练习在同一条路线里。</p>
      </div>
      <div class="hero-mark">9<span>阶段</span></div>
    </div>

    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState
      v-else-if="courses.length === 0"
      icon="🎓"
      title="课程正在准备"
      description="课程目录导入后会出现在这里。"
    />
    <div v-else class="catalog-grid">
      <RouterLink
        v-for="course in courses"
        :key="course.id"
        :to="`/courses/${course.slug}`"
        class="course-card"
      >
        <div class="course-number">COURSE 01</div>
        <h2>{{ course.title }}</h2>
        <p>{{ course.description }}</p>
        <div class="tech-stack">{{ course.techStack }}</div>
        <div class="course-meta">
          <span>{{ course.moduleCount }} 个阶段</span>
          <span>{{ course.lessonCount }} 节已开放</span>
          <span>{{ course.progressPercent }}%</span>
        </div>
        <div class="progress-track">
          <div class="progress-fill" :style="{ width: `${course.progressPercent}%` }" />
        </div>
      </RouterLink>
    </div>
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

.course-hero {
  display: flex;
  justify-content: space-between;
  gap: 32px;
  margin-bottom: 24px;
  padding: 36px;
  border-radius: 18px;
  background: #171b29;
  color: white;
}

.course-hero h1 {
  max-width: 650px;
  margin-top: 10px;
  font-size: 32px;
}

.course-hero p {
  max-width: 700px;
  margin-bottom: 0;
  color: #b5bbcf;
}

.eyebrow,
.course-number {
  color: #a5b4fc;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: .12em;
}

.hero-mark {
  display: flex;
  align-items: baseline;
  color: #a5b4fc;
  font-size: 72px;
  font-weight: 800;
}

.hero-mark span {
  margin-left: 5px;
  font-size: 13px;
}

.catalog-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.course-card {
  padding: 24px;
  border: 1px solid var(--color-border);
  border-radius: 14px;
  background: white;
  color: var(--color-text);
}

.course-card h2 {
  margin-top: 8px;
}

.course-card p,
.tech-stack {
  color: var(--color-text-secondary);
}

.tech-stack {
  padding: 8px 10px;
  border-radius: 7px;
  background: #f6f7fb;
  font-size: 12px;
}

.course-meta {
  display: flex;
  justify-content: space-between;
  margin: 16px 0 8px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

@media (max-width: 720px) {
  .legacy-roadmap-banner { align-items: flex-start; flex-direction: column; }
  .course-hero { padding: 24px; }
  .hero-mark { display: none; }
  .catalog-grid { grid-template-columns: 1fr; }
}
</style>
