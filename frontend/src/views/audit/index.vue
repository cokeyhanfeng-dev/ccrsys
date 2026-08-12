<template>
  <div>
    <div class="section-head">
      <div class="section-title">审计管理</div>
      <InfoTip content="实际投票人反查 / 导出记录 / 配置版本查询(§12.14,仅审计人员与管理员)" />
    </div>

    <!-- ① 实际投票人反查:批次 + 分项 → 真实投票人/票型/匿名码对照 -->
    <div class="card">
      <div class="card-title">实际投票人反查</div>
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
      </div>
      <table class="table" v-if="ballotRows.length">
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

    <!-- ② 导出记录:档案导出留痕(导出人/机构/时间/对象) -->
    <div class="card">
      <div class="card-title">导出记录</div>
      <table class="table">
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

    <!-- ③ 配置版本查询:配置变更日志(§8A.2),另可跳参数管理页 -->
    <div class="card">
      <div class="card-title">
        配置版本查询
        <router-link to="/system/params" class="btn btn--text">前往参数管理</router-link>
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
      <table class="table">
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

    <!-- ④ 操作日志:登录/提交/字段级修改/配置/反查等全程留痕(§15.2) -->
    <div class="card">
      <div class="card-title">操作日志</div>
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
        <button class="btn btn--primary" :disabled="logsLoading" @click="loadLogs">
          {{ logsLoading ? '查询中…' : '查询' }}
        </button>
      </div>
      <table class="table">
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

// ---------- ④ 操作日志 ----------
const logQuery = reactive<{ logType: string; operator: string; startTime: string; endTime: string; keyword: string }>({
  logType: '',
  operator: '',
  startTime: '',
  endTime: '',
  keyword: ''
})
const auditLogs = ref<any[]>([])
const logsLoading = ref(false)

const auditTypeMap: Record<string, string> = {
  LOGIN: '登录',
  LOGIN_FAIL: '登录失败',
  LOGOUT: '退出登录',
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
    auditLogs.value = await listAuditLogs(logQuery)
  } catch {
    auditLogs.value = []
  } finally {
    logsLoading.value = false
  }
}

onMounted(() => {
  loadExportRecords()
  loadChangeLog()
})
</script>

<style scoped>
.sensitive-alert { margin-bottom: 12px; }
.query-bar { display: flex; align-items: flex-end; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.query-field { display: flex; flex-direction: column; gap: 4px; }
.query-label { font-size: 12px; color: var(--color-text-sub); }
.query-input { width: 220px; }
.table { border-radius: var(--radius-sm); overflow-x: auto; }
.log-content {
  max-width: 480px;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  color: var(--color-text-sub);
  line-height: 1.6;
}
</style>
