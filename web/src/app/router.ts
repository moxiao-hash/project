import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/modules/auth/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/modules/auth/RegisterView.vue'),
      meta: { public: true, title: '注册' },
    },
    {
      path: '/',
      component: () => import('@/components/AppShell.vue'),
      children: [
        {
          path: '',
          name: 'dashboard',
          component: () => import('@/modules/dashboard/DashboardView.vue'),
          meta: { title: '工作台' },
        },
        {
          path: 'goals',
          name: 'goals',
          component: () => import('@/modules/learning/GoalsView.vue'),
          meta: { title: '学习目标' },
        },
        {
          path: 'plans',
          name: 'plans',
          component: () => import('@/modules/learning/PlansView.vue'),
          meta: { title: '学习计划' },
        },
        {
          path: 'plans/:id',
          name: 'plan-detail',
          component: () => import('@/modules/learning/PlanDetailView.vue'),
          meta: { title: '计划详情' },
        },
        {
          path: 'today',
          name: 'today',
          component: () => import('@/modules/learning/TodayView.vue'),
          meta: { title: '今日任务' },
        },
        {
          path: 'materials',
          name: 'materials',
          component: () => import('@/modules/materials/MaterialsView.vue'),
          meta: { title: '学习资料' },
        },
        {
          path: 'materials/:id',
          name: 'material-detail',
          component: () => import('@/modules/materials/MaterialDetailView.vue'),
          meta: { title: '资料详情' },
        },
        {
          path: 'quizzes/:id',
          name: 'quiz',
          component: () => import('@/modules/assessment/QuizView.vue'),
          meta: { title: '测验作答' },
        },
        {
          path: 'attempts/:id',
          name: 'attempt',
          component: () => import('@/modules/assessment/AttemptView.vue'),
          meta: { title: '测验结果' },
        },
        {
          path: 'mastery',
          name: 'mastery',
          component: () => import('@/modules/assessment/MasteryView.vue'),
          meta: { title: '掌握度' },
        },
        {
          path: 'knowledge',
          name: 'knowledge',
          component: () => import('@/modules/agent/KnowledgeView.vue'),
          meta: { title: '知识问答' },
        },
        {
          path: 'agent/plan',
          name: 'agent-plan',
          component: () => import('@/modules/agent/PlanChatView.vue'),
          meta: { title: '对话生成计划' },
        },
        {
          path: 'agent/tasks',
          name: 'agent-tasks',
          component: () => import('@/modules/agent/TaskAgentView.vue'),
          meta: { title: '任务 Agent' },
        },
        {
          path: 'activity',
          name: 'activity',
          component: () => import('@/modules/agent/ActivityView.vue'),
          meta: { title: '执行与审计' },
        },
        {
          path: 'notifications',
          name: 'notifications',
          component: () => import('@/modules/notifications/NotificationsView.vue'),
          meta: { title: '通知' },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/modules/settings/SettingsView.vue'),
          meta: { title: '学习设置' },
        },
        {
          path: 'settings/ai',
          name: 'settings-ai',
          component: () => import('@/modules/settings/AiSettingsView.vue'),
          meta: { title: 'AI 设置' },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/modules/NotFoundView.vue'),
      meta: { public: true, title: '页面不存在' },
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  // 首次导航前先用 /api/auth/me 恢复会话，不能只信本地缓存
  if (!auth.restored) await auth.restore()

  if (!to.meta.public && !auth.isAuthenticated) {
    return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  if (to.meta.public && auth.isAuthenticated && (to.name === 'login' || to.name === 'register')) {
    return { name: 'dashboard' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · StudyPilot` : 'StudyPilot'
})

export default router
