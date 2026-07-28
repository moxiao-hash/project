<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">对话生成计划</h1>
        <p class="page-subtitle">与 AI 对话起草学习计划，确认后才写入业务库</p>
      </div>
    </div>

    <MockBanner />

    <!-- 选择目标 -->
    <div v-if="!conversation" class="card">
      <LoadingBlock v-if="goalsLoading" />
      <ErrorState v-else-if="goalsError" :message="goalsError" @retry="loadGoals" />
      <template v-else>
        <h2 class="section-title" style="margin-bottom: 10px">选择学习目标</h2>
        <EmptyState
          v-if="goals.length === 0"
          icon="🎯"
          title="还没有学习目标"
          description="对话生成计划需要依附一个学习目标。"
        >
          <RouterLink to="/goals" class="btn btn-primary">去创建目标</RouterLink>
        </EmptyState>
        <template v-else>
          <div class="form-field" style="max-width: 420px">
            <select v-model="selectedGoalId" class="select">
              <option v-for="goal in goals" :key="goal.id" :value="goal.id">
                {{ goal.title }}（{{ goal.targetDate }}）
              </option>
            </select>
          </div>
          <button class="btn btn-primary" :disabled="creating" @click="startConversation">
            {{ creating ? '创建会话…' : '开始对话' }}
          </button>
        </template>
      </template>
    </div>

    <div v-else class="chat-layout">
      <!-- 对话区 -->
      <div class="card chat-card">
        <div ref="messagesEl" class="messages">
          <div v-for="(msg, i) in messages" :key="i" class="message" :class="msg.role">
            <div class="message-bubble">
              {{ msg.text }}
              <div v-if="msg.warnings?.length" class="msg-warnings">
                <div v-for="(w, wi) in msg.warnings" :key="wi" class="alert alert-warning" style="margin: 6px 0 0">
                  {{ w }}
                </div>
              </div>
            </div>
          </div>
          <div v-if="sending && !waitingInBackground" class="message assistant">
            <div class="message-bubble"><span class="spinner" /> 正在思考…</div>
          </div>
          <div v-if="sending && waitingInBackground" class="message assistant">
            <div class="message-bubble">请求正在后台处理中，返回前暂不能继续发送。</div>
          </div>
        </div>

        <div v-if="conversation.error" class="alert alert-danger">{{ conversation.error }}</div>

        <form class="chat-input" @submit.prevent="onSend">
          <input
            v-model.trim="draft"
            class="input"
            :placeholder="inputPlaceholder"
            :disabled="sending || conversation.status === 'COMPLETED'"
          />
          <button
            class="btn btn-primary"
            type="submit"
            :disabled="sending || !draft || conversation.status === 'COMPLETED'"
          >
            发送
          </button>
          <button
            v-if="sending"
            class="btn btn-secondary"
            type="button"
            @click="cancelWaiting"
          >
            转到后台等待
          </button>
        </form>
        <p class="muted" style="font-size: 12px; margin: 8px 0 0">
          聊天中说「确认」只是一条消息；只有「保存计划」按钮会真正写入。
        </p>
      </div>

      <!-- 草稿区 -->
      <div v-if="conversation.status === 'DRAFT_READY' && conversation.draft" class="card draft-card">
        <h2 class="section-title">计划草案</h2>
        <div class="draft-meta">
          <div><strong>{{ conversation.draft.title }}</strong></div>
          <div class="muted" style="font-size: 13px">
            {{ conversation.draft.startDate }} ～ {{ conversation.draft.endDate }} ·
            {{ conversation.draft.tasks.length }} 个任务
          </div>
        </div>
        <div class="draft-tasks">
          <div v-for="(task, i) in conversation.draft.tasks" :key="i" class="draft-task">
            <div class="draft-task-title">{{ task.title }}</div>
            <div class="muted" style="font-size: 12px">
              {{ task.scheduledDate }} · {{ task.estimatedMinutes }} 分钟
            </div>
          </div>
        </div>
        <p class="muted" style="font-size: 12px">
          需要修改？在左侧对话中描述调整（例如「把第二个任务改到周五」），AI 会返回新的完整草案。
        </p>
        <button class="btn btn-primary" style="width: 100%" :disabled="confirming" @click="confirmDialog = true">
          保存计划
        </button>
      </div>

      <!-- 保存成功 -->
      <div v-if="conversation.status === 'COMPLETED'" class="card draft-card">
        <EmptyState icon="🎉" title="计划已保存" :description="`计划 ID：${conversation.savedPlanId}`">
          <RouterLink v-if="gatewayMode === 'http' && conversation.savedPlanId" :to="`/plans/${conversation.savedPlanId}`" class="btn btn-primary">
            查看计划
          </RouterLink>
          <RouterLink v-else to="/plans" class="btn btn-primary">前往计划列表</RouterLink>
        </EmptyState>
      </div>
    </div>

    <ConfirmDialog
      v-model="confirmDialog"
      title="保存计划"
      :confirm-text="`确认保存计划「${conversation?.draft?.title ?? ''}」`"
      :loading="confirming"
      @confirm="onConfirmPlan"
    >
      <p>
        将把草案中的 {{ conversation?.draft?.tasks.length ?? 0 }} 个任务写入学习计划，
        保存后可在计划详情页继续调整。
      </p>
    </ConfirmDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { learningApi } from '@/services/current/learning'
import { agentGateway, gatewayMode } from '@/services/planned'
import { describeError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import type { LearningGoal } from '@/types/api'
import type { PlanConversation } from '@/types/agent'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'
import MockBanner from '@/components/MockBanner.vue'

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  warnings?: string[]
}

const route = useRoute()
const toast = useToastStore()

const goals = ref<LearningGoal[]>([])
const goalsLoading = ref(true)
const goalsError = ref('')
const selectedGoalId = ref('')
const creating = ref(false)

const conversation = ref<PlanConversation | null>(null)
const messages = ref<ChatMessage[]>([])
const draft = ref('')
const sending = ref(false)
const waitingInBackground = ref(false)
const confirming = ref(false)
const confirmDialog = ref(false)
const messagesEl = ref<HTMLElement | null>(null)

const inputPlaceholder = computed(() => {
  if (!conversation.value) return ''
  switch (conversation.value.status) {
    case 'COLLECTING':
      return '描述你的基础、节奏和可用时间…'
    case 'DRAFT_READY':
      return '描述想调整的地方，例如「把周三任务改到周四」…'
    case 'COMPLETED':
      return '计划已保存'
    default:
      return '输入消息…'
  }
})

async function loadGoals() {
  goalsLoading.value = true
  goalsError.value = ''
  try {
    goals.value = await learningApi.listGoals()
    const fromQuery = route.query.goalId
    selectedGoalId.value =
      (typeof fromQuery === 'string' && goals.value.some((g) => g.id === fromQuery)
        ? fromQuery
        : goals.value[0]?.id) ?? ''
  } catch (e) {
    goalsError.value = describeError(e)
  } finally {
    goalsLoading.value = false
  }
}

async function startConversation() {
  if (!selectedGoalId.value || creating.value) return
  creating.value = true
  try {
    conversation.value = await agentGateway.createPlanConversation(selectedGoalId.value)
    messages.value = [{ role: 'assistant', text: conversation.value.reply }]
    await scrollToBottom()
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    creating.value = false
  }
}

async function onSend() {
  if (!conversation.value || !draft.value || sending.value) return
  const text = draft.value
  draft.value = ''
  messages.value.push({ role: 'user', text })
  sending.value = true
  waitingInBackground.value = false
  await scrollToBottom()
  try {
    const updated = await agentGateway.sendPlanMessage(conversation.value.conversationId, text)
    conversation.value = updated
    messages.value.push({
      role: 'assistant',
      text: updated.reply,
      warnings: updated.warnings.length > 0 ? updated.warnings : undefined,
    })
    await scrollToBottom()
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    sending.value = false
    waitingInBackground.value = false
  }
}

function cancelWaiting() {
  // HTTP 请求不能在不丢失结果的情况下真正取消。转入后台展示后仍保持
  // sending=true，避免用户在同一会话并发发送导致服务端返回 409。
  waitingInBackground.value = true
  toast.info('已转到后台等待；结果返回前暂不能继续发送')
}

async function onConfirmPlan() {
  if (!conversation.value || confirming.value) return
  confirming.value = true
  try {
    conversation.value = await agentGateway.confirmPlan(conversation.value.conversationId)
    confirmDialog.value = false
    messages.value.push({ role: 'assistant', text: conversation.value.reply })
    toast.success('计划已保存')
    await scrollToBottom()
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    confirming.value = false
  }
}

async function scrollToBottom() {
  await nextTick()
  if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight
}

onMounted(loadGoals)
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

.draft-card {
  position: sticky;
  top: 76px;
}

.draft-meta {
  margin: 10px 0;
}

.draft-tasks {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
  max-height: 280px;
  overflow-y: auto;
}

.draft-task {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 8px 12px;
}

.draft-task-title {
  font-size: 13px;
  font-weight: 600;
}
</style>
