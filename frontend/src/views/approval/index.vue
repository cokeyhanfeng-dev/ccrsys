<template>
  <div>
    <div class="section-head">
      <div class="section-title">我的审批工作台 · 待我审批</div>
      <div class="section-tip">仅展示流转到本人当前审批节点的申请(按登录人角色过滤)。</div>
    </div>

    <!-- 待办统计(真实数据) -->
    <div class="stat-row">
      <div class="stat-card">
        <span class="stat-card__label">待我审批</span>
        <b class="stat-card__num stat-card__num--warning">{{ todoCards.length }} 笔</b>
      </div>
      <div class="stat-card">
        <span class="stat-card__label">累计已办</span>
        <b class="stat-card__num stat-card__num--success">{{ historyTotal }} 笔</b>
        <div class="stat-card__sub">本人审批/表决/决策过的申请</div>
      </div>
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
          <button class="btn btn--primary" @click="goDetail(c)">进入完整审批</button>
        </div>
      </div>
      <div class="empty" v-if="!todoCards.length">暂无待审批任务</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listApprovalTasks, pageApprovalHistory } from '@/api/approval'

const router = useRouter()
const todoCards = ref<any[]>([])
const historyTotal = ref(0)

const NODE_LABELS: Record<string, string> = {
  BRANCH_MANAGER: '支行行长', DEPT_GENERAL_MANAGER: '部门总经理',
  VICE_PRESIDENT: '分管行长', SIX_PEOPLE_GROUP: '六人小组表决', PRESIDENT: '行长决策'
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

function goDetail(c: any) {
  router.push(`/approval/${c.id}`)
}

onMounted(load)
</script>

<style scoped>
.section-head { margin-bottom: 16px; }
.section-title { font-size: var(--fs-h2); font-weight: 600; margin-bottom: 6px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
.stat-row { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; margin-bottom: 16px; }
.stat-card { background: var(--color-bg); border-radius: var(--radius); padding: 16px; }
.stat-card__label { font-size: 13px; color: var(--color-text-sub); }
.stat-card__num { display: block; font-size: 24px; }
.stat-card__num--success { color: var(--color-success); }
.stat-card__num--warning { color: var(--color-warning); }
.stat-card__sub { font-size: 12px; color: var(--color-text-light); margin-top: 2px; }
.todo-list { display: flex; flex-direction: column; gap: 12px; }
.todo-card { display: flex; align-items: center; justify-content: space-between; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow-sm); }
.todo-card__customer { font-weight: 600; font-size: 16px; }
.todo-card__sub { font-size: 13px; color: var(--color-text-sub); margin: 2px 0 10px; }
.todo-card__grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px 16px; font-size: 14px; }
.tc-item .dg-label { color: var(--color-text-sub); margin-right: 6px; }
.empty { text-align: center; padding: 32px; color: var(--color-text-light); }
</style>
