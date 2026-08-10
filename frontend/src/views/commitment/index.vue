<template>
  <div>
    <div class="section-head">
      <div class="section-title">贡献度跟踪</div>
      <div class="section-tip">承诺计划三级钻取(§12.11):客户 → 承诺记录 → 指标明细;数据范围由服务端按登录人角色确定。</div>
    </div>

    <!-- 返回上级导航(二级/三级) -->
    <div class="breadcrumb-bar" v-if="level > 1">
      <button class="btn btn--secondary" @click="goUp">← 返回上级</button>
      <span class="section-tip">
        {{ level === 2 ? `客户 ${currentCustomer}` : `客户 ${currentCustomer} · 计划 ${currentPlan?.plan_no || ''}` }}
      </span>
    </div>

    <!-- ============ 一级:客户列表(按客户聚合) ============ -->
    <template v-if="level === 1">
      <div class="stat-row">
        <div class="stat-card">
          <span class="stat-card__label">跟踪中</span>
          <b class="stat-card__num stat-card__num--primary">{{ statCards.tracking }}</b>
          <div class="stat-card__sub">生效跟踪中的承诺计划</div>
        </div>
        <div class="stat-card">
          <span class="stat-card__label">有风险</span>
          <b class="stat-card__num stat-card__num--warning">{{ statCards.atRisk }}</b>
          <div class="stat-card__sub">计划或指标评估有风险</div>
        </div>
        <div class="stat-card">
          <span class="stat-card__label">已达成</span>
          <b class="stat-card__num stat-card__num--success">{{ statCards.achieved }}</b>
          <div class="stat-card__sub">评估已达成的承诺指标</div>
        </div>
      </div>

      <div class="card">
        <div class="card__head">
          <span>客户承诺概览</span>
          <button class="btn btn--secondary" @click="openPolicies">策略管理</button>
        </div>
        <table class="table" v-if="customerRows.length">
          <thead>
            <tr><th>客户号</th><th>计划数</th><th>指标数</th><th>平均达成率</th><th>有风险指标</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="c in customerRows" :key="c.customerNo">
              <td>{{ c.customerNo }}</td>
              <td class="num">{{ c.planCount }}</td>
              <td class="num">{{ c.metricCount }}</td>
              <td class="num">
                <span v-if="c.avgRatio != null" :class="ratioClass(c.avgRatio)">{{ c.avgRatio }}%</span>
                <span v-else>暂无数据</span>
              </td>
              <td class="num">
                <span v-if="c.atRiskCount" class="badge badge--danger">{{ c.atRiskCount }} 项</span>
                <span v-else class="badge badge--success">0 项</span>
              </td>
              <td><button class="btn btn--text" @click="enterCustomer(c.customerNo)">查看承诺记录</button></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">暂无数据</div>
      </div>
    </template>

    <!-- ============ 二级:该客户的承诺记录(当前/历史分组) ============ -->
    <template v-else-if="level === 2">
      <div class="card">
        <div class="card__head"><span>当前承诺</span><span class="badge badge--info">{{ currentPlans.length }} 个计划</span></div>
        <div class="plan-grid" v-if="currentPlans.length">
          <div class="plan-card" v-for="p in currentPlans" :key="p.id" @click="enterPlan(p)">
            <div class="plan-card__head">
              <b>{{ p.plan_no }}</b>
              <span :class="statusBadge(p.status)">{{ statusText(p.status) }}</span>
            </div>
            <div class="plan-card__meta">
              <span class="dg-label">范围</span>{{ scopeText(p.scope_type) }}
              <span class="dg-label" style="margin-left:12px">指标</span>{{ p.metrics.length }} 项
            </div>
            <div class="plan-card__meta">
              <span class="dg-label">平均达成率</span>
              <span v-if="p.avgRatio != null" :class="ratioClass(p.avgRatio)">{{ p.avgRatio }}%</span>
              <span v-else>暂无数据</span>
            </div>
          </div>
        </div>
        <div v-else class="empty">暂无当前承诺</div>
      </div>

      <div class="card">
        <div class="card__head"><span>历史承诺</span><span class="badge badge--neutral">{{ historyPlans.length }} 个计划</span></div>
        <div class="plan-grid" v-if="historyPlans.length">
          <div class="plan-card" v-for="p in historyPlans" :key="p.id" @click="enterPlan(p)">
            <div class="plan-card__head">
              <b>{{ p.plan_no }}</b>
              <span :class="statusBadge(p.status)">{{ statusText(p.status) }}</span>
            </div>
            <div class="plan-card__meta">
              <span class="dg-label">范围</span>{{ scopeText(p.scope_type) }}
              <span class="dg-label" style="margin-left:12px">指标</span>{{ p.metrics.length }} 项
            </div>
            <div class="plan-card__meta">
              <span class="dg-label">平均达成率</span>
              <span v-if="p.avgRatio != null" :class="ratioClass(p.avgRatio)">{{ p.avgRatio }}%</span>
              <span v-else>暂无数据</span>
            </div>
          </div>
        </div>
        <div v-else class="empty">暂无历史承诺</div>
      </div>
    </template>

    <!-- ============ 三级:指标钻取(计划详情) ============ -->
    <template v-else>
      <div class="card">
        <div class="card__head">
          <span>计划 {{ currentPlan?.plan_no }}</span>
          <span :class="statusBadge(currentPlan?.status)">{{ statusText(currentPlan?.status) }}</span>
        </div>
        <div class="detail-grid">
          <div><span class="dg-label">客户号</span>{{ currentPlan?.customer_no || '—' }}</div>
          <div><span class="dg-label">范围</span>{{ scopeText(currentPlan?.scope_type) }}</div>
          <div><span class="dg-label">指标数</span>{{ planMetrics.length }} 项</div>
        </div>
      </div>

      <!-- 指标完成进度(绿≥100%/黄≥80%/红<80%) -->
      <div class="card">
        <div class="card__head"><span>指标完成进度</span></div>
        <div v-for="(m, i) in planMetrics" :key="i" class="metric-row">
          <div class="metric-row__head">
            <b>{{ m.metric_name || metricName(m.metric_code) }}</b>
            <span v-if="m.metric_code !== 'OTHER'" class="dg-label">目标 {{ m.target_value ?? '—' }} · 实际 {{ m.actual_value ?? '暂无数据' }}</span>
            <span v-else class="dg-label">其它手工承诺(§6.4)</span>
            <span :class="resultBadge(m.result_status)">{{ resultText(m.result_status) }}</span>
          </div>
          <!-- §6.4 "其它"承诺:无数值达成率/进度条,不参与机构达成率(D19);描述录入依赖后端 track_desc 接口 -->
          <div v-if="m.metric_code === 'OTHER'" class="section-tip" style="margin-top:6px">
            手工描述跟踪,无数值达成率、不参与机构达成率(D19);跟踪反馈录入依赖后端 track_desc 字段/接口(登记依赖未就绪)。
          </div>
          <el-progress v-else
            :percentage="progressPct(m.achievement_ratio)"
            :color="progressColor(m.achievement_ratio)"
            :format="() => (m.achievement_ratio != null ? `${m.achievement_ratio}%` : '暂无数据')"
          />
        </div>
        <div v-if="!planMetrics.length" class="empty">暂无指标数据</div>
      </div>

      <!-- 评估历史(计划详情接口) -->
      <div class="card">
        <div class="card__head"><span>评估历史</span></div>
        <table class="table" v-if="evaluations.length">
          <thead><tr><th>指标</th><th>数据日期</th><th>实际值</th><th>达成率</th><th>结论</th></tr></thead>
          <tbody>
            <tr v-for="(e, i) in evaluations" :key="i">
              <td>{{ metricName(e.metricCode || e.metric_code) }}</td>
              <td>{{ e.dataDt || e.data_dt || '—' }}</td>
              <td class="num">{{ e.actualValue ?? e.actual_value ?? '—' }}</td>
              <td class="num">
                <span v-if="(e.achievementRatio ?? e.achievement_ratio) != null" :class="ratioClass(Number(e.achievementRatio ?? e.achievement_ratio))">
                  {{ e.achievementRatio ?? e.achievement_ratio }}%
                </span>
                <span v-else>暂无数据</span>
              </td>
              <td><span :class="resultBadge(e.resultStatus || e.result_status)">{{ resultText(e.resultStatus || e.result_status) }}</span></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">暂无评估历史</div>
      </div>

      <!-- 月报入口(§12.11) -->
      <div class="card">
        <div class="card__head"><span>承诺月报</span></div>
        <div class="report-bar">
          <input class="form-input" type="month" v-model="reportMonth" style="width:180px" />
          <input class="form-input" v-model="reportOrgId" placeholder="机构ID(可空,默认本机构)" style="width:200px" />
          <button class="btn btn--primary" :disabled="reportLoading" @click="loadReport">查询月报</button>
        </div>
        <table class="table" v-if="reportRows.length" style="margin-top:12px">
          <thead><tr><th v-for="h in reportHeaders" :key="h">{{ h }}</th></tr></thead>
          <tbody>
            <tr v-for="(r, i) in reportRows" :key="i">
              <td v-for="h in reportHeaders" :key="h">{{ r[h] ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty" style="margin-top:12px">{{ reportHint }}</div>
      </div>
    </template>

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
            <td>{{ metricName(p.metricCode) }}</td>
            <td>{{ businessTypeText(p.businessType, '不限') }}</td>
            <td>{{ p.orgCode || '通用' }}</td>
            <td class="num">{{ p.priority }}</td>
            <td><span :class="statusBadge(p.status)">{{ configStatusText(p.status) }}</span></td>
          </tr>
          <tr v-if="!policyDialog.list.length"><td colspan="7" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>

      <div class="dlg-section-title" style="margin-top:16px">策略试算(§11.7:传入历史计划,返回命中策略与预警判定)</div>
      <div class="simulate-bar">
        <select class="form-select" v-model="policyDialog.simPlanId" style="width:280px">
          <option value="">选择承诺计划</option>
          <option v-for="p in planList" :key="p.id" :value="p.id">{{ p.plan_no }}({{ p.customer_no || '集团' }})</option>
        </select>
        <button class="btn btn--primary" @click="runSimulate">试算</button>
      </div>
      <table class="table" v-if="policyDialog.simResult" style="margin-top:8px">
        <thead>
          <tr><th>指标</th><th>命中策略</th><th>命中版本</th><th>达成线</th><th>预警线</th><th>达成率</th><th>判定</th></tr>
        </thead>
        <tbody>
          <tr v-for="(m, i) in policyDialog.simResult.metrics || []" :key="i">
            <td>{{ metricName(m.metricCode) }}</td>
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
import { listCommitmentPlans, listTrackingPolicies, simulatePolicy } from '@/api/commitment'
import { getCommitmentPlanDetail, getCommitmentMonthlyReport } from '@/api/approval2'
import {
  planStatusText, configStatusText, evalResultText,
  customerScopeText, metricName, businessTypeText
} from '@/utils/dict'

// ---------- 钻取层级(§12.11):1 客户列表 / 2 客户承诺记录 / 3 指标明细 ----------
const level = ref<1 | 2 | 3>(1)
const currentCustomer = ref('')
const currentPlan = ref<any | null>(null)

const rows = ref<any[]>([])

// 计划状态:当前(跟踪中口径) vs 历史(终态口径)
const CURRENT_STATUS = ['PENDING', 'TRACKING', 'AT_RISK', 'DATA_PENDING']

// ---------- 一级:按客户聚合 ----------
interface CustomerRow {
  customerNo: string
  planCount: number
  metricCount: number
  avgRatio: number | null
  atRiskCount: number
}

const customerRows = computed<CustomerRow[]>(() => {
  const map = new Map<string, any[]>()
  for (const r of rows.value) {
    const key = r.customer_no || '(集团/未关联客户号)'
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(r)
  }
  return [...map.entries()].map(([customerNo, list]) => {
    const ratios = list.map((r) => r.achievement_ratio).filter((v) => v != null).map(Number)
    return {
      customerNo,
      planCount: new Set(list.map((r) => r.plan_no)).size,
      metricCount: list.filter((r) => r.metric_code).length,
      avgRatio: ratios.length ? Number((ratios.reduce((a, b) => a + b, 0) / ratios.length).toFixed(1)) : null,
      atRiskCount: list.filter((r) => r.result_status === 'AT_RISK' || r.status === 'AT_RISK').length
    }
  })
})

// 一级统计卡:跟踪中/有风险/已达成
const statCards = computed(() => {
  const plans = planList.value
  return {
    tracking: plans.filter((p) => CURRENT_STATUS.includes(p.status)).length,
    atRisk: plans.filter((p) => p.status === 'AT_RISK' || p.metrics.some((m: any) => m.result_status === 'AT_RISK')).length,
    achieved: rows.value.filter((r) => r.result_status === 'ACHIEVED').length
  }
})

// ---------- 计划聚合(二级/三级共用) ----------
const planList = computed(() => {
  const map = new Map<number, any>()
  for (const r of rows.value) {
    if (!map.has(r.id)) {
      map.set(r.id, {
        id: r.id, plan_no: r.plan_no, scope_type: r.scope_type,
        customer_no: r.customer_no, status: r.status, metrics: [] as any[]
      })
    }
    if (r.metric_code) map.get(r.id)!.metrics.push(r)
  }
  const list = [...map.values()]
  for (const p of list) {
    const ratios = p.metrics.map((m: any) => m.achievement_ratio).filter((v: any) => v != null).map(Number)
    p.avgRatio = ratios.length ? Number((ratios.reduce((a: number, b: number) => a + b, 0) / ratios.length).toFixed(1)) : null
  }
  return list
})

// 二级:当前客户的承诺记录(当前/历史分组)
const customerPlans = computed(() =>
  planList.value.filter((p) => (p.customer_no || '(集团/未关联客户号)') === currentCustomer.value))
const currentPlans = computed(() => customerPlans.value.filter((p) => CURRENT_STATUS.includes(p.status)))
const historyPlans = computed(() => customerPlans.value.filter((p) => !CURRENT_STATUS.includes(p.status)))

function enterCustomer(customerNo: string) {
  currentCustomer.value = customerNo
  level.value = 2
}

function goUp() {
  if (level.value === 3) {
    level.value = 2
    currentPlan.value = null
  } else {
    level.value = 1
    currentCustomer.value = ''
  }
}

// ---------- 三级:指标钻取(计划详情 + 评估历史 + 月报) ----------
const planDetail = ref<any | null>(null)
const evaluations = ref<any[]>([])
const reportMonth = ref(new Date().toISOString().slice(0, 7))
const reportOrgId = ref('')
const reportRows = ref<any[]>([])
const reportLoading = ref(false)
const reportHint = ref('选择月份后查询承诺月报')

// 指标行:优先计划详情接口返回,缺失则用列表行兜底
const planMetrics = computed(() => {
  const fromDetail = planDetail.value?.metrics
  if (Array.isArray(fromDetail) && fromDetail.length) return fromDetail
  return currentPlan.value?.metrics || []
})

const reportHeaders = computed(() => (reportRows.value.length ? Object.keys(reportRows.value[0]) : []))

async function enterPlan(p: any) {
  currentPlan.value = p
  planDetail.value = null
  evaluations.value = []
  reportRows.value = []
  reportHint.value = '选择月份后查询承诺月报'
  level.value = 3
  try {
    const d = await getCommitmentPlanDetail(p.id)
    planDetail.value = d
    evaluations.value = d?.evaluations || d?.evaluationHistory || []
  } catch {
    // 计划详情接口不可用/无数据:以列表最新评估兜底展示
  }
}

async function loadReport() {
  if (!reportMonth.value) {
    ElMessage.warning('请选择月份')
    return
  }
  reportLoading.value = true
  try {
    const data = await getCommitmentMonthlyReport(reportMonth.value, reportOrgId.value || undefined)
    reportRows.value = Array.isArray(data) ? data : (data?.rows || [])
    reportHint.value = reportRows.value.length ? '' : '该月份暂无月报数据'
  } catch {
    reportRows.value = []
    reportHint.value = '月报查询失败或接口暂未开放'
  } finally {
    reportLoading.value = false
  }
}

// ---------- 数据加载(无参,服务端定数据范围) ----------
async function load() {
  try {
    rows.value = await listCommitmentPlans()
  } catch {
    rows.value = []
  }
}

// ---------- 策略管理 ----------
const policyDialog = reactive({
  show: false,
  list: [] as any[],
  simPlanId: '' as any,
  simResult: null as any
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
function scopeText(s?: string) {
  return customerScopeText(s)
}
function ratioClass(ratio: number) {
  return ratio >= 100 ? 'badge badge--success' : ratio >= 80 ? 'badge badge--warning' : 'badge badge--danger'
}
function progressPct(ratio: any) {
  if (ratio == null) return 0
  return Math.min(Math.max(Number(ratio), 0), 100)
}
// 颜色分级:绿≥100% / 黄≥80% / 红<80%
function progressColor(ratio: any) {
  if (ratio == null) return 'var(--color-text-light)'
  const r = Number(ratio)
  return r >= 100 ? 'var(--color-success)' : r >= 80 ? 'var(--color-warning)' : 'var(--color-danger)'
}
function statusText(s?: string) {
  return planStatusText(s)
}
function statusBadge(s?: string) {
  const map: Record<string, string> = {
    TRACKING: 'badge badge--info', PENDING: 'badge badge--warning', AT_RISK: 'badge badge--warning',
    ACHIEVED: 'badge badge--success', EFFECTIVE: 'badge badge--success',
    DATA_PENDING: 'badge badge--warning', DRAFT: 'badge badge--neutral', REVIEW: 'badge badge--warning'
  }
  return map[s || ''] || 'badge badge--neutral'
}
function resultText(s?: string) {
  return evalResultText(s)
}
function resultBadge(s?: string) {
  const map: Record<string, string> = {
    ACHIEVED: 'badge badge--success', AT_RISK: 'badge badge--warning',
    DATA_PENDING: 'badge badge--neutral', ON_TRACK: 'badge badge--info'
  }
  return map[s || ''] || 'badge badge--neutral'
}

onMounted(load)
</script>

<style scoped>
.breadcrumb-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.stat-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 16px; }
.table { border-radius: var(--radius-sm); overflow: hidden; }
.dg-label { color: var(--color-text-sub); margin-right: 6px; }
.detail-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px 16px; font-size: 14px; }
.plan-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.plan-card { border: 1px solid var(--color-border-light); border-radius: var(--radius); padding: 14px; cursor: pointer; background: var(--color-surface); box-shadow: var(--shadow-sm); transition: border-color .18s, box-shadow .18s, transform .18s; }
.plan-card:hover { border-color: var(--color-primary); box-shadow: var(--shadow); transform: translateY(-2px); }
.plan-card__head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.plan-card__meta { font-size: 13px; color: var(--color-text-sub); margin-top: 4px; }
.metric-row { margin-bottom: 14px; }
.metric-row__head { display: flex; align-items: center; gap: 12px; margin-bottom: 4px; font-size: 14px; }
.report-bar { display: flex; gap: 8px; align-items: center; }
.dlg-section-title { font-weight: 600; margin-bottom: 8px; }
.simulate-bar { display: flex; gap: 8px; }
</style>
