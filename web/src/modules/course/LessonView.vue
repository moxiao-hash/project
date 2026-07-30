<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import { courseApi } from '@/services/course'
import { describeError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import type { Lesson } from '@/types/course'
import BilibiliPlayer from './components/BilibiliPlayer.vue'
import LessonBlockRenderer from './components/LessonBlockRenderer.vue'
import LessonProgressRail from './components/LessonProgressRail.vue'
import LessonTutorPanel from './components/LessonTutorPanel.vue'

const route = useRoute()
const toast = useToastStore()
const lesson = ref<Lesson | null>(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')

const videoSource = computed(() =>
  lesson.value?.sources.find(
    (source) => source.type === 'VIDEO' && source.bvid && source.videoPage,
  ),
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    lesson.value = await courseApi.getLesson(String(route.params.lessonId))
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    loading.value = false
  }
}

async function saveProgress(
  patch: Partial<{ videoCompleted: boolean; readingCompleted: boolean }>,
  section: string,
) {
  if (!lesson.value || saving.value) return
  saving.value = true
  try {
    lesson.value = await courseApi.updateProgress(lesson.value.id, {
      videoCompleted: patch.videoCompleted ?? lesson.value.progress.videoCompleted,
      readingCompleted: patch.readingCompleted ?? lesson.value.progress.readingCompleted,
      lastSectionKey: section,
    })
    toast.success('学习进度已保存')
  } catch (cause) {
    toast.error(describeError(cause))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="lesson-page">
    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else-if="lesson">
      <header class="lesson-header">
        <div>
          <RouterLink to="/courses" class="back-link">← 课程路线</RouterLink>
          <h1>{{ lesson.title }}</h1>
          <p>{{ lesson.summary }} · 预计 {{ lesson.estimatedMinutes }} 分钟</p>
        </div>
        <span class="badge badge-primary">{{ lesson.progress.status }}</span>
      </header>

      <div class="lesson-layout">
        <LessonProgressRail :blocks="lesson.content.blocks" :progress="lesson.progress" />

        <main class="lesson-main">
          <BilibiliPlayer
            v-if="videoSource?.bvid && videoSource.videoPage"
            :bvid="videoSource.bvid"
            :page="videoSource.videoPage"
            :title="videoSource.title"
          />
          <div v-else class="alert alert-info">本课暂未配置站内视频，可先阅读讲义。</div>

          <div class="progress-actions">
            <button
              class="btn btn-secondary"
              data-test="video-complete"
              :disabled="saving || lesson.progress.videoCompleted"
              @click="saveProgress({ videoCompleted: true }, 'video')"
            >
              {{ lesson.progress.videoCompleted ? '✓ 视频已学习' : '本段视频已学习' }}
            </button>
            <button
              class="btn btn-secondary"
              :disabled="saving || lesson.progress.readingCompleted"
              @click="saveProgress({ readingCompleted: true }, 'reading')"
            >
              {{ lesson.progress.readingCompleted ? '✓ 讲义已读完' : '标记讲义已读完' }}
            </button>
          </div>

          <div
            v-if="lesson.progress.videoCompleted && !lesson.progress.practiceCompleted"
            class="alert alert-info"
          >
            还需完成讲义和课时练习；练习尚未完成，系统不会提前标记本课完成。
          </div>
          <div v-else-if="!lesson.progress.practiceCompleted" class="practice-status">
            <strong>练习尚未完成</strong>
            <span>完成视频与讲义后，下一步将接通课时检查题和掌握度。</span>
          </div>

          <LessonBlockRenderer
            v-for="block in lesson.content.blocks"
            :key="block.key"
            :block="block"
          />

          <section class="sources">
            <h2>本课来源</h2>
            <a
              v-for="source in lesson.sources"
              :key="`${source.title}-${source.locator}`"
              :href="source.url.startsWith('project://') ? undefined : source.url"
              :target="source.url.startsWith('http') ? '_blank' : undefined"
              :rel="source.url.startsWith('http') ? 'noopener noreferrer' : undefined"
              class="source-row"
            >
              <span class="badge badge-neutral">{{ source.type }}</span>
              <span><strong>{{ source.title }}</strong><small>{{ source.locator }}</small></span>
            </a>
          </section>
        </main>

        <LessonTutorPanel :lesson-id="lesson.id" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.lesson-page {
  max-width: 1500px;
  margin: 0 auto;
  padding: 24px 20px 64px;
}

.lesson-header {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
}

.lesson-header h1 {
  margin-top: 8px;
  font-size: 27px;
}

.lesson-header p {
  margin: 5px 0 0;
  color: var(--color-text-secondary);
}

.back-link {
  font-size: 12px;
}

.lesson-layout {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr) 300px;
  gap: 18px;
  align-items: start;
}

.lesson-main {
  min-width: 0;
}

.progress-actions {
  display: flex;
  gap: 10px;
  margin: 14px 0;
}

.practice-status {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
  padding: 12px 14px;
  border: 1px dashed #cdd2df;
  border-radius: 10px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.sources {
  margin-top: 16px;
  padding: 20px;
  border: 1px solid var(--color-border);
  border-radius: 14px;
  background: white;
}

.sources h2 {
  margin-bottom: 12px;
}

.source-row {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 10px;
  padding: 9px 0;
  border-top: 1px solid #f0f1f5;
  color: var(--color-text);
}

.source-row small {
  display: block;
  color: var(--color-text-secondary);
}

@media (max-width: 1150px) {
  .lesson-layout { grid-template-columns: 160px minmax(0, 1fr); }
  .lesson-layout > :last-child { position: static; grid-column: 1 / -1; }
}

@media (max-width: 760px) {
  .lesson-layout { grid-template-columns: 1fr; }
  .lesson-layout > :first-child { display: none; }
}
</style>
