<template>
  <div>
    <div class="section-head">
      <div class="section-title">工作台</div>
      <InfoTip :content="roleHint" />
    </div>

    <!-- 欢迎区:问候语(按时段) + 姓名/角色/机构 + 日期 -->
    <div class="welcome-card">
      <div class="welcome-card__main">
        <div class="welcome-card__greet">{{ greeting }},{{ nickName }}</div>
        <div class="welcome-card__meta">
          {{ roleName }}<span v-if="orgId"> · 机构 #{{ orgId }}</span>
        </div>
      </div>
      <div class="welcome-card__date">
        <div class="welcome-card__day">{{ dateDay }}</div>
        <div class="welcome-card__date-text">{{ dateText }}</div>
      </div>
    </div>

    <!-- KPI 统计卡(按角色差异化) -->
    <div class="stat-grid" style="margin-top:16px">
      <div
        class="stat-card stat-card--link"
        :class="'stat-card--tone' + (i % 3)"
        v-for="(s, i) in stats"
        :key="s.label"
        @click="s.to && router.push(s.to)"
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

    <!-- 主区:左栏当前工作,右栏贡献度概况 -->
    <div class="workbench-grid">
      <!-- ============ 左栏 ============ -->
      <div class="workbench-grid__left">
        <!-- 客户经理:我的申请动态 -->
        <div class="card workbench-card" v-if="role === 'customer_manager'">
          <div class="card__head">
            <span>我的申请动态</span>
            <button class="btn btn--primary btn-sm" @click="router.push('/application/loan')">
              <el-icon><Plus /></el-icon>&nbsp;发起新申请
            </button>
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
          <div class="empty" v-else>暂无申请记录,点击右上角"发起新申请"开始</div>
        </div>

        <!-- 其他角色:待我处理(审批/表决/决策聚合) -->
        <div class="card workbench-card" v-else>
          <div class="card__head">
            <span>待我处理</span>
            <span class="badge badge--info">{{ todoItems.length }} 项</span>
          </div>
          <div class="todo-list" v-if="todoItems.length">
            <div class="todo-card" v-for="t in todoItems" :key="t.key">
              <div class="todo-card__body">
                <div class="todo-card__customer">
                  {{ t.title }}
                  <span class="badge" :class="t.kindBadge">{{ t.kindText }}</span>
                </div>
                <div class="todo-card__sub">{{ t.sub ? t.sub : `分项 ${t.itemNo} · ${t.nodeText} · ${t.time}` }}</div>
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
          <div class="empty" v-else>{{ todoEmptyText }}</div>
        </div>
      </div>

      <!-- ============ 右栏 ============ -->
      <div class="workbench-grid__right">
        <!-- 贡献度跟踪概况 -->
        <div class="card workbench-card">
          <div class="card__head">
            <span>贡献度跟踪概况</span>
            <button class="btn btn--text" @click="router.push('/commitment')">查看全部</button>
          </div>
          <template v-if="planTotal">
            <div class="dist-row" v-for="d in distList" :key="d.label">
              <span class="dist-row__label">{{ d.label }}</span>
              <div class="dist-row__bar"><i :style="{ width: d.pct + '%', background: d.color }"></i></div>
              <b class="dist-row__num" :style="{ color: d.color }">{{ d.count }}</b>
            </div>
          </template>
          <div class="empty" v-else>暂无承诺计划</div>

          <template v-if="atRiskTop.length">
            <div class="risk-title">有风险计划(前 5)</div>
            <div class="risk-item" v-for="(r, i) in atRiskTop" :key="i" @click="router.push('/commitment')">
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
import { listApprovalDone } from '@/api/approval2'
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { get } from '@/api/request'
import { listApprovalTasks, pageApprovalHistory } from '@/api/approval'
import { listVoteTodo, listPresidentTodo } from '@/api/vote'
import { listCommitmentPlans } from '@/api/commitment'
import {
  appStatusText, roleText, productName,
  businessTypeText, nodeLabel, actionText, metricName
} from '@/utils/dict'

const router = useRouter()
const userStore = useUserStore()

const role = computed(() => userStore.userInfo?.roles?.[0] || 'customer_manager')
const nickName = computed(() => userStore.userInfo?.nickName || userStore.userInfo?.userName || '同事')
const orgId = computed(() => userStore.userInfo?.orgId)
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

// ---------- 欢迎区:问候语 + 日期 ----------
const now = new Date()
const greeting = computed(() => {
  const h = now.getHours()
  if (h < 6) return '凌晨好'
  if (h < 9) return '早上好'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const WEEK = ['日', '一', '二', '三', '四', '五', '六']
const dateDay = `${now.getMonth() + 1}月${now.getDate()}日`
const dateText = `${now.getFullYear()}年${now.getMonth() + 1}月${now.getDate()}日 星期${WEEK[now.getDay()]}`
const todayStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`

// ---------- 数据 ----------
const tasks = ref<any[]>([])          // 审批待办(支行行长/部门总经理/分管行长)
const voteTodos = ref<any[]>([])      // 委员待表决
const presidentTodos = ref<any[]>([]) // 行长待决策
const doneRows = ref<any[]>([])       // 本人已办
const historyTotal = ref(0)           // 历史 total(累计已办)
const applications = ref<any[]>([])   // 客户经理本人申请 / admin 全量在途
const planRows = ref<any[]>([])       // 承诺计划(按指标打平的行)

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
      title: first.pricingCustomerNo || '—',
      itemNo: single ? (first.pricingItemNo || first.id) : `${ps.length} 个担保分项`,
      nodeText: nodeLabel(first.currentNodeCode),
      amount: single
        ? (first.pricingAmount != null ? `${first.pricingAmount} 万元` : '—')
        : `${ps.reduce((s, x) => s + (Number(x.pricingAmount) || 0), 0)} 万元`,
      rate: single
        ? (first.requestedRate != null ? `${first.requestedRate}%` : '—')
        : (rates.length ? `${Math.min(...rates)} ~ ${Math.max(...rates)}%` : '—'),
      product: productName(first.productCode),
      time: fmtTime(first.createTime), to: `/approval/${first.id}`, actionText: '去审批',
      extra: single ? null : { label: '担保分项', value: `${ps.length} 个` }
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
      key: `vote-${appId}`, kindText: '待表决', kindBadge: 'badge--warning',
      title: first.pricingCustomerNo || first.customerNo || '—',
      itemNo: single ? (first.pricingItemNo || first.pricingItemId) : `${ps.length} 个担保分项`,
      nodeText: nodeLabel(first.currentNodeCode),
      amount: single
        ? (first.pricingAmount != null ? `${first.pricingAmount} 万元` : '—')
        : `${ps.reduce((s, x) => s + (Number(x.pricingAmount) || 0), 0)} 万元`,
      rate: single
        ? (first.requestedRate != null ? `${first.requestedRate}%` : '—')
        : (rates.length ? `${Math.min(...rates)} ~ ${Math.max(...rates)}%` : '—'),
      product: productName(first.productCode),
      time: fmtTime(first.createTime), to: `/approval/${first.pricingItemId}`, actionText: '去表决',
      sub: `申请 ${first.applicationNo || '—'} · ${nodeLabel(first.currentNodeCode)}`,
      extra: single ? null : { label: '担保分项', value: `${ps.length} 个` }
    })
  }
  for (const p of presidentTodos.value) {
    items.push({
      key: `president-${p.pricingItemId}`, kindText: '待决策', kindBadge: 'badge--info',
      title: p.customerNo || '—',
      itemNo: p.pricingItemNo || p.pricingItemId, nodeText: nodeLabel('PRESIDENT'),
      amount: '—',
      rate: p.requestedRate != null ? `${p.requestedRate}%` : '—',
      product: '—',
      time: '', to: '/president', actionText: '去决策',
      extra: { label: '表决结果', value: `赞成 ${p.approveCount ?? 0} / 反对 ${p.rejectCount ?? 0}` }
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

const APP_FINALS = ['DRAFT', 'APPROVED', 'REJECTED', 'CLOSED']

// ---------- 客户经理:我的申请动态(仅显示在途,终态申请看历史档案) ----------
const myApps = computed(() =>
  [...applications.value]
    .filter((a) => a.status && !APP_FINALS.includes(a.status))
    .sort((a, b) => Number(b.id || 0) - Number(a.id || 0)))
const inProgressCount = computed(
  () => applications.value.filter((a) => a.status && !APP_FINALS.includes(a.status)).length
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

// ---------- 贡献度跟踪(复用 commitment 页的聚合口径) ----------
const CURRENT_STATUS = ['PENDING', 'TRACKING', 'AT_RISK', 'DATA_PENDING']

const planList = computed(() => {
  const map = new Map<number, any>()
  for (const r of planRows.value) {
    if (!map.has(r.id)) {
      map.set(r.id, {
        id: r.id, plan_no: r.plan_no, customer_no: r.customer_no, status: r.status, metrics: [] as any[]
      })
    }
    if (r.metric_code) map.get(r.id)!.metrics.push(r)
  }
  const list = [...map.values()]
  for (const p of list) {
    const ratios = p.metrics.map((m: any) => m.achievement_ratio).filter((v: any) => v != null).map(Number)
    p.avgRatio = ratios.length ? Number((ratios.reduce((a: number, b: number) => a + b, 0) / ratios.length).toFixed(1)) : null
  }
  return list
})

const planStats = computed(() => {
  const plans = planList.value
  return {
    // PENDING(待首次评估)/TRACKING 均属"跟踪中",与 /commitment 页 CURRENT_STATUS 口径一致(否则 PENDING 计划在工作台四档分布中无处安放)
    tracking: plans.filter((p) => p.status === 'TRACKING' || p.status === 'PENDING').length,
    atRisk: plans.filter((p) => p.status === 'AT_RISK' || p.metrics.some((m: any) => m.result_status === 'AT_RISK')).length,
    achieved: plans.filter((p) => p.status === 'ACHIEVED').length,
    dataPending: plans.filter((p) => p.status === 'DATA_PENDING').length,
    active: plans.filter((p) => CURRENT_STATUS.includes(p.status)).length
  }
})

const planTotal = computed(() => planList.value.length)

// 状态分布条:跟踪中/有风险/已达成/数据待齐
const distList = computed(() => {
  const s = planStats.value
  const entries = [
    { label: '跟踪中', count: s.tracking, color: 'var(--color-primary)' },
    { label: '有风险', count: s.atRisk, color: 'var(--color-warning)' },
    { label: '已达成', count: s.achieved, color: 'var(--color-success)' },
    { label: '数据待齐', count: s.dataPending, color: 'var(--color-text-light)' }
  ]
  const max = Math.max(...entries.map((e) => e.count), 1)
  return entries.map((e) => ({ ...e, pct: Math.max(Math.round((e.count / max) * 100), e.count ? 6 : 0) }))
})

// 有风险计划前 5 条(客户/指标/达成率)
const atRiskTop = computed(() => {
  const rows: any[] = []
  for (const p of planList.value) {
    if (rows.length >= 5) break
    const riskMetrics = p.metrics.filter((m: any) => m.result_status === 'AT_RISK')
    if (riskMetrics.length) {
      const m = riskMetrics[0]
      rows.push({
        customer: p.customer_no || '(集团)',
        metric: `${metricName(m.metric_code)}${riskMetrics.length > 1 ? ` 等 ${riskMetrics.length} 项` : ''}`,
        ratio: m.achievement_ratio != null ? `${m.achievement_ratio}%` : '暂无数据'
      })
    } else if (p.status === 'AT_RISK') {
      rows.push({
        customer: p.customer_no || '(集团)',
        metric: `${p.metrics.length} 项指标`,
        ratio: p.avgRatio != null ? `${p.avgRatio}%` : '暂无数据'
      })
    }
  }
  return rows
})

// ---------- 已办 ----------
const doneToday = computed(
  () => doneRows.value.filter((r) => String(r.operationTime || '').slice(0, 10) === todayStr).length
)

// ---------- KPI 卡(按角色差异化) ----------
const stats = computed(() => {
  const s = planStats.value
  const trackCard = {
    icon: 'Timer', label: '贡献度跟踪', value: s.tracking,
    cls: 'stat-card__num--warning', to: '/commitment',
    sub: s.atRisk ? `有风险 ${s.atRisk} 项` : '暂无风险计划', subDanger: s.atRisk > 0
  }
  const todayCard = {
    icon: 'CircleCheck', label: '今日已办', value: doneToday.value,
    cls: 'stat-card__num--success', to: '/history', sub: '本人今日办理的任务', subDanger: false
  }
  const totalCard = {
    icon: 'Finished', label: '累计已办', value: historyTotal.value,
    cls: 'stat-card__num--primary', to: '/history', sub: '本人审批/表决/决策过的申请', subDanger: false
  }
  const r = role.value
  if (r === 'customer_manager') {
    return [
      { icon: 'Document', label: '我的申请', value: applications.value.length, cls: 'stat-card__num--primary', to: '', sub: '本人发起的全部申请', subDanger: false },
      { icon: 'Loading', label: '审批中', value: inProgressCount.value, cls: 'stat-card__num--primary', to: '/history', sub: '正在流转审批的申请', subDanger: false },
      { icon: 'RefreshLeft', label: '被否决/可重提', value: rejectedCount.value, cls: rejectedCount.value ? 'stat-card__num--danger' : '', to: '/history', sub: '终态否决,可重新发起', subDanger: rejectedCount.value > 0 },
      trackCard
    ]
  }
  if (r === 'committee_member') {
    return [
      { icon: 'Key', label: '待我表决', value: voteTodos.value.length, cls: 'stat-card__num--warning', to: '/approval', sub: '待表决的申请', subDanger: false },
      todayCard, totalCard, trackCard
    ]
  }
  if (r === 'president') {
    return [
      { icon: 'Stamp', label: '待我决策', value: presidentTodos.value.length, cls: 'stat-card__num--warning', to: '/president', sub: '小组通过后待行长决策', subDanger: false },
      todayCard, totalCard, trackCard
    ]
  }
  if (r === 'admin' || r === 'auditor') {
    return [
      { icon: 'Document', label: '在途申请', value: inProgressCount.value, cls: 'stat-card__num--primary', to: '/history', sub: '全行流转中的申请', subDanger: false },
      { icon: 'Timer', label: '跟踪中计划', value: s.tracking, cls: 'stat-card__num--primary', to: '/commitment', sub: '生效跟踪中的承诺计划', subDanger: false },
      { icon: 'Warning', label: '有风险计划', value: s.atRisk, cls: s.atRisk ? 'stat-card__num--danger' : '', to: '/commitment', sub: '计划或指标评估有风险', subDanger: s.atRisk > 0 },
      todayCard
    ]
  }
  // 审批角色:branch_manager / dept_gm / vice_president
  return [
    { icon: 'Stamp', label: '待我审批', value: tasks.value.length, cls: 'stat-card__num--warning', to: '/approval', sub: '流转到本人当前节点的分项', subDanger: false },
    todayCard, totalCard, trackCard
  ]
})

// ---------- 数据加载(每个接口独立容错) ----------
async function safe(fn: () => Promise<void>) {
  try { await fn() } catch { /* 单接口失败不影响整页 */ }
}

async function load() {
  const r = role.value
  const jobs: Promise<void>[] = [
    safe(async () => { planRows.value = (await listCommitmentPlans()) || [] })
  ]
  if (r === 'customer_manager' || r === 'admin' || r === 'auditor') {
    jobs.push(safe(async () => { applications.value = (await get<any[]>('/ccr/applications')) || [] }))
  }
  if (r !== 'customer_manager') {
    jobs.push(safe(async () => { doneRows.value = (await listApprovalDone<any[]>()) || [] }))
    jobs.push(safe(async () => {
      const h = await pageApprovalHistory(1, 1)
      historyTotal.value = Number(h?.total) || 0
    }))
  }
  if (APPROVAL_ROLES.includes(r)) {
    jobs.push(safe(async () => { tasks.value = (await listApprovalTasks<any[]>()) || [] }))
  } else if (r === 'committee_member') {
    jobs.push(safe(async () => { voteTodos.value = (await listVoteTodo<any[]>()) || [] }))
  } else if (r === 'president') {
    jobs.push(safe(async () => { presidentTodos.value = (await listPresidentTodo<any[]>()) || [] }))
  }
  await Promise.all(jobs)
}

onMounted(load)
</script>

<style scoped>
/* ---------- 欢迎区 ---------- */
.welcome-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: var(--grad-navy, linear-gradient(135deg, #1e3a8a, #1d4ed8));
  border-radius: var(--radius);
  padding: 22px 24px;
  color: #fff;
  box-shadow: var(--shadow);
}
.welcome-card__greet { font-size: 20px; font-weight: 600; }
.welcome-card__meta { margin-top: 6px; font-size: 13px; opacity: .85; }
.welcome-card__date { text-align: right; flex: none; }
.welcome-card__day { font-size: 22px; font-weight: 700; font-variant-numeric: tabular-nums; }
.welcome-card__date-text { margin-top: 4px; font-size: 12px; opacity: .8; }

/* ---------- KPI 卡可点击 ---------- */
.stat-card--link { cursor: pointer; }
.stat-card__arrow { color: var(--color-text-light); transition: color .15s, transform .15s; }
.stat-card--link:hover .stat-card__arrow { color: var(--color-primary); transform: translateX(2px); }
.stat-card__num--danger { color: var(--color-danger); }
.stat-card__sub--danger { color: var(--color-danger); }

/* ---------- 主区两栏(窄屏堆叠;客户经理工作台「存款承诺/我的申请」区块留出呼吸感) ---------- */
.workbench-grid {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
  gap: 20px;
  align-items: start;
}
@media (max-width: 1100px) {
  .workbench-grid { grid-template-columns: 1fr; gap: 20px; }
}
.workbench-card { margin-bottom: 0; }
.workbench-grid__right { display: flex; flex-direction: column; gap: 20px; }

/* 工作台内表格行高放宽(上下不再贴紧) */
.workbench-card .table th,
.workbench-card .table td { padding: 14px 14px; }

/* ---------- 待办列表 ---------- */
.todo-list { display: flex; flex-direction: column; gap: 12px; }
.todo-card__body { flex: 1; min-width: 0; }
.todo-card__customer { font-weight: 600; font-size: 15px; display: flex; align-items: center; gap: 8px; }
.todo-card__sub { font-size: 13px; color: var(--color-text-sub); margin: 2px 0 10px; }
.todo-card__grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 6px 16px; font-size: 14px; }
.todo-card__action { flex: none; display: flex; align-items: center; gap: 8px; margin-left: 16px; }
.dg-label { color: var(--color-text-sub); margin-right: 6px; }
.btn-sm { padding: 4px 10px; font-size: 13px; }

/* ---------- 贡献度分布条 ---------- */
.dist-row { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; font-size: 13px; }
.dist-row__label { flex: none; width: 60px; color: var(--color-text-sub); }
.dist-row__bar {
  flex: 1; height: 8px; border-radius: 4px;
  background: var(--color-disabled, #eef0f4); overflow: hidden;
}
.dist-row__bar i { display: block; height: 100%; border-radius: 4px; transition: width .3s var(--ease, ease); }
.dist-row__num { flex: none; width: 28px; text-align: right; font-variant-numeric: tabular-nums; }

/* ---------- 有风险计划 ---------- */
.risk-title { font-size: 13px; font-weight: 600; color: var(--color-text-sub); margin: 18px 0 10px; }
.risk-item {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 10px 12px; border-radius: var(--radius-sm, 8px);
  cursor: pointer; transition: background .15s;
}
.risk-item + .risk-item { margin-top: 4px; }
.risk-item:hover { background: var(--color-primary-light, #eff4ff); }
.risk-item__main { min-width: 0; display: flex; align-items: baseline; gap: 8px; }
.risk-item__main b { font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.risk-item__main .dg-label { font-size: 12px; margin-right: 0; }
.risk-item__ratio { flex: none; font-weight: 600; color: var(--color-warning); font-variant-numeric: tabular-nums; }

</style>
