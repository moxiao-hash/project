<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-brand">
        <span class="auth-logo">✈</span>
        <h1>StudyPilot</h1>
        <p class="muted">个人 AI 学习执行工作台</p>
      </div>

      <div v-if="route.query.expired" class="alert alert-warning">
        登录已过期，请重新登录。
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
            placeholder="learner@example.com"
            required
          />
        </div>
        <div class="form-field">
          <label class="form-label" for="password">密码</label>
          <input
            id="password"
            v-model="password"
            class="input"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
            required
          />
        </div>
        <div v-if="error" class="alert alert-danger">{{ error }}</div>
        <button class="btn btn-primary btn-block" type="submit" :disabled="loading">
          <span v-if="loading" class="spinner spinner-light" />
          登录
        </button>
      </form>

      <p class="auth-switch muted">
        还没有账号？<RouterLink to="/register">立即注册</RouterLink>
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { describeError } from '@/services/http'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const email = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function onSubmit() {
  if (loading.value) return
  error.value = ''
  loading.value = true
  try {
    await auth.login(email.value, password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    void router.push(redirect)
  } catch (e) {
    error.value = describeError(e)
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
