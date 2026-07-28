<template>
  <div class="citation-card">
    <div class="citation-head">
      <span class="badge" :class="citationSourceBadge(citation.sourceType)">
        {{ citationSourceLabel(citation.sourceType) }}
      </span>
      <span class="citation-title">{{ citation.title }}</span>
      <span v-if="citation.locator" class="citation-locator">{{ citation.locator }}</span>
    </div>
    <div v-if="citation.snippet" class="citation-snippet">
      <button class="snippet-toggle" @click="expanded = !expanded">
        {{ expanded ? '收起片段 ▲' : '展开片段 ▼' }}
      </button>
      <blockquote v-if="expanded" class="snippet-text">{{ citation.snippet }}</blockquote>
    </div>
    <div class="citation-actions">
      <a
        v-if="citation.url"
        :href="citation.url"
        target="_blank"
        rel="noopener noreferrer"
        class="citation-link"
      >
        查看原文 ↗
      </a>
      <button
        v-if="citation.resultId && importable"
        class="btn btn-secondary btn-sm"
        :disabled="importing || imported"
        @click="$emit('import', citation.resultId)"
      >
        {{ imported ? '已导入' : importing ? '导入中…' : '确认导入资料' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { Citation } from '@/types/agent'
import { citationSourceBadge, citationSourceLabel } from '@/utils/labels'

withDefaults(
  defineProps<{
    citation: Citation
    /** 是否展示“确认导入资料”按钮（网页来源且有 resultId 时） */
    importable?: boolean
    importing?: boolean
    imported?: boolean
  }>(),
  { importable: false, importing: false, imported: false },
)

defineEmits<{ import: [resultId: string] }>()

const expanded = ref(false)
</script>

<style scoped>
.citation-card {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  background: #fbfbfe;
  font-size: 13px;
}

.citation-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.citation-title {
  font-weight: 600;
}

.citation-locator {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.snippet-toggle {
  background: none;
  border: none;
  color: var(--color-primary);
  cursor: pointer;
  font-size: 12px;
  padding: 4px 0;
}

.snippet-text {
  margin: 4px 0 0;
  padding: 8px 10px;
  border-left: 3px solid var(--color-border);
  color: var(--color-text-secondary);
  font-size: 12px;
  white-space: pre-wrap;
}

.citation-actions {
  margin-top: 6px;
  display: flex;
  gap: 10px;
  align-items: center;
}

.citation-link {
  font-size: 12px;
}
</style>
