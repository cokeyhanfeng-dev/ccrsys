<template>
  <div>
    <div class="section-head">
      <div class="section-title">总行行长决策工作台 · 待我决策</div>
      <InfoTip content="仅在六人审批完成后接收汇总结果和全链路审批流程;统一执行&quot;行长同意&quot;或&quot;行长一票否决&quot;,汇总结果仅行领导可见。" />
    </div>

    <!-- 统计卡片(真实数据) -->
    <div class="stat-row">
      <div class="stat-card">
        <span class="stat-card__label">待我决策</span>
        <b class="stat-card__num stat-card__num--primary">{{ cards.length }} 笔</b>
        <div class="stat-card__sub">六人小组表决通过待决策分项</div>
      </div>
    </div>

    <!-- 待决策卡片列表 -->
    <div class="todo-list">
      <div class="todo-card" v-for="c in cards" :key="c.id">
        <div class="todo-card__body">
          <div class="todo-card__customer">{{ c.customer }}</div>
          <div class="todo-card__summary">六人表决 {{ c.votes }} 通过 · 申请利率 {{ c.rate }}% · 审批利率 {{ c.approvalRate }}%</div>
          <div class="todo-card__meta">
            <span class="badge badge--success">六人审批已通过</span>
            <span class="badge badge--info">分项 {{ c.pricingItemNo }}</span>
          </div>
        </div>
        <div class="todo-card__action">
          <button class="btn btn--primary" @click="openDetail(c)">进入行长决策</button>
        </div>
      </div>
      <div class="empty" v-if="!cards.length">暂无待决策申请</div>
    </div>

    <!-- 行长决策详情 -->
    <div class="modal" v-if="detail.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">行长决策 · {{ detail.customer }}</div>
        <div class="modal__body" v-if="detail.loaded">
          <!-- 汇总票数 + 建议利率 -->
          <div class="detail-block">
            <div class="detail-block__title">六人表决汇总</div>
            <div class="vote-summary" v-if="detail.voteResult">
              <span class="badge badge--success">{{ detail.voteResult.approveCount }} 票赞成</span>
              <span class="badge badge--danger">{{ detail.voteResult.rejectCount }} 票反对</span>
              <span class="badge badge--info">需 {{ detail.voteResult.requiredCount }} 票 · 已收 {{ detail.voteResult.submittedCount }} 票</span>
              <span class="badge badge--info">最终建议利率 {{ detail.rate }}%</span>
            </div>
            <div v-else class="empty">暂无计票结果</div>
          </div>

          <!-- 六人小组表决意见(匿名,§12.7,默认收起) -->
          <div class="detail-block">
            <el-collapse>
              <el-collapse-item title="六人小组表决意见(匿名)" name="opinions">
                <table class="table" v-if="detail.opinions.length">
                  <thead><tr><th>委员(匿名)</th><th>表决</th><th>意见</th><th>提交时间</th></tr></thead>
                  <tbody>
                    <tr v-for="(o, i) in detail.opinions" :key="i">
                      <td>{{ o.anonymNo || '—' }}</td>
                      <td>
                        <span class="badge" :class="o.voteChoice === 'APPROVE' ? 'badge--success' : 'badge--danger'">
                          {{ voteChoiceText(o.voteChoice) }}
                        </span>
                      </td>
                      <td>{{ o.voteComment || '—' }}</td>
                      <td>{{ o.submitTime ? String(o.submitTime).replace('T', ' ').slice(0, 16) : '—' }}</td>
                    </tr>
                  </tbody>
                </table>
                <div v-else class="empty">暂无委员意见数据</div>
              </el-collapse-item>
            </el-collapse>
          </div>

          <!-- 贡献度 / 资料校验 -->
          <div class="detail-block">
            <div class="detail-block__title">审批参考</div>
            <div class="detail-grid">
              <div><span class="dg-label">当前贡献度</span>{{ detail.contribution }}</div>
              <div><span class="dg-label">拟达成贡献度</span>{{ detail.commitment }}</div>
              <!-- P1-2:历史履约/机构达成接数(approval detail tracking/orgPerformance) -->
              <div><span class="dg-label">历史履约</span>{{ trackingSummary() }}</div>
              <div><span class="dg-label">机构达成</span>{{ orgSummary() }}</div>
              <div>
                <span class="dg-label">资料校验</span>
                <span v-if="detail.qualityOverall" class="badge" :class="detail.qualityOverall === 'BLOCK' ? 'badge--danger' : detail.qualityOverall === 'WARN' ? 'badge--warning' : 'badge--success'">
                  {{ detail.qualityOverall === 'BLOCK' ? '阻断' : detail.qualityOverall === 'WARN' ? '预警' : '通过' }}
                </span>
                <span v-else>暂无数据</span>
              </div>
            </div>
          </div>

          <!-- 流程轨迹 -->
          <div class="detail-block">
            <div class="detail-block__title">流程轨迹</div>
            <table class="table" v-if="detail.flowTrace.length">
              <thead><tr><th>时间</th><th>节点</th><th>动作</th><th>利率变化</th><th>意见</th></tr></thead>
              <tbody>
                <tr v-for="(t, i) in detail.flowTrace" :key="i">
                  <td>{{ t.operationTime || '—' }}</td>
                  <td>{{ nodeLabel(t.nodeCode) }}</td>
                  <td>{{ actionText(t.actionType) }}</td>
                  <td class="num">{{ t.beforeRate != null && t.afterRate != null && t.beforeRate !== t.afterRate ? `${t.beforeRate}% → ${t.afterRate}%` : '—' }}</td>
                  <td>{{ t.actionComment || '—' }}</td>
                </tr>
              </tbody>
            </table>
            <div v-else class="empty">暂无数据</div>
          </div>

          <!-- 申请备注 -->
          <div class="detail-block" v-if="detail.remark">
            <div class="detail-block__title">申请备注(客户经理)</div>
            <div class="remark-text">{{ detail.remark }}</div>
          </div>

          <!-- 决策意见 -->
          <div class="detail-block">
            <div class="detail-block__title">决策意见</div>
            <el-input v-model="detail.opinion" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="同意可填意见;一票否决必须填写意见" />
          </div>
        </div>
        <div class="modal__body" v-else><div class="empty">加载中...</div></div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="detail.show = false">返回</button>
          <button class="btn btn--primary" :disabled="submitting" @click="approve">同意利率</button>
          <button class="btn btn--danger" :disabled="submitting" @click="veto">一票否决</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listPresidentTodo, getVoteResult, submitPresidentDecision } from '@/api/vote'
import { getApprovalDetail } from '@/api/approval'
import { listRoundOpinions } from '@/api/approval2'
import { nodeLabel, actionText, voteChoiceText, metricName } from '@/utils/dict'

const cards = ref<any[]>([])
const submitting = ref(false)
const detail = ref<any>({
  show: false, loaded: false, id: null, customer: '', rate: '', opinion: '',
  voteResult: null, contribution: '—', commitment: '—', qualityOverall: '', flowTrace: [], remark: '',
  opinions: [], tracking: [], orgPerformance: []
})

// 行长待决策(表决通过的定价分项)
async function load() {
  try {
    const data = await listPresidentTodo<any[]>()
    cards.value = (data || []).map((p) => ({
      id: p.pricingItemId,
      pricingItemNo: p.pricingItemNo || p.pricingItemId,
      customer: p.customerNo || '-',
      votes: `${p.approveCount}:${p.rejectCount}`,
      rate: p.requestedRate ?? '-',
      approvalRate: p.approvalRate ?? '-'
    }))
  } catch {
    cards.value = []
  }
}

async function openDetail(c: any) {
  detail.value = {
    show: true, loaded: false, id: c.id, customer: c.customer, rate: c.approvalRate !== '-' ? c.approvalRate : c.rate,
    opinion: '', voteResult: null, contribution: '—', commitment: '—', qualityOverall: '', flowTrace: [], remark: '',
    opinions: [], tracking: [], orgPerformance: []
  }
  try {
    const [d, vr] = await Promise.all([
      getApprovalDetail(c.id),
      getVoteResult(c.id).catch(() => null)
    ])
    const contribution: any[] = d.contribution || []
    const total = contribution.find((m) => m.metricCode === 'TOTAL')
    detail.value.contribution = total ? `${total.metricValue} ${total.valueType || ''}`.trim() : (contribution.length ? `${contribution.length} 项指标` : '暂无数据')
    const commitments: any[] = d.commitments || []
    detail.value.commitment = commitments.length
      ? commitments.map((m) => `${metricName(m.metricCode)} ${m.baselineValue ?? '—'}→${m.targetValue ?? '—'}${m.unit || ''}`).join(';')
      : '暂无数据'
    detail.value.qualityOverall = d.qualityOverall || ''
    detail.value.flowTrace = d.flowTrace || []
    detail.value.remark = d.application?.[0]?.applicationRemark || ''
    // P1-2:历史履约/机构达成接数(审批详情接口已返回 tracking/orgPerformance)
    detail.value.tracking = d.tracking || []
    detail.value.orgPerformance = d.orgPerformance || []
    detail.value.voteResult = vr
    // 委员匿名意见(§12.7):按计票结果 roundId 查询,过滤本分项
    if (vr?.roundId) {
      try {
        const rounds = await listRoundOpinions(vr.roundId)
        const row = (rounds || []).find((r: any) => String(r.pricingItemId) === String(c.id))
        detail.value.opinions = row?.opinions || []
      } catch {
        detail.value.opinions = []
      }
    }
    detail.value.loaded = true
  } catch {
    ElMessage.error('决策详情加载失败')
    detail.value.show = false
  }
}

// P1-2:历史履约/机构达成摘要(审批详情接口已返回 tracking/orgPerformance)
function trackingSummary() {
  const list = detail.value.tracking || []
  if (!list.length) return '暂无数据'
  const risk = list.filter((t: any) => t.resultStatus === 'AT_RISK').length
  return `${list.length} 项最新评估${risk ? `,${risk} 项风险` : ''}`
}
function orgSummary() {
  const list = detail.value.orgPerformance || []
  if (!list.length) return '暂无数据'
  const latest = list[0]
  return latest.completionRate != null ? `完成率 ${latest.completionRate}%` : '已同步(无数值)'
}

async function approve() {
  // P2-1:行长同意为不可逆终态,提交前二次确认最终利率
  try {
    await ElMessageBox.confirm(`确认同意利率 ${detail.value.rate}%?同意后将触发决议签发,不可撤销。`, '同意利率', {
      type: 'info', confirmButtonText: '确认同意', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  submitting.value = true
  try {
    await submitPresidentDecision({
      pricingItemId: detail.value.id,
      decision: 'APPROVE',
      opinion: detail.value.opinion || undefined
    })
    ElMessage.success(`已同意利率 ${detail.value.rate}%`)
    detail.value.show = false
    load()
  } catch { /* 拦截器提示 */ } finally {
    submitting.value = false
  }
}

function veto() {
  if (!detail.value.opinion?.trim()) {
    ElMessage.warning('一票否决必须填写决策意见')
    return
  }
  ElMessageBox.confirm(`确认一票否决 ${detail.value.customer}?否决后为终态。`, '一票否决', {
    type: 'warning', confirmButtonText: '确认否决', cancelButtonText: '取消'
  })
    .then(async () => {
      submitting.value = true
      try {
        await submitPresidentDecision({
          pricingItemId: detail.value.id,
          decision: 'VETO',
          opinion: detail.value.opinion
        })
        ElMessage.warning(`已一票否决 ${detail.value.customer}`)
        detail.value.show = false
        load()
      } catch { /* 拦截器提示 */ } finally {
        submitting.value = false
      }
    })
    .catch(() => undefined)
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
.modal__card--wide { max-width: 720px; max-height: 90vh; overflow-y: auto; }
.detail-block { margin-bottom: 16px; }
.detail-block__title { font-weight: 600; margin-bottom: 8px; }
.detail-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px 16px; font-size: 14px; }
.dg-label { color: var(--color-text-sub); margin-right: 6px; }
.vote-summary { display: flex; gap: 12px; flex-wrap: wrap; }
.table { border-radius: var(--radius-sm); overflow-x: auto; }
.remark-text { font-size: 14px; background: var(--color-bg); border-radius: 6px; padding: 12px; line-height: 1.6; }
</style>
