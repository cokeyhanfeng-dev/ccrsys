<template>
  <div>
    <div class="section-head">
      <div class="section-title">运行日志监控</div>
      <InfoTip content="系统运行报错监控(非审计):报错(含完整堆栈)采集入库可搜索;后台日志文件可查看/下载;后台 SQL 全量打印在 ccr-run.log(仅 admin)" />
    </div>

    <div class="segmented">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="segmented__item"
        :class="{ 'segmented__item--active': activeTab === t.key }"
        @click="switchTab(t.key)"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- ① 运行错误监控 -->
    <div v-if="activeTab === 'errors'" class="card">
      <div class="error-stats">
        <span class="stat stat--pending">待处理 <b>{{ stats.PENDING }}</b></span>
        <span class="stat stat--handled">已处理 <b>{{ stats.HANDLED }}</b></span>
        <span class="stat stat--ignored">已忽略 <b>{{ stats.IGNORED }}</b></span>
        <span class="stat">合计 <b>{{ stats.total }}</b></span>
        <span class="stat-right">
          <el-switch v-model="autoRefresh" size="small" active-text="自动刷新" @change="onAutoRefresh" />
          <span v-if="autoRefresh" class="auto-hint">每 10s</span>
        </span>
      </div>

      <div class="query-bar">
        <div class="query-field">
          <label class="query-label">级别</label>
          <el-select v-model="query.level" placeholder="全部" clearable class="query-input" style="width: 110px">
            <el-option v-for="lv in options.levels" :key="lv" :label="lv" :value="lv" />
          </el-select>
        </div>
        <div class="query-field">
          <label class="query-label">状态</label>
          <el-select v-model="query.status" placeholder="全部" clearable class="query-input" style="width: 120px">
            <el-option v-for="s in options.statuses" :key="s" :label="statusText(s)" :value="s" />
          </el-select>
        </div>
        <div class="query-field">
          <label class="query-label">开始</label>
          <el-date-picker v-model="query.startTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="开始时间" class="query-input" style="width: 190px" />
        </div>
        <div class="query-field">
          <label class="query-label">结束</label>
          <el-date-picker v-model="query.endTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="结束时间" class="query-input" style="width: 190px" />
        </div>
        <div class="query-field">
          <label class="query-label">关键词</label>
          <el-input v-model="query.keyword" placeholder="消息/堆栈/类名" clearable class="query-input" style="width: 180px" @keyup.enter="search" />
        </div>
        <button class="btn btn--primary" :disabled="loading" @click="search">{{ loading ? '查询中…' : '查询' }}</button>
        <button class="btn btn--text" @click="reset">重置</button>
      </div>

      <table class="table table--full">
        <thead>
          <tr>
            <th>时间</th><th>级别</th><th>类名</th><th>请求路径</th><th>消息摘要</th><th>状态</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in records" :key="row.id">
            <td>{{ fmtTime(row.errorTime) }}</td>
            <td><span :class="levelBadge(row.level)">{{ row.level }}</span></td>
            <td class="log-content" :title="row.loggerName">{{ shortLogger(row.loggerName) }}</td>
            <td class="log-content" :title="row.requestUri">{{ row.requestUri || '—' }}</td>
            <td class="log-content" :title="row.message">{{ short(row.message, 60) }}</td>
            <td><span :class="statusBadge(row.handleStatus)">{{ statusText(row.handleStatus) }}</span></td>
            <td>
              <button class="btn btn--text" @click="openDetail(row)">详情</button>
              <button v-if="row.handleStatus !== 'HANDLED'" class="btn btn--text" @click="mark(row, 'HANDLED')">已处理</button>
              <button v-if="row.handleStatus !== 'IGNORED'" class="btn btn--text" @click="mark(row, 'IGNORED')">忽略</button>
            </td>
          </tr>
          <tr v-if="!records.length"><td colspan="7" class="empty-cell">暂无报错记录</td></tr>
        </tbody>
      </table>

      <div v-if="total > 0" class="pagination-wrap">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :total="total"
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          @current-change="load"
          @size-change="onSizeChange"
        />
      </div>
    </div>

    <!-- ② 日志文件 -->
    <div v-if="activeTab === 'files'" class="card">
      <div class="tab-toolbar">
        <span class="tab-toolbar__hint">后台日志目录(容器内 /app/logs,宿主机 logs/)</span>
        <button class="btn btn--primary" @click="loadFiles">刷新</button>
      </div>
      <table class="table table--full">
        <thead>
          <tr><th>文件名</th><th>大小</th><th>修改时间</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="f in files" :key="f.name">
            <td class="log-content">{{ f.name }}</td>
            <td>{{ fmtSize(f.size) }}</td>
            <td>{{ fmtTime(f.lastModified) }}</td>
            <td>
              <button class="btn btn--text" @click="openPreview(f)">预览</button>
              <button class="btn btn--text" @click="downloadFile(f)">下载</button>
            </td>
          </tr>
          <tr v-if="!files.length"><td colspan="4" class="empty-cell">logs/ 目录暂无日志文件</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 报错详情弹窗 -->
    <el-dialog v-model="detailVisible" title="报错详情" width="760px" append-to-body>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-meta">
            <span :class="levelBadge(detail.level)">{{ detail.level }}</span>
            <span class="detail-time">{{ fmtTime(detail.errorTime) }}</span>
            <span class="detail-logger">{{ detail.loggerName }}</span>
          </div>
          <div class="detail-row"><span class="detail-label">请求路径</span>{{ detail.requestUri || '—' }}</div>
          <div class="detail-row"><span class="detail-label">线程</span>{{ detail.threadName || '—' }}</div>
          <div class="detail-row"><span class="detail-label">消息</span>{{ detail.message || '—' }}</div>
          <div class="detail-label">堆栈</div>
          <pre class="stack">{{ detail.stackTrace || '无堆栈信息' }}</pre>
        </template>
      </div>
    </el-dialog>

    <!-- 日志文件预览弹窗 -->
    <el-dialog v-model="previewVisible" :title="previewName" width="780px" append-to-body>
      <div class="preview-toolbar">
        <span class="preview-hint">尾部 {{ previewLines.length }} 行</span>
        <button class="btn btn--text" @click="refreshPreview">刷新</button>
      </div>
      <pre class="stack">{{ previewText }}</pre>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  pageRunErrors, getRunErrorDetail, getRunErrorStats, updateRunErrorStatus,
  getRunErrorOptions, listLogFiles, tailLogFile, downloadLogFile,
  type RunErrorRow, type LogFileInfo
} from '@/api/runLog'

const tabs = [
  { key: 'errors', label: '运行错误' },
  { key: 'files', label: '日志文件' }
]
const activeTab = ref('errors')

// ---------- 错误监控 ----------
const loading = ref(false)
const records = ref<RunErrorRow[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const query = ref<{ level?: string; status?: string; startTime?: string; endTime?: string; keyword?: string }>({})
const options = ref<{ levels: string[]; statuses: string[]; loggers: string[] }>({ levels: [], statuses: [], loggers: [] })
const stats = ref<{ PENDING: number; HANDLED: number; IGNORED: number; total: number }>({ PENDING: 0, HANDLED: 0, IGNORED: 0, total: 0 })

async function load() {
  loading.value = true
  try {
    const data = await pageRunErrors({ ...query.value, pageNum: pageNum.value, pageSize: pageSize.value })
    records.value = data.records || []
    total.value = Number(data.total) || 0
  } catch {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}
function search() {
  pageNum.value = 1
  load()
}
function reset() {
  query.value = {}
  pageNum.value = 1
  load()
}
function onSizeChange() {
  pageNum.value = 1
  load()
}
async function loadStats() {
  try {
    stats.value = await getRunErrorStats()
  } catch { /* 忽略 */ }
}
async function loadOptions() {
  try {
    options.value = await getRunErrorOptions()
  } catch { /* 忽略 */ }
}

// 详情(含完整堆栈)
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<RunErrorRow | null>(null)
async function openDetail(row: RunErrorRow) {
  detail.value = null
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await getRunErrorDetail(row.id)
  } catch {
    detail.value = row
  } finally {
    detailLoading.value = false
  }
}

// 标记处理状态
async function mark(row: RunErrorRow, status: string) {
  try {
    await updateRunErrorStatus(row.id, status)
    row.handleStatus = status
    loadStats()
    if (query.value.status && query.value.status !== status) {
      load()
    }
  } catch { /* request 层已提示 */ }
}

// 自动刷新(错误页签每 10s)
let timer: number | null = null
const autoRefresh = ref(false)
function onAutoRefresh(v: boolean) {
  if (timer) { clearInterval(timer); timer = null }
  if (v && activeTab.value === 'errors') {
    timer = window.setInterval(() => { load(); loadStats() }, 10_000)
  }
}

// ---------- 日志文件 ----------
const files = ref<LogFileInfo[]>([])
async function loadFiles() {
  try {
    files.value = await listLogFiles()
  } catch {
    files.value = []
  }
}
function downloadFile(f: LogFileInfo) {
  downloadLogFile(f.name)
}

const previewVisible = ref(false)
const previewName = ref('')
const previewLines = ref<string[]>([])
const previewText = computed(() => previewLines.value.join('\n'))
async function openPreview(f: LogFileInfo) {
  previewName.value = f.name
  previewLines.value = []
  previewVisible.value = true
  await refreshPreview()
}
async function refreshPreview() {
  try {
    const data = await tailLogFile(previewName.value, 300)
    previewLines.value = data.lines || []
  } catch {
    previewLines.value = ['预览失败']
  }
}

function switchTab(key: string) {
  activeTab.value = key
  if (timer) { clearInterval(timer); timer = null }
  if (key === 'errors') {
    load()
    loadStats()
    if (autoRefresh.value) onAutoRefresh(true)
  } else {
    loadFiles()
  }
}

// ---------- 工具 ----------
function fmtTime(t: unknown) {
  if (!t) return '—'
  if (typeof t === 'number') return new Date(t).toLocaleString('zh-CN')
  return String(t).replace('T', ' ').slice(0, 19)
}
function fmtSize(n: number) {
  if (!n) return '0 B'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}
function short(s: unknown, n: number) {
  if (!s) return '—'
  const str = String(s)
  return str.length > n ? str.slice(0, n) + '…' : str
}
function shortLogger(s: unknown) {
  if (!s) return '—'
  const parts = String(s).split('.')
  return parts.length > 1 ? parts.slice(-2).join('.') : s
}
function statusText(s: unknown) {
  const map: Record<string, string> = { PENDING: '待处理', HANDLED: '已处理', IGNORED: '已忽略' }
  return map[String(s || '')] || String(s || '—')
}
function levelBadge(lv: unknown) {
  const map: Record<string, string> = { ERROR: 'badge badge--danger', WARN: 'badge badge--warning', FATAL: 'badge badge--danger' }
  return map[String(lv || '')] || 'badge badge--neutral'
}
function statusBadge(s: unknown) {
  const map: Record<string, string> = { PENDING: 'badge badge--info', HANDLED: 'badge badge--success', IGNORED: 'badge badge--neutral' }
  return map[String(s || '')] || 'badge badge--neutral'
}

onMounted(() => {
  load()
  loadStats()
  loadOptions()
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.error-stats {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 10px 14px;
  background: var(--fill-light, #f7f8fa);
  border-radius: var(--radius-sm, 6px);
  margin-bottom: 14px;
}
.stat { font-size: 13px; color: #606266; }
.stat b { font-size: 16px; margin-left: 2px; }
.stat--pending b { color: #e6a23c; }
.stat--handled b { color: #52c41a; }
.stat--ignored b { color: #909399; }
.stat-right { margin-left: auto; display: flex; align-items: center; gap: 6px; }
.auto-hint { font-size: 12px; color: #909399; }
.detail-meta { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.detail-time { color: #606266; font-size: 13px; }
.detail-logger { color: #909399; font-size: 12px; }
.detail-row { font-size: 13px; color: #303133; margin-bottom: 8px; }
.detail-label { display: inline-block; width: 72px; color: #909399; font-size: 12px; margin-bottom: 4px; }
.stack {
  margin: 6px 0 0;
  padding: 12px;
  background: #0f1419;
  color: #d9e2ec;
  border-radius: var(--radius-sm, 6px);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 420px;
  overflow: auto;
}
.tab-toolbar__hint { font-size: 12px; color: #909399; margin-right: auto; }
.preview-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.preview-hint { font-size: 12px; color: #909399; }
</style>
