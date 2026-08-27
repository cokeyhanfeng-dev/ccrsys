<template>
  <div v-if="modelValue" class="modal" role="dialog" aria-modal="true" aria-label="提交确认" @click.self="onCancel">
    <!-- §UI审查:弹窗卡可聚焦(tabindex=-1),配合 ESC 关闭 -->
    <div class="modal__card modal__card--wide" ref="dialogRef" tabindex="-1">
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
        <!-- 审批路由预览(参照存款预览样式;§2026-08-26 提交确认弹窗统一展示,贷款/存款均传 routeResult) -->
        <template v-if="routePreview?.items?.length">
          <div class="check-section">
            <div class="check-section__title">
              审批路由预览
              <span class="badge badge--info">LPR 版本:{{ routePreview.lprVersionCode || '暂无数据' }}</span>
            </div>
            <table class="table">
              <thead>
                <tr><th>分项编号</th><th>产品</th><th>申请利率</th><th>比较方向</th><th>路由链路</th><th>终审岗位</th><th>硬边界</th></tr>
              </thead>
              <tbody>
                <tr v-for="it in routePreview.items" :key="it.pricingItemId">
                  <td>{{ it.pricingItemNo }}</td>
                  <td>{{ productName(it.productCode || '') }}</td>
                  <td class="num">{{ it.requestedRate != null ? it.requestedRate + '%' : '—' }}</td>
                  <td>{{ rateDirectionText(it.rateDirection) }}</td>
                  <td>
                    <template v-if="it.errorCode">
                      <span class="badge badge--danger">路由失败:{{ it.errorMessage || it.errorCode }}</span>
                    </template>
                    <template v-else-if="it.routeChain?.length">
                      <span v-for="(n, ni) in it.routeChain" :key="ni">
                        <span class="route-node">{{ nodeLabel(n) }}</span><span v-if="ni < it.routeChain.length - 1"> → </span>
                      </span>
                    </template>
                    <span v-else>暂无数据</span>
                  </td>
                  <td>{{ it.errorCode ? '—' : nodeLabel(it.finalNodeCode) }}</td>
                  <td>
                    <span v-if="it.hardBoundaryPass === true" class="badge badge--success">通过({{ it.hardBoundaryRate }}%)</span>
                    <span v-else-if="it.hardBoundaryPass === false" class="badge badge--danger">突破({{ it.hardBoundaryRate }}%)</span>
                    <span v-else class="badge badge--neutral">暂无数据</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
        <template v-if="check">
          <div v-if="check.blockSubmit" class="check-block-tip">
            存在阻断项（质量 BLOCK 或突破硬边界），禁止提交。请返回修改申请内容后重新校验。
          </div>
          <div v-else class="check-pass-tip">
            校验未发现阻断项，确认后正式提交，提交后首先流转至支行行长节点。
          </div>
        </template>
        <!-- §UI审查:校验加载态用 spinner 提示,不再用空态插画 -->
        <div v-else class="check-loading">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>校验中…</span>
        </div>
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
import { computed, ref, nextTick, watch, onBeforeUnmount } from 'vue'
import type { SubmitCheck, RoutePreview } from '@/api/application'
import { nodeLabel, rateDirectionText, productName } from '@/utils/dict'

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
  /** 审批路由预览结果(提交确认弹窗展示;§2026-08-26 贷款/存款均传 routeResult) */
  routePreview?: RoutePreview | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'confirm'): void
}>()

// 仅展示 WARN/BLOCK 项,PASS 不逐条罗列

/** 额度明细是否含集团成员列(有任一行带 member 才展示,§2026-08-26) */
const showMemberCol = computed(() => props.detailRows?.some(r => r.member) ?? false)

// §UI审查:弹窗 ESC 关闭(焦点陷阱按审查提示可不做,ESC 必须补;打开时聚焦弹窗卡提升键盘可达)
const dialogRef = ref<HTMLElement | null>(null)
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') onCancel()
}
watch(() => props.modelValue, (open) => {
  if (open) {
    document.addEventListener('keydown', onKeydown)
    nextTick(() => dialogRef.value?.focus())
  } else {
    document.removeEventListener('keydown', onKeydown)
  }
})
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))

function onCancel() {
  emit('update:modelValue', false)
}
</script>

<style scoped>
.modal__card--wide { max-width: 860px; max-height: 85vh; overflow-y: auto; }
/* §UI审查:弹窗卡可聚焦(tabindex=-1)但不显示聚焦外框 */
.modal__card--wide:focus { outline: none; }
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
/* §UI审查:校验加载态 spinner 提示 */
.check-loading {
  display: flex; align-items: center; justify-content: center; gap: 8px;
  padding: 24px 0; color: var(--color-text-sub); font-size: 13px;
}
/* 提交确认弹窗申请概要(§2026-08-26 概要/明细移入弹窗) */
.confirm-summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px 18px; }
/* §UI审查:窄屏降为 2 列,避免值被 ellipsis 截断 */
@media (max-width: 600px) {
  .confirm-summary { grid-template-columns: repeat(2, 1fr); }
}
.confirm-summary__item { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.confirm-summary__item span { font-size: 12px; color: var(--color-text-sub); }
.confirm-summary__item b {
  font-size: 14px; color: var(--color-text-main); font-weight: 600;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
/* 审批路由预览节点徽标(§2026-08-26 提交确认弹窗展示路由链路) */
.route-node {
  display: inline-block; padding: 2px 8px; margin-right: 4px;
  border-radius: var(--radius-sm); background: var(--color-primary-light);
  color: var(--color-primary); font-size: 12px;
}
</style>
