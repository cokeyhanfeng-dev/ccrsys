<template>
  <div>
    <div class="section-head">
      <div class="section-title">审计管理</div>
      <div class="section-tip">实际投票人反查 / 导出记录 / 配置版本查询(§12.14,仅审计人员与管理员)</div>
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
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getBallotDetail,
  listExportRecords,
  listConfigChangeLog,
  type BallotDetailRow
} from '@/api/audit'

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
  const map: Record<string, string> = { APPROVE: '赞成', REJECT: '反对', AGREE: '赞成', DISAGREE: '反对' }
  return map[t || ''] || t || '—'
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
function exportTypeText(t?: string) {
  const map: Record<string, string> = { ARCHIVE: '申请档案', RESOLUTION: '决议档案' }
  return map[t || ''] || t || '—'
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
function configTypeText(t?: string) {
  const map: Record<string, string> = {
    LPR: 'LPR 阈值',
    MATRIX: '权限矩阵',
    RULE_SET: '利率规则集',
    PRODUCT_LIMIT: '产品硬边界'
  }
  return map[t || ''] || t || '—'
}
function actionText(a?: string) {
  const map: Record<string, string> = {
    CREATE: '新增',
    SUBMIT: '送复核',
    PUBLISH: '发布',
    DISABLE: '停用',
    REJECT: '复核退回'
  }
  return map[a || ''] || a || '—'
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

onMounted(() => {
  loadExportRecords()
  loadChangeLog()
})
</script>

<style scoped>
.section-head { margin-bottom: 16px; }
.section-title { font-size: var(--fs-h2); font-weight: 600; margin-bottom: 6px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
.card { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius); padding: var(--space-4); box-shadow: var(--shadow-sm); margin-bottom: 16px; }
.card-title { font-size: var(--fs-h3); font-weight: 600; margin-bottom: 12px; display: flex; align-items: center; justify-content: space-between; }
.sensitive-alert { margin-bottom: 12px; }
.query-bar { display: flex; align-items: flex-end; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.query-field { display: flex; flex-direction: column; gap: 4px; }
.query-label { font-size: 12px; color: var(--color-text-sub); }
.query-input { width: 220px; }
.table { border-radius: var(--radius); overflow: hidden; }
.empty-cell { text-align: center; color: var(--color-text-light); padding: 24px 0; }
</style>
