<template>
  <div class="workspace-page">
    <header class="page-header">
      <div class="header-content">
        <p class="eyebrow">PROJECT WORKSPACES</p>
        <h1>工作区与实践成果</h1>
        <p class="muted">登记允许 StudyPilot 访问的代码目录。后续 Runner 仍会逐次展示执行预览。</p>
      </div>
      <div class="header-actions">
        <button
          type="button"
          data-testid="open-results-panel-trigger"
          class="btn btn-secondary"
          @click="toggleResultsPanel"
        >
          {{ showResultsPanel ? '收起实践成果评测结果' : '查看实践成果评测结果' }}
        </button>
      </div>
    </header>

    <!-- 真实验收面板：展示当前已登录用户的实践成果与评测结果（只读汇总） -->
    <section
      v-if="showResultsPanel"
      data-testid="workspace-results-panel"
      class="panel results-panel"
      aria-label="实践成果评测结果"
    >
      <div class="panel-heading">
        <h2>实践成果与评测结果</h2>
        <span v-if="artifacts.length" class="badge badge-info">{{ artifacts.length }} 项记录</span>
      </div>

      <div v-if="loadingArtifacts" class="loading-state" data-testid="workspace-results-loading">
        <span class="spinner" /> 正在加载实践成果与评测结果…
      </div>

      <!-- 明确的错误状态：API 失败时不伪造成空状态，提供重试按钮 -->
      <div v-else-if="artifactsError" class="error-state" data-testid="workspace-results-error">
        <p class="error-text">加载评测结果失败：{{ artifactsError }}</p>
        <button type="button" class="btn btn-secondary" @click="loadArtifacts">重试加载</button>
      </div>

      <!-- 真实的 200 空列表状态 -->
      <div v-else-if="artifacts.length === 0" class="empty-state" data-testid="workspace-results-empty">
        尚未提交实践成果。在对应路线节点提交代码并通过 Runner 评测后，结果将在此展示。
      </div>

      <!-- 真实的评测结果汇总列表：严格基于隐私最小化的摘要字段展示，不显示正文/代码路径/测试证据 -->
      <div v-else class="artifact-results-list" data-testid="workspace-results-list">
        <article v-for="artifact in artifacts" :key="artifact.id" class="artifact-result-card">
          <div class="artifact-header">
            <strong>{{ artifact.roadmapNode?.title || '实践项目成果' }}</strong>
            <div class="header-tags">
              <span class="version-tag">版本 v{{ artifact.submissionVersion }}</span>
              <span class="badge" :class="statusBadgeClass(artifact.status)">
                {{ artifact.status }}
              </span>
            </div>
          </div>
          <div class="artifact-meta">
            <span v-if="artifact.rubricScore !== null && artifact.rubricScore !== undefined" class="score-tag">
              评测得分：<strong>{{ artifact.rubricScore }} 分</strong>
            </span>
            <span v-if="artifact.rubricFeedback" class="feedback-text">
              评测反馈：{{ artifact.rubricFeedback }}
            </span>
          </div>
        </article>
      </div>
    </section>

    <section class="panel registration-card">
      <h2>登记本地工作区</h2>
      <form class="form-grid" @submit.prevent="register">
        <label>名称<input v-model.trim="form.name" required maxlength="100" placeholder="例如 StudyPilot" /></label>
        <label>绝对路径<input v-model.trim="form.rootPath" required placeholder="/Users/.../project" /></label>
        <button class="btn btn-primary" :disabled="saving">{{ saving ? '登记中…' : '登记工作区' }}</button>
      </form>
    </section>

    <section class="workspace-grid" aria-label="已登记工作区">
      <article v-for="workspace in workspaces" :key="workspace.id" class="panel workspace-card">
        <span class="status-dot" />
        <div><h2>{{ workspace.name }}</h2><code>{{ workspace.rootPath }}</code></div>
        <span class="badge badge-success">{{ workspace.status }}</span>
      </article>
      <div v-if="!loading && workspaces.length === 0" class="panel empty-state">
        尚未登记工作区。只有这里登记过的目录，未来才能交给受控 Runner 检查。
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { roadmapApi } from '@/services/roadmap'
import { describeError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import type { ProjectWorkspace, RoadmapArtifactSummaryItem } from '@/types/roadmap'

const toast = useToastStore()
const workspaces = ref<ProjectWorkspace[]>([])
const artifacts = ref<RoadmapArtifactSummaryItem[]>([])
const loading = ref(true)
const loadingArtifacts = ref(false)
const artifactsError = ref<string | null>(null)
const saving = ref(false)
const showResultsPanel = ref(false)
const form = reactive({ name: '', rootPath: '' })

onMounted(async () => {
  await Promise.all([loadWorkspaces(), loadArtifacts()])
})

async function loadWorkspaces() {
  loading.value = true
  try {
    workspaces.value = await roadmapApi.listWorkspaces()
  } catch (error) {
    toast.error(describeError(error))
  } finally {
    loading.value = false
  }
}

async function loadArtifacts() {
  loadingArtifacts.value = true
  artifactsError.value = null
  try {
    artifacts.value = await roadmapApi.listArtifacts()
  } catch (error: unknown) {
    const errorMsg = (error as Error)?.message || describeError(error)
    artifactsError.value = errorMsg
  } finally {
    loadingArtifacts.value = false
  }
}

function toggleResultsPanel() {
  showResultsPanel.value = !showResultsPanel.value
}

function statusBadgeClass(status: string) {
  switch (status) {
    case 'ACCEPTED':
    case 'EVALUATED':
      return 'badge-success'
    case 'SUBMITTED':
      return 'badge-warning'
    case 'REJECTED':
      return 'badge-danger'
    default:
      return 'badge-secondary'
  }
}

async function register() {
  if (saving.value) return
  saving.value = true
  try {
    await roadmapApi.registerWorkspace(form)
    form.name = ''
    form.rootPath = ''
    await loadWorkspaces()
    toast.success('工作区已登记')
  } catch (error) {
    toast.error(describeError(error))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.workspace-page { max-width: 1080px; margin: 0 auto; padding: 36px 28px 72px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-end; gap: 20px; margin-bottom: 24px; flex-wrap: wrap; }
.page-header h1 { margin: 7px 0; font-size: 36px; letter-spacing: -.03em; }
.eyebrow { margin: 0; color: var(--color-primary); font-size: 12px; font-weight: 800; letter-spacing: .14em; }
.muted { color: var(--color-text-secondary); margin: 0; }
.panel { padding: 22px; background: #fff; border: 1px solid var(--color-border); border-radius: 16px; margin-bottom: 20px; }
.results-panel { border-left: 4px solid var(--color-primary); background: #fafbfc; }
.panel-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.panel-heading h2 { margin: 0; font-size: 18px; }
.artifact-results-list { display: grid; gap: 12px; }
.artifact-result-card { padding: 14px; background: #fff; border: 1px solid var(--color-border); border-radius: 10px; }
.artifact-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.header-tags { display: flex; align-items: center; gap: 8px; }
.version-tag { font-size: 11px; color: var(--color-text-secondary); background: #f0f2f5; padding: 2px 6px; border-radius: 4px; }
.artifact-meta { display: flex; gap: 14px; font-size: 12px; color: var(--color-text-secondary); flex-wrap: wrap; }
.score-tag strong { color: var(--color-primary); }
.registration-card h2, .workspace-card h2 { margin: 0 0 14px; font-size: 18px; }
.form-grid { display: grid; grid-template-columns: 1fr 2fr auto; align-items: end; gap: 14px; }
label { display: grid; gap: 7px; color: var(--color-text-secondary); font-size: 13px; }
input { min-height: 42px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: 9px; color: var(--color-text); }
.workspace-grid { display: grid; gap: 12px; margin-top: 18px; }
.workspace-card { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 14px; }
.workspace-card h2 { margin-bottom: 5px; }
.workspace-card code { color: var(--color-text-secondary); word-break: break-all; }
.status-dot { width: 10px; height: 10px; border-radius: 50%; background: var(--color-success); }
.empty-state { color: var(--color-text-secondary); text-align: center; padding: 16px 0; }
.error-state { color: var(--color-danger, #d32f2f); text-align: center; padding: 16px 0; display: flex; flex-direction: column; align-items: center; gap: 10px; }
.error-text { margin: 0; font-size: 13px; }
.loading-state { color: var(--color-text-secondary); display: flex; align-items: center; gap: 8px; justify-content: center; padding: 16px 0; }
@media (max-width: 760px) {
  .page-header { flex-direction: column; align-items: flex-start; }
  .form-grid { grid-template-columns: 1fr; }
  .workspace-card { grid-template-columns: auto 1fr; }
  .workspace-card .badge { grid-column: 2; justify-self: start; }
}
</style>
