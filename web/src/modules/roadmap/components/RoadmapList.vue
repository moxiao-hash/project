<template>
  <section id="roadmap-list" class="roadmap-list" aria-label="学习路线列表">
    <ol>
      <li v-for="stage in stages" :key="stage.id" class="roadmap-list__stage">
        <header>
          <p>阶段 {{ stage.order }} · {{ stage.completedRequiredNodes }}/{{ stage.totalRequiredNodes }} 必修完成</p>
          <h2>
            <RouterLink :to="{ name: 'roadmap-stage', params: { id: stage.id } }">{{ stage.title }}</RouterLink>
          </h2>
        </header>
        <ol class="roadmap-list__nodes">
          <template v-if="stage.modules?.length">
            <li v-for="item in stage.modules" :key="item.id">
              <RoadmapModuleCard :module="item" compact />
            </li>
          </template>
          <template v-else>
            <li v-for="item in stage.nodes" :key="item.id">
              <RoadmapNodeCard :node="item" compact />
            </li>
          </template>
        </ol>
      </li>
    </ol>
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
.roadmap-list > ol,
.roadmap-list__nodes { margin: 0; padding: 0; list-style: none; }
.roadmap-list > ol { display: grid; gap: 28px; }
.roadmap-list__stage { display: grid; grid-template-columns: minmax(180px, 0.7fr) minmax(0, 2fr); gap: 24px; }
.roadmap-list__stage > header { padding-top: 4px; }
.roadmap-list__stage > header p { margin: 0 0 6px; color: var(--color-text-secondary); font-size: 12px; }
.roadmap-list__stage h2 { font-size: 17px; }
.roadmap-list__stage h2 a { color: var(--color-text); }
.roadmap-list__stage h2 a:hover { color: var(--color-primary); }
.roadmap-list__nodes { display: grid; gap: 10px; }
@media (max-width: 700px) {
  .roadmap-list__stage { grid-template-columns: 1fr; gap: 10px; }
}
</style>
