<template>
  <div>
    <div class="section-head">
      <div class="section-title">审计管理</div>
      <InfoTip content="实际投票人反查 / 导出记录 / 配置版本查询(§12.14,仅审计人员与管理员)" />
    </div>

    <!-- 审计四个功能区块以分段页签展示(参照流程配置页) -->
    <div class="segmented">
      <button
        v-for="t in auditTabs"
        :key="t.key"
        class="segmented__item"
        :class="{ 'segmented__item--active': activeAuditTab === t.key }"
        @click="activeAuditTab = t.key"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- ① 实际投票人反查 -->
    <div v-if="activeAuditTab === 'ballot'" class="card">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="敏感查询已留痕:本查询将解除表决匿名性,每次查询均记录操作人、时间与查询条件,纳入审计日志"
        class="sensitive-alert"
      />
      <div class="query-bar">
        <div class="query-field">
          <label class="query-label">表决批次(roundId)</label>
          <el-input v-model="ballotQuery.roundId" placeholder="表决批次主键" clearable class="query-input" />
        </div>
        <div class="query-field">
          <label class="query-label">分项(pricingItemId)</label>
          <el-input v-model="ballotQuery.pricingItemId" placeholder="分项主键" clearable class="query-input" />
        </div>
        <button class="btn btn--primary" :disabled="ballotLoading" @click="queryBallot">
          {{ ballotLoading ? '查询中…' : '查询' }}
        </button>
        <button class="btn btn--primary" :disabled="!ballotRows.length" @click="exportCsv(ballotRows, ballotCsvCols, '实际投票人反查')">
          导出
        </button>
      </div>
      <table class="table table--full" v-if="ballotRows.length">
        <thead>
          <tr>
            <th>真实投票人</th><th>岗位</th><th>机构</th><th>票型</th><th>匿名码对照</th><th>投票时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in ballotRows" :key="i">
            <td>{{ row.voterName || row.userName || '—' }}</td>
            <td>{{ row.postName || '—' }}</td>
            <td>{{ row.orgName || '—' }}</td>
            <td><span :class="ballotBadge(row.ballotType)">{{ ballotText(row.ballotType) }}</span></td>
            <td>{{ row.anonymousCode || '—' }}</td>
            <td>{{ fmtTime(row.voteTime) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-cell">{{ ballotQueried ? '无匹配投票记录' : '输入批次/分项后查询' }}</div>
    </div>

    <!-- ② 导出记录 -->
    <div v-if="activeAuditTab === 'export'" class="card">
      <div class="tab-toolbar">
        <button class="btn btn--primary" :disabled="!exportRecords.length" @click="exportCsv(exportRecords, exportCsvCols, '导出记录')">
          导出
        </button>
      </div>
      <table class="table table--full">
        <thead>
          <tr>
            <th>导出对象</th><th>导出类型</th><th>导出人</th><th>所属机构</th><th>导出时间</th><th>水印标识</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in exportRecords" :key="row.id || i">
            <td>{{ row.targetNo || row.applicationNo || row.targetId || '—' }}</td>
            <td>{{ exportTypeText(row.exportType) }}</td>
            <td>{{ row.operatorName || row.exportBy || '—' }}</td>
            <td>{{ row.orgName || '—' }}</td>
            <td>{{ fmtTime(row.exportTime || row.createTime) }}</td>
            <td>{{ row.watermark || '—' }}</td>
          </tr>
          <tr v-if="!exportRecords.length"><td colspan="6" class="empty-cell">暂无导出记录</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ③ 配置版本查询 -->
    <div v-if="activeAuditTab === 'config'" class="card">
      <div class="tab-toolbar">
        <router-link to="/system/params" class="btn btn--text">前往参数管理</router-link>
        <button class="btn btn--primary" :disabled="!changeLogs.length" @click="exportCsv(changeLogs, changeLogCsvCols, '配置变更日志')">
          导出
        </button>
      </div>
      <div class="query-bar">
        <div class="query-field">
          <label class="query-label">配置域</label>
          <el-select v-model="changeLogType" placeholder="全部" clearable class="query-input">
            <el-option label="LPR 阈值" value="LPR" />
            <el-option label="权限矩阵" value="MATRIX" />
            <el-option label="利率规则集" value="RULE_SET" />
            <el-option label="产品硬边界" value="PRODUCT_LIMIT" />
          </el-select>
        </div>
        <button class="btn btn--primary" :disabled="changeLogLoading" @click="loadChangeLog">
          {{ changeLogLoading ? '查询中…' : '查询' }}
        </button>
      </div>
      <table class="table table--full">
        <thead>
          <tr>
            <th>配置域</th><th>配置ID</th><th>动作</th><th>操作人</th><th>操作时间</th><th>复核意见</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in changeLogs" :key="row.id || i">
            <td>{{ configTypeText(row.configType) }}</td>
            <td>{{ row.configId ?? '—' }}</td>
            <td><span :class="actionBadge(row.action)">{{ actionText(row.action) }}</span></td>
            <td>{{ row.operatorId ?? '—' }}</td>
            <td>{{ fmtTime(row.operateTime) }}</td>
            <td>{{ row.opinion || '—' }}</td>
          </tr>
          <tr v-if="!changeLogs.length"><td colspan="6" class="empty-cell">暂无变更记录</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ④ 操作日志 -->
    <div v-if="activeAuditTab === 'log'" class="card">
      <div class="tab-toolbar">
        <button class="btn btn--primary" :disabled="!auditLogs.length" @click="exportCsv(auditLogs, auditLogCsvCols, '操作日志')">
          导出
        </button>
      </div>
      <div class="query-bar">
        <div class="query-field">
          <label class="query-label">日志类型</label>
          <el-select v-model="logQuery.logType" placeholder="全部" clearable class="query-input">
            <el-option v-for="(label, code) in auditTypeMap" :key="code" :label="label" :value="code" />
          </el-select>
        </div>
        <div class="query-field">
          <label class="query-label">操作人</label>
          <el-input v-model="logQuery.operator" placeholder="操作人姓名" clearable class="query-input" />
        </div>
        <div class="query-field">
          <label class="query-label">开始时间</label>
          <el-date-picker v-model="logQuery.startTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="开始" class="query-input" />
        </div>
        <div class="query-field">
          <label class="query-label">结束时间</label>
          <el-date-picker v-model="logQuery.endTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="结束" class="query-input" />
        </div>
        <div class="query-field">
          <label class="query-label">关键词</label>
          <el-input v-model="logQuery.keyword" placeholder="申请号/合同号等" clearable class="query-input" />
        </div>
        <button class="btn btn--primary" :disabled="logsLoading" @click="searchLogs">
          {{ logsLoading ? '查询中…' : '查询' }}
        </button>
      </div>
      <table class="table table--full">
        <thead>
          <tr>
            <th>类型</th><th>操作人</th><th>操作时间</th><th>对象</th><th>内容</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in auditLogs" :key="row.id || i">
            <td><span :class="logBadge(row.logType)">{{ auditTypeText(row.logType) }}</span></td>
            <td>{{ row.operatorName || row.operatorId || '—' }}</td>
            <td>{{ fmtTime(row.operateTime) }}</td>
            <td>{{ row.bizId || '—' }}</td>
            <td class="log-content">{{ row.content || '—' }}</td>
          </tr>
          <tr v-if="!auditLogs.length"><td colspan="5" class="empty-cell">暂无操作日志</td></tr>
        </tbody>
      </table>
      <div v-if="logTotal > 0" class="pagination-wrap">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :total="logTotal"
          v-model:current-page="logPageNum"
          v-model:page-size="logPageSize"
          :page-sizes="[10, 20, 50, 100]"
          @current-change="loadLogs"
          @size-change="changeLogSize"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getBallotDetail,
  listExportRecords,
  listConfigChangeLog,
  listAuditLogs,
  type BallotDetailRow
} from '@/api/audit'
import { voteChoiceText, exportTypeText, configTypeText, configActionText } from '@/utils/dict'

// ---------- 分段页签(参照流程配置页) ----------
const auditTabs = [
  { key: 'ballot', label: '实际投票人反查' },
  { key: 'export', label: '导出记录' },
  { key: 'config', label: '配置版本查询' },
  { key: 'log', label: '操作日志' }
]
const activeAuditTab = ref('ballot')

// ---------- ① 实际投票人反查 ----------
const ballotQuery = reactive<{ roundId: string; pricingItemId: string }>({ roundId: '', pricingItemId: '' })
const ballotRows = ref<BallotDetailRow[]>([])
const ballotLoading = ref(false)
const ballotQueried = ref(false)

async function queryBallot() {
  if (!ballotQuery.roundId && !ballotQuery.pricingItemId) {
    ElMessage.warning('请至少输入表决批次或分项之一')
    return
  }
  ballotLoading.value = true
  try {
    ballotRows.value = await getBallotDetail({
      roundId: ballotQuery.roundId || undefined,
      pricingItemId: ballotQuery.pricingItemId || undefined
    })
    ballotQueried.value = true
  } catch {
    ballotRows.value = []
  } finally {
    ballotLoading.value = false
  }
}

function ballotText(t?: string) {
  return voteChoiceText(t)
}
function ballotBadge(t?: string) {
  return ['APPROVE', 'AGREE'].includes(t || '') ? 'badge badge--success' : 'badge badge--danger'
}

// ---------- ② 导出记录 ----------
const exportRecords = ref<any[]>([])

async function loadExportRecords() {
  try {
    exportRecords.value = await listExportRecords()
  } catch {
    exportRecords.value = []
  }
}

// ---------- ③ 配置版本查询 ----------
const changeLogType = ref('')
const changeLogs = ref<any[]>([])
const changeLogLoading = ref(false)

async function loadChangeLog() {
  changeLogLoading.value = true
  try {
    changeLogs.value = await listConfigChangeLog(changeLogType.value ? { configType: changeLogType.value } : {})
  } catch {
    changeLogs.value = []
  } finally {
    changeLogLoading.value = false
  }
}
function actionText(a?: string) {
  return configActionText(a)
}
function actionBadge(a?: string) {
  const map: Record<string, string> = {
    PUBLISH: 'badge badge--success',
    DISABLE: 'badge badge--neutral',
    REJECT: 'badge badge--danger',
    SUBMIT: 'badge badge--warning'
  }
  return map[a || ''] || 'badge badge--info'
}

function fmtTime(t?: string) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}

// ---------- CSV 导出(带 BOM 防 Excel 中文乱码) ----------
interface CsvCol {
  title: string
  key: string
  fmt?: (v: any) => any
}
function exportCsv(rows: any[], cols: CsvCol[], name: string) {
  if (!rows.length) return
  const esc = (v: unknown) => {
    const s = v == null ? '' : String(v)
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
  }
  const head = cols.map(c => esc(c.title)).join(',')
  const body = rows
    .map(r => cols.map(c => esc(c.fmt ? c.fmt(r[c.key]) : r[c.key])).join(','))
    .join('\n')
  const blob = new Blob(['﻿' + head + '\n' + body], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${name}_${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.csv`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(a.href)
}
// 各页签导出列(值取原始字段;fmt 用于枚举→中文)
const ballotCsvCols: CsvCol[] = [
  { title: '真实投票人', key: 'voterName' },
  { title: '岗位', key: 'postName' },
  { title: '机构', key: 'orgName' },
  { title: '票型', key: 'ballotType', fmt: v => ballotText(v) },
  { title: '匿名码对照', key: 'anonymousCode' },
  { title: '投票时间', key: 'voteTime', fmt: v => fmtTime(v) }
]
const exportCsvCols: CsvCol[] = [
  { title: '导出对象', key: 'targetNo' },
  { title: '导出类型', key: 'exportType', fmt: v => exportTypeText(v) },
  { title: '导出人', key: 'operatorName' },
  { title: '所属机构', key: 'orgName' },
  { title: '导出时间', key: 'exportTime', fmt: v => fmtTime(v) },
  { title: '水印标识', key: 'watermark' }
]
const changeLogCsvCols: CsvCol[] = [
  { title: '配置域', key: 'configType', fmt: v => configTypeText(v) },
  { title: '配置ID', key: 'configId' },
  { title: '动作', key: 'action', fmt: v => actionText(v) },
  { title: '操作人', key: 'operatorId' },
  { title: '操作时间', key: 'operateTime', fmt: v => fmtTime(v) },
  { title: '复核意见', key: 'opinion' }
]
const auditLogCsvCols: CsvCol[] = [
  { title: '类型', key: 'logType', fmt: v => auditTypeText(v) },
  { title: '操作人', key: 'operatorName' },
  { title: '操作时间', key: 'operateTime', fmt: v => fmtTime(v) },
  { title: '对象', key: 'bizId' },
  { title: '内容', key: 'content' }
]

// ---------- ④ 操作日志 ----------
const logQuery = reactive<{ logType: string; operator: string; startTime: string; endTime: string; keyword: string }>({
  logType: '',
  operator: '',
  startTime: '',
  endTime: '',
  keyword: ''
})
const auditLogs = ref<any[]>([])
const logTotal = ref(0)
const logPageNum = ref(1)
const logPageSize = ref(20)
const logsLoading = ref(false)

const auditTypeMap: Record<string, string> = {
  LOGIN: '登录',
  LOGIN_FAIL: '登录失败',
  LOGOUT: '退出登录',
  CHANGE_PASSWORD: '修改密码',
  CHANGE_PASSWORD_FAIL: '修改密码失败',
  APPLY_SUBMIT: '申请提交',
  FIELD_CHANGE: '字段修改',
  ASSIGNEE_CHANGE: '指派变更',
  DELEGATE: '代理设置',
  BALLOT_DETAIL: '票据反查',
  EXPORT: '导出'
}
function auditTypeText(t?: string) {
  return (t && auditTypeMap[t]) || t || '—'
}
function logBadge(t?: string) {
  const map: Record<string, string> = {
    LOGIN: 'badge badge--success',
    LOGIN_FAIL: 'badge badge--danger',
    LOGOUT: 'badge badge--neutral',
    CHANGE_PASSWORD: 'badge badge--success',
    CHANGE_PASSWORD_FAIL: 'badge badge--danger',
    APPLY_SUBMIT: 'badge badge--warning',
    FIELD_CHANGE: 'badge badge--info',
    ASSIGNEE_CHANGE: 'badge badge--info',
    DELEGATE: 'badge badge--info',
    BALLOT_DETAIL: 'badge badge--danger'
  }
  return map[t || ''] || 'badge badge--info'
}
async function loadLogs() {
  logsLoading.value = true
  try {
    const data = await listAuditLogs({ ...logQuery, pageNum: logPageNum.value, pageSize: logPageSize.value })
    auditLogs.value = data?.records || []
    logTotal.value = data?.total || 0
  } catch {
    auditLogs.value = []
    logTotal.value = 0
  } finally {
    logsLoading.value = false
  }
}
// 查询:重置到第 1 页再加载(新过滤条件下页码越界会查空)
function searchLogs() {
  logPageNum.value = 1
  loadLogs()
}
// 每页条数变更:回到第 1 页
function changeLogSize() {
  logPageNum.value = 1
  loadLogs()
}

onMounted(() => {
  loadExportRecords()
  loadChangeLog()
})
</script>

<style scoped>
.sensitive-alert { margin-bottom: 12px; }
.segmented { margin-bottom: 14px; }
/* 页签内工具行(导出/跳转等)右对齐 */
.tab-toolbar { display: flex; justify-content: flex-end; gap: 8px; margin-bottom: 12px; align-items: center; }
.query-bar { display: flex; align-items: flex-end; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.query-field { display: flex; flex-direction: column; gap: 4px; }
.query-label { font-size: 12px; color: var(--color-text-sub); }
.query-input { width: 220px; }
/* 审计属管理/配置列表页:表格撑满容器宽度,右侧不留白(恢复 table 布局,列随宽度分布) */
.card .table--full { display: table; width: 100%; }
.table { border-radius: var(--radius-sm); overflow-x: auto; }
.pagination-wrap { display: flex; justify-content: flex-end; margin-top: 12px; }
.log-content {
  max-width: 480px;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  color: var(--color-text-sub);
  line-height: 1.6;
}
</style>
