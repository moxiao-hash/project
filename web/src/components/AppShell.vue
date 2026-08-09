<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-logo">✈</span>
        <span class="brand-name">StudyPilot</span>
      </div>
      <nav class="nav">
        <template v-for="group in navGroups" :key="group.title">
          <div class="nav-group-title">{{ group.title }}</div>
          <RouterLink
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            class="nav-item"
            :class="{ active: isActive(item) }"
            :aria-label="item.label"
          >
            <span class="nav-icon" aria-hidden="true">{{ item.icon }}</span>
            <span>{{ item.label }}</span>
            <span v-if="item.mock && gatewayMode === 'mock'" class="nav-mock">Mock</span>
          </RouterLink>
        </template>
      </nav>
      <div class="sidebar-footer">
        <RouterLink
          to="/settings"
          class="nav-item"
          :class="{ active: route.path.startsWith('/settings') }"
          aria-label="设置"
        >
          <span class="nav-icon" aria-hidden="true">⚙️</span>
          <span>设置</span>
        </RouterLink>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="topbar-title">{{ route.meta.title }}</div>
        <div class="spacer" />
        <RouterLink to="/notifications" class="topbar-bell" title="通知">🔔</RouterLink>
        <div class="topbar-user">
          <span class="avatar">{{ avatarLetter }}</span>
          <span class="username">{{ auth.user?.displayName ?? '' }}</span>
          <button class="btn btn-ghost btn-sm" @click="onLogout">退出</button>
        </div>
      </header>
      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { gatewayMode } from '@/services/planned'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

interface NavItem {
  to: string
  icon: string
  label: string
  mock?: boolean
  exact?: boolean
}

const navGroups: Array<{ title: string; items: NavItem[] }> = [
  {
    title: '总览',
    items: [
      { to: '/', icon: '📊', label: '工作台', exact: true },
      { to: '/notifications', icon: '🔔', label: '通知' },
    ],
  },
  {
    title: '学习',
    items: [
      { to: '/roadmap', icon: '🧭', label: '学习路线' },
      { to: '/today', icon: '✅', label: '今日任务' },
      { to: '/materials', icon: '📚', label: '学习资料' },
      { to: '/mastery', icon: '📈', label: '掌握度' },
    ],
  },
  {
    title: 'AI 助手',
    items: [
      { to: '/knowledge', icon: '💬', label: '知识问答', mock: true },
      { to: '/agent/plan', icon: '🪄', label: '对话生成计划', mock: true },
      { to: '/agent/tasks', icon: '🤖', label: '任务 Agent', mock: true },
      { to: '/activity', icon: '🧾', label: '执行与审计' },
    ],
  },
]

function isActive(item: NavItem): boolean {
  if (item.exact) return route.path === item.to
  return route.path === item.to || route.path.startsWith(item.to + '/')
}

const avatarLetter = computed(() =>
  (auth.user?.displayName ?? auth.user?.email ?? '?').slice(0, 1).toUpperCase(),
)

async function onLogout() {
  await auth.logout()
  void router.push({ name: 'login' })
}
</script>

<style scoped>
.shell {
  display: flex;
  min-height: 100vh;
}

.sidebar {
  width: 224px;
  flex-shrink: 0;
  background: var(--color-sidebar);
  color: var(--color-sidebar-text);
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-y: auto;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 18px 16px;
}

.brand-logo {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  color: #fff;
}

.brand-name {
  font-size: 16px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 0.2px;
}

.nav {
  flex: 1;
  padding: 4px 10px;
}

.nav-group-title {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: #5b6178;
  padding: 14px 10px 6px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  color: var(--color-sidebar-text);
  font-size: 14px;
  margin-bottom: 2px;
  transition: background 0.15s, color 0.15s;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}

.nav-item.active {
  background: rgba(99, 102, 241, 0.22);
  color: #fff;
  font-weight: 600;
}

.nav-icon {
  width: 20px;
  text-align: center;
}

.nav-mock {
  margin-left: auto;
  font-size: 10px;
  font-weight: 700;
  background: rgba(217, 119, 6, 0.25);
  color: #fbbf24;
  border-radius: 4px;
  padding: 1px 5px;
}

.sidebar-footer {
  padding: 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
  padding: 0 24px;
  height: 56px;
  position: sticky;
  top: 0;
  z-index: 10;
}

.topbar-title {
  font-size: 15px;
  font-weight: 600;
}

.topbar-bell {
  font-size: 17px;
  text-decoration: none;
}

.topbar-user {
  display: flex;
  align-items: center;
  gap: 8px;
}

.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--color-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
}

.username {
  font-size: 13px;
  font-weight: 500;
}

.content {
  flex: 1;
}

@media (max-width: 768px) {
  .sidebar {
    width: 64px;
  }
  .brand-name,
  .nav-group-title,
  .nav-item span:not(.nav-icon):not(.nav-mock),
  .nav-mock {
    display: none;
  }
  .nav-item {
    justify-content: center;
  }
}
</style>
