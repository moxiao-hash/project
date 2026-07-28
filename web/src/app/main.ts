import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from '@/services/http'
import { useAuthStore } from '@/stores/auth'
import '@/styles/main.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

// 401 统一处理：清理会话并回登录页，页面无需各自处理
setUnauthorizedHandler(() => {
  const auth = useAuthStore(pinia)
  auth.clearSession()
  if (router.currentRoute.value.name !== 'login') {
    void router.push({ name: 'login', query: { expired: '1' } })
  }
})

app.use(router)
app.mount('#app')
