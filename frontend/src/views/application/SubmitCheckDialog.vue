<template>
  <div v-if="modelValue" class="modal" role="dialog" aria-modal="true" aria-label="提交确认" @click.self="onCancel">
    <!-- §UI审查:弹窗卡可聚焦(tabindex=-1),配合 ESC 关闭 -->
    <div class="modal__card modal__card--wide" ref="dialogRef" tabindex="-1">
      <div class="modal__title">提交确认</div>
      <div class="modal__body">
        <!-- 客户信息(存款提交确认弹窗;§2026-08-26;键值区统一 .desc-grid,§2026-08-28) -->
        <template v-if="customerSummary?.length">
          <div class="check-section">
            <div class="check-section__title">客户信息</div>
            <div class="desc-grid">
              <div v-for="(s, i) in customerSummary" :key="i">
                <div class="desc-item__label">{{ s.label }}</div>
                <div class="desc-item__value">{{ s.value }}</div>
              </div>
            </div>
          </div>
        </template>
        <!-- 申请概要(贷款提交前核对;§2026-08-26 概要/明细移入确认弹窗,存款不传保持原样) -->
        <template v-if="summary?.length">
          <div class="check-section">
            <div class="check-section__title">申请概要</div>
            <div class="desc-grid">
              <div v-for="(s, i) in summary" :key="i">
                <div class="desc-item__label">{{ s.label }}</div>
                <div class="desc-item__value">{{ s.value }}</div>
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
        <!-- 审批路由预览(整单交付改造 2026-08-29:展示申请级整单主链 + 分项明细只读;贷款=分项中利率最低者定链,存款=原流程) -->
        <template v-if="routePreview">
          <div class="check-section">
            <div class="check-section__title">
              审批路由预览
              <span class="badge badge--info">LPR 版本:{{ routePreview.lprVersionCode || '暂无数据' }}</span>
            </div>
            <!-- 整单主链:一条流程(贷款按分项最低利率,存款按原流程);后续审批人按整单推进 -->
            <template v-if="routePreview.routeChain?.length">
              <div class="desc-grid desc-grid--3">
                <div class="desc-item">
                  <div class="desc-item__label">整单路由</div>
                  <div class="desc-item__value">
                    <span v-for="(n, ni) in routePreview.routeChain" :key="ni">
                      <span class="route-node">{{ nodeLabel(n) }}</span><span v-if="ni < routePreview.routeChain.length - 1"> → </span>
                    </span>
                  </div>
                </div>
                <div class="desc-item">
                  <div class="desc-item__label">终审岗位</div>
                  <div class="desc-item__value">{{ nodeLabel(routePreview.finalNodeCode) }}</div>
                </div>
                <div class="desc-item">
                  <div class="desc-item__label">下一步审批人</div>
                  <div class="desc-item__value">{{ (routePreview.nextApproverNames || []).join('、') || '—' }}</div>
                </div>
              </div>
            </template>
            <template v-else>
              <div class="empty" style="padding:12px 0">整单路由暂无数据</div>
            </template>
            <!-- 分项明细(只读;路由失败/硬边界逐分项提示,整单化后不再逐分项展示独立链路) -->
            <template v-if="routePreview.items?.length">
              <div class="check-section__title" style="margin-top:14px">分项明细</div>
              <table class="table">
                <thead>
                  <tr><th>分项编号</th><th>产品</th><th>申请利率</th><th>比较方向</th><th>硬边界</th><th>说明</th></tr>
                </thead>
                <tbody>
                  <tr v-for="it in routePreview.items" :key="it.pricingItemId">
                    <td>{{ it.pricingItemNo }}</td>
                    <td>{{ productName(it.productCode || '') }}</td>
                    <td class="num">{{ it.requestedRate != null ? it.requestedRate + '%' : '—' }}</td>
                    <td>{{ rateDirectionText(it.rateDirection) }}</td>
                    <td>
                      <span v-if="it.hardBoundaryPass === true" class="badge badge--success">通过({{ it.hardBoundaryRate }}%)</span>
                      <span v-else-if="it.hardBoundaryPass === false" class="badge badge--danger">突破({{ it.hardBoundaryRate }}%)</span>
                      <span v-else class="badge badge--neutral">暂无数据</span>
                    </td>
                    <td>
                      <span v-if="it.errorCode" class="badge badge--danger">路由失败:{{ it.errorMessage || it.errorCode }}</span>
                      <span v-else class="section-tip">已并入整单路由</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </template>
          </div>
        </template>
        <template v-if="check">
          <div v-if="check.blockSubmit" class="check-block-tip">
            <div>存在阻断项（质量 BLOCK 或突破硬边界），禁止提交。请返回修改申请内容后重新校验。</div>
            <ul v-if="blockReasons.length" class="check-block-reasons">
              <li v-for="(r, i) in blockReasons" :key="i">{{ r }}</li>
            </ul>
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

/** 阻断原因明细(质量 BLOCK + 硬边界未通过的具体 message,弹窗内直接告知为何不能提交) */
const blockReasons = computed(() => {
  if (!props.check) return []
  const reasons: string[] = []
  for (const h of props.check.hardBoundaries ?? []) {
    if (h.pass === false && h.message) reasons.push(h.message)
  }
  for (const p of props.check.qualityPrecheck ?? []) {
    if (p.level === 'BLOCK' && p.message) reasons.push(p.message)
  }
  return reasons
})

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
.check-block-reasons {
  margin: 8px 0 0; padding-left: 18px; font-weight: 400; font-size: 13px;
  display: flex; flex-direction: column; gap: 4px;
}
.check-pass-tip {
  padding: 10px 14px; border-radius: var(--radius-sm);
  background: var(--color-success-light); color: #047857;
}
/* §UI审查:校验加载态 spinner 提示 */
.check-loading {
  display: flex; align-items: center; justify-content: center; gap: 8px;
  padding: 24px 0; color: var(--color-text-sub); font-size: 13px;
}
/* 审批路由预览节点徽标(§2026-08-26 提交确认弹窗展示路由链路) */
.route-node {
  display: inline-block; padding: 2px 8px; margin-right: 4px;
  border-radius: var(--radius-sm); background: var(--color-primary-light);
  color: var(--color-primary); font-size: 12px;
}
</style>
