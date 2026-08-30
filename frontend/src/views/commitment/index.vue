<template>
  <div>
    <div class="section-head">
      <div class="section-title">贡献度跟踪</div>
      <InfoTip content="承诺跟踪:跟踪中行实时取数仓最新批次算完成度,到期自动定案;数据范围由服务端按登录人角色确定。" />
    </div>

    <!-- 统计卡:跟踪中 / 已完成 / 未完成(基于当前列表) -->
    <div class="stat-row">
      <div class="stat-card">
        <span class="stat-card__label">跟踪中</span>
        <b class="stat-card__num stat-card__num--primary">{{ stats.tracking }}</b>
        <div class="stat-card__sub">生效跟踪中的承诺</div>
      </div>
      <div class="stat-card">
        <span class="stat-card__label">已完成</span>
        <b class="stat-card__num stat-card__num--success">{{ stats.met }}</b>
        <div class="stat-card__sub">到期达成定案的承诺</div>
      </div>
      <div class="stat-card">
        <span class="stat-card__label">未完成</span>
        <b class="stat-card__num stat-card__num--danger">{{ stats.unmet }}</b>
        <div class="stat-card__sub">到期未达成定案的承诺</div>
      </div>
    </div>

    <div class="card">
      <div class="card-toolbar">
        <span class="card-toolbar__title">承诺跟踪</span>
        <span class="card-toolbar__actions">
          <select class="form-select" v-model="query.status" style="width:130px" aria-label="状态筛选">
            <option value="">全部状态</option>
            <option value="TRACKING">跟踪中</option>
            <option value="FINISHED_MET">已完成</option>
            <option value="FINISHED_UNMET">未完成</option>
          </select>
          <input class="form-input" v-model="query.customerNo" style="width:180px" placeholder="客户号" @keyup.enter="load" />
          <button class="btn btn--primary" @click="load">查询</button>
        </span>
      </div>
      <div v-loading="listLoading">
        <table class="table table--full" v-if="rows.length">
          <thead>
            <tr>
              <th>跟踪编号</th><th>客户</th><th>承诺指标</th><th>目标类型</th><th>目标值</th>
              <th>当前值</th><th>完成比例</th><th>数据日期</th><th>截止日期</th><th>状态</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.id">
              <td>{{ r.trackNo || '—' }}</td>
              <td>
                <div>{{ r.customerName || r.customerNo || '—' }}</div>
                <div v-if="r.customerName" class="section-tip">{{ r.customerNo }}</div>
              </td>
              <td>
                <div>{{ r.metricName || r.metricCode || '—' }}</div>
                <div v-if="r.metricName" class="section-tip">{{ r.metricCode }}</div>
              </td>
              <td>{{ targetTypeText(r.targetKind) }}</td>
              <td class="num">{{ fmtValue(r.targetValue) }}<span v-if="r.unit" class="cell-unit">{{ r.unit }}</span></td>
              <td class="num">
                <span v-if="r.actualValue != null">{{ fmtValue(r.actualValue) }}<span v-if="r.unit" class="cell-unit">{{ r.unit }}</span></span>
                <span v-else-if="r.dataStatus === 'NO_DATA'" class="section-tip">暂无数据</span>
                <span v-else>—</span>
              </td>
              <td class="num">
                <span v-if="r.ratio != null" :class="ratioBadge(r.ratio)">{{ pct(r.ratio) }}%</span>
                <span v-else>—</span>
              </td>
              <td class="num">{{ r.dataDt || '—' }}</td>
              <td class="num">{{ r.endDate || '—' }}</td>
              <td><span :class="trackStatusBadge(r.status)">{{ trackStatusText(r.status) }}</span></td>
              <td><button class="btn btn--text" @click="openDetail(r)">详情</button></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-line">{{ listError ? '加载失败，请刷新' : '暂无数据' }}</div>
      </div>
    </div>

    <!-- 承诺跟踪详情弹窗:承诺要素 + 实时/定案 + 所属申请 -->
    <div class="modal" v-if="detail.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">承诺跟踪详情 <span class="section-tip">{{ detail.row?.trackNo || '' }}</span></div>
        <div class="modal__body" v-loading="detail.loading">
          <template v-if="detail.row">
            <div class="form-group-title">承诺要素</div>
            <div class="desc-grid desc-grid--3">
              <div><div class="desc-item__label">客户</div><div class="desc-item__value">{{ detail.row.customerName || detail.row.customerNo || '—' }}</div></div>
              <div><div class="desc-item__label">客户号</div><div class="desc-item__value">{{ detail.row.customerNo || '—' }}</div></div>
              <div><div class="desc-item__label">成员客户号</div><div class="desc-item__value">{{ detail.row.memberCustomerNo || '—' }}</div></div>
              <div><div class="desc-item__label">所属申请</div><div class="desc-item__value">{{ detail.row.applicationNo || '—' }}</div></div>
              <div><div class="desc-item__label">承诺指标</div><div class="desc-item__value">{{ detail.row.metricName || detail.row.metricCode || '—' }}</div></div>
              <div><div class="desc-item__label">目标类型</div><div class="desc-item__value">{{ targetTypeText(detail.row.targetKind) }}</div></div>
              <div><div class="desc-item__label">目标值</div><div class="desc-item__value desc-item__value--num">{{ fmtValue(detail.row.targetValue) }} {{ detail.row.unit || '' }}</div></div>
              <div><div class="desc-item__label">截止日期</div><div class="desc-item__value">{{ detail.row.endDate || '—' }}</div></div>
              <div><div class="desc-item__label">状态</div><div class="desc-item__value"><span :class="trackStatusBadge(detail.row.status)">{{ trackStatusText(detail.row.status) }}</span></div></div>
            </div>

            <div class="form-group-title" style="margin-top:16px">完成情况</div>
            <div class="desc-grid desc-grid--3">
              <div><div class="desc-item__label">当前值</div><div class="desc-item__value desc-item__value--num">{{ fmtValue(detail.row.actualValue) }} {{ detail.row.unit || '' }}</div></div>
              <div>
                <div class="desc-item__label">完成比例</div>
                <div class="desc-item__value desc-item__value--num">
                  <span v-if="detail.row.ratio != null" :class="ratioBadge(detail.row.ratio)">{{ pct(detail.row.ratio) }}%</span>
                  <span v-else-if="detail.row.dataStatus === 'NO_DATA'" class="section-tip">暂无数据</span>
                  <span v-else>—</span>
                </div>
              </div>
              <div><div class="desc-item__label">数仓数据日期</div><div class="desc-item__value">{{ detail.row.dataDt || '—' }}</div></div>
              <div v-if="detail.row.status !== 'TRACKING'"><div class="desc-item__label">定案值</div><div class="desc-item__value desc-item__value--num">{{ fmtValue(detail.row.finalActual) }} {{ detail.row.unit || '' }}</div></div>
              <div v-if="detail.row.status !== 'TRACKING'"><div class="desc-item__label">定案比例</div><div class="desc-item__value desc-item__value--num">{{ detail.row.finalRatio != null ? pct(detail.row.finalRatio) + '%' : '—' }}</div></div>
              <div v-if="detail.row.status !== 'TRACKING'"><div class="desc-item__label">定案时间</div><div class="desc-item__value">{{ fmtTime(detail.row.finishTime) }}</div></div>
            </div>
            <div v-if="detail.row.remark" class="desc-item__label" style="margin-top:10px">备注：{{ detail.row.remark }}</div>

            <div v-if="detail.application" class="form-group-title" style="margin-top:16px">所属申请</div>
            <div class="desc-grid desc-grid--3" v-if="detail.application">
              <div><div class="desc-item__label">申请编号</div><div class="desc-item__value">{{ detail.application.application_no || '—' }}</div></div>
              <div><div class="desc-item__label">业务类型</div><div class="desc-item__value">{{ businessTypeText(detail.application.business_type) }}</div></div>
              <div><div class="desc-item__label">申请状态</div><div class="desc-item__value"><span class="badge" :class="appStatusBadge(detail.application.status)">{{ appStatusText(detail.application.status) }}</span></div></div>
              <div><div class="desc-item__label">提交时间</div><div class="desc-item__value">{{ fmtTime(detail.application.submit_time) }}</div></div>
              <div><div class="desc-item__label">申请金额(万元)</div><div class="desc-item__value desc-item__value--num">{{ fmtValue(detail.application.application_amount) }}</div></div>
            </div>
          </template>
          <div v-else class="empty-line">详情加载失败</div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="detail.show = false">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted } from 'vue'
import { listCommitmentTracks, getCommitmentTrackDetail } from '@/api/commitment'
import { targetTypeText, appStatusText, appStatusBadge, businessTypeText } from '@/utils/dict'

const rows = ref<any[]>([])
const listLoading = ref(false)
const listError = ref(false)
const query = reactive({ status: '', customerNo: '' })

// 统计卡:跟踪中 / 已完成 / 未完成(基于当前列表)
const stats = computed(() => {
  const s = { tracking: 0, met: 0, unmet: 0 }
  for (const r of rows.value) {
    if (r.status === 'TRACKING') s.tracking++
    else if (r.status === 'FINISHED_MET') s.met++
    else if (r.status === 'FINISHED_UNMET') s.unmet++
  }
  return s
})

async function load() {
  listLoading.value = true
  listError.value = false
  try {
    rows.value = await listCommitmentTracks({
      status: query.status || undefined,
      customerNo: query.customerNo?.trim() || undefined
    })
  } catch {
    rows.value = []
    listError.value = true
  } finally {
    listLoading.value = false
  }
}

// ---------- 详情弹窗(承诺要素 + 实时/定案 + 所属申请) ----------
const detail = reactive({ show: false, row: null as any, application: null as any, loading: false })
async function openDetail(r: any) {
  detail.show = true
  detail.loading = true
  detail.row = null
  detail.application = null
  try {
    const d: any = await getCommitmentTrackDetail(r.id)
    detail.row = d
    detail.application = d?.application || null
  } catch {
    // 详情接口不可用时以列表行兜底展示(字段同 toView)
    detail.row = r
    detail.application = null
  } finally {
    detail.loading = false
  }
}

// ---------- 展示映射 ----------
function trackStatusText(s?: string) {
  return { TRACKING: '跟踪中', FINISHED_MET: '已完成', FINISHED_UNMET: '未完成' }[s || ''] || s || '—'
}
function trackStatusBadge(s?: string) {
  const map: Record<string, string> = {
    TRACKING: 'badge--info', FINISHED_MET: 'badge--success', FINISHED_UNMET: 'badge--danger'
  }
  return `badge ${map[s || ''] || 'badge--neutral'}`
}
// 比率(0.84)→百分比(84.0)
function pct(r: any): any {
  const n = Number(r)
  return r != null && Number.isFinite(n) ? Number((n * 100).toFixed(1)) : (r == null ? null : r)
}
// 完成比例徽标:≥100% 达成 / ≥80% 关注 / <80% 未达成
function ratioBadge(ratio: any) {
  if (ratio == null) return 'badge badge--neutral'
  const p = Number(ratio) * 100
  return p >= 100 ? 'badge badge--success' : p >= 80 ? 'badge badge--warning' : 'badge badge--danger'
}
function fmtValue(v: any): string {
  if (v == null || v === '') return '—'
  const n = Number(v)
  return Number.isFinite(n) ? String(n) : String(v)
}
function fmtTime(v: any): string {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 19)
}

onMounted(load)
</script>

<style scoped>
.stat-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 16px; }
@media (max-width: 1200px) { .stat-row { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 768px) { .stat-row { grid-template-columns: 1fr; } }
.cell-unit { margin-left: 2px; color: var(--color-text-light); font-size: 12px; }
</style>
