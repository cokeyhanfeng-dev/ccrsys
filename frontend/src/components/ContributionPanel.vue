<template>
  <div class="contrib-dual" :class="{ 'contrib-dual--single': !showCommitments }">
    <!-- 当前贡献度(数仓取数) -->
    <div class="contrib-dual__col">
      <div class="contrib-dual__title">
        当前贡献度 <span class="badge badge--info">数仓</span>
        <span v-if="asOfDate" class="section-tip">截至 {{ asOfDate }}</span>
      </div>
      <table class="table contrib-table" v-if="contribution.length">
        <thead><tr><th class="col-metric">指标</th><th class="col-value">数值</th><th class="col-scope">范围</th><th class="col-check">勾稽</th></tr></thead>
        <tbody>
          <tr v-for="(c, i) in contribution" :key="i">
            <td class="col-metric">
              <div class="metric-name">{{ c.metricName || c.metricCode }}</div>
              <div class="metric-code">{{ c.metricCode }}</div>
            </td>
            <td class="num col-value">
              <span class="metric-value">{{ c.metricValue ?? '—' }}</span>
              <span class="metric-unit">{{ unitOf(c.valueType) }}</span>
            </td>
            <td class="col-scope">{{ scopeOf(c) }}</td>
            <td class="col-check">
              <span class="badge" :class="checkBadgeOf(c).cls">{{ checkBadgeOf(c).text }}</span>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty">暂无数据</div>
    </div>

    <!-- 拟达成贡献度(承诺基线) -->
    <div class="contrib-dual__col" v-if="showCommitments">
      <div class="contrib-dual__title">拟达成贡献度 <span class="badge badge--warning">承诺基线</span></div>
      <table class="table contrib-table" v-if="commitments.length">
        <thead><tr><th class="col-metric">指标</th><th>基线 → 目标</th><th>单位</th><th class="col-scope">范围</th></tr></thead>
        <tbody>
          <tr v-for="(c, i) in commitments" :key="i">
            <td class="col-metric">
              <div class="metric-name">{{ c.metricName || metricNameOf(c.metricCode) }}</div>
              <div class="metric-code">{{ c.metricCode }}</div>
            </td>
            <td class="num">{{ c.baselineValue ?? '—' }} → {{ c.targetValue ?? '—' }}</td>
            <td>{{ c.unit || '—' }}</td>
            <td class="col-scope">{{ scopeOf(c) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty">暂无数据</div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 贡献度只读双栏组件(D1)
 * 左栏:当前贡献度(数仓实时取数);右栏:拟达成贡献度(承诺基线)。
 * 勾稽 badge 依据数据可用性:已取数 / 待取数(有承诺目标但当前值缺失) / 无数据。
 * 适用于审批详情、申请单当前贡献度参考;录入场景传 show-commitments=false 仅展示左栏(整行通栏)。
 */
const props = withDefaults(
  defineProps<{
    /** 当前贡献度数组(metricCode/metricName/metricValue/valueType) */
    contribution: any[]
    /** 承诺基线数组(metricCode/baselineValue/targetValue/unit/metricScope/memberCustomerNo) */
    commitments?: any[]
    /** 是否展示右栏拟达成贡献度 */
    showCommitments?: boolean
    /** 数据日期文案 */
    asOfDate?: string
  }>(),
  { commitments: () => [], showCommitments: true, asOfDate: '' }
)

/** 指标编码→中文名(兜底,数据未带 metricName 时用) */
const METRIC_NAMES: Record<string, string> = {
  TOTAL: '综合贡献总额',
  PUBLIC_DEPOSIT_AVG: '存款日均',
  PUBLIC_LOAN_AVG: '流贷日均',
  PUBLIC_PROJECT_LOAN_AVG: '项目贷日均',
  PUBLIC_DISCOUNT: '贴现利差收益',
  PUBLIC_DISCOUNT_SPREAD: '贴现规模',
  PUBLIC_INTERMEDIATE: '对公中间业务收入',
  PUBLIC_OFF_BALANCE_INCOME: '对公中间业务收入',
  PUBLIC_EXCHANGE: '汇兑利差收益',
  PUBLIC_PAYROLL: '代发贡献度',
  PUBLIC_WEALTH: '对公财富中收',
  PRIVATE_DEPOSIT_AVG: '本人存款日均',
  PRIVATE_LOAN_AVG: '本人贷款日均',
  PRIVATE_WEALTH: '本人财富中收'
}
function metricNameOf(code?: string): string {
  return code ? (METRIC_NAMES[code] || code) : '—'
}

/** 数值口径→单位文案 */
function unitOf(valueType?: string): string {
  const map: Record<string, string> = {
    AVG_BALANCE: '万元·日均',
    INCOME: '万元',
    CONTRIBUTION_AMOUNT: '万元'
  }
  return valueType ? (map[valueType] || '') : ''
}

const SCOPE_NAMES: Record<string, string> = {
  PUBLIC: '对公',
  PRIVATE_SELF: '本人',
  RELATED: '关联人',
  GROUP: '集团',
  GROUP_MEMBER: '集团成员'
}
function scopeOf(c: any): string {
  if (c.memberCustomerNo) return `成员 ${c.memberCustomerNo}`
  return SCOPE_NAMES[c.metricScope] || c.metricScope || '整体'
}

function checkBadgeOf(c: any): { cls: string; text: string } {
  const hasValue = c.metricValue != null && c.metricValue !== '' && c.metricValue !== '-'
  if (hasValue) return { cls: 'badge--success', text: '已取数' }
  const hasCommitment = props.commitments.some((x) => x.metricCode === c.metricCode && x.targetValue != null)
  if (hasCommitment) return { cls: 'badge--warning', text: '待取数' }
  return { cls: 'badge--neutral', text: '无数据' }
}
</script>

<style scoped>
.contrib-dual { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
/* 单栏(仅当前贡献度)通栏展示 */
.contrib-dual--single { grid-template-columns: 1fr; }
.contrib-dual__col { min-width: 0; }
.contrib-dual__title { font-size: 14px; font-weight: 600; margin-bottom: 8px; display: flex; align-items: center; gap: 8px; }

/* 指标列:中文名为主,编码小字次要,不换行挤压 */
.contrib-table .col-metric { min-width: 150px; }
.metric-name { font-weight: 500; white-space: nowrap; }
.metric-code { font-size: 12px; color: var(--color-text-light); white-space: nowrap; }
.metric-value { font-weight: 600; }
.metric-unit { font-size: 12px; color: var(--color-text-light); margin-left: 4px; white-space: nowrap; }
.contrib-table .col-scope { min-width: 72px; white-space: nowrap; }
.contrib-table .col-check { width: 76px; }
</style>
