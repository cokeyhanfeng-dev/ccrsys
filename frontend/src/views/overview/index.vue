<template>
  <div>
    <div class="section-head">
      <div class="section-title">{{ workbenchTitle }}</div>
      <div class="section-tip">{{ roleHint }} · 当前角色:{{ roleName }}</div>
    </div>

    <!-- 待办统计卡片(真实数据,点击一键跳转) -->
    <div class="stat-grid">
      <div
        class="stat-card"
        :class="'stat-card--tone' + (i % 3)"
        v-for="(s, i) in stats"
        :key="s.label"
        @click="s.to && $router.push(s.to)"
      >
        <div class="stat-card__icon">
          <el-icon :size="20">
            <component
              :is="{
                '我的申请': 'Document',
                '审批中申请': 'Loading',
                '待我决策': 'Stamp',
                '待我表决': 'Key',
                '待我审批': 'Stamp',
                '已办': 'CircleCheck'
              }[s.label] || 'DataLine'"
            />
          </el-icon>
        </div>
        <div class="stat-card__body">
          <span class="stat-card__label">{{ s.label }}</span>
          <b :class="'stat-card__num ' + s.cls">{{ s.value }}</b>
          <div class="stat-card__sub" v-if="s.sub">{{ s.sub }}</div>
        </div>
        <el-icon v-if="s.to" class="stat-card__arrow"><ArrowRight /></el-icon>
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
        <span class="shortcut__icon"><el-icon :size="18"><EditPen /></el-icon></span>
        <div>
          <div class="shortcut__title">贷款利率申请</div>
          <div class="shortcut__desc">按贷款合同/担保方式切分</div>
        </div>
      </router-link>
      <router-link to="/application/deposit" class="shortcut">
        <span class="shortcut__icon"><el-icon :size="18"><Coin /></el-icon></span>
        <div>
          <div class="shortcut__title">存款利率申请</div>
          <div class="shortcut__desc">现有账户或拟开户方案</div>
        </div>
      </router-link>
      <router-link to="/commitment" class="shortcut">
        <span class="shortcut__icon"><el-icon :size="18"><Timer /></el-icon></span>
        <div>
          <div class="shortcut__title">贡献度跟踪</div>
          <div class="shortcut__desc">履约进度与落差提醒</div>
        </div>
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
import { itemStatusText, roleText, productName, businessTypeText } from '@/utils/dict'

const router = useRouter()
const userStore = useUserStore()

const role = computed(() => userStore.userInfo?.roles?.[0] || 'customer_manager')
const roleName = computed(() => roleText(role.value, role.value))
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

async function load() {
  if (role.value === 'customer_manager') {
    try {
      const data = await get<any[]>('/ccr/applications', { applicantId: userStore.userInfo?.userId })
      applications.value = data || []
      todoRows.value = (data || []).map((a) => ({
        applicationNo: a.applicationNo,
        customer: a.customerNo || '-',
        businessType: businessTypeText(a.businessType),
        guarantee: '-',
        amount: '-',
        rate: '-',
        status: a.status,
        statusText: itemStatusText(a.status),
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
        guarantee: productName(p.productCode),
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
        guarantee: productName(p.productCode),
        amount: p.pricingAmount ?? '-',
        rate: p.requestedRate ?? '-',
        status: 'processing',
        statusText: itemStatusText(p.status),
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
.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; }
/* KPI 统计卡:图标 + 大数字,三种色调 */
.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  position: relative;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius);
  padding: 20px;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  transition: transform .18s var(--ease), box-shadow .18s var(--ease);
}
.stat-card:hover { transform: translateY(-3px); box-shadow: var(--shadow); }
.stat-card__icon {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 12px;
  color: #fff;
  background: var(--grad-primary);
  box-shadow: var(--shadow-primary);
}
.stat-card--tone1 .stat-card__icon {
  background: linear-gradient(135deg, #f59e0b, #d97706);
  box-shadow: 0 4px 12px rgba(245, 158, 11, .3);
}
.stat-card--tone2 .stat-card__icon {
  background: linear-gradient(135deg, #10b981, #059669);
  box-shadow: 0 4px 12px rgba(16, 185, 129, .3);
}
.stat-card__body { flex: 1; min-width: 0; }
.stat-card__label { font-size: 13px; color: var(--color-text-sub); }
.stat-card__num { display: block; font-size: 28px; font-weight: 700; line-height: 1.3; font-variant-numeric: tabular-nums; }
.stat-card__num--primary { color: var(--color-primary); }
.stat-card__num--warning { color: var(--color-warning); }
.stat-card__num--success { color: var(--color-success); }
.stat-card__sub { font-size: 12px; color: var(--color-text-light); margin-top: 2px; }
.stat-card__arrow { color: var(--color-text-light); transition: color .15s, transform .15s; }
.stat-card:hover .stat-card__arrow { color: var(--color-primary); transform: translateX(2px); }
.shortcut-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.shortcut {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius);
  padding: 18px 20px;
  text-decoration: none;
  color: var(--color-text-main);
  box-shadow: var(--shadow-sm);
  transition: transform .18s var(--ease), box-shadow .18s var(--ease), border-color .18s;
}
.shortcut:hover { transform: translateY(-2px); box-shadow: var(--shadow); border-color: #c7d7f8; }
.shortcut__icon {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  color: var(--color-primary);
  background: var(--color-primary-light);
}
.shortcut__title { font-weight: 600; margin-bottom: 4px; color: var(--color-primary); }
.shortcut__desc { font-size: 12px; color: var(--color-text-sub); }
.card { background: var(--color-surface); border: 1px solid var(--color-border-light); border-radius: var(--radius); padding: 20px; box-shadow: var(--shadow); }
.card__head { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 14px; }
.card__head > span:first-child { display: inline-flex; align-items: center; }
.card__head > span:first-child::before { content: ""; display: inline-block; width: 4px; height: 15px; margin-right: 8px; border-radius: 2px; background: var(--grad-primary); }
.table { border-radius: var(--radius-sm); overflow: hidden; }
.btn-sm { padding: 4px 10px; font-size: 13px; }
.empty { padding: 44px 32px 48px; }
</style>
