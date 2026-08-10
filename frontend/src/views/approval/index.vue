<template>
  <div>
    <div class="section-head">
      <div class="section-title">利率审批</div>
      <div class="section-tip">流转到本人当前审批节点、需要处理的申请列表(按登录人角色过滤);已办与统计见工作台。</div>
    </div>



    <!-- 待办卡片列表 -->
    <div class="todo-list">
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
import { listApprovalTasks, getApprovalDetail } from '@/api/approval'
import { nodeLabel, itemStatusText, actionText, productName } from '@/utils/dict'

const router = useRouter()
const todoCards = ref<any[]>([])

const check = ref<any>({
  show: false, loaded: false, id: null,
  customer: '', amount: '', rate: '', originalRate: '', dataDt: ''
})

function statusText(s?: string) {
  return itemStatusText(s)
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
      productCode: productName(p.productCode),
      nodeText: nodeLabel(p.currentNodeCode),
      createTime: p.createTime ? String(p.createTime).replace('T', ' ').slice(0, 16) : '—'
    }))
  } catch {
    todoCards.value = []
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
