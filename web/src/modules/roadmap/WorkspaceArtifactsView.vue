<template>
  <div class="workspace-page">
    <header class="page-header">
      <p class="eyebrow">PROJECT WORKSPACES</p>
      <h1>工作区与实践成果</h1>
      <p class="muted">登记允许 StudyPilot 访问的代码目录。后续 Runner 仍会逐次展示执行预览。</p>
    </header>

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
import type { ProjectWorkspace } from '@/types/roadmap'

const toast = useToastStore()
const workspaces = ref<ProjectWorkspace[]>([])
const loading = ref(true)
const saving = ref(false)
const form = reactive({ name: '', rootPath: '' })

onMounted(load)

async function load() {
  loading.value = true
  try {
    workspaces.value = await roadmapApi.listWorkspaces()
  } catch (error) {
    toast.error(describeError(error))
  } finally {
    loading.value = false
  }
}

async function register() {
  if (saving.value) return
  saving.value = true
  try {
    await roadmapApi.registerWorkspace(form)
    form.name = ''
    form.rootPath = ''
    await load()
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
.page-header { margin-bottom: 24px; }
.page-header h1 { margin: 7px 0; font-size: 36px; letter-spacing: -.03em; }
.eyebrow { margin: 0; color: var(--color-primary); font-size: 12px; font-weight: 800; letter-spacing: .14em; }
.muted { color: var(--color-text-secondary); }
.panel { padding: 22px; background: #fff; border: 1px solid var(--color-border); border-radius: 16px; }
.registration-card h2, .workspace-card h2 { margin: 0 0 14px; font-size: 18px; }
.form-grid { display: grid; grid-template-columns: 1fr 2fr auto; align-items: end; gap: 14px; }
label { display: grid; gap: 7px; color: var(--color-text-secondary); font-size: 13px; }
input { min-height: 42px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: 9px; color: var(--color-text); }
.workspace-grid { display: grid; gap: 12px; margin-top: 18px; }
.workspace-card { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 14px; }
.workspace-card h2 { margin-bottom: 5px; }
.workspace-card code { color: var(--color-text-secondary); word-break: break-all; }
.status-dot { width: 10px; height: 10px; border-radius: 50%; background: var(--color-success); }
.empty-state { color: var(--color-text-secondary); text-align: center; }
@media (max-width: 760px) { .form-grid { grid-template-columns: 1fr; } .workspace-card { grid-template-columns: auto 1fr; } .workspace-card .badge { grid-column: 2; justify-self: start; } }
</style>
