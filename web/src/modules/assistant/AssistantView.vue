<template>
  <div class="assistant-page">
    <section class="assistant-hero">
      <div>
        <span class="eyebrow">STUDYPILOT AGENT</span>
        <h1>今天想从哪里继续？</h1>
        <p>一句话打开章节、开始测验、整理错题，或查询你的学习状态。</p>
      </div>
      <div v-if="conversation" class="model-pill">
        <span class="model-dot" />{{ conversation.modelName }}
      </div>
    </section>

    <section v-if="!conversation && loading" class="assistant-card loading-state">
      <span class="spinner" /> 正在准备你的 Agent 工作区…
    </section>

    <template v-else-if="conversation">
      <section v-if="conversation.messages.length === 0" class="prompt-grid">
        <button v-for="prompt in prompts" :key="prompt.title" class="prompt-card" @click="usePrompt(prompt.text)">
          <span class="prompt-icon">{{ prompt.icon }}</span>
          <strong>{{ prompt.title }}</strong>
          <span>{{ prompt.text }}</span>
        </button>
      </section>

      <section class="assistant-card conversation-card">
        <div class="conversation-stream" aria-live="polite">
          <div v-if="conversation.messages.length === 0" class="welcome-message">
            <div class="agent-mark">✦</div>
            <div>
              <strong>我已读取你当前的 StudyPilot 状态</strong>
              <p>导航和查询会直接完成；涉及数据修改时，我会先展示操作预览。</p>
            </div>
          </div>
          <div
            v-for="(message, index) in conversation.messages"
            :key="index"
            class="assistant-message"
            :class="message.role"
          >
            <div class="message-label">{{ message.role === 'user' ? '你' : 'StudyPilot' }}</div>
            <div class="message-content">
              <AiMarkdownMessage v-if="message.role === 'assistant'" :content="message.content" />
              <span v-else>{{ message.content }}</span>
            </div>
          </div>

          <div v-if="sending" class="working-row">
            <span class="spinner" /> 正在理解目标并调用应用工具…
          </div>
        </div>

        <div v-if="conversation.toolSteps.length" class="process-panel">
          <div class="process-title">本轮执行过程</div>
          <div v-for="step in conversation.toolSteps" :key="step.toolName" class="process-step">
            <span class="step-check">✓</span>
            <div><strong>{{ step.summary }}</strong><small>{{ step.toolName }}</small></div>
            <span class="badge badge-success">{{ step.status }}</span>
          </div>
        </div>

        <div v-if="conversation.pendingAction" class="action-preview">
          <div class="action-heading">
            <span>需要你的确认</span>
            <span class="badge" :class="conversation.pendingAction.riskLevel === 'HIGH' ? 'badge-danger' : 'badge-warning'">
              {{ conversation.pendingAction.riskLevel }} 风险
            </span>
          </div>
          <p>{{ conversation.pendingAction.summary }}</p>
          <div class="row">
            <button data-testid="confirm-action" class="btn btn-primary" :disabled="actionBusy" @click="confirmAction">确认执行</button>
            <button class="btn btn-secondary" :disabled="actionBusy" @click="rejectAction">取消操作</button>
          </div>
        </div>

        <div v-for="warning in conversation.warnings" :key="warning" class="alert alert-warning">
          {{ warning }}
        </div>

        <form class="composer" @submit.prevent="send">
          <textarea
            v-model.trim="message"
            class="composer-input"
            rows="2"
            placeholder="例如：继续昨天没学完的章节"
            :disabled="sending"
            @keydown.enter.exact.prevent="send"
          />
          <button class="send-button" type="submit" :disabled="sending || !message" aria-label="发送消息">↑</button>
        </form>
        <p class="integrity-note">Agent 不会替你答题、伪造打卡或跳过高风险确认。</p>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AiMarkdownMessage from '@/components/AiMarkdownMessage.vue'
import { assistantApi } from '@/services/current/assistant'
import { describeError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import type { AssistantConversation } from '@/types/assistant'
import { dispatchUiAction } from './uiActionDispatcher'

const STORAGE_KEY = 'studypilot.assistantConversationId'
const router = useRouter()
const route = useRoute()
const toast = useToastStore()
const conversation = ref<AssistantConversation | null>(null)
const message = ref('')
const loading = ref(true)
const sending = ref(false)
const actionBusy = ref(false)

const prompts = [
  { icon: '↗', title: '继续学习', text: '继续昨天没学完的章节' },
  { icon: '✓', title: '开始测验', text: '开始当前节点的测验' },
  { icon: '↺', title: '复习错题', text: '打开错题集并重做五题' },
  { icon: '⌕', title: '查找资料', text: '帮我查找 Redis 入门学习资料' },
]

onMounted(async () => {
  try {
    const saved = sessionStorage.getItem(STORAGE_KEY)
    conversation.value = saved
      ? await assistantApi.getConversation(saved).catch(() => assistantApi.createConversation())
      : await assistantApi.createConversation()
    sessionStorage.setItem(STORAGE_KEY, conversation.value.conversationId)
  } catch (error) {
    toast.error(describeError(error))
  } finally {
    loading.value = false
  }
})

function usePrompt(value: string) {
  message.value = value
  void send()
}

async function send() {
  if (!conversation.value || !message.value || sending.value) return
  const outgoing = message.value
  message.value = ''
  sending.value = true
  try {
    conversation.value = await assistantApi.sendMessage(conversation.value.conversationId, {
      message: outgoing,
      idempotencyKey: `assistant-turn:${crypto.randomUUID()}`,
      clientContext: {
        routeName: String(route.name ?? 'assistant'),
        routeParams: Object.fromEntries(
          Object.entries(route.params).map(([key, value]) => [key, String(value)]),
        ),
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai',
      },
    })
    await executeUiActions()
  } catch (error) {
    toast.error(describeError(error))
  } finally {
    sending.value = false
  }
}

async function executeUiActions() {
  if (!conversation.value) return
  for (const action of conversation.value.uiActions) {
    try {
      await dispatchUiAction(action, router)
    } catch {
      toast.warning('自动打开页面失败，你仍可通过左侧菜单继续操作')
    }
  }
}

async function confirmAction() {
  const action = conversation.value?.pendingAction
  if (!conversation.value || !action || actionBusy.value) return
  actionBusy.value = true
  try {
    conversation.value = await assistantApi.confirmAction(conversation.value.conversationId, action.actionId)
    await executeUiActions()
  } catch (error) {
    toast.error(describeError(error))
  } finally {
    actionBusy.value = false
  }
}

async function rejectAction() {
  const action = conversation.value?.pendingAction
  if (!conversation.value || !action || actionBusy.value) return
  actionBusy.value = true
  try {
    conversation.value = await assistantApi.rejectAction(conversation.value.conversationId, action.actionId)
  } catch (error) {
    toast.error(describeError(error))
  } finally {
    actionBusy.value = false
  }
}
</script>

<style scoped>
.assistant-page { max-width: 1120px; margin: 0 auto; padding: 42px 28px 72px; }
.assistant-hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; margin-bottom: 26px; }
.eyebrow { color: var(--color-primary); font-size: 12px; font-weight: 800; letter-spacing: .16em; }
.assistant-hero h1 { margin-top: 8px; font-size: clamp(30px, 4vw, 48px); letter-spacing: -.04em; }
.assistant-hero p { margin: 8px 0 0; color: var(--color-text-secondary); font-size: 15px; }
.model-pill { display: flex; align-items: center; gap: 8px; padding: 8px 12px; background: #fff; border: 1px solid var(--color-border); border-radius: 999px; color: var(--color-text-secondary); font-size: 12px; }
.model-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--color-success); box-shadow: 0 0 0 4px var(--color-success-soft); }
.assistant-card { background: #fff; border: 1px solid var(--color-border); border-radius: 18px; box-shadow: 0 18px 50px rgba(31, 36, 48, .08); }
.loading-state { padding: 48px; display: flex; justify-content: center; gap: 10px; color: var(--color-text-secondary); }
.prompt-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
.prompt-card { text-align: left; min-height: 116px; padding: 16px; border: 1px solid var(--color-border); background: rgba(255,255,255,.78); border-radius: 14px; cursor: pointer; color: var(--color-text); transition: transform .16s, border-color .16s; }
.prompt-card:hover { transform: translateY(-2px); border-color: #aaa5ff; }
.prompt-card strong, .prompt-card span { display: block; }
.prompt-card > span:last-child { margin-top: 5px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.45; }
.prompt-icon { color: var(--color-primary); font-size: 19px; margin-bottom: 10px; }
.conversation-card { overflow: hidden; }
.conversation-stream { min-height: 330px; max-height: 56vh; overflow-y: auto; padding: 28px; }
.welcome-message { display: flex; gap: 14px; align-items: flex-start; padding: 18px; background: linear-gradient(135deg, #f5f4ff, #fafaff); border-radius: 14px; }
.welcome-message p { margin: 4px 0 0; color: var(--color-text-secondary); }
.agent-mark { display: grid; place-items: center; width: 34px; height: 34px; flex: none; border-radius: 10px; color: #fff; background: linear-gradient(135deg, #4f46e5, #8b5cf6); }
.assistant-message { max-width: 82%; margin: 22px 0; }
.assistant-message.user { margin-left: auto; }
.message-label { margin-bottom: 5px; color: var(--color-text-secondary); font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .08em; }
.assistant-message.user .message-label { text-align: right; }
.message-content { padding: 13px 16px; border-radius: 14px; background: #f1f3f8; }
.assistant-message.user .message-content { background: var(--color-primary); color: #fff; }
.working-row { display: flex; align-items: center; gap: 9px; color: var(--color-text-secondary); }
.process-panel { margin: 0 28px 18px; padding: 15px; background: #f8f9fc; border: 1px solid var(--color-border); border-radius: 12px; }
.process-title { margin-bottom: 10px; font-size: 12px; color: var(--color-text-secondary); font-weight: 700; }
.process-step { display: flex; align-items: center; gap: 10px; padding: 7px 0; }
.process-step div { min-width: 0; flex: 1; }
.process-step strong, .process-step small { display: block; }
.process-step small { color: var(--color-text-secondary); font-family: 'SF Mono', monospace; }
.step-check { display: grid; place-items: center; width: 22px; height: 22px; border-radius: 50%; background: var(--color-success-soft); color: var(--color-success); font-weight: 800; }
.action-preview { margin: 0 28px 18px; padding: 18px; border: 1px solid #f2d299; border-radius: 12px; background: #fffbf3; }
.action-heading { display: flex; justify-content: space-between; gap: 12px; font-weight: 750; }
.action-preview p { color: #74521b; }
.composer { display: flex; align-items: flex-end; gap: 10px; margin: 0 20px 10px; padding: 10px 10px 10px 16px; border: 1px solid var(--color-border); border-radius: 16px; background: #fff; box-shadow: 0 8px 24px rgba(31,36,48,.07); }
.composer:focus-within { border-color: #a8a3ff; box-shadow: 0 0 0 3px rgba(79,70,229,.1); }
.composer-input { flex: 1; border: none; resize: none; outline: none; font: inherit; color: inherit; background: transparent; }
.send-button { width: 38px; height: 38px; flex: none; border: none; border-radius: 11px; background: var(--color-primary); color: #fff; font-size: 20px; cursor: pointer; }
.send-button:disabled { opacity: .45; cursor: not-allowed; }
.integrity-note { margin: 0; padding: 0 24px 18px; text-align: center; color: var(--color-text-secondary); font-size: 11px; }
@media (max-width: 860px) { .prompt-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 560px) { .assistant-page { padding: 24px 12px 48px; } .assistant-hero { align-items: flex-start; flex-direction: column; } .prompt-grid { grid-template-columns: 1fr; } .assistant-message { max-width: 94%; } .conversation-stream { padding: 18px; } }
</style>
