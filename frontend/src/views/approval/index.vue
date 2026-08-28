<template>
  <div>
    <div class="section-head">
      <div class="section-title">利率审批</div>
      <InfoTip content="流转到本人当前审批节点、需要处理的申请列表(按登录人角色过滤);已办与统计见工作台。" />
    </div>



    <!-- 待办卡片列表(按申请聚合:一个申请一张卡片,多分项在卡内展开) -->
    <div class="todo-list" v-loading="loading">
      <div class="empty" v-if="loadError">
        加载失败,请刷新
        <div style="margin-top:12px"><button class="btn btn--secondary" @click="load">重新加载</button></div>
      </div>
      <template v-else>
      <div class="todo-card" v-for="c in todoCards" :key="c.applicationId"
           tabindex="0" role="button"
           :aria-label="`进入 ${c.customer} 的完整审批`"
           @click="goDetail(c)"
           @keydown.enter.prevent="goDetail(c)"
           @keydown.space.prevent="goDetail(c)">
        <div class="todo-card__body">
          <div class="todo-card__customer">
            {{ c.customer }}
            <span class="tc-badge" v-if="!c.single">{{ c.itemCount }} 个担保分项</span>
          </div>
          <div class="todo-card__sub" v-if="c.single">申请 {{ c.applicationNo }} · 当前节点 {{ c.nodeText }}</div>
          <div class="todo-card__sub" v-else>申请 {{ c.applicationNo }} · 当前节点 {{ c.nodeText }} · 需完成 {{ c.itemCount }} 个担保分项</div>
          <!-- 卡片摘要键值:统一 design-system 描述列表 -->
          <div class="desc-grid desc-grid--3">
            <div class="desc-item"><div class="desc-item__label">申请利率</div><div class="desc-item__value">{{ c.rate }}{{ c.single ? '%' : '' }}</div></div>
            <div class="desc-item"><div class="desc-item__label">原执行利率</div><div class="desc-item__value">{{ c.originalRate }}</div></div>
            <div class="desc-item"><div class="desc-item__label">分项金额</div><div class="desc-item__value">{{ fmtAmount(c.amount) }} 万元</div></div>
            <div class="desc-item"><div class="desc-item__label">产品编码</div><div class="desc-item__value">{{ c.productCode }}</div></div>
            <div class="desc-item"><div class="desc-item__label">提交时间</div><div class="desc-item__value">{{ c.createTime }}</div></div>
          </div>
          <div class="todo-card__items" v-if="!c.single">
            <div class="tc-item-row" v-for="it in c.items" :key="it.id">
              <span class="tc-item-row__no">{{ it.pricingItemNo }}</span>
              <span>{{ fmtAmount(it.amount) }} 万元</span>
              <span>申请 {{ it.rate }}%</span>
              <span>原执行 {{ it.originalRate }}%</span>
              <span class="section-tip">{{ nodeLabel(it.nodeCode) }}</span>
            </div>
          </div>
        </div>
        <div class="todo-card__action">
          <button class="btn btn--secondary" @click.stop="openCheck(c)">核验资料</button>
          <button class="btn btn--primary" @click.stop="goDetail(c)">进入完整审批</button>
        </div>
      </div>
      <div class="empty" v-if="!loading && !todoCards.length">暂无待审批任务</div>
      </template>
    </div>


    <!-- 核验资料弹层(§12.8:6 格摘要 + 进入完整审批) -->
    <div class="modal" v-if="check.show">
      <div class="modal__card">
        <div class="modal__title">核验资料 · {{ check.customer }}</div>
        <div class="modal__body" v-if="check.loaded">
          <div class="desc-grid desc-grid--2">
            <div class="desc-item"><div class="desc-item__label">客户</div><div class="desc-item__value">{{ check.customer }}</div></div>
            <div class="desc-item"><div class="desc-item__label">金额</div><div class="desc-item__value">{{ check.amount }}</div></div>
            <div class="desc-item"><div class="desc-item__label">申请利率</div><div class="desc-item__value">{{ check.rate }}</div></div>
            <div class="desc-item"><div class="desc-item__label">原利率</div><div class="desc-item__value">{{ check.originalRate }}</div></div>
            <div class="desc-item"><div class="desc-item__label">快照数据日期</div><div class="desc-item__value">{{ check.dataDt }}</div></div>
          </div>
        </div>
        <div class="modal__body" v-else-if="check.error">
          <div class="empty">
            {{ check.error }}
            <div style="margin-top:12px">
              <button class="btn btn--secondary" @click="openCheck(check)">重试</button>
            </div>
          </div>
        </div>
        <div class="modal__body" v-else>
          <div class="check-loading">
            <el-icon class="is-loading" style="font-size:22px"><Loading /></el-icon>
            <span>核验资料加载中...</span>
          </div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="check.show = false">关闭</button>
          <button class="btn btn--primary" @click="goDetail(check)">进入完整审批</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listApprovalTasks, getApprovalDetail } from '@/api/approval'
import { listVoteTodo, listPresidentTodo } from '@/api/vote'
import { useUserStore } from '@/store/user'
import { nodeLabel, itemStatusText, actionText, productName } from '@/utils/dict'
import { fmtAmount } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()
const todoCards = ref<any[]>([])
const loading = ref(true)
const loadError = ref(false)
// 委员身份(role_code=committee_member 或六人小组配置名单兼岗,§D-7):待办走表决待办(listVoteTodo),入口与普通审批一致
const isCommitteeMember = computed(() => (userStore.userInfo?.roles || []).includes('committee_member'))
// 审批角色(§D-7 兼岗):与委员身份并存,同时加载普通审批待办
const isApprovalRole = computed(() => (userStore.userInfo?.roles || [])
  .some((r) => ['branch_manager', 'dept_gm', 'vice_president'].includes(r)))
// 行长(总行):待决策分项走行长决策接口(表决通过/行长决议状态),在利率审批页一并展示
const isPresident = computed(() => (userStore.userInfo?.roles || []).includes('president'))

const check = ref<any>({
  show: false, loaded: false, id: null,
  customer: '', amount: '', rate: '', originalRate: '', dataDt: '', error: ''
})

function statusText(s?: string) {
  return itemStatusText(s)
}

async function load() {
  loading.value = true
  loadError.value = false
  try {
    // §D-7 兼岗:委员身份走表决待办、审批角色走普通审批待办、行长走行长决策待办,合并后统一按申请聚合
    const [voteRows, taskRows, presRows] = await Promise.all([
      isCommitteeMember.value ? listVoteTodo<any[]>() : Promise.resolve([]),
      isApprovalRole.value ? listApprovalTasks<any[]>() : Promise.resolve([]),
      isPresident.value ? listPresidentTodo<any[]>() : Promise.resolve([])
    ])
    // 行长待决策为按申请聚合(items 分项明细在顶层),展平成分项行以便统一聚合(与审批/表决待办同粒度)
    const presFlat = (presRows || []).flatMap((p: any) =>
      (p.items || []).map((it: any) => ({
        applicationId: p.applicationId, applicationNo: p.applicationNo,
        businessType: p.businessType, customerNo: p.customerNo,
        id: it.pricingItemId, pricingItemId: it.pricingItemId, pricingItemNo: it.pricingItemNo,
        pricingAmount: it.pricingAmount, requestedRate: it.requestedRate,
        originalRate: it.originalRate, productCode: it.productCode,
        status: it.status, currentNodeCode: 'PRESIDENT', createTime: ''
      })))
    const data = [...(voteRows || []), ...(taskRows || []), ...presFlat]
    // 待办以申请为粒度:同申请多分项聚合为一张卡片,进入详情后一次性完成全部担保分项
    const byApp = new Map<string, any[]>()
    for (const p of data) {
      // 委员待办行含 applicationId(申请聚合,与普通审批一致);无则按分项兜底
      const appId = p.applicationId || p.id || p.pricingItemId || p.roundId || ''
      if (!byApp.has(appId)) byApp.set(appId, [])
      byApp.get(appId)!.push(p)
    }
    todoCards.value = [...byApp.entries()].map(([appId, items]) => {
      const first = items[0]
      const rates = items.map((x) => Number(x.requestedRate) || 0)
      const single = items.length === 1
      // 表决待办行主键为 pricingItemId、审批待办行为 id,按行取其一
      const keyId = first.pricingItemId || first.id
      return {
        id: keyId, // 进入申请详情用组内第一个分项
        applicationId: appId,
        applicationNo: first.applicationNo || '-',
        itemCount: items.length,
        single,
        pricingItemNo: single ? (first.pricingItemNo || keyId) : `${items.length} 个担保分项`,
        customer: first.pricingCustomerNo || first.customerNo || '-',
        amount: single ? (first.pricingAmount ?? '-') : items.reduce((s, x) => s + (Number(x.pricingAmount) || 0), 0),
        rate: single ? (first.requestedRate ?? '-') : (rates.length ? `${Math.min(...rates)} ~ ${Math.max(...rates)}` : '-'),
        // 原执行利率按全部分项收集:有值显区间、全空显「新增业务」,不再只取第一个分项
        originalRate: (() => {
          const ors = items.map((x) => x.originalRate).filter((v) => v != null && v !== '').map(Number)
          return ors.length ? (ors.length === 1 ? `${ors[0]}%` : `${Math.min(...ors)} ~ ${Math.max(...ors)}%`) : '新增业务'
        })(),
        productCode: productName(first.productCode),
        nodeText: nodeLabel(first.currentNodeCode),
        createTime: first.createTime ? String(first.createTime).replace('T', ' ').slice(0, 16) : '—',
        items: items.map((x) => ({
          id: x.pricingItemId || x.id,
          pricingItemNo: x.pricingItemNo || x.pricingItemId || x.id,
          amount: x.pricingAmount ?? '-', rate: x.requestedRate ?? '-',
          originalRate: x.originalRate != null ? x.originalRate : '新增业务',
          nodeCode: nodeLabel(x.currentNodeCode), status: x.status
        }))
      }
    })
  } catch {
    todoCards.value = []
    loadError.value = true
  } finally {
    loading.value = false
  }
}


// 核验资料(§12.8):取审批详情的摘要信息
async function openCheck(c: any) {
  check.value = {
    show: true, loaded: false, id: c.id,
    customer: c.customer, amount: `${fmtAmount(c.amount)} 万元`, rate: `${c.rate}%`,
    originalRate: c.originalRate, qualityOverall: '', dataDt: '—', error: ''
  }
  try {
    const d = await getApprovalDetail(c.id)
    const pi = d.pricingItem || {}
    const customer = d.customer?.[0] || {}
    check.value.customer = customer.customerName || pi.pricing_customer_no || c.customer
    check.value.amount = pi.pricing_amount != null ? `${fmtAmount(pi.pricing_amount)} 万元` : `${fmtAmount(c.amount)} 万元`
    check.value.rate = pi.requested_rate != null ? `${pi.requested_rate}%` : `${c.rate}%`
    check.value.originalRate = pi.original_rate != null ? `${pi.original_rate}%` : '新增业务'
    check.value.dataDt = d.source === 'SNAPSHOT' ? (d.snapshotInfo?.dataDt || '—') : '实时取数(未冻结快照)'
    check.value.loaded = true
  } catch {
    check.value.error = '核验资料加载失败,请重试或进入完整审批查看'
  }
}

function goDetail(c: any) {
  check.value.show = false
  router.push(`/approval/${c.id}`)
}

onMounted(load)
</script>

<style scoped>
.todo-list { display: flex; flex-direction: column; gap: 12px; }
.todo-card { cursor: pointer; }
.todo-card:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.todo-card__customer { font-weight: 600; font-size: 16px; }
.tc-badge { display: inline-block; margin-left: 8px; padding: 1px 8px; border-radius: 10px; font-size: 12px; font-weight: 500; color: #b45309; background: #fef3c7; vertical-align: middle; }
.todo-card__sub { font-size: 13px; color: var(--color-text-sub); margin: 2px 0 10px; }
.todo-card__items { margin-top: 10px; padding: 8px 12px; border: 1px dashed var(--color-border, #ddd); border-radius: var(--radius-sm, 6px); display: flex; flex-direction: column; gap: 6px; font-size: 13px; }
.tc-item-row { display: flex; gap: 18px; align-items: center; }
.tc-item-row__no { font-weight: 600; min-width: 140px; }
.todo-card__action { display: flex; align-items: center; gap: 8px; }
.check-loading { display: flex; align-items: center; justify-content: center; gap: 8px; min-height: 80px; color: var(--color-text-sub); }
/* 768px 断点:操作区换到卡片下方,避免挤压摘要内容 */
@media (max-width: 767px) {
  .todo-card { flex-direction: column; align-items: stretch; gap: 10px; }
  .todo-card__action { justify-content: flex-end; }
}
</style>
