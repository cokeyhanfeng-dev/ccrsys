import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'

// 路由菜单参照 demo(v3.3-html-demo)8 大页面组织
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/',
    component: () => import('@/layout/index.vue'),
    redirect: '/overview',
    children: [
      {
        path: 'overview',
        name: 'Overview',
        component: () => import('@/views/overview/index.vue'),
        meta: { title: '我的申请工作台', icon: 'HomeFilled' }
      },
      {
        path: 'application/loan',
        name: 'ApplicationLoan',
        component: () => import('@/views/application/loan.vue'),
        meta: { title: '贷款利率申请', icon: 'EditPen' }
      },
      {
        path: 'application/deposit',
        name: 'ApplicationDeposit',
        component: () => import('@/views/application/deposit.vue'),
        meta: { title: '存款利率申请', icon: 'EditPen' }
      },
      {
        path: 'approval',
        name: 'Approval',
        component: () => import('@/views/approval/index.vue'),
        meta: { title: '审批', icon: 'Checked' }
      },
      {
        path: 'approval/:id',
        name: 'ApprovalDetail',
        component: () => import('@/views/approval/detail.vue'),
        meta: { title: '审批详情' }
      },
      {
        path: 'voting',
        name: 'Voting',
        component: () => import('@/views/voting/index.vue'),
        meta: { title: '六人表决', icon: 'Key' }
      },
      {
        path: 'president',
        name: 'President',
        component: () => import('@/views/president/index.vue'),
        meta: { title: '行长决策', icon: 'Stamp' }
      },
      {
        path: 'commitment',
        name: 'Commitment',
        component: () => import('@/views/commitment/index.vue'),
        meta: { title: '贡献度跟踪', icon: 'Timer' }
      },
      {
        path: 'history',
        name: 'History',
        component: () => import('@/views/history/index.vue'),
        meta: { title: '历史', icon: 'Document' }
      },
      {
        path: 'history/archive/:id',
        name: 'HistoryArchive',
        component: () => import('@/views/history/archive.vue'),
        meta: { title: '申请档案' }
      },
      {
        path: 'system/user',
        name: 'SysUser',
        component: () => import('@/views/system/user.vue'),
        meta: { title: '用户管理', roles: ['admin'] }
      },
      {
        path: 'system/role',
        name: 'SysRole',
        component: () => import('@/views/system/role.vue'),
        meta: { title: '权限管理', roles: ['admin'] }
      },
      {
        path: 'system/flow',
        name: 'SysFlow',
        component: () => import('@/views/system/flow.vue'),
        meta: { title: '流程配置', roles: ['admin'] }
      },
      {
        path: 'system/params',
        name: 'SysParams',
        component: () => import('@/views/system/params.vue'),
        meta: { title: '参数管理', roles: ['admin', 'param_admin', 'config_reviewer'] }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/overview'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 登录守卫 + 角色守卫(meta.roles 与登录角色比对,admin 放行全部)
router.beforeEach((to, _from, next) => {
  const userStore = useUserStore()
  if (to.path === '/login') {
    next()
    return
  }
  if (!userStore.token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  const needRoles = to.meta.roles as string[] | undefined
  if (needRoles?.length) {
    const role = userStore.userInfo?.roles?.[0] || ''
    if (role !== 'admin' && !needRoles.includes(role)) {
      ElMessage.warning('无权限访问该页面')
      next({ path: '/overview' })
      return
    }
  }
  next()
})

export default router
