<template>
  <section id="roadmap-graph" class="roadmap-graph" data-testid="roadmap-graph" aria-label="学习路线图">
    <article v-for="stage in stages" :key="stage.id" class="roadmap-graph__stage">
      <header class="roadmap-graph__stage-header">
        <span class="roadmap-graph__marker" aria-hidden="true">{{ stage.order }}</span>
        <div>
          <p class="roadmap-graph__eyebrow">阶段 {{ stage.order }} · {{ stage.completedRequiredNodes }}/{{ stage.totalRequiredNodes }} 必修完成</p>
          <h2>
            <RouterLink :to="{ name: 'roadmap-stage', params: { id: stage.id } }">{{ stage.title }}</RouterLink>
          </h2>
          <p>{{ stage.description }}</p>
        </div>
      </header>
      <ol class="roadmap-graph__branches">
        <template v-if="stage.modules?.length">
          <li v-for="item in stage.modules" :key="item.id">
            <RoadmapModuleCard :module="item" />
          </li>
        </template>
        <template v-else>
          <li v-for="item in stage.nodes" :key="item.id">
            <RoadmapNodeCard :node="item" />
          </li>
        </template>
      </ol>
    </article>
  </section>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router'
import RoadmapNodeCard from './RoadmapNodeCard.vue'
import RoadmapModuleCard from './RoadmapModuleCard.vue'
import type { RoadmapStage } from '@/types/roadmap'

defineProps<{ stages: RoadmapStage[] }>()
</script>

<style scoped>
.roadmap-graph { position: relative; display: grid; gap: 34px; }
.roadmap-graph::before {
  content: '';
  position: absolute;
  top: 22px;
  bottom: 22px;
  left: 20px;
  width: 2px;
  background: var(--color-border);
}
.roadmap-graph__stage { position: relative; }
.roadmap-graph__stage-header {
  display: grid;
  grid-template-columns: 42px 1fr;
  gap: 16px;
  align-items: start;
}
.roadmap-graph__marker {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border: 2px solid var(--color-primary);
  border-radius: 50%;
  background: var(--color-surface);
  color: var(--color-primary);
  font-weight: 750;
}
.roadmap-graph__eyebrow {
  margin: 0 0 4px;
  color: var(--color-text-secondary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.roadmap-graph h2 { font-size: 18px; }
.roadmap-graph h2 a { color: var(--color-text); }
.roadmap-graph h2 a:hover { color: var(--color-primary); }
.roadmap-graph__stage-header p:last-child { margin: 5px 0 0; color: var(--color-text-secondary); font-size: 13px; }
.roadmap-graph__branches {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 16px 0 0 58px;
  padding: 0;
  list-style: none;
}
.roadmap-graph__branches li { position: relative; }
.roadmap-graph__branches li::before {
  content: '';
  position: absolute;
  top: 26px;
  right: 100%;
  width: 18px;
  border-top: 1px solid var(--color-border);
}
@media (max-width: 700px) {
  .roadmap-graph__branches { grid-template-columns: 1fr; margin-left: 54px; }
}
</style>
