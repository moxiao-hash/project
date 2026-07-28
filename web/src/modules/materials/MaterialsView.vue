<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">学习资料</h1>
        <p class="page-subtitle">支持文本、网页和文件（TXT / Markdown / PDF / DOCX，≤ 20 MB）</p>
      </div>
      <button class="btn btn-primary" @click="importOpen = !importOpen">
        {{ importOpen ? '收起导入' : '导入资料' }}
      </button>
    </div>

    <!-- 导入面板 -->
    <div v-if="importOpen" class="card">
      <div class="import-tabs">
        <button
          v-for="tab in importTabs"
          :key="tab.key"
          class="import-tab"
          :class="{ active: importTab === tab.key }"
          @click="importTab = tab.key"
        >
          {{ tab.label }}
        </button>
      </div>

      <div class="form-field" style="margin-top: 14px">
        <label class="form-label">标题</label>
        <input v-model.trim="importForm.title" class="input" maxlength="180" placeholder="资料标题" />
      </div>

      <div v-if="importTab === 'text'" class="form-field">
        <label class="form-label">正文（最多 200,000 字符）</label>
        <textarea
          v-model="importForm.content"
          class="textarea"
          rows="8"
          placeholder="粘贴文本内容…"
        />
      </div>

      <div v-if="importTab === 'web'" class="form-field">
        <label class="form-label">网页地址</label>
        <input v-model.trim="importForm.url" class="input" type="url" placeholder="https://…" />
      </div>

      <div v-if="importTab === 'file'" class="form-field">
        <label class="form-label">文件</label>
        <input class="input" type="file" accept=".txt,.md,.markdown,.pdf,.docx" @change="onFileChange" />
        <span class="form-hint">扫描版 PDF 暂不支持 OCR。</span>
      </div>

      <div class="grid cols-2">
        <div class="form-field">
          <label class="form-label">分类</label>
          <select v-model="importForm.category" class="select">
            <option v-for="(label, key) in materialCategoryLabels" :key="key" :value="key">
              {{ label }}
            </option>
          </select>
        </div>
        <div class="form-field">
          <label class="form-label">隐私级别</label>
          <select v-model="importForm.privacyLevel" class="select">
            <option v-for="(label, key) in privacyLevelLabels" :key="key" :value="key">
              {{ label }} — {{ privacyLevelHints[key] }}
            </option>
          </select>
        </div>
      </div>

      <div v-if="importForm.privacyLevel !== 'NORMAL'" class="alert alert-info">
        「{{ privacyLevelLabels[importForm.privacyLevel] }}」资料的正文不会发送到 DeepSeek 或
        Tavily，仅提供本地解析、索引和原文片段能力。
      </div>

      <div v-if="importError" class="alert alert-danger">{{ importError }}</div>
      <div class="row" style="justify-content: flex-end">
        <button class="btn btn-primary" :disabled="importing" @click="onImport">
          {{ importing ? '导入中…' : '开始导入' }}
        </button>
      </div>
    </div>

    <!-- 列表 -->
    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <div v-else-if="materials.length === 0" class="card">
      <EmptyState icon="📚" title="还没有资料" description="导入资料后可用于知识问答和测验生成。" />
    </div>
    <div v-else class="material-list">
      <RouterLink
        v-for="m in materials"
        :key="m.id"
        :to="`/materials/${m.id}`"
        class="card material-row"
      >
        <div class="material-icon">{{ materialIcon(m.materialType) }}</div>
        <div class="material-info">
          <div class="row">
            <span class="material-title">{{ m.title }}</span>
            <StatusBadge
              :label="materialStatusLabels[m.processingStatus]"
              :badge-class="materialStatusBadge[m.processingStatus]"
            />
          </div>
          <div class="muted" style="font-size: 12px; margin-top: 4px">
            {{ materialTypeLabels[m.materialType] }} · {{ materialCategoryLabels[m.category] }} ·
            {{ privacyLevelLabels[m.privacyLevel] }}
            <template v-if="m.knowledgePoints.length > 0">
              · 知识点：{{ m.knowledgePoints.slice(0, 3).join('、') }}
            </template>
          </div>
        </div>
      </RouterLink>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { materialsApi } from '@/services/current/materials'
import { describeError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import {
  materialCategoryLabels,
  materialStatusBadge,
  materialStatusLabels,
  materialTypeLabels,
  privacyLevelHints,
  privacyLevelLabels,
} from '@/utils/labels'
import type { Material, MaterialCategory, MaterialType, PrivacyLevel } from '@/types/api'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const router = useRouter()
const toast = useToastStore()

const materials = ref<Material[]>([])
const loading = ref(true)
const error = ref('')

const importOpen = ref(false)
const importTab = ref<'text' | 'web' | 'file'>('text')
const importTabs = [
  { key: 'text' as const, label: '📝 粘贴文本' },
  { key: 'web' as const, label: '🔗 网页' },
  { key: 'file' as const, label: '📎 文件' },
]
const importing = ref(false)
const importError = ref('')
const importForm = reactive({
  title: '',
  content: '',
  url: '',
  category: 'LEARNING_MATERIAL' as MaterialCategory,
  privacyLevel: 'NORMAL' as PrivacyLevel,
  file: null as File | null,
})

function materialIcon(type: MaterialType): string {
  const map: Record<MaterialType, string> = {
    MARKDOWN: '📝',
    TEXT: '📄',
    PDF: '📕',
    WORD: '📘',
    WEB_PAGE: '🔗',
    IMAGE: '🖼️',
    PASTED_ARTICLE: '📋',
  }
  return map[type]
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  importForm.file = input.files?.[0] ?? null
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    materials.value = await materialsApi.list()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function onImport() {
  importError.value = ''
  if (!importForm.title) {
    importError.value = '请输入资料标题'
    return
  }
  if (importTab.value === 'text') {
    if (!importForm.content) {
      importError.value = '请输入正文内容'
      return
    }
    if (importForm.content.length > 200_000) {
      importError.value = '正文不能超过 200,000 字符'
      return
    }
  }
  if (importTab.value === 'web' && !/^https?:\/\/.+/.test(importForm.url)) {
    importError.value = '请输入合法的 http(s) 地址'
    return
  }
  if (importTab.value === 'file') {
    if (!importForm.file) {
      importError.value = '请选择文件'
      return
    }
    if (importForm.file.size > 20 * 1024 * 1024) {
      importError.value = '单文件不能超过 20 MB'
      return
    }
  }
  if (importing.value) return
  importing.value = true
  try {
    let created: Material
    if (importTab.value === 'text') {
      created = await materialsApi.createText({
        title: importForm.title,
        content: importForm.content,
        category: importForm.category,
        privacyLevel: importForm.privacyLevel,
      })
    } else if (importTab.value === 'web') {
      created = await materialsApi.createWeb({
        title: importForm.title,
        url: importForm.url,
        category: importForm.category,
        privacyLevel: importForm.privacyLevel,
      })
    } else {
      created = await materialsApi.uploadFile(
        {
          title: importForm.title,
          category: importForm.category,
          privacyLevel: importForm.privacyLevel,
        },
        importForm.file as File,
      )
    }
    toast.success('资料已导入，正在解析')
    void router.push(`/materials/${created.id}`)
  } catch (e) {
    importError.value = describeError(e)
  } finally {
    importing.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.import-tabs {
  display: flex;
  gap: 8px;
}

.import-tab {
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  border-radius: 999px;
  padding: 6px 16px;
  font-size: 13px;
  cursor: pointer;
}

.import-tab.active {
  background: var(--color-primary-soft);
  border-color: var(--color-primary);
  color: var(--color-primary);
  font-weight: 600;
}

.material-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.material-row {
  display: flex;
  align-items: center;
  gap: 14px;
  color: var(--color-text);
  padding: 14px 18px;
  transition: border-color 0.15s;
}

.material-row:hover {
  border-color: var(--color-primary);
}

.material-icon {
  font-size: 24px;
}

.material-title {
  font-weight: 600;
}
</style>
