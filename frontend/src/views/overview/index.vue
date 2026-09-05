<template>
  <div>
    <div class="section-head">
      <div class="section-title">工作台</div>
      <InfoTip :content="roleHint" />
    </div>

    <!-- 欢迎卡(AntD Pro 工作台):头像 + 问候语(按时段) + 副信息;右侧统计项 -->
    <div class="welcome-card">
      <div class="welcome-card__main">
        <div class="welcome-card__avatar">{{ nickName.charAt(0) }}</div>
        <div class="welcome-card__intro">
          <div class="welcome-card__greet">{{ greeting }}，{{ nickName }}，开始你一天的工作吧</div>
          <div class="welcome-card__meta">
            {{ roleName }}<span v-if="orgId"> · {{ orgName || '机构 #' + orgId }}</span>
            · 今日待办 {{ todoItems.length }} 项 · {{ dateText }}
          </div>
        </div>
      </div>
      <div class="welcome-card__stats">
        <div class="ws-item">
          <span class="ws-item__label">在途申请</span>
          <b class="ws-item__num">{{ inProgressCount }}</b>
        </div>
        <div class="ws-item">
          <span class="ws-item__label">今日已办</span>
          <b class="ws-item__num">{{ todayDoneCount }}</b>
        </div>
        <div class="ws-item">
          <span class="ws-item__label">承诺达成率</span>
          <b class="ws-item__num">{{ planTotal ? Math.round((trackStats.met / planTotal) * 100) + '%' : '—' }}</b>
        </div>
      </div>
    </div>

    <!-- KPI 统计卡(按角色差异化) -->
    <div class="stat-grid" style="margin-top:16px">
      <div
        class="stat-card stat-card--link"
        :class="'stat-card--tone' + (i % 3)"
        v-for="(s, i) in stats"
        :key="s.label"
        :tabindex="s.to ? 0 : undefined"
        :role="s.to ? 'link' : undefined"
        :aria-label="s.to ? ('前往' + s.label) : undefined"
        @click="s.to && router.push(s.to)"
        @keydown.enter="s.to && router.push(s.to)"
      >
        <div class="stat-card__icon">
          <el-icon :size="20"><component :is="s.icon" /></el-icon>
        </div>
        <div class="stat-card__body">
          <span class="stat-card__label">{{ s.label }}</span>
          <b class="stat-card__num" :class="s.cls">{{ s.value }}</b>
          <div class="stat-card__sub" :class="{ 'stat-card__sub--danger': s.subDanger }" v-if="s.sub">{{ s.sub }}</div>
        </div>
        <el-icon v-if="s.to" class="stat-card__arrow"><ArrowRight /></el-icon>
      </div>
    </div>

    <!-- 主区:左栏工作区(我的申请动态/待我处理),右栏快捷操作 + 贡献度概况 -->
    <div class="workbench-grid">
      <!-- ============ 左栏 ============ -->
      <div class="workbench-grid__left">
        <!-- 客户经理:我的申请动态 -->
        <div class="card workbench-card" v-if="role === 'customer_manager'">
          <div class="card-toolbar">
            <span class="card-toolbar__title">我的申请动态</span>
            <span class="card-toolbar__actions">
              <button class="card-link" @click="router.push('/history')">全部 →</button>
            </span>
          </div>
          <table class="table" v-if="myApps.length">
            <thead>
              <tr><th>申请号</th><th>业务类型</th><th>状态</th><th>当前节点</th><th>到达当前节点</th><th>申请时间</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="a in myApps" :key="a.id ?? a.applicationNo">
                <td>{{ a.applicationNo || '—' }}</td>
                <td>{{ businessTypeText(a.businessType) }}</td>
                <td><span :class="appBadge(a.status)">{{ appStatusText(a.status) }}</span></td>
                <td>{{ a.currentNodeText || '—' }}</td>
                <td>{{ fmtTime(a.nodeReachTime) }}</td>
                <td>{{ fmtTime(a.createTime || a.submitTime) }}</td>
                <td>
                  <button class="btn btn--text" @click="router.push(`/history/archive/${a.id}`)">查看档案</button>
                  <button
                    v-if="a.status === 'REJECTED'"
                    class="btn btn--text"
                    @click="router.push(`/application/loan?reapply=${a.id}`)"
                  >重新发起</button>
                </td>
              </tr>
            </tbody>
          </table>
          <div class="empty-line" v-else>暂无进行中的申请，已完成申请请在「历史申请」中查看</div>
        </div>

        <!-- 其他角色:待我处理(审批/表决/决策聚合) -->
        <div class="card workbench-card" v-else>
          <div class="card-toolbar">
            <span class="card-toolbar__title">待我处理</span>
            <span class="card-toolbar__actions"><span class="badge badge--info">{{ todoItems.length }} 项</span></span>
          </div>
          <div class="todo-list" v-if="todoItems.length">
            <div class="todo-card" v-for="t in todoItems" :key="t.key">
              <div class="todo-card__body">
                <div class="todo-card__customer">
                  {{ t.title }}
                  <span class="badge" :class="t.kindBadge">{{ t.kindText }}</span>
                </div>
                <div class="todo-card__sub">{{ t.sub ? t.sub : `分项 ${t.itemNo} · ${t.nodeText}` + (t.time ? ` · ${t.time}` : '') }}</div>
                <div class="todo-card__grid">
                  <div class="tc-item"><span class="dg-label">金额</span><b>{{ t.amount }}</b></div>
                  <div class="tc-item"><span class="dg-label">申请利率</span><b>{{ t.rate }}</b></div>
                  <div class="tc-item"><span class="dg-label">产品</span><b>{{ t.product }}</b></div>
                  <div class="tc-item" v-if="t.extra"><span class="dg-label">{{ t.extra.label }}</span><b>{{ t.extra.value }}</b></div>
                </div>
              </div>
              <div class="todo-card__action">
                <button class="btn btn--primary btn-sm" @click="router.push(t.to)">{{ t.actionText }}</button>
              </div>
            </div>
          </div>
          <div class="empty-line" v-else>{{ todoEmptyText }}</div>
        </div>
      </div>

      <!-- ============ 右栏 ============ -->
      <div class="workbench-grid__right">
        <!-- 快捷操作(原「发起新申请」入口收敛于此,仍仅客户经理可见) -->
        <div class="card workbench-card" v-if="role === 'customer_manager'">
          <div class="card-toolbar">
            <span class="card-toolbar__title">快捷操作</span>
          </div>
          <div class="quick-actions">
            <button class="btn btn--primary" @click="router.push('/application/loan')">
              <el-icon><Plus /></el-icon>&nbsp;发起新申请
            </button>
          </div>
        </div>

        <!-- 贡献度跟踪概况 -->
        <div class="card workbench-card">
          <div class="card-toolbar">
            <span class="card-toolbar__title">贡献度跟踪概况</span>
            <span class="card-toolbar__actions">
              <button class="card-link" @click="router.push('/commitment')">全部 →</button>
            </span>
          </div>
          <template v-if="planTotal">
            <div class="dist-row" v-for="d in distList" :key="d.label">
              <span class="dist-row__label">{{ d.label }}</span>
              <div class="dist-row__bar"><i :style="{ width: d.pct + '%', background: d.color }"></i></div>
              <b class="dist-row__num" :style="{ color: d.color }">{{ d.count }}</b>
            </div>
          </template>
          <div class="empty-line" v-else>暂无承诺跟踪</div>

          <template v-if="atRiskTop.length">
            <div class="risk-title">待关注(前 5)</div>
            <div class="risk-item" v-for="(r, i) in atRiskTop" :key="i" @click="router.push('/commitment')" tabindex="0" role="link" aria-label="前往贡献度跟踪" @keydown.enter="router.push('/commitment')">
              <div class="risk-item__main">
                <b>{{ r.customer }}</b>
                <span class="dg-label">{{ r.metric }}</span>
              </div>
              <span class="risk-item__ratio">{{ r.ratio }}</span>
            </div>
          </template>
        </div>

      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { get } from '@/api/request'
import { listApprovalTasks, listTodayDone, pageApprovalHistory } from '@/api/approval'
import { listVoteTodo, listPresidentTodo } from '@/api/vote'
import { listCommitmentTracks } from '@/api/commitment'
import {
  appStatusText, roleText, productName,
  businessTypeText, nodeLabel, actionText, metricName
} from '@/utils/dict'

const router = useRouter()
const userStore = useUserStore()

const role = computed(() => userStore.userInfo?.roles?.[0] || 'customer_manager')
// §D-7 兼岗:roles 含 committee_member(含主角色非委员被配置进小组名单)即按委员识别
const isCommittee = computed(() => (userStore.userInfo?.roles || []).includes('committee_member'))
const nickName = computed(() => userStore.userInfo?.nickName || userStore.userInfo?.userName || '同事')
const orgId = computed(() => userStore.userInfo?.orgId)
const orgName = computed(() => userStore.userInfo?.orgName)
const roleName = computed(() => roleText(role.value, role.value))

const APPROVAL_ROLES = ['branch_manager', 'dept_gm', 'vice_president']

const roleHint = computed(() => {
  const map: Record<string, string> = {
    customer_manager: '我的申请进度与贡献度承诺一览',
    branch_manager: '当前需要我处理的工作、已办与贡献度跟踪概况',
    dept_gm: '当前需要我处理的工作、已办与贡献度跟踪概况',
    vice_president: '当前需要我处理的工作、已办与贡献度跟踪概况',
    committee_member: '当前需要我处理的工作、已办与贡献度跟踪概况',
    president: '当前需要我处理的工作、已办与贡献度跟踪概况',
    admin: '全行在途业务与贡献度跟踪概况',
    auditor: '全行在途业务与贡献度跟踪概况'
  }
  return map[role.value] || '当前需要我处理的工作、已办与贡献度跟踪概况'
})

// ---------- 欢迎区:问候语 + 日期(跨零点自动刷新,§UI审查) ----------
const now = ref(new Date())
const greeting = computed(() => {
  const h = now.value.getHours()
  if (h < 6) return '凌晨好'
  if (h < 9) return '早上好'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const WEEK = ['日', '一', '二', '三', '四', '五', '六']
const dateDay = computed(() => `${now.value.getMonth() + 1}月${now.value.getDate()}日`)
const dateText = computed(() => `${now.value.getFullYear()}年${now.value.getMonth() + 1}月${now.value.getDate()}日 星期${WEEK[now.value.getDay()]}`)

// ---------- 数据 ----------
const tasks = ref<any[]>([])          // 审批待办(支行行长/部门总经理/分管行长)
const voteTodos = ref<any[]>([])      // 委员待表决
const presidentTodos = ref<any[]>([]) // 行长待决策
const todayDoneCount = ref(0)         // 今日已办(后端统计:今日 action∪表决∪决策,§2026-09-05 与累计同口径)
const historyTotal = ref(0)           // 历史 total(累计已办)
const applications = ref<any[]>([])   // 客户经理本人申请 / admin 全量在途
const trackRows = ref<any[]>([])       // 承诺跟踪记录(v2:TRACKING/FINISHED_MET/FINISHED_UNMET)

function fmtTime(t?: string) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '—'
}

// ---------- 待我处理(聚合三类待办) ----------
const todoItems = computed(() => {
  const items: any[] = []
  // 待办以申请为粒度:同申请多分项聚合为一条待办,进入详情一次性完成
  const byApp = new Map<string, any[]>()
  for (const p of tasks.value) {
    const appId = p.applicationId || p.id
    if (!byApp.has(appId)) byApp.set(appId, [])
    byApp.get(appId)!.push(p)
  }
  for (const [appId, ps] of byApp) {
    const first = ps[0]
    const single = ps.length === 1
    const rates = ps.map((x) => Number(x.requestedRate) || 0)
    items.push({
      key: `task-${appId}`, kindText: '待审批', kindBadge: 'badge--processing',
      // 客户显示名优先(listTodo 已按申请补 customerName,§2026-09-01),回退客户号
      title: first.customerName || first.pricingCustomerNo || '—',
      itemNo: single ? (first.pricingItemNo || first.id) : `${ps.length} 个授信分项`,
      nodeText: nodeLabel(first.currentNodeCode),
      amount: single
        ? (first.pricingAmount != null ? `${first.pricingAmount} 万元` : '—')
        : `${ps.reduce((s, x) => s + (Number(x.pricingAmount) || 0), 0)} 万元`,
      rate: single
        ? (first.requestedRate != null ? `${first.requestedRate}%` : '—')
        : (rates.length ? `${Math.min(...rates)} ~ ${Math.max(...rates)}%` : '—'),
      product: productName(first.productCode),
      // 整单详情入口用申请 id(后端 /ccr/approval/{applicationId}/detail);待办项带 applicationId,勿传分项 id 否则 404
      time: fmtTime(first.createTime), to: `/approval/${first.applicationId || first.id}`, actionText: '去审批',
      extra: single ? null : { label: '授信分项', value: `${ps.length} 个` }
    })
  }
  // 委员待表决:同样按申请聚合(六人小组按整单表决,与普通审批一致的卡片形态)
  const byAppVote = new Map<string, any[]>()
  for (const p of voteTodos.value) {
    const appId = p.applicationId || p.roundId
    if (!byAppVote.has(appId)) byAppVote.set(appId, [])
    byAppVote.get(appId)!.push(p)
  }
  for (const [appId, ps] of byAppVote) {
    const first = ps[0]
    const single = ps.length === 1
    const rates = ps.map((x) => Number(x.requestedRate) || 0)
    items.push({
      // §委员工作台:表决条目并入审批待办展示(用户口径:无「表决」概念),kind/badge/按钮与审批待办一致
      key: `vote-${appId}`, kindText: '待审批', kindBadge: 'badge--processing',
      // 客户显示名优先(listVoteTodo 同 listTodo 口径带 customerName),回退客户号
      title: first.customerName || first.pricingCustomerNo || first.customerNo || '—',
      itemNo: single ? (first.pricingItemNo || first.pricingItemId) : `${ps.length} 个授信分项`,
      nodeText: nodeLabel(first.currentNodeCode),
      amount: single
        ? (first.pricingAmount != null ? `${first.pricingAmount} 万元` : '—')
        : `${ps.reduce((s, x) => s + (Number(x.pricingAmount) || 0), 0)} 万元`,
      rate: single
        ? (first.requestedRate != null ? `${first.requestedRate}%` : '—')
        : (rates.length ? `${Math.min(...rates)} ~ ${Math.max(...rates)}%` : '—'),
      product: productName(first.productCode),
      // 委员待办:同样用申请 id 进整单详情(分项 id 会导致 404)
      time: fmtTime(first.createTime), to: `/approval/${first.applicationId || first.pricingItemId}`, actionText: '去审批',
      sub: `申请 ${first.applicationNo || '—'} · ${nodeLabel(first.currentNodeCode)}`,
      extra: single ? null : { label: '授信分项', value: `${ps.length} 个` }
    })
  }
  for (const p of presidentTodos.value) {
    // 后端按申请聚合:申请级字段在顶层(含 customerName/submitTime,§2026-09-02),分项级(利率/计票/编号)在 items[]
    // 行长待办与审批/委员同构:整单粒度展示(多分项金额求和/利率区间/待决策分项数),点卡直达整单详情,外层锚点页签与审批一致
    const ps = p.items || []
    const first = ps[0] || {}
    const single = ps.length === 1
    const rates = ps.map((x) => Number(x.requestedRate) || 0)
    items.push({
      key: `president-${p.applicationId || first.pricingItemId || 'x'}`,
      kindText: '待决策', kindBadge: 'badge--info',
      // 客户/集团显示名(与审批待办同口径:快照 customerName/集团 groupName),回退客户号
      title: p.customerName || p.customerNo || '—',
      itemNo: single ? (first.pricingItemNo || first.pricingItemId) : `${ps.length} 个待决策分项`,
      nodeText: nodeLabel('PRESIDENT'),
      amount: single
        ? (first.pricingAmount != null ? `${first.pricingAmount} 万元` : '—')
        : `${ps.reduce((s, x) => s + (Number(x.pricingAmount) || 0), 0)} 万元`,
      rate: single
        ? (first.requestedRate != null ? `${first.requestedRate}%` : '—')
        : (rates.length ? `${Math.min(...rates)} ~ ${Math.max(...rates)}%` : '—'),
      product: productName(first.productCode),
      time: p.submitTime ? fmtTime(p.submitTime) : '',
      // §2026-09-02 行长整单审批与审批/委员同链:直达整单详情(外层锚点页签一致),行长决策卡在详情内按角色渲染;/president 行长工作台保留为返回地
      to: `/approval/${p.applicationId || first.pricingItemId || ''}`, actionText: '去决策',
      extra: { label: '表决结果', value: `赞成 ${first.approveCount ?? 0} / 反对 ${first.rejectCount ?? 0}` }
    })
  }
  return items
})

const todoEmptyText = computed(() => {
  const map: Record<string, string> = {
    committee_member: '暂无待表决批次,新的表决批次发起后会出现在这里',
    president: '暂无待决策分项,小组表决通过后会出现在这里',
    admin: '暂无待处理任务',
    auditor: '暂无待处理任务'
  }
  return map[role.value] || '暂无待审批分项,新申请提交后会出现在这里'
})

// 动态列表隐藏状态:草稿(未提交,可继续编辑) + 终态(已完成:FINAL/VETOED/APPROVED/REJECTED/CLOSED),
// 已完成申请进历史档案查看/重提(§12.10),不再占用申请动态列表
const APP_DYNAMIC_HIDDEN = ['DRAFT', 'FINAL', 'VETOED', 'APPROVED', 'REJECTED', 'CLOSED']

// ---------- 客户经理:我的申请动态(仅显示未完成,终态申请看历史档案) ----------
const myApps = computed(() =>
  [...applications.value]
    .filter((a) => a.status && !APP_DYNAMIC_HIDDEN.includes(a.status))
    .sort((a, b) => Number(b.id || 0) - Number(a.id || 0)))
const inProgressCount = computed(
  () => applications.value.filter((a) => a.status && !APP_DYNAMIC_HIDDEN.includes(a.status)).length
)
const rejectedCount = computed(
  () => applications.value.filter((a) => a.status === 'REJECTED').length
)

function appBadge(status?: string) {
  const map: Record<string, string> = {
    APPROVED: 'badge badge--approved', PARTIAL_APPROVED: 'badge badge--approved',
    PROCESSING: 'badge badge--processing', SUBMITTING: 'badge badge--processing',
    REJECTED: 'badge badge--rejected', DRAFT: 'badge badge--neutral', CLOSED: 'badge badge--neutral'
  }
  return map[status || ''] || 'badge badge--neutral'
}

// ---------- 贡献度跟踪(v2:track 记录,TRACKING/FINISHED_MET/FINISHED_UNMET) ----------
// trackRows 声明见上方「数据」区(§217),此处不重复声明

const trackStats = computed(() => {
  const rows = trackRows.value
  return {
    tracking: rows.filter((r) => r.status === 'TRACKING').length,
    met: rows.filter((r) => r.status === 'FINISHED_MET').length,
    unmet: rows.filter((r) => r.status === 'FINISHED_UNMET').length
  }
})

const planTotal = computed(() => trackRows.value.length)

// 状态分布条:跟踪中/已完成/未完成
const distList = computed(() => {
  const s = trackStats.value
  const entries = [
    { label: '跟踪中', count: s.tracking, color: 'var(--color-primary)' },
    { label: '已完成', count: s.met, color: 'var(--color-success)' },
    { label: '未完成', count: s.unmet, color: 'var(--color-danger)' }
  ]
  const max = Math.max(...entries.map((e) => e.count), 1)
  return entries.map((e) => ({ ...e, pct: Math.max(Math.round((e.count / max) * 100), e.count ? 6 : 0) }))
})

// 待关注前 5 条(未完成优先,其次跟踪中实时达成率升序;达成率 null 视为暂无数据排最后)
const atRiskTop = computed(() => {
  const rows: any[] = []
  const ratioOf = (r: any) => {
    const ratio = r.status === 'TRACKING' ? r.ratio : r.finalRatio
    return ratio == null ? Number.POSITIVE_INFINITY : Number(ratio)
  }
  const unmet = trackRows.value.filter((r) => r.status === 'FINISHED_UNMET')
  const tracking = trackRows.value.filter((r) => r.status === 'TRACKING')
      .sort((a, b) => ratioOf(a) - ratioOf(b))
  for (const r of [...unmet, ...tracking]) {
    if (rows.length >= 5) break
    const ratio = r.status === 'TRACKING' ? r.ratio : r.finalRatio
    rows.push({
      customer: r.customerName || r.memberCustomerNo || r.customerNo || '(未知)',
      metric: `${metricName(r.metricCode)}${r.status === 'FINISHED_UNMET' ? '(未完成)' : ''}`,
      ratio: ratio != null ? `${(Number(ratio) * 100).toFixed(1)}%` : '暂无数据'
    })
  }
  return rows
})


// ---------- KPI 卡(按角色差异化) ----------
// 审批中=复合多状态(与历史申请页筛选/后端 status IN 口径一致;§2026-08-26 统计卡点击跳转历史并自动筛选)
const IN_PROGRESS_STATUS = 'ROUTING,SUBMITTED,SUBMITTING,APPROVED_LEVEL,PROCESSING,VOTING,COMMITTEE_PASS,PRESIDENT_DECISION'
const stats = computed(() => {
  const s = trackStats.value
  const trackCard = {
    icon: 'Timer', label: '贡献度跟踪', value: s.tracking,
    cls: 'stat-card__num--warning', to: '/commitment',
    sub: s.unmet ? `到期未完成 ${s.unmet} 项` : '暂无到期未完成', subDanger: s.unmet > 0
  }
  const todayCard = {
    icon: 'CircleCheck', label: '今日已办', value: todayDoneCount.value,
    cls: 'stat-card__num--success', to: '/history', sub: '本人今日办理的任务', subDanger: false
  }
  const totalCard = {
    icon: 'Finished', label: '累计已办', value: historyTotal.value,
    cls: 'stat-card__num--primary', to: '/history', sub: '本人审批/表决/决策过的申请', subDanger: false
  }
  const r = role.value
  if (r === 'customer_manager') {
    return [
      { icon: 'Document', label: '我的申请', value: applications.value.length, cls: 'stat-card__num--primary', to: '/history', sub: '本人发起的全部申请', subDanger: false },
      { icon: 'Loading', label: '审批中', value: inProgressCount.value, cls: 'stat-card__num--primary', to: `/history?status=${IN_PROGRESS_STATUS}`, sub: '正在流转审批的申请', subDanger: false },
      { icon: 'RefreshLeft', label: '被否决/可重提', value: rejectedCount.value, cls: rejectedCount.value ? 'stat-card__num--danger' : '', to: '/history?status=REJECTED', sub: '终态否决,可重新发起', subDanger: rejectedCount.value > 0 },
      trackCard
    ]
  }
  if (r === 'admin' || r === 'auditor') {
    return [
      { icon: 'Document', label: '在途申请', value: inProgressCount.value, cls: 'stat-card__num--primary', to: `/history?status=${IN_PROGRESS_STATUS}`, sub: '全行流转中的申请', subDanger: false },
      { icon: 'Timer', label: '跟踪中承诺', value: s.tracking, cls: 'stat-card__num--primary', to: '/commitment', sub: '跟踪中的承诺指标', subDanger: false },
      { icon: 'Warning', label: '到期未完成', value: s.unmet, cls: s.unmet ? 'stat-card__num--danger' : '', to: '/commitment', sub: '承诺到期未达成', subDanger: s.unmet > 0 },
      todayCard
    ]
  }
  // §D-7 兼岗:主角色审批人/行长且 roles 含 committee_member 时同时展示对应待办卡
  const cards: any[] = []
  if (r === 'president') {
    cards.push({ icon: 'Stamp', label: '待我决策', value: presidentTodos.value.length, cls: 'stat-card__num--warning', to: '/president', sub: '小组通过后待行长决策', subDanger: false })
  } else if (APPROVAL_ROLES.includes(r) || isCommittee.value) {
    // §委员工作台:表决不再单设「待我表决」卡,并入「待我审批」——/approval 页已把普通审批待办与
    // 表决待办(listVoteTodo)按申请聚合展示,两类入口同一页面,故合并计数;去掉该卡后委员 5 卡变 4 卡不换行
    const mergeTodo = tasks.value.length + voteTodos.value.length
    cards.push({ icon: 'Stamp', label: '待我审批', value: mergeTodo, cls: 'stat-card__num--warning', to: '/approval', sub: isCommittee.value ? '待本人审批/表决的分项' : '流转到本人当前节点的分项', subDanger: false })
  }
  cards.push(todayCard, totalCard, trackCard)
  return cards
})

// ---------- 数据加载(每个接口独立容错) ----------
async function safe(fn: () => Promise<void>) {
  try { await fn() } catch { /* 单接口失败不影响整页 */ }
}

async function load() {
  const r = role.value
  const jobs: Promise<void>[] = [
    safe(async () => { trackRows.value = (await listCommitmentTracks()) || [] })
  ]
  if (r === 'customer_manager' || r === 'admin' || r === 'auditor') {
    jobs.push(safe(async () => { applications.value = (await get<any[]>('/ccr/applications')) || [] }))
  }
  if (r !== 'customer_manager') {
    jobs.push(safe(async () => { todayDoneCount.value = Number(await listTodayDone()) || 0 }))
    jobs.push(safe(async () => {
      const h = await pageApprovalHistory(1, 1)
      historyTotal.value = Number(h?.total) || 0
    }))
  }
  // §D-7 兼岗:审批角色与委员身份并行加载(兼岗用户两类待办同时出现)
  if (APPROVAL_ROLES.includes(r)) {
    jobs.push(safe(async () => { tasks.value = (await listApprovalTasks<any[]>()) || [] }))
  }
  if (r === 'president') {
    jobs.push(safe(async () => { presidentTodos.value = (await listPresidentTodo<any[]>()) || [] }))
  }
  if (isCommittee.value) {
    jobs.push(safe(async () => { voteTodos.value = (await listVoteTodo<any[]>()) || [] }))
  }
  await Promise.all(jobs)
}

let dateTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  load()
  // §UI审查:欢迎区日期跨零点自动刷新(每分钟校准一次)
  dateTimer = setInterval(() => { now.value = new Date() }, 60 * 1000)
})
onBeforeUnmount(() => { if (dateTimer) clearInterval(dateTimer) })
</script>

<style scoped>
/* ---------- 欢迎卡(AntD Pro 工作台:白卡 + 头像 + 问候 + 右侧统计项) ---------- */
.welcome-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  background: var(--color-surface);
  border-radius: var(--radius);
  padding: 24px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, .05);
}
.welcome-card__main { display: flex; align-items: center; gap: 16px; min-width: 0; }
.welcome-card__avatar {
  flex: none; width: 56px; height: 56px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: var(--grad-primary); color: #fff;
  font-size: 22px; font-weight: 600; user-select: none;
}
.welcome-card__intro { min-width: 0; }
.welcome-card__greet { font-size: 20px; font-weight: 600; color: var(--color-text-main); }
.welcome-card__meta { margin-top: 6px; font-size: 13px; color: var(--color-text-sub); }
/* 右侧统计项:label 灰小字 + 大数字,项间右分隔线 */
.welcome-card__stats { display: flex; align-items: center; flex: none; }
.ws-item { padding: 0 24px; text-align: right; }
.ws-item + .ws-item { border-left: 1px solid var(--color-border-light); }
.ws-item__label { font-size: 12px; color: var(--color-text-sub); }
.ws-item__num {
  display: block; margin-top: 4px;
  font-size: 24px; font-weight: 600; line-height: 1.3;
  font-variant-numeric: tabular-nums; color: var(--color-text-main);
}
/* 窄屏欢迎卡纵向堆叠,统计项回到左对齐 */
@media (max-width: 768px) {
  .welcome-card { flex-direction: column; align-items: flex-start; }
  .welcome-card__stats { width: 100%; }
  .ws-item { flex: 1; padding: 0 12px; text-align: left; }
  .ws-item:first-child { padding-left: 0; }
}

/* ---------- KPI 卡可点击(键盘可达,§UI审查) ---------- */
.stat-card--link { cursor: pointer; }
.stat-card--link:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.stat-card__arrow { color: var(--color-text-light); transition: color .15s, transform .15s; }
.stat-card--link:hover .stat-card__arrow { color: var(--color-primary); transform: translateX(2px); }
.stat-card__num--danger { color: var(--color-danger); }
.stat-card__sub--danger { color: var(--color-danger); }

/* ---------- 主区两栏(左 2fr 右 1fr,间距 16px;窄屏降单栏) ---------- */
.workbench-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
  margin-top: 16px;
}
@media (max-width: 1100px) {
  .workbench-grid { grid-template-columns: 1fr; }
}
.workbench-grid__left { display: flex; flex-direction: column; gap: 16px; }
.workbench-grid__right { display: flex; flex-direction: column; gap: 16px; }

/* ---------- 卡片规范:内边距 24px;卡头 16px 600 + 底部 1px #f0f0f0 分隔线 ---------- */
.workbench-card { margin-bottom: 0; padding: 24px; }
.workbench-card .card-toolbar {
  margin-bottom: 16px; padding-bottom: 12px;
  border-bottom: 1px solid var(--color-border-light);
}
.workbench-card .card-toolbar__title { font-size: 16px; font-weight: 600; }
/* 卡头右上「全部 →」链接 */
.card-link {
  background: none; border: none; padding: 0; cursor: pointer;
  font-size: 13px; color: var(--color-text-sub);
  transition: color .15s;
}
.card-link:hover { color: var(--color-primary); }
.card-link:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }

/* 快捷操作按钮组 */
.quick-actions { display: flex; flex-wrap: wrap; gap: 12px; }

/* 工作台内表格行高放宽(上下不再贴紧) */
.workbench-card .table th,
.workbench-card .table td { padding: 14px 14px; }

/* ---------- 待办列表 ---------- */
.todo-list { display: flex; flex-direction: column; gap: 12px; }
.todo-card__body { flex: 1; min-width: 0; }
.todo-card__customer {
  font-weight: 600; font-size: 15px;
  display: flex; align-items: center; gap: 8px;
  transition: color .15s;
}
/* 待办行 hover 时标题变品牌蓝 */
.todo-card:hover .todo-card__customer { color: var(--color-primary); }
.todo-card__sub { font-size: 13px; color: var(--color-text-sub); margin: 2px 0 10px; }
.todo-card__grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 6px 16px; font-size: 14px; }
@media (max-width: 1100px) { .todo-card__grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 768px) { .todo-card__grid { grid-template-columns: 1fr; } }
.todo-card__action { flex: none; display: flex; align-items: center; gap: 8px; margin-left: 16px; }
/* 窄屏待办卡操作区换行到内容下方 */
@media (max-width: 768px) {
  .todo-card { flex-direction: column; align-items: stretch; }
  .todo-card__action { margin-left: 0; margin-top: 10px; }
}
.dg-label { color: var(--color-text-sub); margin-right: 6px; }
.btn-sm { padding: 4px 10px; font-size: 13px; }

/* ---------- 贡献度分布条 ---------- */
.dist-row { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; font-size: 13px; }
.dist-row__label { flex: none; width: 60px; color: var(--color-text-sub); }
.dist-row__bar {
  flex: 1; height: 8px; border-radius: 4px;
  background: var(--color-border-light); overflow: hidden;
}
.dist-row__bar i { display: block; height: 100%; border-radius: 4px; transition: width .3s var(--ease, ease); }
.dist-row__num { flex: none; width: 28px; text-align: right; font-variant-numeric: tabular-nums; }

/* ---------- 待关注(未达标项比率用警示橙) ---------- */
.risk-title { font-size: 13px; font-weight: 600; color: var(--color-text-sub); margin: 18px 0 10px; }
.risk-item {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 10px 12px; border-radius: var(--radius-sm, 8px);
  cursor: pointer; transition: background .15s;
}
.risk-item + .risk-item { margin-top: 4px; }
.risk-item:hover { background: var(--color-primary-light); }
.risk-item:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.risk-item__main { min-width: 0; display: flex; align-items: baseline; gap: 8px; }
.risk-item__main b { font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.risk-item__main .dg-label { font-size: 12px; margin-right: 0; }
.risk-item__ratio { flex: none; font-weight: 600; color: var(--color-warning); font-variant-numeric: tabular-nums; }

</style>
