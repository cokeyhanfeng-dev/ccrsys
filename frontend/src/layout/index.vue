<template>
  <div class="app-shell">
    <!-- 侧边栏导航(design-system) -->
    <aside class="app-sidebar">
      <div class="brand">
        <span class="brand-title">利率决策系统</span>
      </div>
      <router-link
        v-for="item in menus"
        :key="item.path"
        :to="item.path"
        class="app-sidebar__item"
        :class="{ 'app-sidebar__item--active': route.path.startsWith(item.path) }"
      >
        {{ item.title }}
      </router-link>
    </aside>

    <div class="app-main">
      <!-- 顶栏:仅用户信息;页面标题由页面内提供(避免重复) -->
      <div class="topbar">
        <div class="topbar__user">
          <el-dropdown @command="onCommand">
            <span class="user-name">
              {{ userStore.userInfo?.nickName || '用户' }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>

      <router-view />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const currentRole = computed(() => userStore.userInfo?.roles?.[0] || 'customer_manager')

// 参照 design-system 工作台菜单(demo.html §6);按角色过滤(PRD §4 角色权限)
const allMenus = [
  { path: '/overview', title: '工作台', roles: ['*'] },
  { path: '/application/loan', title: '贷款利率申请', roles: ['customer_manager'] },
  { path: '/application/deposit', title: '存款利率申请', roles: ['customer_manager'] },
  // 利率审批:单独菜单(审批人/6人小组/行长);行长决策并入其中(详情按角色展示同意/一票否决)
  { path: '/approval', title: '利率审批', roles: ['branch_manager', 'dept_gm', 'vice_president', 'committee_member', 'president'] },
  // 贡献度跟踪:所有业务角色可见(审批人看自己审批过的客户,数据权限;6人小组/行长看全部)
  { path: '/commitment', title: '贡献度跟踪', roles: ['*'] },
  { path: '/history', title: '历史', roles: ['*'] },
  // 行长决策并入审批:行长工作台展示待审批,审批详情按角色展示同意/一票否决
  // 基础系统功能(管理端)
  { path: '/system/user', title: '用户管理', roles: ['admin'] },
  { path: '/system/role', title: '权限管理', roles: ['admin'] },
  { path: '/system/flow', title: '流程配置', roles: ['admin'] },
  // 参数管理:参数管理员维护草稿/配置复核人复核发布(admin 全量可见)
  { path: '/system/params', title: '参数管理', roles: ['param_admin', 'config_reviewer'] }
]

// 审批人角色:客户经理看到"历史申请",审批人看到"历史审批"
const isApprover = computed(() =>
  ['branch_manager', 'committee_member', 'president', 'dept_gm', 'vice_president'].includes(currentRole.value)
)
const menus = computed(() =>
  allMenus
    // admin 可见全部功能与数据
    .filter((m) => currentRole.value === 'admin' || m.roles.includes('*') || m.roles.includes(currentRole.value))
    .map((m) =>
      m.path === '/history'
        ? { ...m, title: isApprover.value ? '历史审批' : '历史申请' }
        : m
    )
)

const currentTitle = computed(() => (route.meta.title as string) || '')
const activePath = computed(() => route.path)

function onCommand(cmd: string) {
  if (cmd === 'logout') {
    userStore.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
/* 侧边栏固定,不随页面滚动;内容区独立滚动 */
.app-shell { min-height: 100vh; }
.app-sidebar {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  overflow-y: auto;
  z-index: 10;
}
.app-main {
  margin-left: 220px;
  min-height: 100vh;
}
.brand {
  padding: 14px 20px;
  border-bottom: 1px solid var(--color-border);
  margin-bottom: 8px;
}
.brand-title {
  font-weight: 700;
  font-size: 16px;
  color: var(--color-primary);
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: flex-end; /* 用户信息靠右 */
  padding: 0 0 16px;
}
.topbar__title {
  font-size: var(--fs-h2);
  font-weight: 600;
}
.user-name {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--color-text-sub);
}
</style>
