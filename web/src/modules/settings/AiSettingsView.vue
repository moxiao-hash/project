<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">AI 设置</h1>
        <p class="page-subtitle">模型与搜索服务的 Key 管理；服务端只返回配置状态和脱敏尾号</p>
      </div>
      <RouterLink to="/settings" class="btn btn-secondary">学习设置</RouterLink>
    </div>

    <MockBanner />

    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />

    <template v-else-if="settings">
      <div class="card">
        <h2 class="section-title">模型</h2>
        <dl class="meta-grid">
          <dt>提供商</dt>
          <dd>{{ settings.modelProvider }}</dd>
          <dt>模型</dt>
          <dd class="mono">{{ settings.modelName }}</dd>
        </dl>
      </div>

      <!-- DeepSeek Key -->
      <div class="card">
        <div class="row">
          <h2 class="section-title">DeepSeek API Key</h2>
          <span class="spacer" />
          <StatusBadge
            :label="settings.deepseekConfigured ? '已配置' : '未配置'"
            :badge-class="settings.deepseekConfigured ? 'badge-success' : 'badge-warning'"
          />
        </div>
        <p v-if="settings.deepseekConfigured" class="muted" style="font-size: 13px">
          当前 Key 尾号：••••{{ settings.deepseekMaskedSuffix }}
        </p>
        <p v-else class="muted" style="font-size: 13px">
          未配置时由服务器级 .env 提供 Key；配置后以你的 Key 为准。
        </p>
        <form class="key-form" @submit.prevent="onUpdate('deepseek')">
          <input
            v-model.trim="deepseekKey"
            class="input"
            type="password"
            autocomplete="off"
            placeholder="输入新的 DeepSeek Key（提交后立即清空）"
          />
          <button class="btn btn-primary" type="submit" :disabled="!deepseekKey || savingKey === 'deepseek'">
            {{ savingKey === 'deepseek' ? '保存中…' : '保存 Key' }}
          </button>
          <button
            v-if="settings.deepseekConfigured"
            class="btn btn-danger"
            type="button"
            :disabled="savingKey === 'deepseek'"
            @click="onDelete('deepseek')"
          >
            删除
          </button>
        </form>
      </div>

      <!-- Tavily Key -->
      <div class="card">
        <div class="row">
          <h2 class="section-title">Tavily 搜索 Key</h2>
          <span class="spacer" />
          <StatusBadge
            :label="settings.tavilyConfigured ? '已配置' : '未配置'"
            :badge-class="settings.tavilyConfigured ? 'badge-success' : 'badge-warning'"
          />
        </div>
        <p v-if="settings.tavilyConfigured" class="muted" style="font-size: 13px">
          当前 Key 尾号：••••{{ settings.tavilyMaskedSuffix }}
        </p>
        <p v-else class="muted" style="font-size: 13px">
          未配置时知识问答的联网搜索不可用，会降级为仅本地检索。
        </p>
        <form class="key-form" @submit.prevent="onUpdate('tavily')">
          <input
            v-model.trim="tavilyKey"
            class="input"
            type="password"
            autocomplete="off"
            placeholder="输入新的 Tavily Key（提交后立即清空）"
          />
          <button class="btn btn-primary" type="submit" :disabled="!tavilyKey || savingKey === 'tavily'">
            {{ savingKey === 'tavily' ? '保存中…' : '保存 Key' }}
          </button>
          <button
            v-if="settings.tavilyConfigured"
            class="btn btn-danger"
            type="button"
            :disabled="savingKey === 'tavily'"
            @click="onDelete('tavily')"
          >
            删除
          </button>
        </form>
      </div>

      <div class="alert alert-info">
        安全说明：Key 仅保存在服务端，前端只保留输入框的临时值，提交成功立即清空；
        不会写入 Pinia 持久化、LocalStorage 或日志。
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { agentGateway } from '@/services/planned'
import { describeError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import type { AiSettings } from '@/types/agent'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import MockBanner from '@/components/MockBanner.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const toast = useToastStore()
const settings = ref<AiSettings | null>(null)
const loading = ref(true)
const error = ref('')

// Key 只保留在组件临时内存中，绝不进入 Pinia/持久化存储
const deepseekKey = ref('')
const tavilyKey = ref('')
const savingKey = ref<'deepseek' | 'tavily' | null>(null)

async function onUpdate(kind: 'deepseek' | 'tavily') {
  const key = kind === 'deepseek' ? deepseekKey.value : tavilyKey.value
  if (!key || savingKey.value) return
  savingKey.value = kind
  try {
    settings.value =
      kind === 'deepseek'
        ? await agentGateway.updateDeepseekKey(key)
        : await agentGateway.updateTavilyKey(key)
    // 提交成功后立即清空输入和组件状态
    if (kind === 'deepseek') deepseekKey.value = ''
    else tavilyKey.value = ''
    toast.success('Key 已保存')
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    savingKey.value = null
  }
}

async function onDelete(kind: 'deepseek' | 'tavily') {
  if (savingKey.value) return
  savingKey.value = kind
  try {
    settings.value =
      kind === 'deepseek'
        ? await agentGateway.deleteDeepseekKey()
        : await agentGateway.deleteTavilyKey()
    toast.success('Key 已删除')
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    savingKey.value = null
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    settings.value = await agentGateway.getAiSettings()
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
}

.meta-grid {
  display: grid;
  grid-template-columns: 80px 1fr;
  gap: 8px 16px;
  margin: 12px 0 0;
  font-size: 13px;
}

.meta-grid dt {
  color: var(--color-text-secondary);
}

.meta-grid dd {
  margin: 0;
}

.key-form {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  flex-wrap: wrap;
}
</style>
