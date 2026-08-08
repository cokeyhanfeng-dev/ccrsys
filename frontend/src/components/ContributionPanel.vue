<template>
  <div class="contrib-dual">
    <!-- 当前贡献度(数仓取数) -->
    <div class="contrib-dual__col">
      <div class="contrib-dual__title">
        当前贡献度 <span class="badge badge--info">数仓</span>
        <span v-if="asOfDate" class="section-tip">截至 {{ asOfDate }}</span>
      </div>
      <table class="table" v-if="contribution.length">
        <thead><tr><th>指标</th><th>名称</th><th>数值</th><th>范围</th><th>勾稽</th></tr></thead>
        <tbody>
          <tr v-for="(c, i) in contribution" :key="i">
            <td>{{ c.metricCode }}</td>
            <td>{{ c.metricName || '—' }}</td>
            <td class="num">{{ c.metricValue ?? '—' }}{{ c.valueType || '' }}</td>
            <td>{{ scopeOf(c) }}</td>
            <td>
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
      <table class="table" v-if="commitments.length">
        <thead><tr><th>指标</th><th>基线 → 目标</th><th>单位</th><th>范围</th></tr></thead>
        <tbody>
          <tr v-for="(c, i) in commitments" :key="i">
            <td>{{ c.metricCode }}</td>
            <td class="num">{{ c.baselineValue ?? '—' }} → {{ c.targetValue ?? '—' }}</td>
            <td>{{ c.unit || '—' }}</td>
            <td>{{ scopeOf(c) }}</td>
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
 * 适用于审批详情、申请单当前贡献度参考;录入场景传 show-commitments=false 仅展示左栏。
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

function checkBadgeOf(c: any): { cls: string; text: string } {
  const hasValue = c.metricValue != null && c.metricValue !== '' && c.metricValue !== '-'
  if (hasValue) return { cls: 'badge--success', text: '已取数' }
  const hasCommitment = props.commitments.some((x) => x.metricCode === c.metricCode && x.targetValue != null)
  if (hasCommitment) return { cls: 'badge--warning', text: '待取数' }
  return { cls: 'badge--neutral', text: '无数据' }
}

function scopeOf(c: any): string {
  if (c.memberCustomerNo) return `成员 ${c.memberCustomerNo}`
  return c.metricScope || '整体'
}
</script>

<style scoped>
.contrib-dual { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.contrib-dual__col { min-width: 0; }
.contrib-dual__title { font-size: 14px; font-weight: 600; margin-bottom: 8px; display: flex; align-items: center; gap: 8px; }
</style>
