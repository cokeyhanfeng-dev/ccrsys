<template>
  <div>
    <div class="section-head">
      <div class="section-title">总行行长决策工作台 · 待我决策</div>
      <InfoTip content="六人小组表决通过后整单送总行行长决策;行长统一执行「同意利率」或「一票否决」。六人匿名审批意见与完整审批内容在决策详情中查看,匿名码每批随机分配。" />
    </div>

    <!-- 统计卡片(真实数据) -->
    <div class="stat-row">
      <div class="stat-card">
        <span class="stat-card__label">待我决策</span>
        <b class="stat-card__num stat-card__num--primary">{{ cards.length }} 笔</b>
        <div class="stat-card__sub">六人小组表决通过待决策申请(按申请聚合)</div>
      </div>
    </div>

    <!-- 待决策卡片列表(每申请一张卡片,含该申请全部待决策分项;与申请/审批页一致不拆分为项) -->
    <div class="todo-list">
      <div class="todo-card" v-for="c in cards" :key="c.applicationId">
        <div class="todo-card__body">
          <div class="todo-card__customer">{{ c.customer }}</div>
          <div class="todo-card__summary">
            六人审批结果 {{ c.votes }} 通过 · 申请利率 {{ c.rate }}%
            <template v-if="c.itemCount > 1"> · 共 {{ c.itemCount }} 个分项</template>
          </div>
          <div class="todo-card__meta">
            <span class="badge badge--success">六人审批已通过</span>
            <span class="badge badge--info">{{ c.applicationNo }}</span>
          </div>
        </div>
        <div class="todo-card__action">
          <button class="btn btn--primary" @click="openDetail(c)">进入行长决策</button>
        </div>
      </div>
      <div class="empty" v-if="!cards.length">暂无待决策申请</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listPresidentTodo } from '@/api/vote'

const router = useRouter()
const cards = ref<any[]>([])

// 行长待决策(六人表决通过的申请,按申请聚合;进入后展示完整审批详情与六人匿名意见)
async function load() {
  try {
    const data = await listPresidentTodo<any[]>()
    cards.value = (data || []).map((p) => {
      const items: any[] = p.items || []
      const first = items[0] || {}
      return {
        applicationId: p.applicationId,
        applicationNo: p.applicationNo || '-',
        customer: p.customerNo || '-',
        itemCount: items.length || 1,
        firstItemId: first.pricingItemId,
        votes: first.approveCount != null ? `${first.approveCount}:${first.rejectCount}` : '—:—',
        rate: first.requestedRate ?? '-'
      }
    })
  } catch {
    cards.value = []
  }
}

function openDetail(c: any) {
  if (!c.firstItemId) return
  // 跳转审批详情页:完整申请内容 + 六人匿名意见 + 行长整单决策,与申请/审批页统一
  router.push(`/approval/${c.firstItemId}`)
}
onMounted(load)
</script>

<style scoped>
.stat-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 16px; }
.todo-list { display: flex; flex-direction: column; gap: 12px; }
.todo-card__customer { font-weight: 600; font-size: 16px; margin-bottom: 6px; }
.todo-card__summary { font-size: 14px; color: var(--color-text-sub); margin-bottom: 8px; }
.todo-card__meta { display: flex; gap: 8px; }
.todo-card__action { display: flex; align-items: center; gap: 8px; }
</style>
