<template>
  <div>
    <div class="section-head">
      <div class="section-title">贡献度跟踪</div>
      <InfoTip content="承诺计划三级钻取(§12.11):客户 → 承诺记录 → 指标明细;数据范围由服务端按登录人角色确定。" />
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
        <table class="table customer-overview" v-if="customerRows.length">
          <thead>
            <tr><th>客户</th><th>计划数</th><th>指标数</th><th>平均达成率</th><th>有风险指标</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="c in customerRows" :key="c.customerNo">
              <td>
                <div>{{ c.customerNo }}</div>
                <div v-if="c.customerName && c.customerName !== c.customerNo" class="section-tip">{{ c.customerName }}</div>
              </td>
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

    <!-- ============ 二级:该客户每一次申请(客户 → 申请 → 指标) ============ -->
    <template v-else-if="level === 2">
      <div class="card">
        <div class="card__head"><span>申请承诺</span><span class="badge badge--info">{{ applicationRows.length }} 次申请</span></div>
        <table class="table" v-if="applicationRows.length">
          <thead>
            <tr><th>客户</th><th>申请号</th><th>业务类型</th><th>申请金额(万元)</th><th>申请利率</th><th>申请状态</th><th>申请时间</th><th>承诺计划</th><th>范围</th><th>计划状态</th><th>平均达成率</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="app in applicationRows" :key="app.applicationNo">
              <td>{{ app.customer_name || app.customer_no || '—' }}</td>
              <td>{{ app.applicationNo || '—' }}</td>
              <td>{{ businessTypeText(app.business_type) }}</td>
              <td class="num">{{ fmtAmount(app.application_amount) }}</td>
              <td class="num">{{ fmtRate(app.requested_rate, app.final_rate) }}</td>
              <td><span class="badge" :class="appStatusBadge(app.application_status)">{{ appStatusText(app.application_status) }}</span></td>
              <td>{{ app.submitTime ? String(app.submitTime).replace('T', ' ').slice(0, 16) : '—' }}</td>
              <td><span class="badge badge--info">{{ app.plan_no }}</span></td>
              <td>{{ scopeText(app.scope_type) }}</td>
              <td><span :class="statusBadge(app.status)">{{ statusText(app.status) }}</span></td>
              <td class="num">
                <span v-if="app.avgRatio != null" :class="ratioClass(app.avgRatio)">{{ app.avgRatio }}%</span>
                <span v-else>暂无数据</span>
              </td>
              <td><button class="btn btn--text" @click="enterApplication(app)">查看指标</button></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">该客户暂无申请承诺</div>
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
          <div><span class="dg-label">客户名称</span>{{ currentPlan?.customer_name || currentPlan?.customer_no || '—' }}</div>
          <div><span class="dg-label">客户号</span>{{ currentPlan?.customer_no || '—' }}</div>
          <div><span class="dg-label">所属申请</span>{{ currentPlan?.application_no || '—' }}</div>
          <div><span class="dg-label">业务类型</span>{{ businessTypeText(currentPlan?.business_type) }}</div>
          <div><span class="dg-label">申请状态</span><span class="badge" :class="appStatusBadge(currentPlan?.application_status)">{{ appStatusText(currentPlan?.application_status) }}</span></div>
          <div><span class="dg-label">范围</span>{{ scopeText(currentPlan?.scope_type) }}</div>
          <div><span class="dg-label">指标数</span>{{ planMetrics.length }} 项</div>
          <div>
            <span class="dg-label">承诺截止</span>
            <span v-if="planEndDate">{{ planEndDate }}</span>
            <span v-else>—</span>
          </div>
          <div>
            <span class="dg-label">剩余时间</span>
            <span v-if="deadlineTip" :class="deadlineTip.cls">{{ deadlineTip.text }}</span>
            <span v-else>—</span>
          </div>
        </div>
      </div>

      <!-- 总体跟踪进度(Σ实际 / Σ目标,按各项指标加总计算) -->
      <div class="card">
        <div class="card__head">
          <span>总体跟踪进度 <InfoTip content="按各项指标实际值与目标值加总计算(不含&quot;其它&quot;手工承诺)" style="margin-left:6px" /></span>
        </div>
        <div v-if="overall" class="overall">
          <div class="overall__sum">
            <div class="overall__sum-item">
              <span class="dg-label">累计实际值</span>
              <b class="metric-val__num">{{ overall.sumActual }}</b>
            </div>
            <div class="overall__sum-item">
              <span class="dg-label">累计目标值</span>
              <b class="metric-val__num">{{ overall.sumTarget }}</b>
            </div>
            <div class="overall__sum-item">
              <span class="dg-label">总体达成率</span>
              <b :class="ratioClass(overall.ratio)">{{ overall.ratio }}%</b>
            </div>
          </div>
          <el-progress
            :percentage="progressPct(overall.ratio)"
            :color="progressColor(overall.ratio)"
            :format="() => `${overall.ratio}%`"
            :stroke-width="14"
          />
        </div>
        <div v-else class="empty">暂无数值指标,无法计算总体进度</div>
      </div>

      <!-- 指标完成进度(每指标:当前完成值 / 目标值 / 离达成值 / 达成率;绿≥100%/黄≥80%/红<80%) -->
      <div class="card">
        <div class="card__head"><span>指标完成进度</span></div>
        <div v-for="(m, i) in planMetrics" :key="i" class="metric-row">
          <div class="metric-row__head">
            <b>{{ metricName(m.metricCode) }}</b>
            <span v-if="m.dataDt" class="section-tip">评估截至 {{ m.dataDt }}</span>
            <span :class="resultBadge(m.resultStatus)">{{ resultText(m.resultStatus) }}</span>
          </div>
          <!-- §6.4 "其它"承诺:无数值达成率/进度条,不参与总体进度(D19);以客户经理手工描述跟踪(track_desc 留痕) -->
          <div v-if="m.metricCode === 'OTHER'" class="other-track">
            <div class="other-track__desc">
              <span class="dg-label">跟踪描述</span>
              <span v-if="m.trackDesc">{{ m.trackDesc }}</span>
              <span v-else class="section-tip">暂无跟踪描述,手工录入留痕(§6.4)</span>
            </div>
            <div class="other-track__edit">
              <textarea class="form-input" rows="2"
                :value="trackDraft[m.metricId ?? m.id] || ''"
                @input="setTrackDraft(m, ($event.target as HTMLTextAreaElement).value)"
                placeholder="录入本期跟踪描述(留痕;以文本替代数值对比)" style="width:100%;resize:vertical" />
              <button class="btn btn--secondary" style="margin-top:6px" @click="saveTrack(m)">保存跟踪描述</button>
            </div>
          </div>
          <template v-else>
            <div class="metric-row__vals">
              <div class="metric-val">
                <span class="dg-label">当前完成值</span>
                <b class="metric-val__num">{{ m.actualValue ?? '—' }}</b>
                <span class="metric-val__unit">{{ commitmentUnitText(m.unit) }}</span>
              </div>
              <div class="metric-val">
                <span class="dg-label">目标值</span>
                <b class="metric-val__num">{{ m.targetValue ?? '—' }}</b>
                <span class="metric-val__unit">{{ commitmentUnitText(m.unit) }}</span>
              </div>
              <div class="metric-val">
                <span class="dg-label">离达成值</span>
                <b class="metric-val__num" :class="gapBadge(m)">{{ gapText(m) }}</b>
                <span class="metric-val__unit" v-if="gapOf(m) != null">{{ commitmentUnitText(m.unit) }}</span>
              </div>
              <div class="metric-val">
                <span class="dg-label">达成率</span>
                <b class="metric-val__num" :class="ratioClass(m.achievementRatio)">{{ m.achievementRatio != null ? `${m.achievementRatio}%` : '—' }}</b>
              </div>
            </div>
            <el-progress
              :percentage="progressPct(m.achievementRatio)"
              :color="progressColor(m.achievementRatio)"
              :format="() => (m.achievementRatio != null ? `${m.achievementRatio}%` : '暂无数据')"
            />
            <!-- 每期履约明细(该申请承诺计划下指标各评估期完成情况) -->
            <div v-if="m.evaluations?.length" class="period-block">
              <button class="btn btn--text" @click="toggleEvals(m)">
                {{ expandedEvals.has(m.metricId ?? m.id) ? '收起每期履约' : `查看每期履约(${m.evaluations.length} 期)` }}
              </button>
              <table class="table" v-if="expandedEvals.has(m.metricId ?? m.id)" style="margin-top:6px">
                <thead><tr><th>评估期</th><th>实际值</th><th>达成率</th><th>结论</th></tr></thead>
                <tbody>
                  <tr v-for="(e, ei) in m.evaluations" :key="ei">
                    <td>{{ String(e.dataDt).slice(0, 10) }}</td>
                    <td class="num">{{ e.actualValue ?? '—' }}</td>
                    <td class="num"><span :class="ratioBadge(e.achievementRatio)">{{ e.achievementRatio != null ? `${e.achievementRatio}%` : '—' }}</span></td>
                    <td><span class="badge" :class="resultBadge(e.resultStatus)">{{ resultText(e.resultStatus) }}</span></td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
        <div v-if="!planMetrics.length" class="empty">暂无指标数据</div>
      </div>

      <!-- 月报入口(§12.11:月报汇总 + 风险/结论分布) -->
      <div class="card">
        <div class="card__head"><span>承诺月报</span></div>
        <div class="report-bar">
          <input class="form-input" type="month" v-model="reportMonth" style="width:180px" />
          <input class="form-input" v-model="reportOrgId" placeholder="机构ID(可空,默认本机构)" style="width:200px" />
          <button class="btn btn--primary" :disabled="reportLoading" @click="loadReport">查询月报</button>
        </div>
        <div class="report-summary" v-if="report.month">
          <div><span class="dg-label">统计月份</span>{{ report.month }}</div>
          <div><span class="dg-label">承诺计划数</span>{{ report.planCount ?? '—' }}</div>
          <div><span class="dg-label">评估笔数</span>{{ report.evaluationCount ?? '—' }}</div>
          <div><span class="dg-label">平均达成率</span>{{ report.avgAchievementRatio != null ? Number(report.avgAchievementRatio).toFixed(2) + '%' : '—' }}</div>
        </div>
        <table class="table" v-if="report.riskDistribution?.length" style="margin-top:12px">
          <thead><tr><th>风险等级</th><th>评估笔数</th></tr></thead>
          <tbody>
            <tr v-for="(d, i) in report.riskDistribution" :key="i">
              <td><span class="badge" :class="riskBadge(d.riskLevel)">{{ riskLevelText(d.riskLevel) }}</span></td>
              <td class="num">{{ d.evaluationCount ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
        <table class="table" v-if="report.resultDistribution?.length" style="margin-top:8px">
          <thead><tr><th>评估结论</th><th>评估笔数</th></tr></thead>
          <tbody>
            <tr v-for="(d, i) in report.resultDistribution" :key="i">
              <td>{{ evalResultText(d.resultStatus) }}</td>
              <td class="num">{{ d.evaluationCount ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="report.month && !report.riskDistribution?.length && !report.resultDistribution?.length" class="empty" style="margin-top:12px">该月份暂无月报数据</div>
        <div v-else-if="!report.month" class="empty" style="margin-top:12px">{{ reportHint }}</div>
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
import { listCommitmentPlans, listTrackingPolicies, simulatePolicy, saveMetricTrackDesc } from '@/api/commitment'
import { getCommitmentPlanDetail, getCommitmentMonthlyReport } from '@/api/approval2'
import {
  planStatusText, configStatusText, evalResultText, appStatusText,
  customerScopeText, metricName, businessTypeText, commitmentUnitText
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
  customerName: string
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
    const ratios = list.map((r) => r.achievement_ratio).filter((v) => v != null).map((v) => Number(v) * 100)
    const customerName = list.find((r) => r.customer_name)?.customer_name || ''
    return {
      customerNo,
      customerName,
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
        customer_no: r.customer_no, status: r.status, metrics: [] as any[],
        // 所属申请摘要(贡献度跟踪页展示"跟着哪个申请",后端 listPlans 随行返回)
        application_no: r.application_no, submit_time: r.submit_time,
        business_type: r.business_type, application_status: r.application_status,
        customer_name: r.customer_name, application_amount: r.application_amount,
        requested_rate: r.requested_rate, final_rate: r.final_rate
      })
    }
    if (r.metric_code) map.get(r.id)!.metrics.push(r)
  }
  const list = [...map.values()]
  for (const p of list) {
    const ratios = p.metrics.map((m: any) => m.achievement_ratio).filter((v: any) => v != null).map((v: any) => Number(v) * 100)
    p.avgRatio = ratios.length ? Number((ratios.reduce((a: number, b: number) => a + b, 0) / ratios.length).toFixed(1)) : null
  }
  return list
})

// 二级:当前客户的承诺记录(当前/历史分组)
const customerPlans = computed(() =>
  planList.value.filter((p) => (p.customer_no || '(集团/未关联客户号)') === currentCustomer.value))
const currentPlans = computed(() => customerPlans.value.filter((p) => CURRENT_STATUS.includes(p.status)))
const historyPlans = computed(() => customerPlans.value.filter((p) => !CURRENT_STATUS.includes(p.status)))

// 二级:该客户每一次申请(客户 → 申请 → 指标;承诺计划↔申请 1:1,按申请聚合展示)
const applicationRows = computed(() => {
  const map = new Map<string, any>()
  for (const p of customerPlans.value) {
    const key = p.application_no || `plan-${p.id}`
    if (!map.has(key)) {
      map.set(key, {
        applicationNo: p.application_no,
        submitTime: p.submit_time,
        plan_no: p.plan_no,
        scope_type: p.scope_type,
        status: p.status,
        id: p.id,
        customer_no: p.customer_no,
        business_type: p.business_type,
        application_status: p.application_status,
        customer_name: p.customer_name,
        application_amount: p.application_amount,
        requested_rate: p.requested_rate,
        final_rate: p.final_rate,
        metrics: [],
      })
    }
    const row = map.get(key)!
    if (Array.isArray(p.metrics)) row.metrics.push(...p.metrics)
  }
  const list = [...map.values()]
  for (const app of list) {
    const ratios = app.metrics.map((m: any) => m.achievement_ratio).filter((v: any) => v != null).map((v: any) => Number(v) * 100)
    app.avgRatio = ratios.length ? Number((ratios.reduce((a: number, b: number) => a + b, 0) / ratios.length).toFixed(1)) : null
  }
  return list
})

function enterApplication(app: any) {
  enterPlan(app)
}

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

// ---------- 三级:指标钻取(计划详情 + 总体进度 + 月报) ----------
const planDetail = ref<any | null>(null)
const reportMonth = ref(new Date().toISOString().slice(0, 7))
const reportOrgId = ref('')
const report = ref<any>({})
const reportLoading = ref(false)
const reportHint = ref('选择月份后查询承诺月报')

// 指标行:计划详情接口返回 items=[{metric, latestEvaluation}],缺失时用列表行兜底
const planMetrics = computed<any[]>(() => {
  const items = planDetail.value?.items
  if (Array.isArray(items) && items.length) {
    return items.map((it: any) => {
      const m = it.metric || {}
      const ev = it.latestEvaluation || {}
      return {
        id: m.id,
        metricId: m.id,
        metricCode: m.metricCode,
        metricName: m.metricName,
        unit: m.unit,
        targetValue: m.targetValue,
        baselineValue: m.baselineValue,
        targetType: m.targetType,
        trackDesc: m.trackDesc,
        actualValue: ev.actualValue,
        achievementRatio: pctOf(ev.achievementRatio),
        resultStatus: ev.resultStatus,
        dataDt: ev.dataDt,
        // 每期履约明细(planDetail 返回该指标全部评估期,按 data_dt 倒序)
        evaluations: (it.evaluations || []).map((e: any) => ({
          dataDt: e.dataDt, actualValue: e.actualValue,
          achievementRatio: pctOf(e.achievementRatio), resultStatus: e.resultStatus
        }))
      }
    })
  }
  return (currentPlan.value?.metrics || []).map((r: any) => ({
    id: r.id, metricId: r.id, metricCode: r.metric_code, metricName: r.metric_name,
    unit: r.unit, targetValue: r.target_value, baselineValue: r.baseline_value,
    trackDesc: r.track_desc, actualValue: r.actual_value,
    achievementRatio: pctOf(r.achievement_ratio), resultStatus: r.result_status, dataDt: r.data_dt,
    evaluations: [] as any[]
  }))
})

// 时间维度:承诺截止日期 + 剩余天数(用户诉求②:总截止时间跟踪)
const planEndDate = computed(() => planDetail.value?.plan?.endDate || currentPlan.value?.end_date || '')
function daysLeft(endDate?: string): number | null {
  if (!endDate) return null
  const end = new Date(`${endDate}T00:00:00`).getTime()
  const now = new Date().getTime()
  const diff = Math.ceil((end - now) / 86400000)
  return Number.isFinite(diff) ? diff : null
}
const deadlineTip = computed(() => {
  const days = daysLeft(planEndDate.value)
  if (days == null) return null
  if (days < 0) return { cls: 'badge badge--danger', text: `已过期 ${-days} 天` }
  if (days === 0) return { cls: 'badge badge--warning', text: '今日到期' }
  return { cls: 'badge badge--info', text: `剩余 ${days} 天` }
})

// 总体跟踪进度(用户诉求③):Σ实际值 / Σ目标值,仅数值指标(不含 OTHER)
const overall = computed(() => {
  const items = planMetrics.value.filter((m: any) => m.metricCode !== 'OTHER')
  let sumActual = 0
  let sumTarget = 0
  let hasTarget = false
  for (const m of items) {
    const target = Number(m.targetValue)
    const actual = Number(m.actualValue)
    if (Number.isFinite(target) && target > 0) {
      sumTarget += target
      hasTarget = true
    }
    if (Number.isFinite(actual)) sumActual += actual
  }
  if (!hasTarget) return null
  return {
    sumActual: Number(sumActual.toFixed(2)),
    sumTarget: Number(sumTarget.toFixed(2)),
    ratio: sumTarget ? Number((sumActual / sumTarget * 100).toFixed(1)) : 0
  }
})

// 离达成值(用户诉求①)= 目标值 - 当前完成值;负值表示已超额达成
function gapOf(m: any): number | null {
  if (m.targetValue == null || m.actualValue == null) return null
  const t = Number(m.targetValue)
  const a = Number(m.actualValue)
  if (!Number.isFinite(t) || !Number.isFinite(a)) return null
  return Number((t - a).toFixed(2))
}
function gapText(m: any): string {
  const gap = gapOf(m)
  if (gap == null) return '—'
  if (gap <= 0) return gap === 0 ? '已达成' : `已超额达成 ${Math.abs(gap)}`
  return `还差 ${gap}`
}
function gapBadge(m: any): string {
  const gap = gapOf(m)
  if (gap == null) return ''
  return gap <= 0 ? 'badge badge--success' : 'badge badge--warning'
}

async function enterPlan(p: any) {
  currentPlan.value = p
  planDetail.value = null
  report.value = {}
  reportHint.value = '选择月份后查询承诺月报'
  level.value = 3
  try {
    const d = await getCommitmentPlanDetail(p.id)
    planDetail.value = d
  } catch {
    // 计划详情接口不可用/无数据:以列表最新评估兜底展示
  }
}

// ---------- "其它"承诺跟踪描述录入(§6.4:以 metric 主键为键的草稿,保存 track_desc 留痕) ----------
const trackDraft = reactive<Record<number, string>>({})
function setTrackDraft(m: any, v: string) {
  trackDraft[Number(m.metricId ?? m.id)] = v
}
async function saveTrack(m: any) {
  const metricId = Number(m.metricId ?? m.id)
  const desc = (trackDraft[metricId] || '').trim()
  if (!desc) {
    ElMessage.warning('请录入跟踪描述')
    return
  }
  try {
    await saveMetricTrackDesc(metricId, desc)
    // 更新本地行数据与当前计划指标,无需重拉列表
    m.trackDesc = desc
    trackDraft[metricId] = ''
    ElMessage.success('跟踪描述已保存留痕')
  } catch {
    ElMessage.error('跟踪描述保存失败')
  }
}

async function loadReport() {
  if (!reportMonth.value) {
    ElMessage.warning('请选择月份')
    return
  }
  reportLoading.value = true
  try {
    // P1-3 月报联调:后端返回 {month,planCount,evaluationCount,avgAchievementRatio,riskDistribution,resultDistribution}
    const data = await getCommitmentMonthlyReport(reportMonth.value, reportOrgId.value || undefined)
    report.value = (data || {}) as any
    if (!report.value.month) report.value.month = reportMonth.value
    reportHint.value = report.value.planCount != null ? '' : '该月份暂无月报数据'
  } catch {
    report.value = {}
    reportHint.value = '月报查询失败或接口暂未开放'
  } finally {
    reportLoading.value = false
  }
}

// P1-3 月报联调:风险等级文案与徽标(ccr_tracking_evaluation.risk_level)
function riskLevelText(code?: string) {
  const map: Record<string, string> = { LOW: '低', MEDIUM: '中', HIGH: '高', UNKNOWN: '未知' }
  return map[code || ''] || (code || '—')
}
function riskBadge(code?: string) {
  const map: Record<string, string> = { LOW: 'badge--success', MEDIUM: 'badge--warning', HIGH: 'badge--danger', UNKNOWN: 'badge--neutral' }
  return map[code || ''] || 'badge--neutral'
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
// 申请状态徽标(ccr_application.status:草稿/审批中/已通过/已否决等,承诺跟踪页展示所属申请)
function appStatusBadge(s?: string) {
  const map: Record<string, string> = {
    DRAFT: 'badge badge--neutral', SUBMITTING: 'badge badge--warning', PROCESSING: 'badge badge--info',
    PARTIAL_APPROVED: 'badge badge--warning', APPROVED: 'badge badge--success',
    REJECTED: 'badge badge--danger', CLOSED: 'badge badge--neutral',
    ROUTING: 'badge badge--info', FINAL: 'badge badge--success'
  }
  return map[s || ''] || 'badge badge--neutral'
}
// 申请金额(万元)展示:千分位,空值显示 —
function fmtAmount(v: any): string {
  if (v == null || v === '') return '—'
  const n = Number(v)
  return Number.isFinite(n) ? n.toLocaleString('zh-CN') : '—'
}
// 申请利率展示:优先最终利率,无则申请利率(库中为数值如 3.5)
function fmtRate(requested: any, final: any): string {
  const v = final ?? requested
  if (v == null || v === '') return '—'
  const n = Number(v)
  return Number.isFinite(n) ? `${n.toFixed(2)}%` : String(v)
}
function resultText(s?: string) {
  return evalResultText(s)
}
// 达成率比率→百分比(库中 achievement_ratio 为比率 0.84,展示统一转 84)
function pctOf(r: any): any {
  const n = Number(r)
  return r != null && Number.isFinite(n) ? Number((n * 100).toFixed(1)) : (r == null ? null : r)
}
// 每期履约明细展开状态(按指标主键)
const expandedEvals = reactive<Set<number>>(new Set())
function toggleEvals(m: any) {
  const id = Number(m.metricId ?? m.id)
  if (expandedEvals.has(id)) expandedEvals.delete(id)
  else expandedEvals.add(id)
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
/* 客户承诺概览:列少时拉长撑满容器(参照 approval/detail.vue 宽屏恢复拉伸的做法) */
.customer-overview { width: 100%; display: table; }
.breadcrumb-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.stat-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 16px; }
.table { border-radius: var(--radius-sm); overflow-x: auto; }
.dg-label { color: var(--color-text-sub); margin-right: 6px; }
.detail-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px 16px; font-size: 14px; }
.plan-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.plan-card { border: 1px solid var(--color-border-light); border-radius: var(--radius); padding: 14px; cursor: pointer; background: var(--color-surface); box-shadow: var(--shadow-sm); transition: border-color .18s, box-shadow .18s, transform .18s; }
.plan-card:hover { border-color: var(--color-primary); box-shadow: var(--shadow); transform: translateY(-2px); }
.plan-card__head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.plan-card__meta { font-size: 13px; color: var(--color-text-sub); margin-top: 4px; }
.metric-row { margin-bottom: 14px; }
.metric-row__head { display: flex; align-items: center; gap: 12px; margin-bottom: 4px; font-size: 14px; }
.metric-row__code { font-size: 12px; color: var(--color-text-light); }
.metric-row__vals { display: flex; flex-wrap: wrap; gap: 24px; padding: 8px 0 4px; }
.metric-val { display: inline-flex; align-items: baseline; gap: 6px; font-size: 14px; }
.metric-val__num { font-size: 16px; font-weight: 700; }
.metric-val__unit { font-size: 12px; color: var(--color-text-light); }
.overall__sum { display: flex; flex-wrap: wrap; gap: 32px; margin-bottom: 10px; }
.overall__sum-item { display: inline-flex; align-items: baseline; gap: 8px; font-size: 14px; }
.other-track { background: #f8fafc; border: 1px dashed var(--color-border); border-radius: var(--radius-sm); padding: 10px 12px; margin-top: 6px; }
.period-block { margin-top: 6px; }
.other-track__desc { font-size: 13px; margin-bottom: 8px; }
.other-track__edit { max-width: 560px; }
.report-bar { display: flex; gap: 8px; align-items: center; }
.report-summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px 16px; font-size: 14px; margin-top: 12px; }
.dg-label { color: var(--color-text-sub); margin-right: 6px; }
.dlg-section-title { font-weight: 600; margin-bottom: 8px; }
.simulate-bar { display: flex; gap: 8px; }
</style>
