<template>
  <main class="page roadmap-page">
    <LoadingBlock v-if="loading && !roadmap" text="正在整理学习路线…" />

    <section v-else-if="notEnrolled" class="card roadmap-empty" aria-labelledby="roadmap-empty-title">
      <p class="roadmap-empty__index" aria-hidden="true">01</p>
      <div>
        <p class="roadmap-kicker">你的 Java + AI 成长主线</p>
        <h1 id="roadmap-empty-title">开启 Java + AI 学习路线</h1>
        <p>建立一份属于你的路线进度，从 Java 工程基础逐步走到能安全操作项目的 Agent。</p>
        <p v-if="enrollmentError" class="alert alert-danger" role="alert">开启失败，请稍后重试。</p>
        <button
          class="btn btn-primary"
          data-testid="roadmap-enroll"
          :disabled="enrolling || loading"
          @click="enroll"
        >
          {{ enrolling ? '正在开启…' : '开启学习路线' }}
        </button>
      </div>
    </section>

    <section v-else-if="error" class="card roadmap-error" role="alert">
      <p class="roadmap-kicker">路线暂时不可用</p>
      <h1>学习路线加载失败</h1>
      <p>请检查网络或 Java 服务状态后重试。</p>
      <button
        class="btn btn-secondary"
        data-testid="roadmap-retry"
        :disabled="loading"
        @click="load"
      >重试</button>
    </section>

    <template v-else-if="roadmap">
      <header class="roadmap-hero">
        <div>
          <p class="roadmap-kicker">{{ roadmap.roadmapCode }} · V{{ roadmap.templateVersion }}</p>
          <h1>{{ roadmap.title }}</h1>
          <p class="roadmap-hero__description">{{ roadmap.description }}</p>
        </div>
        <aside class="roadmap-progress" aria-label="路线必修进度">
          <strong>{{ progressPercent }}%</strong>
          <span>已完成 {{ roadmap.completedRequiredNodes }} / {{ roadmap.totalRequiredNodes }} 个必修节点</span>
          <div class="progress-track" aria-hidden="true">
            <div class="progress-fill" :style="{ width: `${progressPercent}%` }" />
          </div>
        </aside>
      </header>

      <section
        v-if="upgrade"
        class="roadmap-upgrade"
        data-testid="roadmap-upgrade-notice"
        aria-labelledby="roadmap-upgrade-title"
      >
        <div class="roadmap-upgrade__version" aria-hidden="true">
          <span>V{{ upgrade.sourceVersion }}</span>
          <span class="roadmap-upgrade__arrow">→</span>
          <strong>V{{ upgrade.targetVersion }}</strong>
        </div>
        <div class="roadmap-upgrade__copy">
          <p class="roadmap-kicker">新版学习路线已就绪</p>
          <h2 id="roadmap-upgrade-title">V2 初学者细化版</h2>
          <p>
            新路线拆为 {{ upgrade.addedModuleCount }} 个模块、
            {{ upgrade.addedNodeCodes.length }} 个知识节点，从 JDK 和第一个程序开始逐步学习。
          </p>
        </div>
        <button class="btn btn-primary" type="button" @click="upgradeDialogOpen = true">
          查看差异并升级
        </button>
      </section>

      <div v-if="upgradeDialogOpen && upgrade" data-testid="roadmap-upgrade-dialog">
        <ConfirmDialog
          v-model="upgradeDialogOpen"
          title="确认升级到 V2 初学者细化版"
          confirm-text="确认升级到 V2"
          :loading="upgradeLoading"
          @confirm="confirmUpgrade"
        >
          <div class="upgrade-dialog-copy">
            <p>V1 的历史记录会保留，不会删除已有打卡、测验或学习证据。</p>
            <dl>
              <div><dt>课程结构</dt><dd>新增 {{ upgrade.addedModuleCount }} 个模块</dd></div>
              <div><dt>知识节点</dt><dd>{{ upgrade.removedNodeCodes.length }} 个旧节点调整为 {{ upgrade.addedNodeCodes.length }} 个细化节点</dd></div>
              <div><dt>学习起点</dt><dd>从 JDK、变量、分支和循环开始</dd></div>
            </dl>
            <p class="upgrade-dialog-note">升级完成后将切换到 V2，只有内容完全等价的完成记录才会继承。</p>
            <p v-if="upgradeError" class="field-error" role="alert">{{ upgradeError }}</p>
          </div>
        </ConfirmDialog>
      </div>

      <div class="roadmap-toolbar">
        <p>{{ roadmap.stages.length }} 个阶段，按前置关系循序解锁</p>
        <div class="roadmap-toggle" role="group" aria-label="路线展示方式">
          <button
            type="button"
            :class="{ active: viewMode === 'graph' }"
            :aria-pressed="viewMode === 'graph'"
            aria-controls="roadmap-graph"
            @click="viewMode = 'graph'"
          >路线图</button>
          <button
            type="button"
            :class="{ active: viewMode === 'list' }"
            :aria-pressed="viewMode === 'list'"
            aria-controls="roadmap-list"
            @click="viewMode = 'list'"
          >列表</button>
        </div>
      </div>

      <RoadmapGraph v-show="viewMode === 'graph'" :stages="roadmap.stages" />
      <RoadmapList v-show="viewMode === 'list'" :stages="roadmap.stages" />
    </template>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import { roadmapApi } from '@/services/roadmap'
import type { RoadmapMap, RoadmapUpgrade } from '@/types/roadmap'
import RoadmapGraph from './components/RoadmapGraph.vue'
import RoadmapList from './components/RoadmapList.vue'

const roadmap = ref<RoadmapMap | null>(null)
const loading = ref(false)
const enrolling = ref(false)
const notEnrolled = ref(false)
const error = ref(false)
const enrollmentError = ref(false)
const upgrade = ref<RoadmapUpgrade | null>(null)
const upgradeDialogOpen = ref(false)
const upgradeLoading = ref(false)
const upgradeError = ref('')
const viewMode = ref<'graph' | 'list'>('graph')
let active = true
let requestSequence = 0

const progressPercent = computed(() => {
  if (!roadmap.value?.totalRequiredNodes) return 0
  return Math.round(roadmap.value.completedRequiredNodes / roadmap.value.totalRequiredNodes * 100)
})

function isNotFound(value: unknown) {
  return (value as { response?: { status?: number } })?.response?.status === 404
}

async function load() {
  if (!active || loading.value) return
  const sequence = ++requestSequence
  loading.value = true
  error.value = false
  notEnrolled.value = false
  try {
    const result = await roadmapApi.getCurrentMap()
    if (!active || sequence !== requestSequence) return
    roadmap.value = result
    void loadUpgrade(result.templateVersion, sequence)
  } catch (cause) {
    if (!active || sequence !== requestSequence) return
    roadmap.value = null
    if (isNotFound(cause)) notEnrolled.value = true
    else error.value = true
  } finally {
    if (active && sequence === requestSequence) loading.value = false
  }
}

async function enroll() {
  if (!active || enrolling.value || loading.value) return
  enrolling.value = true
  enrollmentError.value = false
  try {
    await roadmapApi.enroll('studypilot-java-ai', 2)
    if (!active) return
    await load()
  } catch {
    if (!active) return
    notEnrolled.value = true
    enrollmentError.value = true
  } finally {
    if (active) enrolling.value = false
  }
}

async function loadUpgrade(currentVersion: number, mapSequence: number) {
  if (currentVersion >= 2) {
    upgrade.value = null
    return
  }
  try {
    const previews = await roadmapApi.getUpgrades()
    if (!active || mapSequence !== requestSequence) return
    upgrade.value = previews
      .filter((item) => item.status === 'PREVIEW' && item.targetVersion > currentVersion)
      .sort((left, right) => right.targetVersion - left.targetVersion)[0] ?? null
  } catch {
    // 升级提示是可选增强，失败不能阻断当前路线的正常学习。
    if (active && mapSequence === requestSequence) upgrade.value = null
  }
}

async function confirmUpgrade() {
  if (!upgrade.value || upgradeLoading.value) return
  upgradeLoading.value = true
  upgradeError.value = ''
  try {
    await roadmapApi.confirmUpgrade(upgrade.value.id)
    if (!active) return
    upgradeDialogOpen.value = false
    await load()
  } catch {
    if (active) upgradeError.value = '升级失败，当前路线没有变化，请稍后重试。'
  } finally {
    if (active) upgradeLoading.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => {
  active = false
  requestSequence += 1
})
</script>

<style scoped>
.roadmap-page { max-width: 1160px; }
.roadmap-kicker {
  margin: 0 0 7px;
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.09em;
  text-transform: uppercase;
}
.roadmap-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 38px;
  align-items: end;
  margin-bottom: 30px;
  padding: 10px 0 25px;
  border-bottom: 1px solid var(--color-border);
}
.roadmap-hero h1 { max-width: 700px; font-size: clamp(28px, 4vw, 44px); letter-spacing: -0.035em; }
.roadmap-hero__description { max-width: 680px; margin: 12px 0 0; color: var(--color-text-secondary); font-size: 15px; }
.roadmap-progress { display: grid; gap: 6px; }
.roadmap-progress strong { font-size: 28px; line-height: 1; font-variant-numeric: tabular-nums; }
.roadmap-progress span { color: var(--color-text-secondary); font-size: 12px; }
.roadmap-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}
.roadmap-upgrade {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 24px;
  align-items: center;
  margin: -3px 0 30px;
  padding: 24px 26px;
  border: 1px solid color-mix(in srgb, var(--color-primary) 28%, var(--color-border));
  border-left: 4px solid var(--color-primary);
  border-radius: var(--radius);
  background: linear-gradient(110deg, var(--color-primary-soft), var(--color-surface) 58%);
}
.roadmap-upgrade__version { display: flex; align-items: center; gap: 8px; font-variant-numeric: tabular-nums; }
.roadmap-upgrade__version span,
.roadmap-upgrade__version strong { display: grid; place-items: center; width: 48px; height: 48px; border-radius: 50%; }
.roadmap-upgrade__version span { border: 1px solid var(--color-border); color: var(--color-text-secondary); background: var(--color-surface); }
.roadmap-upgrade__version strong { color: white; background: var(--color-primary); }
.roadmap-upgrade__arrow { width: auto !important; border: 0 !important; background: transparent !important; }
.roadmap-upgrade__copy h2 { margin: 0; font-size: 22px; }
.roadmap-upgrade__copy > p:last-child { margin: 7px 0 0; color: var(--color-text-secondary); line-height: 1.65; }
.upgrade-dialog-copy p { margin: 0; line-height: 1.65; }
.upgrade-dialog-copy dl { display: grid; gap: 8px; margin: 16px 0; }
.upgrade-dialog-copy dl > div { display: flex; justify-content: space-between; gap: 20px; padding-bottom: 8px; border-bottom: 1px solid var(--color-border); }
.upgrade-dialog-copy dt { color: var(--color-text-secondary); }
.upgrade-dialog-copy dd { margin: 0; text-align: right; font-weight: 650; }
.upgrade-dialog-note { color: var(--color-text-secondary); font-size: 13px; }
.roadmap-toolbar > p { margin: 0; color: var(--color-text-secondary); font-size: 13px; }
.roadmap-toggle { display: inline-flex; padding: 3px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); }
.roadmap-toggle button { border: 0; border-radius: 6px; padding: 6px 13px; background: transparent; color: var(--color-text-secondary); font: inherit; cursor: pointer; }
.roadmap-toggle button.active { background: var(--color-primary-soft); color: var(--color-primary); font-weight: 650; }
.roadmap-toggle button:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.roadmap-empty,
.roadmap-error { min-height: 330px; display: grid; align-content: center; }
.roadmap-empty { grid-template-columns: 90px minmax(0, 600px); gap: 18px; padding: 44px; }
.roadmap-empty__index { margin: 0; color: var(--color-border); font-size: 54px; font-weight: 800; line-height: 1; }
.roadmap-empty h1,
.roadmap-error h1 { font-size: 26px; }
.roadmap-empty > div > p:not(.roadmap-kicker, .alert),
.roadmap-error > p:not(.roadmap-kicker) { margin: 10px 0 18px; color: var(--color-text-secondary); }
.roadmap-error { justify-items: start; padding: 44px; }
@media (max-width: 700px) {
  .roadmap-hero { grid-template-columns: 1fr; gap: 22px; }
  .roadmap-toolbar { align-items: flex-start; flex-direction: column; }
  .roadmap-toggle { width: 100%; }
  .roadmap-toggle button { flex: 1; min-height: 42px; }
  .roadmap-empty { grid-template-columns: 1fr; padding: 28px 20px; }
  .roadmap-empty__index { font-size: 34px; }
  .roadmap-upgrade { grid-template-columns: 1fr; gap: 16px; }
  .roadmap-upgrade__version { justify-content: flex-start; }
  .roadmap-upgrade .btn { width: 100%; }
}
@media (prefers-reduced-motion: reduce) {
  .progress-fill { transition: none; }
}
</style>
