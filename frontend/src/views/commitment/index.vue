<template>
  <div>
    <div class="section-head">
      <div class="section-title">贡献度跟踪</div>
      <div class="section-tip">承诺计划与最新履约评估(按角色数据权限:客户经理看本人申请,审批人看本人审批过的客户,小组/行长/管理员看全部)。</div>
    </div>

    <div class="card">
      <div class="card__head">
        <div class="view-switch">
          <button class="btn" :class="view === 'current' ? 'btn--primary' : 'btn--ghost'" @click="view = 'current'">当前承诺</button>
          <button class="btn" :class="view === 'history' ? 'btn--primary' : 'btn--ghost'" @click="view = 'history'">历史承诺</button>
        </div>
        <button class="btn btn--secondary" @click="openPolicies">策略管理</button>
      </div>

      <table class="table">
        <thead>
          <tr>
            <th>计划编号</th><th>客户号</th><th>范围</th><th>指标</th>
            <th>目标值</th><th>实际值</th><th>达成率</th><th>评估结论</th><th>计划状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in filteredPlans" :key="i">
            <td>{{ row.plan_no }}</td>
            <td>{{ row.customer_no || '—' }}</td>
            <td>{{ scopeText(row.scope_type) }}</td>
            <td>{{ row.metric_name || row.metric_code || '—' }}</td>
            <td class="num">{{ row.target_value ?? '—' }}</td>
            <td class="num">{{ row.actual_value ?? '暂无数据' }}</td>
            <td class="num">
              <span v-if="row.achievement_ratio != null" :class="rateClass(row.achievement_ratio)">{{ row.achievement_ratio }}%</span>
              <span v-else>暂无数据</span>
            </td>
            <td><span :class="resultBadge(row.result_status)">{{ resultText(row.result_status) }}</span></td>
            <td><span :class="statusBadge(row.status)">{{ statusText(row.status) }}</span></td>
          </tr>
          <tr v-if="!filteredPlans.length"><td colspan="9" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 指标完成汇总(由计划+最新评估计算) -->
    <div class="card">
      <div class="card__head"><span>指标完成汇总</span></div>
      <div class="desc-grid">
        <div class="desc-item"><span class="desc-label">承诺计划数</span>{{ summary.planCount }}</div>
        <div class="desc-item"><span class="desc-label">承诺指标数</span>{{ summary.metricCount }}</div>
        <div class="desc-item"><span class="desc-label">已达成</span>{{ summary.achieved }}</div>
        <div class="desc-item"><span class="desc-label">有风险</span>{{ summary.atRisk }}</div>
        <div class="desc-item"><span class="desc-label">数据待齐</span>{{ summary.dataPending }}</div>
        <div class="desc-item"><span class="desc-label">平均达成率</span>{{ summary.avgRatio }}</div>
      </div>
    </div>

    <!-- 策略管理弹窗:策略列表 + 试算 -->
    <el-dialog v-model="policyDialog.show" title="跟踪策略管理" width="760px">
      <div class="dlg-section-title">策略列表</div>
      <table class="table">
        <thead>
          <tr><th>策略编号</th><th>名称</th><th>指标</th><th>业务类型</th><th>机构</th><th>优先级</th><th>状态</th></tr>
        </thead>
        <tbody>
          <tr v-for="p in policyDialog.list" :key="p.id">
            <td>{{ p.policyNo }}</td>
            <td>{{ p.policyName }}</td>
            <td>{{ p.metricCode }}</td>
            <td>{{ p.businessType || '不限' }}</td>
            <td>{{ p.orgCode || '通用' }}</td>
            <td class="num">{{ p.priority }}</td>
            <td><span :class="statusBadge(p.status)">{{ statusText(p.status) }}</span></td>
          </tr>
          <tr v-if="!policyDialog.list.length"><td colspan="7" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>

      <div class="dlg-section-title" style="margin-top:16px">策略试算(§11.7:传入历史计划,返回命中策略与预警判定)</div>
      <div class="simulate-bar">
        <select class="form-select" v-model="policyDialog.simPlanId" style="width:280px">
          <option value="">选择承诺计划</option>
          <option v-for="p in planOptions" :key="p.id" :value="p.id">{{ p.plan_no }}({{ p.customer_no || '集团' }})</option>
        </select>
        <button class="btn btn--primary" @click="runSimulate">试算</button>
      </div>
      <table class="table" v-if="policyDialog.simResult" style="margin-top:8px">
        <thead>
          <tr><th>指标</th><th>命中策略</th><th>命中版本</th><th>达成线</th><th>预警线</th><th>达成率</th><th>判定</th></tr>
        </thead>
        <tbody>
          <tr v-for="(m, i) in policyDialog.simResult.metrics || []" :key="i">
            <td>{{ m.metricCode }}</td>
            <td>{{ m.matchedPolicyName || m.matchedPolicyNo || '默认策略' }}</td>
            <td>{{ m.matchedVersionCode || '—' }}</td>
            <td class="num">{{ m.achieveLine ?? '—' }}</td>
            <td class="num">{{ m.atRiskLine ?? '—' }}</td>
            <td class="num">{{ m.achievementRatio != null ? m.achievementRatio + '%' : '暂无数据' }}</td>
            <td><span :class="resultBadge(m.judgeResult)">{{ resultText(m.judgeResult) }}</span></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="section-tip" style="margin-top:8px">选择计划后点击"试算",输出各指标命中策略与判定。</div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { listCommitmentPlans, listTrackingPolicies, simulatePolicy } from '@/api/commitment'

const userStore = useUserStore()

const view = ref<'current' | 'history'>('current')
const plans = ref<any[]>([])

// 计划状态:当前(跟踪中口径) vs 历史(终态口径)
const CURRENT_STATUS = ['PENDING', 'TRACKING', 'AT_RISK', 'DATA_PENDING']
const filteredPlans = computed(() =>
  plans.value.filter((p) =>
    view.value === 'current' ? CURRENT_STATUS.includes(p.status) : !CURRENT_STATUS.includes(p.status)
  )
)

// 指标完成汇总:由计划 + 最新评估计算
const summary = computed(() => {
  const rows = plans.value
  const ratios = rows.map((r) => r.achievement_ratio).filter((v) => v != null)
  return {
    planCount: new Set(rows.map((r) => r.plan_no)).size,
    metricCount: rows.filter((r) => r.metric_code).length,
    achieved: rows.filter((r) => r.result_status === 'ACHIEVED').length,
    atRisk: rows.filter((r) => r.result_status === 'AT_RISK' || r.status === 'AT_RISK').length,
    dataPending: rows.filter((r) => r.result_status === 'DATA_PENDING' || r.status === 'DATA_PENDING').length,
    avgRatio: ratios.length ? (ratios.reduce((a, b) => a + Number(b), 0) / ratios.length).toFixed(1) + '%' : '暂无数据'
  }
})

async function load() {
  const userId = userStore.userInfo?.userId
  if (!userId) {
    plans.value = []
    return
  }
  try {
    plans.value = await listCommitmentPlans(userId, userStore.userInfo?.roles?.[0])
  } catch {
    plans.value = []
  }
}

// ---------- 策略管理 ----------
const policyDialog = reactive({
  show: false,
  list: [] as any[],
  simPlanId: '' as any,
  simResult: null as any
})

// 试算计划候选:按计划号去重
const planOptions = computed(() => {
  const seen = new Map<string, any>()
  for (const p of plans.value) {
    if (!seen.has(p.plan_no)) seen.set(p.plan_no, p)
  }
  return [...seen.values()]
})

async function openPolicies() {
  policyDialog.show = true
  policyDialog.simResult = null
  try {
    policyDialog.list = await listTrackingPolicies()
  } catch {
    policyDialog.list = []
  }
}
async function runSimulate() {
  if (!policyDialog.simPlanId) {
    ElMessage.warning('请选择承诺计划')
    return
  }
  try {
    policyDialog.simResult = await simulatePolicy(Number(policyDialog.simPlanId))
  } catch {
    policyDialog.simResult = null
  }
}

// ---------- 展示映射 ----------
function scopeText(s: string) {
  const map: Record<string, string> = {
    INDIVIDUAL: '个人', CORPORATE_SINGLE: '企业单户', MEMBER: '集团成员', GROUP: '集团'
  }
  return map[s] || s || '—'
}
function rateClass(ratio: number) {
  return ratio >= 80 ? 'badge badge--success' : ratio >= 50 ? 'badge badge--warning' : 'badge badge--danger'
}
function statusText(s: string) {
  const map: Record<string, string> = {
    PENDING: '待生效', TRACKING: '跟踪中', AT_RISK: '有风险', ACHIEVED: '已达成',
    EXPIRED_UNMET: '到期未达成', DATA_PENDING: '数据待齐', TERMINATED: '已终止', SUPERSEDED: '已被替代',
    DRAFT: '草稿', REVIEW: '待复核', EFFECTIVE: '已生效', INVALID: '已停用'
  }
  return map[s] || s || '—'
}
function statusBadge(s: string) {
  const map: Record<string, string> = {
    TRACKING: 'badge badge--info', PENDING: 'badge badge--warning', AT_RISK: 'badge badge--warning',
    ACHIEVED: 'badge badge--success', EFFECTIVE: 'badge badge--success',
    DATA_PENDING: 'badge badge--warning', DRAFT: 'badge badge--neutral', REVIEW: 'badge badge--warning'
  }
  return map[s] || 'badge badge--neutral'
}
function resultText(s: string) {
  const map: Record<string, string> = {
    ACHIEVED: '已达成', AT_RISK: '有风险', DATA_PENDING: '数据待齐',
    NO_EVALUATION: '暂无评估', ON_TRACK: '正常'
  }
  return map[s] || s || '—'
}
function resultBadge(s: string) {
  const map: Record<string, string> = {
    ACHIEVED: 'badge badge--success', AT_RISK: 'badge badge--warning',
    DATA_PENDING: 'badge badge--neutral', ON_TRACK: 'badge badge--info'
  }
  return map[s] || 'badge badge--neutral'
}

onMounted(load)
</script>

<style scoped>
.section-head { margin-bottom: 16px; }
.section-title { font-size: var(--fs-h2); font-weight: 600; margin-bottom: 6px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
.card { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow-sm); margin-bottom: 16px; }
.card__head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.view-switch { display: flex; gap: 8px; }
.table { border-radius: var(--radius); overflow: hidden; }
.empty-cell { text-align: center; color: var(--color-text-light); padding: 24px 0; }
.desc-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px 16px; font-size: 14px; }
.desc-item { display: flex; flex-direction: column; gap: 2px; }
.desc-label { font-size: 12px; color: var(--color-text-light); }
.dlg-section-title { font-weight: 600; margin-bottom: 8px; }
.simulate-bar { display: flex; gap: 8px; }
</style>
