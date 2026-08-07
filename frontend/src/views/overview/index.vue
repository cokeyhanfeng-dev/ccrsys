<template>
  <div>
    <div class="section-head">
      <div class="section-title">{{ workbenchTitle }}</div>
      <div class="section-tip">{{ roleHint }} · 当前角色:{{ roleName }}</div>
    </div>

    <!-- 待办统计卡片(真实数据,点击一键跳转) -->
    <div class="stat-grid">
      <div class="stat-card" v-for="s in stats" :key="s.label" @click="s.to && $router.push(s.to)">
        <span class="stat-card__label">{{ s.label }}</span>
        <b :class="'stat-card__num ' + s.cls">{{ s.value }}</b>
        <div class="stat-card__sub" v-if="s.sub">{{ s.sub }}</div>
      </div>
    </div>

    <!-- 待办任务列表:当前需处理的工作 -->
    <div class="card" style="margin-top:16px">
      <div class="card__head">
        <span>{{ todoTitle }}</span>
      </div>
      <table class="table">
        <thead>
          <tr>
            <th>申请号</th><th>客户</th><th>业务类型</th><th>担保分项</th>
            <th>金额(万元)</th><th>申请利率</th><th>状态</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in todoRows" :key="row.applicationNo + row.guarantee">
            <td>{{ row.applicationNo }}</td>
            <td>{{ row.customer }}</td>
            <td>{{ row.businessType }}</td>
            <td>{{ row.guarantee }}</td>
            <td class="num">{{ row.amount }}</td>
            <td class="num">{{ row.rate }}%</td>
            <td><span :class="badgeClass(row.status)">{{ row.statusText }}</span></td>
            <td>
              <template v-if="row.action">
                <button class="btn btn--primary btn-sm" @click="row.action.handler(row)">{{ row.action.label }}</button>
              </template>
              <button v-else class="btn btn--text" @click="goHistory">查看</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="empty" v-if="!todoRows.length">暂无待办任务</div>
    </div>

    <!-- 客户经理:快捷入口 -->
    <div class="shortcut-row" style="margin-top:16px" v-if="role === 'customer_manager'">
      <router-link to="/application/loan" class="shortcut">
        <div class="shortcut__title">贷款利率申请</div>
        <div class="shortcut__desc">按贷款合同/担保方式切分</div>
      </router-link>
      <router-link to="/application/deposit" class="shortcut">
        <div class="shortcut__title">存款利率申请</div>
        <div class="shortcut__desc">现有账户或拟开户方案</div>
      </router-link>
      <router-link to="/commitment" class="shortcut">
        <div class="shortcut__title">贡献度跟踪</div>
        <div class="shortcut__desc">履约进度与落差提醒</div>
      </router-link>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { get } from '@/api/request'
import { listApprovalTasks, pageApprovalHistory } from '@/api/approval'
import { listVoteTodo, listPresidentTodo } from '@/api/vote'

const router = useRouter()
const userStore = useUserStore()

const role = computed(() => userStore.userInfo?.roles?.[0] || 'customer_manager')
const roleName = computed(() => {
  const map: Record<string, string> = {
    customer_manager: '客户经理', branch_manager: '支行行长', dept_gm: '部门总经理',
    vice_president: '分管行长', committee_member: '审批小组成员', president: '总行行长'
  }
  return map[role.value] || role.value
})
const workbenchTitle = computed(() => {
  const map: Record<string, string> = {
    customer_manager: '我的申请工作台', branch_manager: '审批工作台', dept_gm: '审批工作台',
    vice_president: '审批工作台', committee_member: '表决工作台', president: '决策工作台'
  }
  return map[role.value] || '工作台'
})
const roleHint = computed(() => {
  const map: Record<string, string> = {
    customer_manager: '查看本人申请并新建', branch_manager: '处理客户经理提交的待审批申请',
    dept_gm: '处理上送至本部门的待审批申请', vice_president: '处理上送至分管节点的待审批申请',
    committee_member: '就申请利率逐项赞成/反对(≥4票)', president: '同意利率 / 一票否决'
  }
  return map[role.value] || ''
})
const todoTitle = computed(() => {
  const map: Record<string, string> = {
    customer_manager: '我的申请', branch_manager: '待我审批(客户经理提交)',
    committee_member: '待我表决', president: '待我决策'
  }
  return map[role.value] || '待办任务'
})

const applications = ref<any[]>([])
const todoRows = ref<any[]>([])
const historyTotal = ref<number | null>(null)

// 审批中(客户经理):排除终态与草稿
const inProgressCount = computed(() => {
  const finals = ['DRAFT', 'APPROVED', 'REJECTED', 'VETOED', 'CLOSED', 'FINAL']
  return applications.value.filter((a) => a.status && !finals.includes(a.status)).length
})

// 待办统计(全部来自真实接口;无接口支撑的卡片不展示)
const stats = computed(() => {
  if (role.value === 'customer_manager') {
    return [
      { label: '我的申请', value: String(applications.value.length), cls: 'stat-card__num--primary', to: '' },
      { label: '审批中申请', value: String(inProgressCount.value), cls: 'stat-card__num--warning', to: '' }
    ]
  }
  if (role.value === 'president') {
    return [{ label: '待我决策', value: String(todoRows.value.length), cls: 'stat-card__num--primary', to: '/president' }]
  }
  if (role.value === 'committee_member') {
    return [{ label: '待我表决', value: String(todoRows.value.length), cls: 'stat-card__num--primary', to: '/voting' }]
  }
  const cards = [{ label: '待我审批', value: String(todoRows.value.length), cls: 'stat-card__num--primary', to: '/approval' }]
  if (historyTotal.value != null) {
    cards.push({ label: '已办', value: String(historyTotal.value), cls: 'stat-card__num--success', to: '/history' })
  }
  return cards
})

const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '已提交', ROUTING: '路由中', APPROVED_LEVEL: '权限内已批',
  VOTING: '小组表决', COMMITTEE_PASS: '小组通过', PRESIDENT_DECISION: '行长决议',
  FINAL: '终态', VETOED: '一票否决', REJECTED: '已否决', RETURNED: '已退回', CLOSED: '已关闭'
}

async function load() {
  if (role.value === 'customer_manager') {
    try {
      const data = await get<any[]>('/ccr/applications', { applicantId: userStore.userInfo?.userId })
      applications.value = data || []
      todoRows.value = (data || []).map((a) => ({
        applicationNo: a.applicationNo,
        customer: a.customerNo || '-',
        businessType: a.businessType === 'LOAN' ? '贷款' : '存款',
        guarantee: '-',
        amount: '-',
        rate: '-',
        status: a.status,
        statusText: STATUS_TEXT[a.status] || a.status,
        action: null
      }))
    } catch { todoRows.value = [] }
  } else if (role.value === 'committee_member') {
    // 6人小组:本人待表决分项
    try {
      const data = await listVoteTodo<any[]>()
      todoRows.value = (data || []).map((p) => ({
        id: p.pricingItemId,
        applicationNo: '分项' + p.pricingItemId,
        customer: '-',
        businessType: '-',
        guarantee: p.productCode || '—',
        amount: p.pricingAmount ?? '-',
        rate: p.requestedRate ?? '-',
        status: 'processing',
        statusText: '待表决',
        action: { label: '去表决', handler: () => router.push('/voting') }
      }))
    } catch { todoRows.value = [] }
  } else if (role.value === 'president') {
    try {
      const data = await listPresidentTodo<any[]>()
      todoRows.value = (data || []).map((p) => ({
        id: p.pricingItemId,
        applicationNo: p.pricingItemNo || p.pricingItemId,
        customer: p.customerNo || '-',
        businessType: '-',
        guarantee: '-',
        amount: '-',
        rate: p.requestedRate ?? '-',
        status: 'processing',
        statusText: '待行长决策',
        action: { label: '去决策', handler: () => router.push('/president') }
      }))
    } catch { todoRows.value = [] }
  } else {
    // 支行行长/部门总经理/分管行长:按登录人角色返回待办(无参)
    try {
      const data = await listApprovalTasks<any[]>()
      todoRows.value = (data || []).map((p) => ({
        id: p.id,
        applicationNo: p.pricingItemNo || p.id,
        customer: p.pricingCustomerNo || '-',
        businessType: '-',
        guarantee: p.productCode || '—',
        amount: p.pricingAmount ?? '-',
        rate: p.requestedRate ?? '-',
        status: 'processing',
        statusText: STATUS_TEXT[p.status] || p.status,
        action: { label: '查看审批', handler: (row: any) => router.push(`/approval/${row.id}`) }
      }))
    } catch { todoRows.value = [] }
    try {
      const h = await pageApprovalHistory(1, 1)
      historyTotal.value = Number(h?.total) || 0
    } catch { historyTotal.value = null }
  }
}

function badgeClass(status: string) {
  const map: Record<string, string> = {
    processing: 'badge badge--processing', ROUTING: 'badge badge--processing',
    APPROVED_LEVEL: 'badge badge--approved', FINAL: 'badge badge--approved',
    REJECTED: 'badge badge--rejected', VETOED: 'badge badge--rejected',
    DRAFT: 'badge badge--neutral', SUBMITTED: 'badge badge--processing'
  }
  return map[status] || 'badge badge--neutral'
}
function goHistory() {
  router.push('/history')
}
onMounted(load)
</script>

<style scoped>
.section-head { margin-bottom: 16px; }
.section-title { font-size: var(--fs-h2); font-weight: 600; margin-bottom: 6px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.stat-card { background: var(--color-bg); border-radius: var(--radius); padding: 16px; cursor: pointer; }
.stat-card:hover { box-shadow: var(--shadow-sm); }
.stat-card__label { font-size: 13px; color: var(--color-text-sub); }
.stat-card__num { display: block; font-size: 24px; }
.stat-card__num--primary { color: var(--color-primary); }
.stat-card__num--warning { color: var(--color-warning); }
.stat-card__num--success { color: var(--color-success); }
.stat-card__sub { font-size: 12px; color: var(--color-text-light); margin-top: 2px; }
.shortcut-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.shortcut { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius); padding: 16px; text-decoration: none; color: var(--color-text-main); }
.shortcut:hover { box-shadow: var(--shadow-sm); }
.shortcut__title { font-weight: 600; margin-bottom: 4px; color: var(--color-primary); }
.shortcut__desc { font-size: 12px; color: var(--color-text-sub); }
.card { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow-sm); }
.card__head { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.table { border-radius: var(--radius); overflow: hidden; }
.btn-sm { padding: 4px 10px; font-size: 13px; }
.empty { text-align: center; padding: 32px; color: var(--color-text-light); }
</style>
