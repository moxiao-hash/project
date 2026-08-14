<template>
  <article class="roadmap-module-card" :class="{ 'roadmap-module-card--compact': compact }">
    <RouterLink :to="{ name: 'roadmap-module', params: { id: module.id } }">
      <div class="roadmap-module-card__topline">
        <span>模块 {{ module.order }}</span>
        <span>{{ statusLabel }}</span>
      </div>
      <h3>{{ module.title }}</h3>
      <p v-if="!compact">{{ module.description }}</p>
      <p>{{ module.completedRequiredNodes }} / {{ module.totalRequiredNodes }} 个必修节点</p>
      <p>里程碑 {{ module.milestoneNodeCode }}</p>
    </RouterLink>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { RoadmapDisplayStatus, RoadmapModuleSummary } from '@/types/roadmap'

const props = withDefaults(defineProps<{
  module: RoadmapModuleSummary
  compact?: boolean
}>(), { compact: false })

const labels: Record<RoadmapDisplayStatus, string> = {
  LOCKED: '已锁定', AVAILABLE: '可开始', SCHEDULED: '已排期', IN_PROGRESS: '学习中',
  QUIZ_PENDING: '待测验', REVIEW_REQUIRED: '待复习', COMPLETED: '已完成',
}
const statusLabel = computed(() => labels[props.module.displayStatus])
</script>

<style scoped>
.roadmap-module-card { height: 100%; border: 1px solid var(--color-border); border-radius: var(--radius); background: var(--color-surface); box-shadow: var(--shadow); overflow: hidden; }
.roadmap-module-card a { display: block; min-height: 100%; padding: 16px 18px; color: var(--color-text); }
.roadmap-module-card a:hover { background: var(--color-primary-soft); }
.roadmap-module-card a:focus-visible { outline: 3px solid rgba(79, 70, 229, 0.28); outline-offset: -3px; }
.roadmap-module-card__topline { display: flex; justify-content: space-between; gap: 12px; color: var(--color-primary); font-size: 12px; font-weight: 700; }
.roadmap-module-card h3 { margin-top: 8px; font-size: 16px; }
.roadmap-module-card p { margin: 7px 0 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.5; }
.roadmap-module-card--compact a { padding: 12px 14px; }
</style>
