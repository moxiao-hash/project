<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">通知</h1>
        <p class="page-subtitle">计划调整、资料就绪、任务逾期等提醒</p>
      </div>
      <button
        v-if="notifications.some((n) => !n.read)"
        class="btn btn-secondary"
        :disabled="markingAll"
        @click="markAllRead"
      >
        全部标为已读
      </button>
    </div>

    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <div v-else-if="notifications.length === 0" class="card">
      <EmptyState icon="🔕" title="暂无通知" />
    </div>

    <div v-else class="notification-list">
      <div
        v-for="n in notifications"
        :key="n.id"
        class="card notification-row"
        :class="{ unread: !n.read }"
      >
        <div class="notification-icon">{{ icon(n.type) }}</div>
        <div class="notification-body">
          <div class="row">
            <span class="notification-title">{{ n.title }}</span>
            <span class="badge badge-neutral" style="font-size: 11px">
              {{ notificationTypeLabels[n.type] }}
            </span>
            <span v-if="!n.read" class="unread-dot" />
          </div>
          <p class="notification-content">{{ n.content }}</p>
          <div class="muted mono" style="font-size: 12px">{{ formatDateTime(n.createdAt) }}</div>
        </div>
        <button v-if="!n.read" class="btn btn-ghost btn-sm" @click="markRead(n.id)">
          标为已读
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { agentOpsApi } from '@/services/current/agentOps'
import { describeError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import { formatDateTime } from '@/utils/datetime'
import { notificationTypeLabels } from '@/utils/labels'
import type { Notification, NotificationType } from '@/types/api'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'

const toast = useToastStore()
const notifications = ref<Notification[]>([])
const loading = ref(true)
const error = ref('')
const markingAll = ref(false)

function icon(type: NotificationType): string {
  const map: Record<NotificationType, string> = {
    PLAN_ADJUSTED: '🗓️',
    PLAN_ADJUSTMENT_READY: '🧭',
    MATERIAL_READY: '📚',
    QUIZ_READY: '🧪',
    TASK_OVERDUE: '⏰',
    AGENT_FAILED: '⚠️',
  }
  return map[type]
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    notifications.value = await agentOpsApi.listNotifications()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function markRead(id: string) {
  try {
    const updated = await agentOpsApi.markNotificationRead(id)
    notifications.value = notifications.value.map((n) => (n.id === id ? updated : n))
  } catch (e) {
    toast.error(describeError(e))
  }
}

async function markAllRead() {
  if (markingAll.value) return
  markingAll.value = true
  try {
    const unread = notifications.value.filter((n) => !n.read)
    for (const n of unread) {
      const updated = await agentOpsApi.markNotificationRead(n.id)
      notifications.value = notifications.value.map((x) => (x.id === n.id ? updated : x))
    }
  } catch (e) {
    toast.error(describeError(e))
  } finally {
    markingAll.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.notification-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.notification-row {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  padding: 14px 18px;
}

.notification-row.unread {
  border-left: 3px solid var(--color-primary);
}

.notification-icon {
  font-size: 22px;
}

.notification-body {
  flex: 1;
  min-width: 0;
}

.notification-title {
  font-weight: 600;
}

.unread-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-primary);
}

.notification-content {
  margin: 6px 0;
  font-size: 13px;
  color: var(--color-text-secondary);
}
</style>
