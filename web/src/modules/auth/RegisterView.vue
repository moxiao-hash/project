<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-brand">
        <span class="auth-logo">✈</span>
        <h1>创建账号</h1>
        <p class="muted">开始你的 AI 学习之旅</p>
      </div>

      <form @submit.prevent="onSubmit">
        <div class="form-field">
          <label class="form-label" for="email">邮箱</label>
          <input
            id="email"
            v-model.trim="email"
            class="input"
            type="email"
            autocomplete="email"
            maxlength="255"
            placeholder="learner@example.com"
            required
          />
          <span v-if="fieldErrors.email" class="field-error">{{ fieldErrors.email }}</span>
        </div>
        <div class="form-field">
          <label class="form-label" for="displayName">昵称</label>
          <input
            id="displayName"
            v-model.trim="displayName"
            class="input"
            maxlength="80"
            placeholder="学习者"
            required
          />
          <span v-if="fieldErrors.displayName" class="field-error">{{ fieldErrors.displayName }}</span>
        </div>
        <div class="form-field">
          <label class="form-label" for="password">密码</label>
          <input
            id="password"
            v-model="password"
            class="input"
            type="password"
            autocomplete="new-password"
            placeholder="8～72 个字符"
            required
          />
          <span v-if="fieldErrors.password" class="field-error">{{ fieldErrors.password }}</span>
        </div>
        <div class="form-field">
          <label class="form-label" for="passwordConfirm">确认密码</label>
          <input
            id="passwordConfirm"
            v-model="passwordConfirm"
            class="input"
            type="password"
            autocomplete="new-password"
            required
          />
          <span v-if="fieldErrors.passwordConfirm" class="field-error">
            {{ fieldErrors.passwordConfirm }}
          </span>
        </div>
        <div v-if="error" class="alert alert-danger">{{ error }}</div>
        <button class="btn btn-primary btn-block" type="submit" :disabled="loading">
          <span v-if="loading" class="spinner spinner-light" />
          注册并登录
        </button>
      </form>

      <p class="auth-switch muted">
        已有账号？<RouterLink to="/login">直接登录</RouterLink>
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { describeError, getApiError } from '@/services/http'

const router = useRouter()
const auth = useAuthStore()

const email = ref('')
const displayName = ref('')
const password = ref('')
const passwordConfirm = ref('')
const loading = ref(false)
const error = ref('')
const fieldErrors = reactive<Record<string, string>>({})

function validate(): boolean {
  fieldErrors.email = ''
  fieldErrors.displayName = ''
  fieldErrors.password = ''
  fieldErrors.passwordConfirm = ''
  let ok = true
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.value)) {
    fieldErrors.email = '请输入合法的邮箱地址'
    ok = false
  }
  if (displayName.value.length === 0 || displayName.value.length > 80) {
    fieldErrors.displayName = '昵称长度需为 1～80 个字符'
    ok = false
  }
  if (password.value.length < 8 || password.value.length > 72) {
    fieldErrors.password = '密码长度需为 8～72 个字符'
    ok = false
  }
  if (passwordConfirm.value !== password.value) {
    fieldErrors.passwordConfirm = '两次输入的密码不一致'
    ok = false
  }
  return ok
}

async function onSubmit() {
  if (loading.value || !validate()) return
  error.value = ''
  loading.value = true
  try {
    await auth.register(email.value, password.value, displayName.value)
    void router.push('/')
  } catch (e) {
    const apiError = getApiError(e)
    if (apiError?.fieldErrors) {
      Object.assign(fieldErrors, apiError.fieldErrors)
    } else {
      error.value = describeError(e)
    }
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(160deg, #eef0fe 0%, #f5f6fa 55%, #fdf3e3 100%);
  padding: 24px;
}

.auth-card {
  width: 100%;
  max-width: 400px;
  background: var(--color-surface);
  border-radius: 16px;
  box-shadow: var(--shadow-lg);
  padding: 36px 32px;
}

.auth-brand {
  text-align: center;
  margin-bottom: 24px;
}

.auth-logo {
  display: inline-flex;
  width: 48px;
  height: 48px;
  border-radius: 14px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
  font-size: 24px;
  align-items: center;
  justify-content: center;
  margin-bottom: 10px;
}

.auth-brand h1 {
  font-size: 22px;
}

.btn-block {
  width: 100%;
  margin-top: 4px;
}

.auth-switch {
  text-align: center;
  margin-top: 18px;
  font-size: 13px;
}

.spinner-light {
  border-color: rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  width: 14px;
  height: 14px;
}
</style>
