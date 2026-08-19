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
    // 改密页:layout 之外独立页(需改密用户在 layout 内任何页面都会被 1016 拦截,不能挂 layout)
    path: '/change-password',
    name: 'ChangePassword',
    component: () => import('@/views/login/change-password.vue'),
    meta: { title: '修改密码' }
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
        meta: { title: '我的工作台', icon: 'HomeFilled' }
      },
      {
        path: 'application/loan',
        name: 'ApplicationLoan',
        component: () => import('@/views/application/loan.vue'),
        meta: { title: '贷款利率申请', icon: 'EditPen', roles: ['customer_manager'] }
      },
      {
        path: 'application/deposit',
        name: 'ApplicationDeposit',
        component: () => import('@/views/application/deposit.vue'),
        meta: { title: '存款利率申请', icon: 'EditPen', roles: ['customer_manager'] }
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
        // 数据中心(§9.6 F8):技术监控数据仅 admin 可见
        path: 'datacenter',
        name: 'DataCenter',
        component: () => import('@/views/datacenter/index.vue'),
        meta: { title: '数据中心', icon: 'DataAnalysis', roles: ['admin'] }
      },
      {
        // 审计管理(§12.14):实际投票人反查/导出记录/配置版本查询,仅审计人员与 admin
        path: 'audit',
        name: 'Audit',
        component: () => import('@/views/audit/index.vue'),
        meta: { title: '审计管理', icon: 'View', roles: ['auditor', 'admin'] }
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
        // 机构管理(§5.1.1/§11.12):机构树 CRUD + 启停用,仅 admin
        path: 'system/dept',
        name: 'SysDept',
        component: () => import('@/views/system/dept.vue'),
        meta: { title: '机构管理', roles: ['admin'] }
      },
      {
        path: 'system/params',
        name: 'SysParams',
        component: () => import('@/views/system/params.vue'),
        // param_admin 角色已取消(详设):维护并入 admin,复核由 config_reviewer 承担
        meta: { title: '参数管理', roles: ['admin', 'config_reviewer'] }
      },
      {
        // Redis 缓存项配置(详设 §3.6):每项 TTL/写入开关,DB 覆盖立即生效不重启
        path: 'system/cache',
        name: 'SysCache',
        component: () => import('@/views/system/cache.vue'),
        meta: { title: '缓存配置', roles: ['admin'] }
      },
      {
        // 手工集团主数据(数仓未统计集团/成员手动补录 + 批复总额度,合并查询用;仅 admin)
        path: 'system/group',
        name: 'SysGroup',
        component: () => import('@/views/system/group.vue'),
        meta: { title: '集团管理', roles: ['admin'] }
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
  // 强制改密:需改密用户只能停留在改密页,其余页面一律弹回(主动访问也拦)
  if (to.path !== '/change-password' && userStore.userInfo?.pwdChangeFlag === '1') {
    next({ path: '/change-password', query: { redirect: to.fullPath } })
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
