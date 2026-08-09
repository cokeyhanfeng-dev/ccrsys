<template>
  <div class="app-shell">
    <!-- 侧边栏导航(design-system,深藏蓝) -->
    <aside class="app-sidebar">
      <div class="brand">
        <span class="brand-mark">
          <svg viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect x="3" y="14" width="5" height="13" rx="1.5" fill="#93c5fd"/>
            <rect x="13.5" y="8" width="5" height="19" rx="1.5" fill="#bfdbfe"/>
            <rect x="24" y="3" width="5" height="24" rx="1.5" fill="#ffffff"/>
          </svg>
        </span>
        <span class="brand-title">利率决策系统</span>
      </div>
      <nav class="app-sidebar__nav">
        <router-link
          v-for="item in menus"
          :key="item.path"
          :to="item.path"
          class="app-sidebar__item"
          :class="{ 'app-sidebar__item--active': route.path.startsWith(item.path) }"
        >
          <el-icon class="app-sidebar__icon" :size="17">
            <component
              :is="{
                '/overview': 'HomeFilled',
                '/application/loan': 'EditPen',
                '/application/deposit': 'Coin',
                '/approval': 'Stamp',
                '/commitment': 'Timer',
                '/history': 'Document',
                '/datacenter': 'DataAnalysis',
                '/audit': 'View',
                '/system/user': 'User',
                '/system/role': 'Key',
                '/system/dept': 'OfficeBuilding',
                '/system/flow': 'Share',
                '/system/params': 'Setting',
                '/system/cache': 'Coin'
              }[item.path] || 'Menu'"
            />
          </el-icon>
          <span>{{ item.title }}</span>
        </router-link>
      </nav>
      <div class="app-sidebar__foot">客户贡献度与利率决策系统</div>
    </aside>

    <div class="app-main">
      <!-- 顶栏:左侧当前页面名;右侧消息中心铃铛 + 用户信息 -->
      <div class="topbar">
        <div class="topbar__title">{{ route.meta.title || '工作台' }}</div>
        <div class="topbar__actions">
          <!-- 消息中心(§12.2):铃铛 + 未读 badge,点击开抽屉 -->
          <el-badge
            :value="unreadCount"
            :hidden="unreadCount === 0"
            :max="99"
            class="msg-badge"
          >
            <el-icon class="msg-bell" :size="20" @click="openDrawer"><Bell /></el-icon>
          </el-badge>
          <span class="topbar__divider"></span>
          <el-dropdown @command="onCommand">
            <span class="user-name">
              <span class="user-avatar">{{ (userStore.userInfo?.nickName || '用').slice(0, 1) }}</span>
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

      <router-view v-slot="{ Component }">
        <transition name="page-fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </div>

    <!-- 消息抽屉(§12.2):approval/result/warning/system 四类分档,未读高亮,点击已读并跳转 -->
    <el-drawer v-model="drawerVisible" title="消息中心" size="420px">
      <div class="msg-tabs">
        <button
          v-for="tab in msgTabs"
          :key="tab.key"
          class="msg-tab"
          :class="{ 'msg-tab--active': activeTab === tab.key }"
          @click="activeTab = tab.key"
        >
          {{ tab.label }}
          <span v-if="tabUnread(tab.key) > 0" class="msg-tab__count">{{ tabUnread(tab.key) }}</span>
        </button>
      </div>

      <div v-if="filteredLogs.length" class="msg-list">
        <div
          v-for="log in filteredLogs"
          :key="log.id"
          class="msg-item"
          :class="{ 'msg-item--unread': !log.receiptTime }"
          @click="onClickMessage(log)"
        >
          <span class="msg-item__icon" :class="`msg-item__icon--${classify(log)}`">
            {{ typeIcon(classify(log)) }}
          </span>
          <div class="msg-item__body">
            <div class="msg-item__content">
              <span v-if="!log.receiptTime" class="msg-item__dot"></span>
              {{ log.messageContent || '(无内容)' }}
            </div>
            <div class="msg-item__time">{{ fmtTime(log.createTime || log.sendTime) }}</div>
          </div>
        </div>
      </div>
      <div v-else class="msg-empty">暂无消息</div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { listNotificationLogs, receiptNotification, type NotificationLog } from '@/api/notification'

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
  // 数据中心(§9.6 F8):批次落地监控 + 数据源时效看板,全角色可见
  { path: '/datacenter', title: '数据中心', roles: ['*'] },
  // 审计管理(§12.14):审计人员专用(admin 全量可见)
  { path: '/audit', title: '审计管理', roles: ['auditor'] },
  // 基础系统功能(管理端)
  { path: '/system/user', title: '用户管理', roles: ['admin'] },
  { path: '/system/role', title: '权限管理', roles: ['admin'] },
  { path: '/system/dept', title: '机构管理', roles: ['admin'] },
  { path: '/system/flow', title: '流程配置', roles: ['admin'] },
  // 参数管理:管理员维护草稿/配置复核人复核发布(param_admin 角色已取消,并入 admin)
  { path: '/system/params', title: '参数管理', roles: ['admin', 'config_reviewer'] },
  // 缓存配置(§3.6):Redis 缓存项 TTL/写入开关,DB 覆盖立即生效
  { path: '/system/cache', title: '缓存配置', roles: ['admin'] }
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

// ---------- 消息中心(§12.2) ----------
type MsgType = 'approval' | 'result' | 'warning' | 'system'

const msgTabs: { key: MsgType; label: string }[] = [
  { key: 'approval', label: '审批流转' },
  { key: 'result', label: '审批结果' },
  { key: 'warning', label: '预警提醒' },
  { key: 'system', label: '系统通知' }
]

const logs = ref<NotificationLog[]>([])
const drawerVisible = ref(false)
const activeTab = ref<MsgType>('approval')
let pollTimer: ReturnType<typeof setInterval> | null = null

/** 按 trigger/消息内容归类:预警关键词优先,其次结果,再次审批流转,兜底系统通知 */
function classify(log: NotificationLog): MsgType {
  const text = `${log.messageKey || ''} ${log.messageContent || ''}`
  if (/预警|低于阈值|未达成|逾期|异常|达成率/.test(text)) return 'warning'
  if (/已通过|被否决|审批结果|办结|决议/.test(text)) return 'result'
  if (/待办|待审批|待决策|审批|表决|决策|提交/.test(text)) return 'approval'
  return 'system'
}

function typeIcon(t: MsgType) {
  const map: Record<MsgType, string> = { approval: '审', result: '结', warning: '警', system: '统' }
  return map[t]
}

const unreadCount = computed(() => logs.value.filter((l) => !l.receiptTime).length)
const filteredLogs = computed(() => logs.value.filter((l) => classify(l) === activeTab.value))
const tabUnread = (key: MsgType) =>
  logs.value.filter((l) => !l.receiptTime && classify(l) === key).length

async function loadLogs() {
  const userId = userStore.userInfo?.userId
  if (!userId) return
  try {
    logs.value = await listNotificationLogs({ recipientId: String(userId) })
  } catch {
    // 拦截器已统一提示;静默保持旧数据
  }
}

function openDrawer() {
  drawerVisible.value = true
  loadLogs()
}

/** 点击消息:未读先登记回执,再按类型跳转对应页面(§12.2) */
async function onClickMessage(log: NotificationLog) {
  if (!log.receiptTime) {
    try {
      await receiptNotification(log.id)
      log.receiptTime = new Date().toISOString()
    } catch {
      // 回执失败不阻断跳转
    }
  }
  const type = classify(log)
  const appId = extractAppId(log)
  drawerVisible.value = false
  if (type === 'approval') {
    router.push(appId ? `/approval/${appId}` : '/approval')
  } else if (type === 'result') {
    router.push(appId ? `/history/archive/${appId}` : '/history')
  } else if (type === 'warning') {
    router.push('/commitment')
  }
  // system 类无跳转
}

/** 从 messageKey/内容中提取关联申请 id(供跳转档案/审批详情) */
function extractAppId(log: NotificationLog): string | null {
  const text = `${log.messageKey || ''} ${log.messageContent || ''}`
  const m = text.match(/(?:APP|申请[号:]?|application)[-_:#\s]*(\d{4,})/i)
  return m ? m[1] : null
}

function fmtTime(t?: string) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : ''
}

onMounted(() => {
  loadLogs()
  // 30 秒轮询未读消息
  pollTimer = setInterval(loadLogs, 30000)
})
onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})

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
.app-sidebar__nav {
  flex: 1;
  padding-top: 8px;
}
.app-sidebar__icon {
  flex: none;
}
.app-sidebar__foot {
  padding: 16px 20px 0;
  font-size: 11px;
  color: rgba(255, 255, 255, .32);
  letter-spacing: .5px;
}
.app-main {
  margin-left: 208px;
  min-height: 100vh;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 18px;
  border-bottom: 1px solid rgba(255, 255, 255, .08);
  margin-bottom: 8px;
}
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: rgba(255, 255, 255, .1);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, .16);
}
.brand-mark svg {
  width: 18px;
  height: 18px;
}
.brand-title {
  font-weight: 700;
  font-size: 15px;
  color: #fff;
  letter-spacing: 1px;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 20px;
  margin-bottom: 20px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
}
.topbar__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-main);
  display: flex;
  align-items: center;
}
.topbar__title::before {
  content: "";
  display: inline-block;
  width: 4px;
  height: 15px;
  margin-right: 8px;
  border-radius: 2px;
  background: var(--grad-primary);
}
.topbar__actions {
  display: inline-flex;
  align-items: center;
  gap: 18px;
}
.topbar__divider {
  width: 1px;
  height: 20px;
  background: var(--color-border);
}
.msg-badge :deep(.el-badge__content) {
  border: none;
}
.msg-bell {
  cursor: pointer;
  color: var(--color-text-sub);
  vertical-align: middle;
  transition: color .15s;
}
.msg-bell:hover {
  color: var(--color-primary);
}
.user-name {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--color-text-main);
  font-weight: 500;
}
.user-name .el-icon {
  color: var(--color-text-light);
}
.user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--grad-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  box-shadow: var(--shadow-primary);
}

/* 消息抽屉(§12.2:四类分档 + 未读高亮圆点) */
.msg-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--color-border);
}
.msg-tab {
  background: transparent;
  border: none;
  padding: 8px 10px;
  cursor: pointer;
  color: var(--color-text-sub);
  font-size: 13px;
  border-bottom: 2px solid transparent;
}
.msg-tab--active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: 600;
}
.msg-tab__count {
  display: inline-block;
  min-width: 16px;
  padding: 0 4px;
  margin-left: 4px;
  border-radius: 8px;
  background: var(--color-danger);
  color: #fff;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
}
.msg-item {
  display: flex;
  gap: 10px;
  padding: 10px 8px;
  border-radius: 6px;
  cursor: pointer;
}
.msg-item:hover {
  background: #f9fafb;
}
.msg-item--unread {
  background: var(--color-primary-light);
}
.msg-item--unread:hover {
  background: var(--color-primary-light);
}
.msg-item__icon {
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}
.msg-item__icon--approval { background: var(--color-primary-light); color: var(--color-primary); }
.msg-item__icon--result { background: var(--color-success-light); color: #047857; }
.msg-item__icon--warning { background: var(--color-warning-light); color: #92400e; }
.msg-item__icon--system { background: var(--color-disabled); color: var(--color-text-sub); }
.msg-item__body { flex: 1; min-width: 0; }
.msg-item__content {
  font-size: 13px;
  color: var(--color-text-main);
  white-space: pre-wrap;
  word-break: break-all;
}
.msg-item--unread .msg-item__content {
  font-weight: 600;
}
.msg-item__dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-danger);
  margin-right: 6px;
  vertical-align: middle;
}
.msg-item__time {
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-text-sub);
}
.msg-empty {
  padding: 40px 0;
  text-align: center;
  color: var(--color-text-sub);
  font-size: 13px;
}
</style>
