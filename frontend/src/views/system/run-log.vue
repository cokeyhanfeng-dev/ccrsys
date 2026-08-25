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
          <el-select v-model="query.level" placeholder="全部" clearable size="small" class="query-input" style="width: 110px">
            <el-option v-for="lv in options.levels" :key="lv" :label="lv" :value="lv" />
          </el-select>
        </div>
        <div class="query-field">
          <label class="query-label">状态</label>
          <el-select v-model="query.status" placeholder="全部" clearable size="small" class="query-input" style="width: 120px">
            <el-option v-for="s in options.statuses" :key="s" :label="statusText(s)" :value="s" />
          </el-select>
        </div>
        <div class="query-field">
          <label class="query-label">开始</label>
          <el-date-picker v-model="query.startTime" type="datetime" size="small" value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="开始时间" class="query-input" style="width: 190px" />
        </div>
        <div class="query-field">
          <label class="query-label">结束</label>
          <el-date-picker v-model="query.endTime" type="datetime" size="small" value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="结束时间" class="query-input" style="width: 190px" />
        </div>
        <div class="query-field">
          <label class="query-label">关键词</label>
          <el-input v-model="query.keyword" placeholder="消息/堆栈/类名" clearable size="small" class="query-input" style="width: 180px" @keyup.enter="search" />
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

    <!-- ③ 流程监控 -->
    <div v-if="activeTab === 'flows'" class="card">
      <div class="query-bar">
        <div class="query-field">
          <label class="query-label">状态</label>
          <el-select v-model="flowQuery.status" placeholder="全部在途" clearable size="small" class="query-input" style="width: 150px">
            <el-option v-for="s in flowStatuses" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </div>
        <div class="query-field">
          <label class="query-label">业务类型</label>
          <el-select v-model="flowQuery.businessType" placeholder="全部" clearable size="small" class="query-input" style="width: 130px">
            <el-option label="贷款" value="LOAN" />
            <el-option label="存款" value="DEPOSIT" />
          </el-select>
        </div>
        <div class="query-field">
          <label class="query-label">申请号</label>
          <el-input v-model="flowQuery.applicationNo" placeholder="申请号模糊" clearable size="small" class="query-input" style="width: 180px" @keyup.enter="searchFlows" />
        </div>
        <button class="btn btn--primary" :disabled="flowsLoading" @click="searchFlows">{{ flowsLoading ? '查询中…' : '查询' }}</button>
        <button class="btn btn--text" @click="resetFlows">重置</button>
      </div>

      <el-table :data="flows" v-loading="flowsLoading" row-key="applicationId" style="width: 100%">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="flow-detail">
              <div class="flow-reason">
                <div class="flow-reason__item">
                  <span class="flow-reason__label">链路形态</span>
                  <span class="flow-reason__text">{{ row.routeReason || '—' }}</span>
                </div>
                <div class="flow-reason__item">
                  <span class="flow-reason__label">当前原因</span>
                  <span class="flow-reason__text">{{ row.currentReason || '—' }}</span>
                </div>
              </div>
              <div v-if="row.nodes && row.nodes.length" class="progress-nodes">
                <div v-for="(n, i) in row.nodes" :key="n.nodeCode" class="progress-node" :class="'node--' + n.status">
                  <div class="node-rail">
                    <span class="node-dot"></span>
                    <span v-if="i < row.nodes.length - 1" class="node-line"></span>
                  </div>
                  <div class="node-body">
                    <div class="node-title">
                      <span>{{ n.label }}</span>
                      <span class="node-state" :class="'state--' + n.status">{{ nodeStatusText(n) }}</span>
                    </div>
                    <div v-if="n.status === 'DONE' && (n.operatorName || n.operationTime || n.result || n.decision)" class="node-meta">
                      <span v-if="n.operatorName">{{ n.operatorName }}</span>
                      <span v-if="n.operationTime">{{ fmtTime(n.operationTime) }}</span>
                      <span v-if="n.result">计票 {{ n.result }}</span>
                      <span v-if="n.decision">决策 {{ n.decision }}</span>
                    </div>
                    <div v-else-if="n.submittedCount != null" class="node-meta vote">
                      <el-progress :percentage="votePct(n)" :stroke-width="8" :show-text="false" :stroke-color="n.approveCount != null && n.requiredCount != null && n.approveCount >= n.requiredCount ? '#67C23A' : '#409EFF'" />
                      <span class="vote-text">已投 {{ n.submittedCount }}/{{ n.voterCount }} · 同意 {{ n.approveCount ?? '—' }} 票(通过线 ≥{{ n.requiredCount }})<span v-if="n.approveCount != null && n.requiredCount != null && n.approveCount >= n.requiredCount" class="vote-pass"> · 已达通过线</span></span>
                    </div>
                    <div v-else-if="n.status === 'SKIPPED'" class="node-meta">该节点无需审批，流程自动跳过</div>
                  </div>
                </div>
              </div>
              <div v-else class="empty-cell">暂无节点数据</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="申请号" min-width="150">
          <template #default="{ row }">{{ row.applicationNo }}</template>
        </el-table-column>
        <el-table-column label="业务类型" width="90">
          <template #default="{ row }">
            <span :class="row.businessType === 'DEPOSIT' ? 'badge badge--warning' : 'badge badge--info'">{{ businessTypeText(row.businessType) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="客户范围" width="110">
          <template #default="{ row }">{{ customerScopeText(row.customerScope) }}</template>
        </el-table-column>
        <el-table-column label="金额(万元)" width="110">
          <template #default="{ row }">{{ row.amount != null ? fmtNum(row.amount) : '—' }}</template>
        </el-table-column>
        <el-table-column label="产品" min-width="130">
          <template #default="{ row }">{{ row.productCode || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span :class="itemStatusBadge(row.status)">{{ itemStatusText(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="当前节点" min-width="120">
          <template #default="{ row }">{{ row.currentNodeLabel || '—' }}</template>
        </el-table-column>
        <el-table-column label="提交时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.submitTime) }}</template>
        </el-table-column>
      </el-table>

      <div v-if="flowsTotal > 0" class="pagination-wrap">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :total="flowsTotal"
          v-model:current-page="flowPageNum"
          v-model:page-size="flowPageSize"
          :page-sizes="[10, 20, 50]"
          @current-change="loadFlows"
          @size-change="onFlowSizeChange"
        />
      </div>
    </div>

    <!-- 报错详情弹窗 -->
    <el-dialog v-model="detailVisible" title="报错详情" width="680px" append-to-body>
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
    <el-dialog v-model="previewVisible" :title="previewName" width="720px" append-to-body>
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
  getRunErrorOptions, listLogFiles, tailLogFile, downloadLogFile, getFlowMonitor,
  type RunErrorRow, type LogFileInfo, type FlowRow
} from '@/api/runLog'
import { businessTypeText, itemStatusText } from '@/utils/dict'

const tabs = [
  { key: 'errors', label: '运行错误' },
  { key: 'files', label: '日志文件' },
  { key: 'flows', label: '流程监控' }
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

// ---------- 流程监控 ----------
const flowStatuses = [
  { value: 'ROUTING', label: '路由中' },
  { value: 'VOTING', label: '表决中' },
  { value: 'COMMITTEE_PASS', label: '已过会待决策' },
  { value: 'PRESIDENT_DECISION', label: '行长决策' }
]
const flowsLoading = ref(false)
const flows = ref<FlowRow[]>([])
const flowsTotal = ref(0)
const flowPageNum = ref(1)
const flowPageSize = ref(10)
const flowQuery = ref<{ status?: string; businessType?: string; applicationNo?: string }>({})

async function loadFlows() {
  flowsLoading.value = true
  try {
    const data = await getFlowMonitor({ ...flowQuery.value, page: flowPageNum.value, size: flowPageSize.value })
    flows.value = data.records || []
    flowsTotal.value = Number(data.total) || 0
  } catch {
    flows.value = []
    flowsTotal.value = 0
  } finally {
    flowsLoading.value = false
  }
}
function searchFlows() {
  flowPageNum.value = 1
  loadFlows()
}
function resetFlows() {
  flowQuery.value = {}
  flowPageNum.value = 1
  loadFlows()
}
function onFlowSizeChange() {
  flowPageNum.value = 1
  loadFlows()
}
function nodeStatusText(n: any) {
  if (n.status === 'DONE') return '已处理'
  if (n.status === 'CURRENT') return '进行中'
  if (n.status === 'SKIPPED') return '跳过'
  return '待处理'
}
function votePct(n: any) {
  if (!n.voterCount) return 0
  return Math.round(((n.submittedCount || 0) / n.voterCount) * 100)
}
function customerScopeText(s: unknown) {
  const map: Record<string, string> = { INDIVIDUAL: '个人', CORPORATE_SINGLE: '企业单户', GROUP: '集团' }
  return map[String(s || '')] || String(s || '—')
}
function fmtNum(v: unknown) {
  if (v == null || v === '') return '—'
  return Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}
function itemStatusBadge(s: unknown) {
  const map: Record<string, string> = {
    ROUTING: 'badge badge--info',
    VOTING: 'badge badge--warning',
    COMMITTEE_PASS: 'badge badge--info',
    PRESIDENT_DECISION: 'badge badge--warning'
  }
  return map[String(s || '')] || 'badge badge--neutral'
}

function switchTab(key: string) {
  activeTab.value = key
  if (timer) { clearInterval(timer); timer = null }
  if (key === 'errors') {
    load()
    loadStats()
    if (autoRefresh.value) onAutoRefresh(true)
  } else if (key === 'flows') {
    loadFlows()
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
/* 查询栏(label 在上/控件在下,与 audit 等页一致,避免字与框紧贴) */
.query-bar { display: flex; align-items: flex-end; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.query-field { display: flex; flex-direction: column; gap: 4px; }
.query-label { font-size: 11px; color: var(--color-text-sub); }
.error-stats {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 8px 12px;
  background: var(--fill-light, #f7f8fa);
  border-radius: var(--radius-sm, 6px);
  margin-bottom: 10px;
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

/* 流程监控:展开行链路形态/当前原因 + 节点时间线(与 history 进度弹窗同款配色) */
.flow-detail { padding: 6px 8px 2px; }
.flow-reason { display: flex; flex-direction: column; gap: 6px; margin-bottom: 14px; }
.flow-reason__item { display: flex; gap: 8px; font-size: 13px; line-height: 1.6; }
.flow-reason__label { flex-shrink: 0; width: 64px; color: #909399; font-size: 12px; padding-top: 1px; }
.flow-reason__text { color: #303133; }
.progress-nodes { padding-left: 4px; }
.progress-node { display: flex; }
.node-rail { display: flex; flex-direction: column; align-items: center; width: 20px; margin-right: 12px; }
.node-dot { width: 12px; height: 12px; border-radius: 50%; background: #d9d9d9; flex-shrink: 0; margin-top: 2px; }
.node-line { width: 2px; flex: 1; min-height: 28px; background: #e8e8e8; }
.node--DONE .node-dot { background: #52c41a; }
.node--CURRENT .node-dot { background: #409eff; box-shadow: 0 0 0 3px rgba(64, 158, 255, 0.2); }
.node-body { flex: 1; padding-bottom: 22px; }
.node-title { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 500; }
.node-state { font-size: 12px; font-weight: 400; padding: 1px 8px; border-radius: 10px; }
.state--DONE { color: #52c41a; background: rgba(82, 196, 26, 0.12); }
.state--CURRENT { color: #409eff; background: rgba(64, 158, 255, 0.12); }
.state--PENDING, .state--SKIPPED { color: #909399; background: rgba(144, 147, 153, 0.12); }
.node-meta { margin-top: 4px; font-size: 12px; color: #909399; display: flex; gap: 12px; }
.node-meta.vote { display: block; margin-top: 8px; }
.vote-text { font-size: 12px; color: #606266; margin-top: 4px; display: inline-block; }
.vote-pass { color: #67c23a; font-weight: 500; }

/* 紧凑化特例(全局类已全局收紧,此处仅保留本页特定组件) */
/* 查询栏按钮与 small 控件对齐 */
.query-bar .btn { padding: 4px 12px; font-size: 13px; line-height: 1.2; }
/* 分页容器 */
.pagination-wrap { margin-top: 10px; }
/* 流程监控展开行:节点时间线紧凑 */
.flow-detail { padding: 4px 6px 0; }
.flow-reason { gap: 4px; margin-bottom: 10px; }
.flow-reason__item { font-size: 12px; line-height: 1.55; }
.flow-reason__label { width: 56px; font-size: 11px; }
.node-dot { width: 10px; height: 10px; }
.node-line { min-height: 20px; }
.node-body { padding-bottom: 16px; }
.node-title { font-size: 13px; }
.node-state { font-size: 11px; padding: 0 7px; }
.node-meta { font-size: 11px; margin-top: 3px; gap: 10px; }
.node-meta.vote { margin-top: 6px; }
.vote-text { font-size: 11px; }
</style>
