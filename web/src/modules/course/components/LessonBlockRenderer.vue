<script setup lang="ts">
import { computed } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'

import type { LessonBlock } from '@/types/course'

const props = defineProps<{ block: LessonBlock }>()

const safeHtml = computed(() =>
  DOMPurify.sanitize(marked.parse(props.block.markdown ?? '', { async: false })),
)
</script>

<template>
  <section :id="block.key" class="lesson-block" :class="`type-${block.type.toLowerCase()}`">
    <div class="block-kicker">{{ block.type.replace('_', ' ') }}</div>
    <h2>{{ block.title }}</h2>
    <div v-if="block.markdown" class="markdown" v-html="safeHtml" />
    <div v-if="block.projectPath" class="project-path">
      <span>项目文件</span>
      <code>{{ block.projectPath }}</code>
    </div>
    <div v-if="block.type === 'CHECKPOINT'" class="checkpoint-preview">
      <strong>{{ block.question }}</strong>
      <p>课时练习将在下一步接通判题与掌握度，现在不会向浏览器泄露答案。</p>
    </div>
  </section>
</template>

<style scoped>
.lesson-block {
  padding: 24px;
  border: 1px solid var(--color-border);
  border-radius: 14px;
  background: var(--color-surface);
  scroll-margin-top: 76px;
}

.lesson-block + .lesson-block {
  margin-top: 16px;
}

.block-kicker {
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: .08em;
}

h2 {
  margin-top: 5px;
  font-size: 19px;
}

.markdown {
  margin-top: 12px;
  color: #3d4352;
}

.project-path {
  display: grid;
  gap: 5px;
  margin-top: 14px;
  padding: 12px;
  border-radius: 8px;
  background: #151925;
  color: #d6daf0;
  overflow-wrap: anywhere;
}

.project-path span {
  color: #8f97b5;
  font-size: 11px;
}

.checkpoint-preview {
  margin-top: 14px;
  padding: 14px;
  border-radius: 10px;
  background: var(--color-primary-soft);
}

.checkpoint-preview p {
  margin-bottom: 0;
  color: var(--color-text-secondary);
  font-size: 13px;
}
</style>
