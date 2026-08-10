<template>
  <div v-if="modelValue" class="modal" @click.self="onCancel">
    <div class="modal__card modal__card--wide">
      <div class="modal__title">提交前校验确认</div>
      <div class="modal__body">
        <template v-if="check">
          <!-- 数据批次差异清单 -->
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
                  <td>{{ d.datasetCode }}</td>
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

          <div v-if="check.blockSubmit" class="check-block-tip">
            存在阻断项(质量 BLOCK 或突破硬边界),禁止提交。请返回修改申请内容后重新校验。
          </div>
          <div v-else class="check-pass-tip">
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
import { productName } from '@/utils/dict'

const props = defineProps<{
  modelValue: boolean
  check: SubmitCheck | null
  submitting?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'confirm'): void
}>()

// 仅展示 WARN/BLOCK 项,PASS 不逐条罗列

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
</style>
