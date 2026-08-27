<template>
  <div>
    <div class="section-head">
      <div class="section-title">数据中心</div>
      <InfoTip content="批次落地监控与数据源时效看板（数据来源于数仓落地批次）" />
      <!-- §UI审查:新增刷新入口,避免数据源时效需整页重载 -->
      <button class="btn btn--secondary head-refresh" :disabled="batchLoading || sourceLoading" @click="load">
        {{ batchLoading || sourceLoading ? '刷新中…' : '刷新' }}
      </button>
    </div>

    <div class="dc-grid">
      <!-- ① 批次落地监控:各表最新批次的数据日期/行数/落地时间 -->
      <!-- §UI审查:加载中 v-loading;失败置 errorFlag,与「真无数据」区分 -->
      <div class="card" v-loading="batchLoading">
        <div class="card-title">批次落地监控</div>
        <table class="table">
          <thead>
            <tr>
              <th>数据表</th><th>数据源</th><th>最新数据日期</th><th>批次行数</th><th>落地时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in batches" :key="row.table || i">
              <td>{{ row.table }}</td>
              <td>{{ row.sourceName || '—' }}</td>
              <td>{{ fmtDate(row.latestDataDt) }}</td>
              <td class="num">{{ row.batchRows ?? '—' }}</td>
              <td>{{ fmtTime(row.landedTime) }}</td>
            </tr>
            <!-- §UI审查:加载失败与真无数据区分;空态统一全局 .empty 插画 -->
            <tr v-if="batchError && !batchLoading">
              <td colspan="5"><div class="empty">加载失败，请刷新</div></td>
            </tr>
            <tr v-else-if="!batches.length && !batchLoading">
              <td colspan="5"><div class="empty">暂无批次数据</div></td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- ② 数据源时效看板:OK/STALE 状态灯,STALE 红色醒目 -->
      <!-- §UI审查:加载中 v-loading;失败/真空态区分,空态统一全局 .empty 插画 -->
      <div class="card" v-loading="sourceLoading">
        <div class="card-title">数据源时效看板</div>
        <div v-if="sources.length" class="source-list">
          <div
            v-for="(row, i) in sources"
            :key="row.table || i"
            class="source-item"
            :class="{ 'source-item--stale': isStale(row.status) }"
          >
            <span class="source-dot" :class="isStale(row.status) ? 'source-dot--stale' : 'source-dot--ok'"></span>
            <div class="source-item__body">
              <div class="source-item__name">
                {{ row.sourceName || datasetName(row.table) }}
                <span :class="isStale(row.status) ? 'badge badge--danger' : 'badge badge--success'">
                  {{ isStale(row.status) ? '已过期' : '正常' }}
                </span>
              </div>
              <div class="source-item__date">最新数据日期：{{ fmtDate(row.latestDataDt) }}</div>
              <div v-if="isStale(row.status)" class="source-item__warn">数据已过期，请联系数据中心刷新</div>
            </div>
          </div>
        </div>
        <div v-else-if="sourceError" class="empty">加载失败，请刷新</div>
        <div v-else-if="!sourceLoading" class="empty">暂无数据源状态</div>
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
import { datasetName } from '@/utils/dict'

const batches = ref<BatchLandingRow[]>([])
const sources = ref<SourceStatusRow[]>([])
// §UI审查:加载/失败态标志,区分「加载中/加载失败/真无数据」三态
const batchLoading = ref(false)
const sourceLoading = ref(false)
const batchError = ref(false)
const sourceError = ref(false)

async function load() {
  // 两个看板独立加载,互不阻塞;失败置 errorFlag 供模板区分「加载失败」与「真无数据」
  batchLoading.value = true
  batchError.value = false
  try {
    batches.value = await listBatches()
  } catch {
    batches.value = []
    batchError.value = true
  } finally {
    batchLoading.value = false
  }
  sourceLoading.value = true
  sourceError.value = false
  try {
    sources.value = await listSourceStatus()
  } catch {
    sources.value = []
    sourceError.value = true
  } finally {
    sourceLoading.value = false
  }
}

const isStale = (status?: string) => (status || '').toUpperCase() === 'STALE'

function fmtDate(t?: string) {
  return t ? String(t).replace('T', ' ').slice(0, 10) : '—'
}
function fmtTime(t?: string) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}

onMounted(load)
</script>

<style scoped>
.dc-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: start; }
@media (max-width: 1200px) { .dc-grid { grid-template-columns: 1fr; } }
/* §UI审查:刷新按钮右对齐 */
.head-refresh { margin-left: auto; }
.table { border-radius: var(--radius-sm); overflow-x: auto; }

/* 时效看板状态灯:OK 绿 / STALE 红 */
.source-list { display: flex; flex-direction: column; gap: 10px; }
.source-item {
  display: flex;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-sm);
  transition: box-shadow .18s;
}
.source-item:hover { box-shadow: var(--shadow-sm); }
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
.source-dot--ok { background: var(--color-success); box-shadow: 0 0 0 3px var(--color-success-light); }
.source-dot--stale { background: var(--color-danger); box-shadow: 0 0 0 3px var(--color-danger-light); }
.source-item__body { flex: 1; min-width: 0; }
.source-item__name { font-weight: 600; display: flex; align-items: center; gap: 8px; }
.source-item__date { margin-top: 4px; font-size: 12px; color: var(--color-text-sub); }
.source-item__warn { margin-top: 4px; font-size: 12px; color: var(--color-danger); font-weight: 600; }
</style>
