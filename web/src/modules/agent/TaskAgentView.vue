<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">任务 Agent</h1>
        <p class="page-subtitle">用对话操作每日任务；所有写操作先预览、后确认</p>
      </div>
    </div>

    <MockBanner />

    <div v-if="!conversation" class="card">
      <h2 class="section-title" style="margin-bottom: 10px">选择目标日期</h2>
      <div class="form-field" style="max-width: 240px">
        <input v-model="targetDate" class="input" type="date" />
      </div>
      <button class="btn btn-primary" :disabled="creating" @click="startConversation">
        {{ creating ? '创建会话…' : '开始会话' }}
      </button>
    </div>

    <div v-else class="chat-layout">
      <div class="card chat-card">
        <div ref="messagesEl" class="messages">
          <div v-for="(msg, i) in messages" :key="i" class="message" :class="msg.role">
            <div class="message-bubble">{{ msg.text }}</div>
          </div>
          <div v-if="sending" class="message assistant">
            <div class="message-bubble"><span class="spinner" /> 正在理解你的意图…</div>
          </div>
        </div>

        <div v-if="conversation.error" class="alert alert-danger">{{ conversation.error }}</div>

        <form class="chat-input" @submit.prevent="onSend">
          <input
            v-model.trim="draftText"
            class="input"
            placeholder="例如「完成第一个任务」「把任务延期到明天」"
            :disabled="sending || conversation.status === 'COMPLETED'"
          />
          <button class="btn btn-primary" type="submit" :disabled="sending || !draftText || conversation.status === 'COMPLETED'">
            发送
          </button>
        </form>
      </div>

      <div class="side">
        <!-- 候选任务 -->
        <div class="card">
          <h2 class="section-title" style="margin-bottom: 10px">候选任务</h2>
          <EmptyState v-if="conversation.candidateTasks.length === 0" icon="📝" title="无候选任务" />
          <div v-for="task in conversation.candidateTasks" :key="task.id" class="candidate">
            <div class="row">
              <span class="candidate-title">{{ task.title }}</span>
              <StatusBadge :label="taskStatusLabels[task.status]" :badge-class="taskStatusBadge[task.status]" />
            </div>
            <div class="muted mono" style="font-size: 11px">version {{ task.version }}</div>
          </div>
        </div>

        <!-- 操作预览 -->
        <div v-if="conversation.status === 'PREVIEW_READY' && conversation.actionDraft" class="card preview-card">
          <h2 class="section-title" style="margin-bottom: 10px">操作预览</h2>
          <dl class="preview-grid">
            <dt>任务</dt>
            <dd>{{ conversation.actionDraft.taskTitle }}</dd>
            <dt>动作</dt>
            <dd>
              <StatusBadge
                :label="taskStatusLabels[conversation.actionDraft.targetStatus]"
                :badge-class="taskStatusBadge[conversation.actionDraft.targetStatus]"
              />
            </dd>
            <template v-if="conversation.actionDraft.reason">
              <dt>原因</dt>
              <dd>{{ conversation.actionDraft.reason }}</dd>
            </template>
            <template v-if="conversation.actionDraft.deferredTo">
              <dt>延期至</dt>
              <dd>{{ conversation.actionDraft.deferredTo }}</dd>
            </template>
            <template v-if="conversation.actionDraft.actualMinutes !== null">
              <dt>实际用时</dt>
              <dd>{{ conversation.actionDraft.actualMinutes }} 分钟</dd>
            </template>
            <dt>预计影响</dt>
            <dd class="muted">
              任务版本 v{{ conversation.actionDraft.expectedVersion }} →
              v{{ conversation.actionDraft.expectedVersion + 1 }}，状态变更为
              {{ taskStatusLabels[conversation.actionDraft.targetStatus] }}
            </dd>
          </dl>
          <button
            class="btn btn-primary"
            style="width: 100%; margin-top: 10px"
            :disabled="confirming"
            @click="confirmDialog = true"
          >
            {{ confirming ? '执行中…' : `确认执行：${taskStatusLabels[conversation.actionDraft.targetStatus]}该任务` }}
          </button>
        </div>

        <!-- 执行结果 -->
        <div v-if="conversation.status === 'COMPLETED' && conversation.updatedTask" class="card">
          <h2 class="section-title" style="margin-bottom: 10px">执行结果</h2>
          <div class="row">
            <span>{{ conversation.updatedTask.title }}</span>
            <StatusBadge
              :label="taskStatusLabels[conversation.updatedTask.status]"
              :badge-class="taskStatusBadge[conversation.updatedTask.status]"
            />
          </div>
          <div class="muted mono" style="font-size: 11px; margin-top: 4px">
            执行记录 {{ conversation.executionId }}
          </div>
        </div>
      </div>
    </div>

    <ConfirmDialog
      v-model="confirmDialog"
      title="确认任务操作"
      :confirm-text="confirmButtonText"
      :loading="confirming"
      @confirm="onConfirm"
    >
      <template v-if="conversation?.actionDraft">
        <p>
          将对任务「{{ conversation.actionDraft.taskTitle }}」执行
          <strong>{{ taskStatusLabels[conversation.actionDraft.targetStatus] }}</strong> 操作。
        </p>
        <p class="muted" style="font-size: 13px">
          基于任务版本 v{{ conversation.actionDraft.expectedVersion }}；若期间任务被修改（409），
          将刷新真实任务状态，不会静默覆盖。
        </p>
      </template>
    </ConfirmDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { agentGateway } from '@/services/planned'
import { describeError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import { todayString } from '@/utils/datetime'
import { taskStatusBadge, taskStatusLabels } from '@/utils/labels'
import type { TaskConversation } from '@/types/agent'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import MockBanner from '@/components/MockBanner.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { AxiosError } from 'axios'

const toast = useToastStore()
const route = useRoute()
const router = useRouter()
const targetDate = ref(todayString())
const creating = ref(false)

const conversation = ref<TaskConversation | null>(null)
const messages = ref<Array<{ role: 'user' | 'assistant'; text: string }>>([])
const draftText = ref('')
const sending = ref(false)
const confirming = ref(false)
const confirmDialog = ref(false)
const messagesEl = ref<HTMLElement | null>(null)
let mounted = true

const confirmButtonText = computed(() => {
  const d = conversation.value?.actionDraft
  if (!d) return '确认执行'
  return `确认${taskStatusLabels[d.targetStatus]}「${d.taskTitle}」`
})

async function startConversation() {
  if (creating.value) return
  const startLocation = route.fullPath
  creating.value = true
  try {
    conversation.value = await agentGateway.createTaskConversation(targetDate.value)
    messages.value = [{ role: 'assistant', text: conversation.value.reply }]
    if (mounted && route.fullPath === startLocation) {
      await router.replace({
        query: { ...route.query, conversationId: conversation.value.conversationId },
      })
    }
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    creating.value = false
  }
}

async function restoreConversation() {
  const id = route.query.conversationId
  if (typeof id !== 'string' || !id) return
  creating.value = true
  try {
    conversation.value = await agentGateway.getTaskConversation(id)
    targetDate.value = conversation.value.targetDate
    messages.value = [{ role: 'assistant', text: conversation.value.reply }]
  } catch (e) {
    toast.error(`无法恢复任务会话：${describeError(e)}`)
    await router.replace({ query: { ...route.query, conversationId: undefined } })
  } finally {
    creating.value = false
  }
}

async function onSend() {
  if (!conversation.value || !draftText.value || sending.value) return
  const text = draftText.value
  draftText.value = ''
  messages.value.push({ role: 'user', text })
  sending.value = true
  await scrollToBottom()
  try {
    conversation.value = await agentGateway.sendTaskMessage(
      conversation.value.conversationId,
      text,
    )
    messages.value.push({ role: 'assistant', text: conversation.value.reply })
    await scrollToBottom()
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    sending.value = false
  }
}

async function onConfirm() {
  if (!conversation.value || confirming.value) return
  confirming.value = true
  try {
    conversation.value = await agentGateway.confirmTaskAction(conversation.value.conversationId)
    confirmDialog.value = false
    messages.value.push({ role: 'assistant', text: conversation.value.reply })
    toast.success('操作已执行')
    await scrollToBottom()
  } catch (e) {
    if (e instanceof AxiosError && e.response?.status === 409) {
      toast.warning('任务版本已变化，请重新获取真实任务状态后再操作')
      confirmDialog.value = false
    } else {
      toast.error(describeError(e))
    }
  } finally {
    confirming.value = false
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

.chat-layout {
  display: grid;
  grid-template-columns: 1fr 360px;
  gap: 16px;
  align-items: start;
}

@media (max-width: 900px) {
  .chat-layout {
    grid-template-columns: 1fr;
  }
}

.chat-card {
  display: flex;
  flex-direction: column;
  height: 560px;
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
  max-width: 80%;
  border-radius: 12px;
  padding: 10px 14px;
  font-size: 14px;
  white-space: pre-wrap;
  background: var(--color-bg);
}

.message.user .message-bubble {
  background: var(--color-primary);
  color: #fff;
}

.chat-input {
  display: flex;
  gap: 8px;
  border-top: 1px solid var(--color-border);
  padding-top: 12px;
}

.side {
  display: flex;
  flex-direction: column;
  gap: 16px;
  position: sticky;
  top: 76px;
}

.candidate {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 8px 12px;
  margin-bottom: 8px;
}

.candidate-title {
  font-size: 13px;
  font-weight: 600;
}

.preview-card {
  border-color: var(--color-primary);
}

.preview-grid {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 6px 12px;
  margin: 0;
  font-size: 13px;
}

.preview-grid dt {
  color: var(--color-text-secondary);
}

.preview-grid dd {
  margin: 0;
}
</style>
