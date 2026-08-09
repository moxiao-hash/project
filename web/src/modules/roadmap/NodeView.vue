<template>
  <main class="page node-page">
    <LoadingBlock v-if="loading" text="正在加载学习节点…" />
    <section v-else-if="notFound" class="card node-state">
      <h1>未找到该学习节点</h1>
      <p>它可能已经调整，或不属于当前学习路线。</p>
      <RouterLink class="btn btn-secondary" to="/roadmap">返回路线</RouterLink>
    </section>
    <section v-else-if="error" class="card node-state" role="alert">
      <h1>节点加载失败</h1>
      <p>请检查网络后重试。</p>
      <button class="btn btn-secondary" :disabled="loading" @click="loadNode">重试</button>
    </section>
    <template v-else-if="node">
      <RouterLink class="node-back" to="/roadmap">← 返回学习路线</RouterLink>
      <header class="node-hero">
        <div class="node-meta">
          <span>{{ statusLabel }}</span>
          <span>{{ node.required ? '必修' : '选修' }}</span>
          <span>{{ node.difficulty }}</span>
          <span>{{ node.estimatedMinutes + node.practiceMinutes }} 分钟</span>
        </div>
        <h1>{{ node.title }}</h1>
        <p class="node-gate">打卡、测验和必交成果全部满足后才完成节点</p>
      </header>

      <div class="node-content">
        <section aria-labelledby="objectives-title">
          <p class="node-section-index">01</p>
          <h2 id="objectives-title">学习目标</h2>
          <ul><li v-for="item in node.objectives" :key="item">{{ item }}</li></ul>
        </section>
        <section aria-labelledby="frequency-title">
          <p class="node-section-index">02</p>
          <h2 id="frequency-title">开发高频点</h2>
          <ul><li v-for="item in node.highFrequency" :key="item">{{ item }}</li></ul>
        </section>
        <section aria-labelledby="mistakes-title">
          <p class="node-section-index">03</p>
          <h2 id="mistakes-title">常见误区</h2>
          <ul><li v-for="item in node.commonMistakes" :key="item">{{ item }}</li></ul>
        </section>
        <section aria-labelledby="keywords-title">
          <p class="node-section-index">04</p>
          <h2 id="keywords-title">资源搜索关键词</h2>
          <ul class="node-keywords"><li v-for="item in node.searchKeywords" :key="item">{{ item }}</li></ul>
        </section>
        <section id="node-prerequisites" data-testid="node-prerequisites" aria-labelledby="prerequisites-title">
          <p class="node-section-index">05</p>
          <h2 id="prerequisites-title">前置节点</h2>
          <p v-if="node.prerequisiteCodes.length === 0" class="muted">无，可直接开始。</p>
          <ul v-else>
            <li v-for="code in node.prerequisiteCodes" :key="code">
              <RouterLink
                v-if="prerequisiteIds[code]"
                :to="{ name: 'roadmap-node', params: { id: prerequisiteIds[code] } }"
              >{{ code }}</RouterLink>
              <template v-else>
                <span>{{ code }}</span>
                <span class="prerequisite-note">
                  — {{ prerequisiteResolution === 'unavailable'
                    ? '前置节点链接暂时无法解析'
                    : '当前路线中未找到该前置节点' }}
                </span>
              </template>
            </li>
          </ul>
        </section>
      </div>
    </template>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import LoadingBlock from '@/components/LoadingBlock.vue'
import { roadmapApi } from '@/services/roadmap'
import type { RoadmapMap, RoadmapNode } from '@/types/roadmap'

const route = useRoute()
const node = ref<RoadmapNode | null>(null)
const loading = ref(false)
const error = ref(false)
const notFound = ref(false)
const prerequisiteIds = ref<Record<string, string>>({})
const prerequisiteResolution = ref<'resolved' | 'unavailable'>('unavailable')
let requestSequence = 0
let active = true

const labels: Record<RoadmapNode['displayStatus'], string> = {
  LOCKED: '已锁定', AVAILABLE: '可开始', SCHEDULED: '已排期', IN_PROGRESS: '学习中',
  QUIZ_PENDING: '待测验', REVIEW_REQUIRED: '待复习', COMPLETED: '已完成',
}
const statusLabel = computed(() => node.value ? labels[node.value.displayStatus] : '')

function isNotFound(value: unknown) {
  return (value as { response?: { status?: number } })?.response?.status === 404
}

async function loadNode() {
  if (!active) return
  const id = String(route.params.id ?? '')
  const sequence = ++requestSequence
  loading.value = true
  error.value = false
  notFound.value = false
  node.value = null
  prerequisiteIds.value = {}
  prerequisiteResolution.value = 'unavailable'

  try {
    const result = await roadmapApi.getNode(id)
    if (!active || sequence !== requestSequence) return
    node.value = result
    loading.value = false
    if (result.prerequisiteCodes.length > 0) void resolvePrerequisites(sequence)
  } catch (cause) {
    if (!active || sequence !== requestSequence) return
    if (isNotFound(cause)) notFound.value = true
    else error.value = true
    loading.value = false
  }
}

async function resolvePrerequisites(sequence: number) {
  try {
    const map = await roadmapApi.getCurrentMap()
    if (!active || sequence !== requestSequence || !isRoadmapMap(map)) return
    prerequisiteIds.value = Object.fromEntries(
      map.stages.flatMap((stage) => stage.nodes.map((item) => [item.code, item.id])),
    )
    prerequisiteResolution.value = 'resolved'
  } catch {
    // Prerequisite links are an optional enhancement; the primary node remains readable.
  }
}

function isRoadmapMap(value: unknown): value is RoadmapMap {
  return typeof value === 'object' && value !== null && Array.isArray((value as RoadmapMap).stages)
}

watch(() => route.params.id, loadNode, { immediate: true })
onBeforeUnmount(() => {
  active = false
  requestSequence += 1
})
</script>

<style scoped>
.node-page { max-width: 930px; }
.node-back { display: inline-block; margin-bottom: 20px; font-size: 13px; }
.node-hero { padding: 8px 0 28px; border-bottom: 1px solid var(--color-border); }
.node-meta { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.node-meta span { padding: 2px 9px; border: 1px solid var(--color-border); border-radius: 999px; color: var(--color-text-secondary); font-size: 11px; font-weight: 650; }
.node-hero h1 { max-width: 770px; font-size: clamp(28px, 4vw, 42px); letter-spacing: -0.035em; }
.node-gate { margin: 18px 0 0; padding: 12px 14px; border-left: 3px solid var(--color-warning); background: var(--color-warning-soft); color: #75510f; }
.node-content { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 34px; }
.node-content section { padding: 25px 0; border-bottom: 1px solid var(--color-border); }
.node-section-index { margin: 0 0 5px; color: var(--color-primary); font-size: 11px; font-weight: 750; }
.node-content h2 { font-size: 17px; }
.node-content ul { margin: 12px 0 0; padding-left: 20px; }
.node-content li + li { margin-top: 6px; }
.prerequisite-note { margin-left: 5px; color: var(--color-text-secondary); font-size: 12px; }
.node-keywords { display: flex; flex-wrap: wrap; gap: 7px; padding: 0 !important; list-style: none; }
.node-keywords li { margin: 0 !important; padding: 3px 9px; border-radius: 6px; background: #eef0f4; color: #4b5563; font-size: 12px; }
.node-state { padding: 40px; }
.node-state p { margin: 8px 0 18px; color: var(--color-text-secondary); }
@media (max-width: 680px) { .node-content { grid-template-columns: 1fr; } }
</style>
