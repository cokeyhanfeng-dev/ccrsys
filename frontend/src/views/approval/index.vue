<template>
  <div>
    <div class="section-head">
      <div class="section-title">我的审批工作台</div>
      <div class="section-tip">待办仅展示流转到本人当前审批节点的申请(按登录人角色过滤);已办为本人审批/表决/决策过的任务(§11.4)。</div>
    </div>

    <!-- 待办统计(KPI 卡,与工作台同款) -->
    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-card__icon"><el-icon :size="20"><Stamp /></el-icon></div>
        <div class="stat-card__body">
          <span class="stat-card__label">待我审批</span>
          <b class="stat-card__num stat-card__num--warning">{{ todoCards.length }} 笔</b>
          <div class="stat-card__sub">流转到本人当前节点的申请</div>
        </div>
      </div>
      <div class="stat-card stat-card--tone2">
        <div class="stat-card__icon"><el-icon :size="20"><CircleCheck /></el-icon></div>
        <div class="stat-card__body">
          <span class="stat-card__label">累计已办</span>
          <b class="stat-card__num stat-card__num--success">{{ historyTotal }} 笔</b>
          <div class="stat-card__sub">本人审批/表决/决策过的申请</div>
        </div>
      </div>
    </div>

    <!-- 待办 / 已办 分段页签 -->
    <div class="segmented" style="margin-top:16px">
      <button class="segmented__item" :class="{ 'segmented__item--active': tab === 'todo' }" @click="tab = 'todo'">待办</button>
      <button class="segmented__item" :class="{ 'segmented__item--active': tab === 'done' }" @click="switchDone">已办</button>
    </div>

    <!-- 待办卡片列表 -->
    <div class="todo-list" v-show="tab === 'todo'">
      <div class="todo-card" v-for="c in todoCards" :key="c.id">
        <div class="todo-card__body">
          <div class="todo-card__customer">{{ c.customer }}</div>
          <div class="todo-card__sub">定价分项 {{ c.pricingItemNo }} · 当前节点 {{ c.nodeText }}</div>
          <div class="todo-card__grid">
            <div class="tc-item"><span class="dg-label">申请利率</span><b>{{ c.rate }}%</b></div>
            <div class="tc-item"><span class="dg-label">原执行利率</span><b>{{ c.originalRate }}</b></div>
            <div class="tc-item"><span class="dg-label">分项金额</span><b>{{ c.amount }} 万元</b></div>
            <div class="tc-item"><span class="dg-label">产品编码</span><b>{{ c.productCode }}</b></div>
            <div class="tc-item"><span class="dg-label">提交时间</span><b>{{ c.createTime }}</b></div>
          </div>
        </div>
        <div class="todo-card__action">
          <button class="btn btn--secondary" @click="openCheck(c)">核验资料</button>
          <button class="btn btn--primary" @click="goDetail(c)">进入完整审批</button>
        </div>
      </div>
      <div class="empty" v-if="!todoCards.length">暂无待审批任务</div>
    </div>

    <!-- 已办列表(/ccr/approval/done) -->
    <div class="card" v-show="tab === 'done'">
      <table class="table" v-if="doneRows.length">
        <thead>
          <tr>
            <th>申请号</th><th>定价分项</th><th>客户号</th><th>节点</th><th>动作</th>
            <th>利率变化</th><th>状态变迁</th><th>分项状态</th><th>办理时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, i) in doneRows" :key="i">
            <td>{{ r.applicationNo || '—' }}</td>
            <td>{{ r.pricingItemNo || '—' }}</td>
            <td>{{ r.customerNo || '—' }}</td>
            <td>{{ nodeLabel(r.nodeCode) }}</td>
            <td>
              <span class="badge" :class="r.actionType === 'REJECT' ? 'badge--danger' : 'badge--success'">
                {{ r.actionType === 'APPROVE' ? '通过' : r.actionType === 'REJECT' ? '否决' : (r.actionType || '—') }}
              </span>
            </td>
            <td class="num">
              {{ r.beforeRate != null && r.afterRate != null && r.beforeRate !== r.afterRate ? `${r.beforeRate}% → ${r.afterRate}%` : '—' }}
            </td>
            <td>
              <span v-if="r.fromStatus || r.toStatus" class="badge badge--neutral">
                {{ statusText(r.fromStatus) }} → {{ statusText(r.toStatus) }}
              </span>
              <span v-else>—</span>
            </td>
            <td>{{ statusText(r.itemStatus) }}</td>
            <td>{{ r.operationTime ? String(r.operationTime).replace('T', ' ').slice(0, 16) : '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div class="empty" v-else>暂无已办任务</div>
    </div>

    <!-- 核验资料弹层(§12.8:6 格摘要 + 进入完整审批) -->
    <div class="modal" v-if="check.show">
      <div class="modal__card">
        <div class="modal__title">核验资料 · {{ check.customer }}</div>
        <div class="modal__body" v-if="check.loaded">
          <div class="check-grid">
            <div class="check-item"><span class="dg-label">客户</span><b>{{ check.customer }}</b></div>
            <div class="check-item"><span class="dg-label">金额</span><b>{{ check.amount }}</b></div>
            <div class="check-item"><span class="dg-label">申请利率</span><b>{{ check.rate }}</b></div>
            <div class="check-item"><span class="dg-label">原利率</span><b>{{ check.originalRate }}</b></div>
            <div class="check-item">
              <span class="dg-label">资料校验结论</span>
              <span v-if="check.qualityOverall" class="badge" :class="check.qualityOverall === 'BLOCK' ? 'badge--danger' : check.qualityOverall === 'WARN' ? 'badge--warning' : 'badge--success'">
                {{ check.qualityOverall === 'BLOCK' ? '阻断' : check.qualityOverall === 'WARN' ? '预警' : '通过' }}
              </span>
              <b v-else>暂无数据</b>
            </div>
            <div class="check-item"><span class="dg-label">快照数据日期</span><b>{{ check.dataDt }}</b></div>
          </div>
        </div>
        <div class="modal__body" v-else><div class="empty">加载中...</div></div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="check.show = false">关闭</button>
          <button class="btn btn--primary" @click="goDetail(check)">进入完整审批</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listApprovalTasks, pageApprovalHistory, getApprovalDetail } from '@/api/approval'
import { listApprovalDone } from '@/api/approval2'

const router = useRouter()
const todoCards = ref<any[]>([])
const historyTotal = ref(0)
const tab = ref<'todo' | 'done'>('todo')
const doneRows = ref<any[]>([])
const doneLoaded = ref(false)

const check = ref<any>({
  show: false, loaded: false, id: null,
  customer: '', amount: '', rate: '', originalRate: '', qualityOverall: '', dataDt: ''
})

const NODE_LABELS: Record<string, string> = {
  BRANCH_MANAGER: '支行行长', DEPT_GENERAL_MANAGER: '部门总经理',
  VICE_PRESIDENT: '分管行长', SIX_PEOPLE_GROUP: '六人小组表决', PRESIDENT: '行长决策'
}
const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '已提交', ROUTING: '路由中', APPROVED_LEVEL: '权限内已批',
  VOTING: '小组表决', COMMITTEE_PASS: '小组通过', PRESIDENT_DECISION: '行长决议',
  FINAL: '终态', VETOED: '一票否决', REJECTED: '已否决', RETURNED: '已退回', CLOSED: '已关闭'
}
function nodeLabel(code?: string) {
  return code ? (NODE_LABELS[code] || code) : '—'
}
function statusText(s?: string) {
  return s ? (STATUS_TEXT[s] || s) : '—'
}

async function load() {
  try {
    const data = await listApprovalTasks<any[]>()
    todoCards.value = (data || []).map((p) => ({
      id: p.id,
      pricingItemNo: p.pricingItemNo || p.id,
      customer: p.pricingCustomerNo || '-',
      amount: p.pricingAmount ?? '-',
      rate: p.requestedRate ?? '-',
      originalRate: p.originalRate != null ? `${p.originalRate}%` : '新增业务',
      productCode: p.productCode || '—',
      nodeText: NODE_LABELS[p.currentNodeCode] || p.currentNodeCode || '—',
      createTime: p.createTime ? String(p.createTime).replace('T', ' ').slice(0, 16) : '—'
    }))
  } catch {
    todoCards.value = []
  }
  try {
    const h = await pageApprovalHistory(1, 1)
    historyTotal.value = Number(h?.total) || 0
  } catch {
    historyTotal.value = 0
  }
}

// 已办页签(首次切换时加载)
async function switchDone() {
  tab.value = 'done'
  if (doneLoaded.value) return
  try {
    doneRows.value = await listApprovalDone<any[]>()
  } catch {
    doneRows.value = []
  } finally {
    doneLoaded.value = true
  }
}

// 核验资料(§12.8):取审批详情的摘要信息
async function openCheck(c: any) {
  check.value = {
    show: true, loaded: false, id: c.id,
    customer: c.customer, amount: `${c.amount} 万元`, rate: `${c.rate}%`,
    originalRate: c.originalRate, qualityOverall: '', dataDt: '—'
  }
  try {
    const d = await getApprovalDetail(c.id)
    const pi = d.pricingItem || {}
    const customer = d.customer?.[0] || {}
    check.value.customer = customer.customerName || pi.pricing_customer_no || c.customer
    check.value.amount = pi.pricing_amount != null ? `${pi.pricing_amount} 万元` : `${c.amount} 万元`
    check.value.rate = pi.requested_rate != null ? `${pi.requested_rate}%` : `${c.rate}%`
    check.value.originalRate = pi.original_rate != null ? `${pi.original_rate}%` : '新增业务'
    check.value.qualityOverall = d.qualityOverall || ''
    check.value.dataDt = d.source === 'SNAPSHOT' ? (d.snapshotInfo?.dataDt || '—') : '实时取数(未冻结快照)'
    check.value.loaded = true
  } catch {
    check.value.show = false
  }
}

function goDetail(c: any) {
  check.value.show = false
  router.push(`/approval/${c.id}`)
}

onMounted(load)
</script>

<style scoped>
.stat-row { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; margin-bottom: 16px; }
.todo-list { display: flex; flex-direction: column; gap: 12px; }
.todo-card__customer { font-weight: 600; font-size: 16px; }
.todo-card__sub { font-size: 13px; color: var(--color-text-sub); margin: 2px 0 10px; }
.todo-card__grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px 16px; font-size: 14px; }
.tc-item .dg-label { color: var(--color-text-sub); margin-right: 6px; }
.todo-card__action { display: flex; align-items: center; gap: 8px; }
.table { border-radius: var(--radius-sm); overflow: hidden; }
.check-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px 16px; font-size: 14px; }
.check-item .dg-label { display: block; color: var(--color-text-sub); font-size: 12px; margin-bottom: 4px; }
.dg-label { color: var(--color-text-sub); margin-right: 6px; }
</style>
