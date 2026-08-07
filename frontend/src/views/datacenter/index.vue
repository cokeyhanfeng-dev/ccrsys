<template>
  <div>
    <div class="section-head">
      <div class="section-title">数据中心</div>
      <div class="section-tip">批次落地监控与数据源时效看板(§9.6 F8,数据来源于数仓落地批次)</div>
    </div>

    <div class="dc-grid">
      <!-- ① 批次落地监控:各表最新批次的数据日期/行数/状态 -->
      <div class="card">
        <div class="card-title">批次落地监控</div>
        <table class="table">
          <thead>
            <tr>
              <th>数据表</th><th>批次号</th><th>最新数据日期</th><th>行数</th><th>状态</th><th>耗时</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in batches" :key="row.batchNo || i">
              <td>{{ row.tableName || '—' }}</td>
              <td>{{ row.batchNo || '—' }}</td>
              <td>{{ fmtDate(row.dataDate) }}</td>
              <td class="num">{{ row.rowCount ?? '—' }}</td>
              <td><span :class="batchBadge(row.status)">{{ row.status || '—' }}</span></td>
              <td>{{ fmtCost(row.costMs) }}</td>
            </tr>
            <tr v-if="!batches.length"><td colspan="6" class="empty-cell">暂无批次数据</td></tr>
          </tbody>
        </table>
      </div>

      <!-- ② 数据源时效看板:OK/STALE 状态灯,STALE 红色醒目 -->
      <div class="card">
        <div class="card-title">数据源时效看板</div>
        <div v-if="sources.length" class="source-list">
          <div
            v-for="(row, i) in sources"
            :key="row.sourceCode || i"
            class="source-item"
            :class="{ 'source-item--stale': isStale(row.status) }"
          >
            <span class="source-dot" :class="isStale(row.status) ? 'source-dot--stale' : 'source-dot--ok'"></span>
            <div class="source-item__body">
              <div class="source-item__name">
                {{ row.sourceName || row.sourceCode || '—' }}
                <span :class="isStale(row.status) ? 'badge badge--danger' : 'badge badge--success'">
                  {{ isStale(row.status) ? 'STALE' : 'OK' }}
                </span>
              </div>
              <div class="source-item__date">最新数据日期:{{ fmtDate(row.dataDate) }}</div>
              <div v-if="isStale(row.status)" class="source-item__warn">数据已过期,请联系数据中心刷新</div>
            </div>
          </div>
        </div>
        <div v-else class="empty-cell">暂无数据源状态</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  listBatches,
  listSourceStatus,
  type BatchLandingRow,
  type SourceStatusRow
} from '@/api/datacenter'

const batches = ref<BatchLandingRow[]>([])
const sources = ref<SourceStatusRow[]>([])

async function load() {
  // 两个看板独立加载,互不阻塞
  try {
    batches.value = await listBatches()
  } catch {
    batches.value = []
  }
  try {
    sources.value = await listSourceStatus()
  } catch {
    sources.value = []
  }
}

const isStale = (status?: string) => (status || '').toUpperCase() === 'STALE'

function batchBadge(status?: string) {
  const s = (status || '').toUpperCase()
  if (['SUCCESS', 'OK', 'DONE'].includes(s)) return 'badge badge--success'
  if (['FAILED', 'ERROR'].includes(s)) return 'badge badge--danger'
  if (['RUNNING', 'PROCESSING'].includes(s)) return 'badge badge--info'
  return 'badge badge--neutral'
}

function fmtDate(t?: string) {
  return t ? String(t).replace('T', ' ').slice(0, 10) : '—'
}
function fmtCost(costMs?: number) {
  if (costMs == null) return '—'
  return costMs >= 1000 ? `${(costMs / 1000).toFixed(1)}s` : `${costMs}ms`
}

onMounted(load)
</script>

<style scoped>
.section-head { margin-bottom: 16px; }
.section-title { font-size: var(--fs-h2); font-weight: 600; margin-bottom: 6px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
.dc-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: start; }
@media (max-width: 1200px) { .dc-grid { grid-template-columns: 1fr; } }
.card { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius); padding: var(--space-4); box-shadow: var(--shadow-sm); }
.card-title { font-size: var(--fs-h3); font-weight: 600; margin-bottom: 12px; }
.table { border-radius: var(--radius); overflow: hidden; }
.empty-cell { text-align: center; color: var(--color-text-light); padding: 24px 0; }

/* 时效看板状态灯:OK 绿 / STALE 红 */
.source-list { display: flex; flex-direction: column; gap: 10px; }
.source-item {
  display: flex;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}
.source-item--stale {
  border-color: var(--color-danger);
  background: var(--color-danger-light);
}
.source-dot {
  flex: none;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-top: 5px;
}
.source-dot--ok { background: var(--color-success); }
.source-dot--stale { background: var(--color-danger); }
.source-item__body { flex: 1; min-width: 0; }
.source-item__name { font-weight: 600; display: flex; align-items: center; gap: 8px; }
.source-item__date { margin-top: 4px; font-size: 12px; color: var(--color-text-sub); }
.source-item__warn { margin-top: 4px; font-size: 12px; color: var(--color-danger); font-weight: 600; }
</style>
