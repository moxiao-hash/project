<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">知识问答</h1>
        <p class="page-subtitle">基于你的资料库和联网搜索的可溯源回答</p>
      </div>
    </div>

    <MockBanner />

    <div v-if="!conversation" class="card">
      <h2 class="section-title" style="margin-bottom: 10px">选择检索模式</h2>
      <div class="mode-options">
        <label class="mode-option" :class="{ active: mode === 'AUTO' }">
          <input v-model="mode" type="radio" value="AUTO" />
          <div>
            <strong>自动模式</strong>
            <div class="muted" style="font-size: 12px">本地资料 + 按需联网搜索</div>
          </div>
        </label>
        <label class="mode-option" :class="{ active: mode === 'LOCAL_ONLY' }">
          <input v-model="mode" type="radio" value="LOCAL_ONLY" />
          <div>
            <strong>仅本地</strong>
            <div class="muted" style="font-size: 12px">只检索本地资料，内容不离开本机</div>
          </div>
        </label>
      </div>
      <button class="btn btn-primary" style="margin-top: 14px" :disabled="creating" @click="startConversation">
        {{ creating ? '创建会话…' : '开始问答' }}
      </button>
    </div>

    <template v-else>
      <div class="card chat-card">
        <div ref="messagesEl" class="messages">
          <template v-for="(round, i) in rounds" :key="i">
            <div v-if="round.question" class="message user">
              <div class="message-bubble">{{ round.question }}</div>
            </div>
            <div class="message assistant">
              <div class="message-bubble answer-bubble">
                <AiMarkdownMessage :content="round.answer" />
                <div class="muted" style="font-size: 12px; margin-bottom: 8px">
                  检索模式：{{ retrievalModeLabel(round.retrievalMode) }}
                </div>
                <div
                  v-if="round.modelProvider || round.modelName"
                  class="muted"
                  style="font-size: 12px; margin-bottom: 8px"
                >
                  模型：{{ [round.modelProvider, round.modelName].filter(Boolean).join(' / ') }}
                </div>

                <div v-for="(w, wi) in round.warnings" :key="wi" class="alert alert-warning">
                  {{ w }}
                </div>

                <template v-if="round.citations.length > 0">
                  <div class="citations-title">来源（{{ round.citations.length }}）</div>
                  <CitationCard
                    v-for="(c, ci) in round.citations"
                    :key="ci"
                    :citation="c"
                    :importable="true"
                    :importing="importingId === c.resultId"
                    :imported="c.resultId !== null && importedIds.has(c.resultId)"
                    @import="onImport"
                  />
                </template>
                <div v-else class="alert alert-info" style="margin-top: 8px">
                  未检索到可验证来源，以上回答仅供参考。
                </div>
              </div>
            </div>
          </template>

          <div v-if="sending" class="message assistant">
            <div class="message-bubble"><span class="spinner" /> 正在检索与生成…</div>
          </div>
        </div>

        <form class="chat-input" @submit.prevent="onSend">
          <select v-model="webSearch" class="select websearch-select" :disabled="conversation.mode === 'LOCAL_ONLY'">
            <option value="AUTO">联网：自动</option>
            <option value="ENABLED">联网：开启</option>
            <option value="DISABLED">联网：关闭</option>
          </select>
          <input
            v-model.trim="question"
            class="input"
            placeholder="输入你的问题…"
          />
          <button class="btn btn-primary" type="submit" :disabled="sending || !question">
            提问
          </button>
        </form>
      </div>
    </template>

    <!-- 导入确认 -->
    <ConfirmDialog
      v-model="importDialog"
      title="导入网页来源为资料"
      confirm-text="确认导入该网页"
      :loading="importingId !== null"
      @confirm="confirmImport"
    >
      <div class="form-field">
        <label class="form-label">分类</label>
        <select v-model="importCategory" class="select">
          <option v-for="(label, key) in materialCategoryLabels" :key="key" :value="key">
            {{ label }}
          </option>
        </select>
      </div>
      <div class="form-field">
        <label class="form-label">隐私级别</label>
        <select v-model="importPrivacy" class="select">
          <option v-for="(label, key) in privacyLevelLabels" :key="key" :value="key">
            {{ label }}
          </option>
        </select>
      </div>
    </ConfirmDialog>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { agentGateway } from '@/services/planned'
import { describeError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import { materialCategoryLabels, privacyLevelLabels } from '@/utils/labels'
import type {
  Citation,
  KnowledgeConversation,
  KnowledgeMode,
  WebSearchPreference,
} from '@/types/agent'
import type { MaterialCategory, PrivacyLevel } from '@/types/api'
import CitationCard from '@/components/CitationCard.vue'
import AiMarkdownMessage from '@/components/AiMarkdownMessage.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import MockBanner from '@/components/MockBanner.vue'

interface QaRound {
  question: string
  answer: string
  retrievalMode: string
  citations: Citation[]
  warnings: string[]
  modelProvider?: string | null
  modelName?: string | null
}

const toast = useToastStore()
const route = useRoute()
const router = useRouter()
const mode = ref<KnowledgeMode>('AUTO')
const creating = ref(false)
const conversation = ref<KnowledgeConversation | null>(null)
const rounds = ref<QaRound[]>([])
const question = ref('')
const webSearch = ref<WebSearchPreference>('AUTO')
const sending = ref(false)
const messagesEl = ref<HTMLElement | null>(null)
let mounted = true

const importDialog = ref(false)
const importingId = ref<string | null>(null)
const importedIds = reactive(new Set<string>())
const pendingImportId = ref<string | null>(null)
const importCategory = ref<MaterialCategory>('REFERENCE')
const importPrivacy = ref<PrivacyLevel>('NORMAL')

function retrievalModeLabel(modeValue: string): string {
  const map: Record<string, string> = {
    LOCAL: '仅本地资料',
    MATERIAL: '仅本地资料',
    LOCAL_ONLY: '仅本地隐私资料',
    WEB: '联网搜索',
    HYBRID: '本地 + 联网',
    NONE: '模型常识（无检索证据）',
  }
  return map[modeValue] ?? modeValue
}

async function startConversation() {
  if (creating.value) return
  const startLocation = route.fullPath
  creating.value = true
  try {
    conversation.value = await agentGateway.createKnowledgeConversation(mode.value)
    if (mounted && route.fullPath === startLocation) {
      await router.replace({
        query: { ...route.query, conversationId: conversation.value.conversationId },
      })
    }
    if (conversation.value.warnings.length > 0) {
      conversation.value.warnings.forEach((w) => toast.warning(w))
    }
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    creating.value = false
  }
}

function snapshotRound(
  snapshot: KnowledgeConversation,
  originalQuestion = '',
): QaRound | null {
  if (!snapshot.answer) return null
  return {
    question: originalQuestion,
    answer: snapshot.answer,
    retrievalMode: snapshot.retrievalMode,
    citations: snapshot.citations,
    warnings: snapshot.warnings,
    modelProvider: snapshot.modelProvider,
    modelName: snapshot.modelName,
  }
}

async function restoreConversation() {
  const id = route.query.conversationId
  if (typeof id !== 'string' || !id) return
  creating.value = true
  try {
    conversation.value = await agentGateway.getKnowledgeConversation(id)
    mode.value = conversation.value.mode
    const restored = snapshotRound(conversation.value)
    if (restored) rounds.value = [restored]
  } catch (e) {
    toast.error(`无法恢复知识会话：${describeError(e)}`)
    await router.replace({ query: { ...route.query, conversationId: undefined } })
  } finally {
    creating.value = false
  }
}

async function onSend() {
  if (!conversation.value || !question.value || sending.value) return
  const q = question.value
  question.value = ''
  sending.value = true
  await scrollToBottom()
  try {
    const updated = await agentGateway.sendKnowledgeMessage(
      conversation.value.conversationId,
      q,
      conversation.value.mode === 'LOCAL_ONLY' ? 'DISABLED' : webSearch.value,
    )
    conversation.value = updated
    const round = snapshotRound(updated, q)
    if (round) rounds.value.push(round)
    await scrollToBottom()
  } catch (e) {
    if (!question.value) question.value = q
    toast.error(describeError(e))
  } finally {
    sending.value = false
  }
}

function onImport(resultId: string) {
  pendingImportId.value = resultId
  importDialog.value = true
}

async function confirmImport() {
  if (!pendingImportId.value || importingId.value) return
  importingId.value = pendingImportId.value
  try {
    await agentGateway.importWebResult(
      pendingImportId.value,
      importCategory.value,
      importPrivacy.value,
    )
    importedIds.add(pendingImportId.value)
    importDialog.value = false
    toast.success('网页已导入为资料')
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    importingId.value = null
  }
}

async function scrollToBottom() {
  await nextTick()
  if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight
}

onMounted(() => {
  mounted = true
  void restoreConversation()
})

onBeforeUnmount(() => {
  mounted = false
})
</script>

<style scoped>
.section-title {
  font-size: 16px;
}

.mode-options {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.mode-option {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 12px 16px;
  cursor: pointer;
  min-width: 220px;
}

.mode-option.active {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}

.chat-card {
  display: flex;
  flex-direction: column;
  height: 600px;
}

.messages {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-bottom: 10px;
}

.message {
  display: flex;
}

.message.user {
  justify-content: flex-end;
}

.message-bubble {
  max-width: 85%;
  border-radius: 12px;
  padding: 10px 14px;
  font-size: 14px;
  background: var(--color-bg);
}

.message.user .message-bubble {
  background: var(--color-primary);
  color: #fff;
}

.answer-bubble {
  width: 85%;
}

.citations-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--color-text-secondary);
  margin: 10px 0 6px;
}

.chat-input {
  display: flex;
  gap: 8px;
  border-top: 1px solid var(--color-border);
  padding-top: 12px;
}

.websearch-select {
  width: 130px;
  flex-shrink: 0;
}
</style>
