<template>
  <div>
    <div class="section-head">
      <div class="section-title">总行行长决策工作台 · 待我决策</div>
      <InfoTip content="六人小组表决通过后整单送总行行长决策;行长统一执行「同意利率」或「一票否决」。六人匿名审批意见与完整审批内容在决策详情中查看,匿名码每批随机分配。" />
    </div>

    <!-- 统计卡片(真实数据,全局 KPI 卡结构) -->
    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-card__body">
          <span class="stat-card__label">待我决策</span>
          <b class="stat-card__num stat-card__num--primary">{{ cards.length }} 笔</b>
          <div class="stat-card__sub">六人小组表决通过待决策申请(按申请聚合)</div>
        </div>
      </div>
    </div>

    <!-- 待决策卡片列表(每申请一张卡片,含该申请全部待决策分项;与申请/审批页一致不拆分为项) -->
    <div class="todo-list" v-loading="loading">
      <div class="empty" v-if="loadError">
        加载失败,请刷新
        <div style="margin-top:12px"><button class="btn btn--secondary" @click="load">重新加载</button></div>
      </div>
      <template v-else>
      <div class="todo-card" v-for="c in cards" :key="c.applicationId">
        <div class="todo-card__body">
          <div class="todo-card__customer">
            {{ c.customer }}
            <span class="badge badge--info" v-if="c.itemCount > 1">{{ c.itemCount }} 个待决策分项</span>
          </div>
          <div class="todo-card__summary">
            申请 {{ c.applicationNo }} · 六人审批已通过{{ c.time ? ` · ${c.time}` : '' }}
          </div>
          <!-- 整单摘要(与利率审批工作台卡同款 desc-grid;§2026-09-02 行长卡对齐审批人) -->
          <div class="desc-grid desc-grid--3">
            <div class="desc-item"><div class="desc-item__label">申请金额(万元)</div><div class="desc-item__value">{{ c.amount }}</div></div>
            <div class="desc-item"><div class="desc-item__label">申请利率</div><div class="desc-item__value">{{ c.rate }}</div></div>
            <div class="desc-item"><div class="desc-item__label">原执行利率</div><div class="desc-item__value">{{ c.originalRate }}</div></div>
            <div class="desc-item"><div class="desc-item__label">六人表决</div><div class="desc-item__value">{{ c.votesText }}</div></div>
          </div>
        </div>
        <div class="todo-card__action">
          <button class="btn btn--primary" @click="openDetail(c)">进入行长决策</button>
        </div>
      </div>
      <div class="empty" v-if="!loading && !cards.length">暂无待决策申请</div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listPresidentTodo } from '@/api/vote'

const router = useRouter()
const cards = ref<any[]>([])
const loading = ref(true)
const loadError = ref(false)

// 行长待决策(六人表决通过的申请,按申请聚合;进入后展示完整审批详情与六人匿名意见)
async function load() {
  loading.value = true
  loadError.value = false
  try {
    const data = await listPresidentTodo<any[]>()
    cards.value = (data || []).map((p) => {
      const items: any[] = p.items || []
      const first = items[0] || {}
      const single = items.length === 1
      const rates = items.map((x) => Number(x.requestedRate) || 0)
      const ors = items.map((x) => x.originalRate).filter((v) => v != null && v !== '').map(Number)
      return {
        applicationId: p.applicationId,
        applicationNo: p.applicationNo || '-',
        // 客户/集团显示名(与审批待办同口径),回退客户号
        customer: p.customerName || p.customerNo || '-',
        itemCount: items.length || 1,
        // 整单粒度摘要:多分项金额求和、利率区间;与利率审批工作台卡同款(§2026-09-02)
        amount: single
          ? (first.pricingAmount != null ? `${first.pricingAmount}` : '—')
          : `${items.reduce((s, x) => s + (Number(x.pricingAmount) || 0), 0)}`,
        rate: single
          ? (first.requestedRate != null ? `${first.requestedRate}%` : '—')
          : (rates.length ? `${Math.min(...rates)} ~ ${Math.max(...rates)}%` : '—'),
        originalRate: ors.length
          ? (ors.length === 1 ? `${ors[0]}%` : `${Math.min(...ors)} ~ ${Math.max(...ors)}%`)
          : '新增业务',
        votesText: first.approveCount != null
          ? `赞成 ${first.approveCount} / 反对 ${first.rejectCount ?? 0}`
          : '—',
        time: p.submitTime ? String(p.submitTime).replace('T', ' ').slice(0, 16) : ''
      }
    })
  } catch {
    cards.value = []
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function openDetail(c: any) {
  if (!c.applicationId) return
  // 跳转审批详情页:完整申请内容 + 六人匿名意见 + 行长整单决策(整单入口用 applicationId,与申请/审批页统一)
  router.push(`/approval/${c.applicationId}`)
}
onMounted(load)
</script>

<style scoped>
.stat-grid { margin-bottom: 16px; }
.todo-list { display: flex; flex-direction: column; gap: 12px; }
.todo-card__customer { font-weight: 600; font-size: 16px; margin-bottom: 6px; }
.todo-card__summary { font-size: 14px; color: var(--color-text-sub); margin-bottom: 8px; }
.todo-card__meta { display: flex; gap: 8px; }
.todo-card__action { display: flex; align-items: center; gap: 8px; }
/* 768px 断点:操作区换到卡片下方 */
@media (max-width: 767px) {
  .todo-card { flex-direction: column; align-items: stretch; gap: 10px; }
  .todo-card__action { justify-content: flex-end; }
}
</style>
