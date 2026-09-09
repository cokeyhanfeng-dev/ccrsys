import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import { useUserStore } from './store/user'
import { bootstrapCodeLogin } from './auth/code-login.mjs'
import InfoTip from './components/InfoTip.vue'
import './styles/design-system.css'
import './styles/index.scss'

const app = createApp(App)

// 注册 Element Plus 图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
// 全局说明图标(悬停展示解释文案)
app.component('InfoTip', InfoTip)

const pinia = createPinia()
app.use(pinia)
app.use(ElementPlus, { locale: zhCn })

async function bootstrap() {
  const user = useUserStore(pinia)
  const root = document.getElementById('app')
  if (root) root.textContent = '正在加载，请稍候…'
  user.ssoError = await bootstrapCodeLogin({
    href: window.location.href,
    replace: (url: string) => window.history.replaceState(window.history.state, '', url),
    login: (code: string) => user.loginByCode(code),
    clear: () => user.logout()
  })
  // createWebHistory 也须在 URL 清理后执行。
  const { default: router } = await import('./router')
  app.use(router)
  await router.isReady()
  app.mount('#app')
}

void bootstrap()
