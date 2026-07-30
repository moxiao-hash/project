<script setup lang="ts">
import type { LessonBlock, LessonProgress } from '@/types/course'

defineProps<{ blocks: LessonBlock[]; progress: LessonProgress }>()
</script>

<template>
  <aside class="rail">
    <div class="rail-title">本课目录</div>
    <a v-for="block in blocks" :key="block.key" :href="`#${block.key}`" class="rail-link">
      <span class="rail-dot" />
      {{ block.title }}
    </a>
    <div class="rail-status">
      <div :class="{ done: progress.videoCompleted }">视频学习</div>
      <div :class="{ done: progress.readingCompleted }">讲义阅读</div>
      <div :class="{ done: progress.practiceCompleted }">课时练习</div>
    </div>
  </aside>
</template>

<style scoped>
.rail {
  position: sticky;
  top: 76px;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: var(--color-surface);
}

.rail-title {
  margin-bottom: 10px;
  font-weight: 700;
}

.rail-link {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.rail-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #c9cedb;
}

.rail-status {
  display: grid;
  gap: 5px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--color-border);
  color: var(--color-text-secondary);
  font-size: 12px;
}

.rail-status div::before {
  content: '○';
  margin-right: 6px;
}

.rail-status .done {
  color: var(--color-success);
}

.rail-status .done::before {
  content: '✓';
}
</style>
