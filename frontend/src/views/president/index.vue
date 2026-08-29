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
          <div class="todo-card__customer">{{ c.customer }}</div>
          <div class="todo-card__summary">
            六人审批结果:{{ c.votesText }} · 申请利率 {{ c.rate }}%
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
      return {
        applicationId: p.applicationId,
        applicationNo: p.applicationNo || '-',
        customer: p.customerNo || '-',
        itemCount: items.length || 1,
        votesText: first.approveCount != null
          ? `赞成 ${first.approveCount} 票 / 反对 ${first.rejectCount ?? 0} 票`
          : '—',
        rate: first.requestedRate ?? '-'
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
