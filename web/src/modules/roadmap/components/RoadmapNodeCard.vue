<template>
  <article
    class="roadmap-node-card"
    :class="{
      'roadmap-node-card--locked': locked,
      'roadmap-node-card--optional': !node.required,
      'roadmap-node-card--compact': compact,
    }"
  >
    <component
      :is="locked ? 'div' : RouterLink"
      v-bind="locked ? {} : { to: { name: 'roadmap-node', params: { id: node.id } } }"
      class="roadmap-node-card__body"
    >
      <div class="roadmap-node-card__topline">
        <span class="roadmap-node-card__order">{{ String(node.order).padStart(2, '0') }}</span>
        <span class="roadmap-node-card__status">{{ statusLabel }}</span>
      </div>
      <h3>{{ node.title }}</h3>
      <p v-if="!compact" class="roadmap-node-card__meta">
        {{ node.estimatedMinutes }} 分钟学习 · {{ node.practiceMinutes }} 分钟实践
        <span aria-hidden="true"> · </span>{{ node.required ? '必修' : '选修' }}
      </p>
      <p v-if="lockedReason" class="roadmap-node-card__locked-reason">{{ lockedReason }}</p>
    </component>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { RoadmapNode } from '@/types/roadmap'

const props = withDefaults(defineProps<{
  node: RoadmapNode
  compact?: boolean
}>(), { compact: false })

const statusLabels: Record<RoadmapNode['displayStatus'], string> = {
  LOCKED: '已锁定',
  AVAILABLE: '可开始',
  SCHEDULED: '已排期',
  IN_PROGRESS: '学习中',
  QUIZ_PENDING: '待测验',
  REVIEW_REQUIRED: '待复习',
  COMPLETED: '已完成',
}

const locked = computed(() => props.node.displayStatus === 'LOCKED')
const statusLabel = computed(() => statusLabels[props.node.displayStatus])
const lockedReason = computed(() => locked.value
  ? `完成前置节点后解锁：${props.node.prerequisiteCodes.join('、')}`
  : '')
</script>

<style scoped>
.roadmap-node-card {
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
  box-shadow: var(--shadow);
  overflow: hidden;
}

.roadmap-node-card--optional { border-style: dashed; }
.roadmap-node-card--locked { background: #f8f9fc; box-shadow: none; }

.roadmap-node-card__body {
  display: block;
  min-height: 100%;
  padding: 16px 18px;
  color: var(--color-text);
}

a.roadmap-node-card__body { transition: border-color 0.15s, background 0.15s; }
a.roadmap-node-card__body:hover { background: var(--color-primary-soft); }
a.roadmap-node-card__body:focus-visible {
  outline: 3px solid rgba(79, 70, 229, 0.28);
  outline-offset: -3px;
}

.roadmap-node-card__topline {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  color: var(--color-text-secondary);
  font-size: 12px;
  font-weight: 650;
  letter-spacing: 0.04em;
}

.roadmap-node-card__order { font-variant-numeric: tabular-nums; }
.roadmap-node-card__status { color: var(--color-primary); }
.roadmap-node-card--locked .roadmap-node-card__status { color: var(--color-text-secondary); }

h3 { font-size: 15px; font-weight: 680; }
.roadmap-node-card__meta,
.roadmap-node-card__locked-reason {
  margin: 8px 0 0;
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1.55;
}
.roadmap-node-card__locked-reason { color: #7a5312; }
.roadmap-node-card--compact .roadmap-node-card__body { padding: 12px 14px; }

@media (prefers-reduced-motion: reduce) {
  a.roadmap-node-card__body { transition: none; }
}
</style>
