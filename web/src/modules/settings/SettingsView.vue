<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">学习设置</h1>
        <p class="page-subtitle">每日学习上限、周末偏好与每周可用时间</p>
      </div>
      <RouterLink to="/settings/ai" class="btn btn-secondary">AI 设置（Key 管理）</RouterLink>
    </div>

    <LoadingBlock v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />

    <form v-else-if="settings" class="card" @submit.prevent="onSave">
      <div class="grid cols-2">
        <div class="form-field">
          <label class="form-label">时区（IANA 名称）</label>
          <input v-model.trim="settings.timeZone" class="input" placeholder="Asia/Shanghai" required />
        </div>
        <div class="form-field">
          <label class="form-label">每日学习上限（分钟，15～720）</label>
          <input
            v-model.number="settings.dailyStudyLimitMinutes"
            class="input"
            type="number"
            min="15"
            max="720"
            required
          />
        </div>
        <div class="form-field">
          <label class="form-label">周末偏好</label>
          <select v-model="settings.weekendPreference" class="select">
            <option v-for="(label, key) in weekendPreferenceLabels" :key="key" :value="key">
              {{ label }}
            </option>
          </select>
        </div>
        <div class="form-field">
          <label class="form-label">默认隐私级别</label>
          <select v-model="settings.defaultPrivacyLevel" class="select">
            <option v-for="(label, key) in privacyLevelLabels" :key="key" :value="key">
              {{ label }}
            </option>
          </select>
        </div>
      </div>

      <hr class="divider" />
      <div class="row" style="margin-bottom: 10px">
        <h2 class="section-title">每周可用时间</h2>
        <span class="spacer" />
        <button type="button" class="btn btn-secondary btn-sm" @click="addSlot">添加时间段</button>
      </div>
      <p v-if="settings.weeklyAvailability.length === 0" class="muted" style="font-size: 13px">
        未设置可用时间，系统会认为全周可用。
      </p>
      <div v-for="(slot, index) in settings.weeklyAvailability" :key="index" class="slot-row">
        <select v-model="slot.dayOfWeek" class="select slot-day">
          <option v-for="(label, day) in dayOfWeekLabels" :key="day" :value="day">{{ label }}</option>
        </select>
        <input v-model="slot.startTime" class="input slot-time" type="time" step="1" />
        <span class="muted">至</span>
        <input v-model="slot.endTime" class="input slot-time" type="time" step="1" />
        <button type="button" class="btn btn-ghost btn-sm" @click="settings.weeklyAvailability.splice(index, 1)">
          删除
        </button>
      </div>
      <span v-if="slotError" class="field-error">{{ slotError }}</span>

      <div v-if="saveError" class="alert alert-danger" style="margin-top: 14px">{{ saveError }}</div>
      <div class="row" style="justify-content: flex-end; margin-top: 18px">
        <button type="submit" class="btn btn-primary" :disabled="saving">
          {{ saving ? '保存中…' : '保存设置' }}
        </button>
      </div>
    </form>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { AxiosError } from 'axios'
import { settingsApi } from '@/services/current/dashboard'
import { describeError, getApiError } from '@/services/http'
import { useToastStore } from '@/stores/toast'
import { dayOfWeekLabels, privacyLevelLabels, weekendPreferenceLabels } from '@/utils/labels'
import type { UserSettings } from '@/types/api'
import ErrorState from '@/components/ErrorState.vue'
import LoadingBlock from '@/components/LoadingBlock.vue'

const toast = useToastStore()
const settings = ref<UserSettings | null>(null)
const loading = ref(true)
const error = ref('')
const saving = ref(false)
const saveError = ref('')
const slotError = ref('')

function defaultSettings(): UserSettings {
  return {
    timeZone: 'Asia/Shanghai',
    dailyStudyLimitMinutes: 120,
    weekendPreference: 'MORE',
    defaultPrivacyLevel: 'NORMAL',
    weeklyAvailability: [],
  }
}

function normalizeTime(t: string): string {
  // 契约要求 HH:mm:ss；time input 可能只给 HH:mm
  return t.length === 5 ? `${t}:00` : t
}

function addSlot() {
  settings.value?.weeklyAvailability.push({
    dayOfWeek: 'SATURDAY',
    startTime: '09:00:00',
    endTime: '12:00:00',
  })
}

function validate(): boolean {
  slotError.value = ''
  if (!settings.value) return false
  const limit = settings.value.dailyStudyLimitMinutes
  if (!Number.isFinite(limit) || limit < 15 || limit > 720) {
    slotError.value = '每日学习上限需为 15～720 分钟'
    return false
  }
  for (const slot of settings.value.weeklyAvailability) {
    if (normalizeTime(slot.endTime) <= normalizeTime(slot.startTime)) {
      slotError.value = `${dayOfWeekLabels[slot.dayOfWeek]}的结束时间必须晚于开始时间`
      return false
    }
  }
  return true
}

async function onSave() {
  if (!settings.value || saving.value || !validate()) return
  saving.value = true
  saveError.value = ''
  try {
    const body: UserSettings = {
      ...settings.value,
      weeklyAvailability: settings.value.weeklyAvailability.map((s) => ({
        ...s,
        startTime: normalizeTime(s.startTime),
        endTime: normalizeTime(s.endTime),
      })),
    }
    settings.value = await settingsApi.update(body)
    toast.success('设置已保存')
  } catch (e) {
    const apiError = getApiError(e)
    saveError.value = apiError?.fieldErrors
      ? Object.values(apiError.fieldErrors).join('；')
      : describeError(e)
  } finally {
    saving.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    settings.value = await settingsApi.get()
  } catch (e) {
    // 新注册用户还没有 user_settings 记录；PUT 本身支持首次创建，
    // 因此 404 应转换成可编辑的默认表单，而不是不可恢复的错误页。
    if (e instanceof AxiosError && e.response?.status === 404) {
      settings.value = defaultSettings()
    } else {
      error.value = describeError(e)
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.section-title {
  font-size: 15px;
}

.slot-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.slot-day {
  width: 110px;
}

.slot-time {
  width: 130px;
}
</style>
