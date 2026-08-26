<template>
  <div v-if="modelValue" class="modal" @click.self="onCancel">
    <div class="modal__card modal__card--wide">
      <div class="modal__title">提交确认</div>
      <div class="modal__body">
        <!-- 客户信息(存款提交确认弹窗;§2026-08-26) -->
        <template v-if="customerSummary?.length">
          <div class="check-section">
            <div class="check-section__title">客户信息</div>
            <div class="confirm-summary">
              <div class="confirm-summary__item" v-for="(s, i) in customerSummary" :key="i">
                <span>{{ s.label }}</span><b>{{ s.value }}</b>
              </div>
            </div>
          </div>
        </template>
        <!-- 申请概要(贷款提交前核对;§2026-08-26 概要/明细移入确认弹窗,存款不传保持原样) -->
        <template v-if="summary?.length">
          <div class="check-section">
            <div class="check-section__title">申请概要</div>
            <div class="confirm-summary">
              <div class="confirm-summary__item" v-for="(s, i) in summary" :key="i">
                <span>{{ s.label }}</span><b>{{ s.value }}</b>
              </div>
            </div>
          </div>
        </template>
        <!-- 额度明细(贷款提交前核对) -->
        <template v-if="detailRows?.length">
          <div class="check-section">
            <div class="check-section__title">额度明细</div>
            <table class="table">
              <thead>
                <tr>
                  <th>额度</th>
                  <th v-if="showMemberCol">成员</th>
                  <th>担保方式</th><th>期限</th>
                  <th>授信金额(万元)</th><th>申请利率</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(r, i) in detailRows" :key="i">
                  <td>{{ r.itemNo }}</td>
                  <td v-if="showMemberCol">{{ r.member }}</td>
                  <td>{{ r.guaranteeType }}</td>
                  <td>{{ r.term }}</td>
                  <td class="num">{{ r.amount }}</td>
                  <td class="num">{{ r.rate }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
        <!-- 下一步审批人(§2026-08-26 贷款提交确认弹窗展示审批去向) -->
        <template v-if="nextApprover">
          <div class="check-section">
            <div class="check-section__title">下一步审批</div>
            <div class="next-approver-tip">提交后审批将首先流转至 <b>{{ nextApprover }}</b></div>
          </div>
        </template>
        <template v-if="check">
          <!-- 数据批次差异清单(仅校验明细场景展示,贷款 showCheckDetails=false 隐藏;§2026-08-26) -->
          <template v-if="showCheckDetails">
            <div class="check-section">
              <div class="check-section__title">
                数据批次差异
                <span class="badge badge--neutral">基线来源:{{ baselineSourceText }}</span>
              </div>
              <table class="table" v-if="check.diffs?.length">
                <thead>
                  <tr><th>数据集</th><th>基线数据日期</th><th>最新批次日期</th><th>是否有新批次</th></tr>
                </thead>
                <tbody>
                  <tr v-for="d in check.diffs" :key="d.datasetCode">
                    <td>{{ datasetName(d.datasetCode) }}</td>
                    <td>{{ d.baselineDataDt || '暂无数据' }}</td>
                    <td>{{ d.latestDataDt || '暂无数据' }}</td>
                    <td>
                      <span :class="d.changed ? 'badge badge--warning' : 'badge badge--success'">
                        {{ d.changed ? '有新批次' : '无变化' }}
                      </span>
                    </td>
                  </tr>
                </tbody>
              </table>
              <div class="empty" v-else>无数据批次差异</div>
            </div>
            <!-- 硬边界 -->
            <div class="check-section">
              <div class="check-section__title">硬边界校验</div>
              <table class="table" v-if="check.hardBoundaries?.length">
                <thead>
                  <tr><th>分项编号</th><th>产品</th><th>申请利率</th><th>硬边界</th><th>结果</th><th>说明</th></tr>
                </thead>
                <tbody>
                  <tr v-for="h in check.hardBoundaries" :key="h.pricingItemId">
                    <td>{{ h.pricingItemNo || h.pricingItemId }}</td>
                    <td>{{ productName(h.productCode) }}</td>
                    <td class="num">{{ h.requestedRate }}%</td>
                    <td class="num">{{ h.boundaryRate != null ? h.boundaryRate + '%' : '暂无数据' }}</td>
                    <td>
                      <span :class="h.pass ? 'badge badge--success' : 'badge badge--danger'">
                        {{ h.pass ? '通过' : '突破硬边界' }}
                      </span>
                    </td>
                    <td>{{ h.message || '—' }}</td>
                  </tr>
                </tbody>
              </table>
              <div class="empty" v-else>暂无硬边界校验结果</div>
            </div>
          </template>

          <div v-if="check.blockSubmit" class="check-block-tip">
            存在阻断项(质量 BLOCK 或突破硬边界),禁止提交。请返回修改申请内容后重新校验。
          </div>
          <div v-else-if="showCheckDetails" class="check-pass-tip">
            校验未发现阻断项,确认后正式提交,提交后首先流转至支行行长节点。
          </div>
        </template>
        <div class="empty" v-else>校验中…</div>
      </div>
      <div class="modal__actions">
        <button class="btn btn--secondary" @click="onCancel">{{ check?.blockSubmit ? '返回修改' : '取消' }}</button>
        <button
          v-if="check && !check.blockSubmit"
          class="btn btn--primary"
          :disabled="submitting"
          @click="$emit('confirm')"
        >{{ submitting ? '提交中…' : '确认提交' }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { SubmitCheck } from '@/api/application'
import { productName, datasetName } from '@/utils/dict'

const props = defineProps<{
  modelValue: boolean
  check: SubmitCheck | null
  submitting?: boolean
  /** 申请概要键值对(贷款提交确认弹窗核对用;§2026-08-26 概要/明细移入弹窗,存款不传保持原样) */
  summary?: Array<{ label: string; value: string }>
  /** 客户信息键值对(存款提交确认弹窗;§2026-08-26 客户信息+概要+下一步审批人) */
  customerSummary?: Array<{ label: string; value: string }>
  /** 额度明细行(贷款提交确认弹窗核对用) */
  detailRows?: Array<{ itemNo: string; member?: string; guaranteeType: string; term: string; amount: string; rate: string }>
  /** 下一步审批人姓名(贷款提交确认弹窗展示审批去向) */
  nextApprover?: string
  /** 是否展示数据批次差异/硬边界等校验明细(贷款提交确认精简为 false,存款保持 true;§2026-08-26) */
  showCheckDetails?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'confirm'): void
}>()

// 仅展示 WARN/BLOCK 项,PASS 不逐条罗列

/** 额度明细是否含集团成员列(有任一行带 member 才展示,§2026-08-26) */
const showMemberCol = computed(() => props.detailRows?.some(r => r.member) ?? false)

const baselineSourceText = computed(() => {
  const s = props.check?.baselineSource
  if (s === 'DRAFT_CREATE') return '草稿创建'
  if (s === 'ROUTE_PREVIEW') return '上次预览'
  return '无基线'
})

function levelBadge(level?: string) {
  if (level === 'BLOCK') return 'badge badge--danger'
  if (level === 'WARN') return 'badge badge--warning'
  return 'badge badge--success'
}

function onCancel() {
  emit('update:modelValue', false)
}
</script>

<style scoped>
.modal__card--wide { max-width: 860px; max-height: 85vh; overflow-y: auto; }
.check-section { margin-bottom: 20px; }
.check-section__title {
  font-size: 14px; font-weight: 600; margin-bottom: 8px;
  display: flex; align-items: center; gap: 8px;
  color: var(--color-text-main);
}
.check-block-tip {
  padding: 10px 14px; border-radius: var(--radius-sm);
  background: var(--color-danger-light); color: #b91c1c; font-weight: 600;
}
.check-pass-tip {
  padding: 10px 14px; border-radius: var(--radius-sm);
  background: var(--color-success-light); color: #047857;
}
.empty { padding: var(--space-4); }
/* 提交确认弹窗申请概要(§2026-08-26 概要/明细移入弹窗) */
.confirm-summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px 18px; }
.confirm-summary__item { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.confirm-summary__item span { font-size: 12px; color: var(--color-text-sub); }
.confirm-summary__item b {
  font-size: 14px; color: var(--color-text-main); font-weight: 600;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
/* 下一步审批人提示条(§2026-08-26) */
.next-approver-tip {
  padding: 10px 14px; border-radius: var(--radius-sm);
  background: var(--color-primary-light); color: var(--color-primary);
  font-size: 13px;
}
.next-approver-tip b { font-size: 14px; font-weight: 600; }
</style>
