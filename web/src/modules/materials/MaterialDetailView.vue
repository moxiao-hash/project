<template>
  <div class="page">
    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />

    <template v-else-if="material">
      <div class="page-header">
        <div>
          <div class="row">
            <h1 class="page-title">{{ material.title }}</h1>
            <StatusBadge
              :label="materialStatusLabels[material.processingStatus]"
              :badge-class="materialStatusBadge[material.processingStatus]"
            />
          </div>
          <p class="page-subtitle">
            {{ materialTypeLabels[material.materialType] }} ·
            {{ materialCategoryLabels[material.category] }} ·
            隐私级别：{{ privacyLevelLabels[material.privacyLevel] }}
          </p>
        </div>
        <RouterLink to="/materials" class="btn btn-secondary">返回列表</RouterLink>
      </div>

      <!-- 解析状态 -->
      <div v-if="isProcessing" class="card processing-card">
        <div class="row">
          <span class="spinner" />
          <div>
            <strong>正在解析资料…</strong>
            <div class="muted" style="font-size: 13px">
              状态每 3 秒自动刷新；切到后台时会降低频率。
              <span v-if="polling.consecutiveErrors.value > 0">
                （连接异常，正在退避重试）
              </span>
            </div>
          </div>
        </div>
      </div>

      <div v-if="material.processingStatus === 'FAILED'" class="alert alert-danger">
        解析失败：{{ material.failureReason ?? '未知原因' }}。请检查文件内容后重新导入。
      </div>

      <div v-if="pollingFailed" class="alert alert-warning">
        状态刷新多次失败，已停止自动轮询。
        <button class="btn btn-secondary btn-sm" style="margin-left: 8px" @click="resumePolling">
          恢复轮询
        </button>
      </div>

      <!-- 元信息 -->
      <div class="card">
        <h2 class="section-title">基本信息</h2>
        <dl class="meta-grid">
          <template v-if="material.sourceUrl">
            <dt>来源链接</dt>
            <dd>
              <a :href="material.sourceUrl" target="_blank" rel="noopener noreferrer">
                {{ material.sourceUrl }} ↗
              </a>
            </dd>
          </template>
          <template v-if="material.originalFilename">
            <dt>原始文件</dt>
            <dd>{{ material.originalFilename }}</dd>
          </template>
          <template v-if="material.mediaType">
            <dt>媒体类型</dt>
            <dd class="mono">{{ material.mediaType }}</dd>
          </template>
          <template v-if="material.contentLength !== null">
            <dt>正文长度</dt>
            <dd>{{ material.contentLength.toLocaleString() }} 字符</dd>
          </template>
        </dl>
      </div>

      <!-- 解析结果 -->
      <div v-if="material.processingStatus === 'READY'" class="card">
        <h2 class="section-title">解析结果</h2>
        <template v-if="material.summary">
          <h3 class="sub-title">摘要</h3>
          <p class="summary">{{ material.summary }}</p>
        </template>
        <template v-if="material.tags.length > 0">
          <h3 class="sub-title">标签</h3>
          <div class="row">
            <span v-for="tag in material.tags" :key="tag" class="tag">{{ tag }}</span>
          </div>
        </template>
        <template v-if="material.knowledgePoints.length > 0">
          <h3 class="sub-title">知识点</h3>
          <div class="row">
            <span v-for="kp in material.knowledgePoints" :key="kp" class="badge badge-primary">
              {{ kp }}
            </span>
          </div>
        </template>
        <div v-if="material.processingWarnings.length > 0" style="margin-top: 12px">
          <div v-for="(w, i) in material.processingWarnings" :key="i" class="alert alert-warning">
            {{ w }}
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { materialsApi } from '@/services/current/materials'
import { describeError } from '@/services/http'
import { usePolling } from '@/composables/usePolling'
import {
  materialCategoryLabels,
  materialStatusBadge,
  materialStatusLabels,
  materialTypeLabels,
  privacyLevelLabels,
} from '@/utils/labels'
import type { Material } from '@/types/api'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const route = useRoute()
const materialId = route.params.id as string

const material = ref<Material | null>(null)
const loading = ref(true)
const error = ref('')
const pollingFailed = ref(false)

const isProcessing = computed(
  () =>
    material.value !== null &&
    (material.value.processingStatus === 'PENDING' ||
      material.value.processingStatus === 'PROCESSING'),
)

const polling = usePolling<Material>({
  fetcher: () => materialsApi.get(materialId),
  shouldContinue: (m) => m.processingStatus === 'PENDING' || m.processingStatus === 'PROCESSING',
  interval: 3000,
  maxConsecutiveErrors: 5,
  onData: (m) => {
    material.value = m
  },
  onFailed: () => {
    pollingFailed.value = true
  },
})

function resumePolling() {
  pollingFailed.value = false
  polling.start()
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    material.value = await materialsApi.get(materialId)
    if (isProcessing.value) polling.start()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.section-title {
  font-size: 16px;
  margin-bottom: 12px;
}

.sub-title {
  font-size: 14px;
  margin: 14px 0 8px;
}

.processing-card {
  background: var(--color-info-soft);
  border-color: #b6ddef;
}

.meta-grid {
  display: grid;
  grid-template-columns: 100px 1fr;
  gap: 8px 16px;
  margin: 0;
  font-size: 13px;
}

.meta-grid dt {
  color: var(--color-text-secondary);
}

.meta-grid dd {
  margin: 0;
  word-break: break-all;
}

.summary {
  font-size: 14px;
  white-space: pre-wrap;
}
</style>
