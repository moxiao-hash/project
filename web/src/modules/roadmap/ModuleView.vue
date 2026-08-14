<template>
  <main class="page module-page">
    <LoadingBlock v-if="loading" text="正在加载路线模块…" />
    <section v-else-if="notFound" class="card module-state">
      <h1>未找到该路线模块</h1>
      <p>它可能已经调整，或不属于当前学习路线。</p>
      <RouterLink class="btn btn-secondary" to="/roadmap">返回路线</RouterLink>
    </section>
    <section v-else-if="error" class="card module-state" role="alert">
      <h1>模块加载失败</h1>
      <p>请检查网络后重试。</p>
      <button class="btn btn-secondary" :disabled="loading" @click="loadModule">重试</button>
    </section>
    <template v-else-if="roadmapModule">
      <RouterLink class="module-back" :to="{ name: 'roadmap-stage', params: { id: roadmapModule.stageId } }">
        ← 返回所属阶段
      </RouterLink>
      <header class="module-hero">
        <p>模块 {{ roadmapModule.order }} · 必修进度 {{ roadmapModule.completedRequiredNodes }} / {{ roadmapModule.totalRequiredNodes }}</p>
        <h1>{{ roadmapModule.title }}</h1>
        <p class="module-description">{{ roadmapModule.description }}</p>
        <p>里程碑：<strong>{{ roadmapModule.milestoneNode.title }}</strong></p>
      </header>

      <aside v-if="roadmapModule.displayStatus === 'LOCKED'" class="module-lock" role="status">
        <strong>该模块尚未解锁，请先完成模块前置节点。</strong>
        <span>{{ prerequisiteLabel }}</span>
      </aside>
      <p v-else class="module-prerequisites">{{ prerequisiteLabel }}</p>

      <section aria-labelledby="module-nodes-title">
        <h2 id="module-nodes-title">模块节点</h2>
        <ol class="module-nodes" data-testid="module-nodes">
          <li v-for="item in orderedNodes" :key="item.id">
            <RoadmapNodeCard :node="item" :module-id="roadmapModule.id" />
          </li>
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
import type { RoadmapModule } from '@/types/roadmap'
import RoadmapNodeCard from './components/RoadmapNodeCard.vue'

const route = useRoute()
const roadmapModule = ref<RoadmapModule | null>(null)
const loading = ref(false)
const error = ref(false)
const notFound = ref(false)
let requestSequence = 0
let active = true

const orderedNodes = computed(() => [...(roadmapModule.value?.nodes ?? [])]
  .sort((left, right) => left.order - right.order))
const moduleNodeCodes = computed(() => new Set(orderedNodes.value.map((item) => item.code)))
const prerequisiteCodes = computed(() => [...new Set(orderedNodes.value
  .flatMap((item) => item.prerequisiteCodes)
  .filter((code) => !moduleNodeCodes.value.has(code)))])
const prerequisiteLabel = computed(() => prerequisiteCodes.value.length
  ? `模块前置：${prerequisiteCodes.value.join('、')}`
  : '模块前置：无，可直接开始。')

function isNotFound(value: unknown) {
  return (value as { response?: { status?: number } })?.response?.status === 404
}

async function loadModule() {
  if (!active) return
  const id = String(route.params.id ?? '')
  const sequence = ++requestSequence
  loading.value = true
  error.value = false
  notFound.value = false
  roadmapModule.value = null
  try {
    const result = await roadmapApi.getModule(id)
    if (active && sequence === requestSequence) roadmapModule.value = result
  } catch (cause) {
    if (!active || sequence !== requestSequence) return
    if (isNotFound(cause)) notFound.value = true
    else error.value = true
  } finally {
    if (active && sequence === requestSequence) loading.value = false
  }
}

watch(() => route.params.id, loadModule, { immediate: true })
onBeforeUnmount(() => {
  active = false
  requestSequence += 1
})
</script>

<style scoped>
.module-page { max-width: 930px; }
.module-back { display: inline-block; margin-bottom: 20px; font-size: 13px; }
.module-hero { padding: 8px 0 26px; border-bottom: 1px solid var(--color-border); }
.module-hero > p:first-child { margin: 0 0 7px; color: var(--color-primary); font-size: 12px; font-weight: 700; }
.module-hero h1 { max-width: 760px; font-size: clamp(28px, 4vw, 40px); letter-spacing: -0.03em; }
.module-description { margin: 12px 0; color: var(--color-text-secondary); }
.module-lock,
.module-prerequisites { margin: 20px 0 28px; padding: 13px 15px; border-left: 3px solid var(--color-warning); background: var(--color-warning-soft); color: #75510f; }
.module-lock { display: grid; gap: 5px; }
.module-page h2 { margin-bottom: 14px; font-size: 18px; }
.module-nodes { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 0; padding: 0; list-style: none; }
.module-state { padding: 40px; }
.module-state p { margin: 8px 0 18px; color: var(--color-text-secondary); }
@media (max-width: 680px) { .module-nodes { grid-template-columns: 1fr; } }
</style>
