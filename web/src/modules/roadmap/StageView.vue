<template>
  <main class="page stage-page">
    <LoadingBlock v-if="loading" text="正在加载路线阶段…" />
    <section v-else-if="notFound" class="card stage-state">
      <h1>未找到该路线阶段</h1>
      <p>它可能已经调整，或不属于当前学习路线。</p>
      <RouterLink class="btn btn-secondary" to="/roadmap">返回路线</RouterLink>
    </section>
    <section v-else-if="error" class="card stage-state" role="alert">
      <h1>阶段加载失败</h1>
      <p>请检查网络后重试。</p>
      <button class="btn btn-secondary" :disabled="loading" @click="loadStage">重试</button>
    </section>
    <template v-else-if="stage">
      <RouterLink class="stage-back" to="/roadmap">← 返回学习路线</RouterLink>
      <header class="stage-hero">
        <p>阶段 {{ stage.order }} · 必修进度 {{ stage.completedRequiredNodes }} / {{ stage.totalRequiredNodes }}</p>
        <h1>{{ stage.title }}</h1>
        <div class="stage-objective">
          <span>阶段目标</span>
          <p>{{ stage.description }}</p>
        </div>
        <p class="stage-project">毕业项目：<strong>{{ stage.graduationProjectTitle }}</strong></p>
      </header>
      <section v-if="orderedModules.length" aria-labelledby="stage-modules-title">
        <h2 id="stage-modules-title">学习模块</h2>
        <ol class="stage-modules" data-testid="stage-modules">
          <li v-for="item in orderedModules" :key="item.id">
            <RouterLink :to="{ name: 'roadmap-module', params: { id: item.id } }">
              <span>模块 {{ item.order }} · {{ statusLabel(item.displayStatus) }}</span>
              <h3>{{ item.title }}</h3>
              <p>{{ item.description }}</p>
              <p>{{ item.completedRequiredNodes }} / {{ item.totalRequiredNodes }} 个必修节点</p>
              <p>里程碑：{{ item.milestoneNodeCode }}</p>
            </RouterLink>
          </li>
        </ol>
      </section>
      <section v-else aria-labelledby="stage-nodes-title" data-testid="stage-nodes">
        <h2 id="stage-nodes-title">学习节点</h2>
        <ol class="stage-nodes">
          <li v-for="item in stage.nodes" :key="item.id"><RoadmapNodeCard :node="item" /></li>
        </ol>
      </section>
    </template>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import LoadingBlock from '@/components/LoadingBlock.vue'
import { roadmapApi } from '@/services/roadmap'
import type { RoadmapStage } from '@/types/roadmap'
import RoadmapNodeCard from './components/RoadmapNodeCard.vue'

const route = useRoute()
const stage = ref<RoadmapStage | null>(null)
const loading = ref(false)
const error = ref(false)
const notFound = ref(false)
let requestSequence = 0
let active = true

const orderedModules = computed(() => [...(stage.value?.modules ?? [])]
  .sort((left, right) => left.order - right.order))

const statusLabels: Record<import('@/types/roadmap').RoadmapDisplayStatus, string> = {
  LOCKED: '已锁定', AVAILABLE: '可开始', SCHEDULED: '已排期', IN_PROGRESS: '学习中',
  QUIZ_PENDING: '待测验', REVIEW_REQUIRED: '待复习', COMPLETED: '已完成',
}

function statusLabel(status: import('@/types/roadmap').RoadmapDisplayStatus) {
  return statusLabels[status]
}

function isNotFound(value: unknown) {
  return (value as { response?: { status?: number } })?.response?.status === 404
}

async function loadStage() {
  if (!active) return
  const id = String(route.params.id ?? '')
  const sequence = ++requestSequence
  loading.value = true
  error.value = false
  notFound.value = false
  stage.value = null
  try {
    const result = await roadmapApi.getStage(id)
    if (active && sequence === requestSequence) stage.value = result
  } catch (cause) {
    if (!active || sequence !== requestSequence) return
    if (isNotFound(cause)) notFound.value = true
    else error.value = true
  } finally {
    if (active && sequence === requestSequence) loading.value = false
  }
}

watch(() => route.params.id, loadStage, { immediate: true })
onBeforeUnmount(() => {
  active = false
  requestSequence += 1
})
</script>

<style scoped>
.stage-page { max-width: 930px; }
.stage-back { display: inline-block; margin-bottom: 20px; font-size: 13px; }
.stage-hero { padding: 8px 0 30px; margin-bottom: 28px; border-bottom: 1px solid var(--color-border); }
.stage-hero > p:first-child { margin: 0 0 7px; color: var(--color-primary); font-size: 12px; font-weight: 700; }
.stage-hero h1 { max-width: 760px; font-size: clamp(28px, 4vw, 40px); letter-spacing: -0.03em; }
.stage-objective { display: grid; grid-template-columns: 100px 1fr; gap: 18px; margin-top: 22px; }
.stage-objective span { color: var(--color-text-secondary); font-size: 12px; font-weight: 700; }
.stage-objective p { margin: 0; font-size: 16px; }
.stage-project { margin: 18px 0 0; padding: 12px 14px; border-left: 3px solid var(--color-primary); background: var(--color-primary-soft); }
.stage-page h2 { margin-bottom: 14px; font-size: 18px; }
.stage-nodes,
.stage-modules { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 0; padding: 0; list-style: none; }
.stage-modules a { display: block; height: 100%; padding: 18px; border: 1px solid var(--color-border); border-radius: var(--radius); background: var(--color-surface); color: var(--color-text); box-shadow: var(--shadow); }
.stage-modules a:hover { border-color: var(--color-primary); }
.stage-modules a:focus-visible { outline: 3px solid rgba(79, 70, 229, 0.28); outline-offset: 2px; }
.stage-modules span { color: var(--color-primary); font-size: 12px; font-weight: 700; }
.stage-modules h3 { margin-top: 7px; font-size: 17px; }
.stage-modules p { margin: 7px 0 0; color: var(--color-text-secondary); font-size: 13px; }
.stage-state { padding: 40px; }
.stage-state p { color: var(--color-text-secondary); margin: 8px 0 18px; }
@media (max-width: 680px) {
  .stage-objective { grid-template-columns: 1fr; gap: 5px; }
  .stage-nodes, .stage-modules { grid-template-columns: 1fr; }
}
</style>
