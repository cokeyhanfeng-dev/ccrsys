<template>
  <div>
    <div class="section-head">
      <div class="section-title">{{ isApprover ? '历史审批' : '历史申请' }}</div>
      <InfoTip :content="isApprover ? '本人审批/表决/决策过的申请(审批人视角,按数据权限过滤)' : '本人历史申请(客户经理视角)'" />
    </div>

    <div class="card">
      <!-- 筛选(§13.2 历史申请查询:申请号/状态/客户名称;工作台统计卡可带 query 跳转) -->
      <div class="filter-bar">
        <input class="form-input" v-model="filters.applicationNo" placeholder="申请号" aria-label="申请号" />
        <el-select v-model="filters.status" placeholder="状态" aria-label="状态">
          <el-option v-for="s in statusOptions" :key="s.value || '_all'" :label="s.label" :value="s.value" />
        </el-select>
        <input class="form-input" v-model="filters.keyword" placeholder="客户名称" aria-label="客户名称" />
        <button class="btn btn--primary" @click="onSearch">查询</button>
        <button class="btn btn--secondary" @click="onReset">重置</button>
      </div>
      <table class="table table--full" v-loading="listLoading">
        <thead>
          <tr>
            <th>申请号</th><th>业务类型</th><th>客户/集团</th><th>提交时间</th>
            <th>状态</th><th>终态时间</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in records" :key="row.id">
            <td>{{ row.applicationNo }}</td>
            <td>{{ businessTypeText(row.businessType) }}</td>
            <td>{{ row.customerName || (row.groupNo ? `集团 ${row.groupNo}` : row.customerNo || '—') }}</td>
            <td>{{ fmtTime(row.submitTime || row.createTime) }}</td>
            <td><span :class="appStatusBadge(row.status)">{{ statusText(row.status) }}</span></td>
            <td>{{ fmtTime(row.finalTime) }}</td>
            <td>
              <button class="btn btn--text" @click="goArchive(row)">档案</button>
              <button class="btn btn--text" @click="openProgress(row)">进度</button>
              <button v-if="row.status === 'DRAFT'" class="btn btn--text" @click="goEdit(row)">继续编辑</button>
              <button v-if="row.status === 'DRAFT'" class="btn btn--danger-text" @click="onDelete(row)">删除</button>
              <button v-if="canReapply(row)" class="btn btn--text" @click="goReapply(row)">重新发起</button>
              <button v-if="row.hasResolution" class="btn btn--text" @click="downloadResolution(row)">决议书</button>
            </td>
          </tr>
          <tr v-if="!records.length"><td colspan="7" class="empty-cell">{{ listError ? '加载失败，请刷新' : '暂无数据' }}</td></tr>
        </tbody>
      </table>

      <div class="pager" v-if="total > 0">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="pageNum"
          @current-change="onPage"
        />
      </div>
    </div>

    <!-- 审批进度(§链路可视化):各节点流转状态 + 表决 n/6 -->
    <el-dialog v-model="progressVisible" title="审批进度" width="min(560px, 92vw)" append-to-body>
      <div v-loading="progressLoading">
        <template v-if="progress">
          <div v-if="progressError" class="empty-cell">加载失败，请刷新</div>
          <template v-else>
          <div class="progress-head">
            <span class="progress-no">{{ progress.applicationNo }}</span>
            <span :class="appStatusBadge(progress.currentStatus)">{{ statusText(progress.currentStatus) }}</span>
          </div>
          <div v-if="progress.nodes && progress.nodes.length" class="progress-nodes">
            <div v-for="(n, i) in progress.nodes" :key="n.nodeCode" class="progress-node" :class="'node--' + n.status">
              <div class="node-rail">
                <span class="node-dot"></span>
                <span v-if="i < progress.nodes.length - 1" class="node-line"></span>
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
                  <el-progress :percentage="votePct(n)" :stroke-width="8" :show-text="false" :stroke-color="'var(--color-primary)'" />
                  <span class="vote-text">已投 {{ n.submittedCount }}/{{ n.voterCount }} · 同意 {{ n.approveCount ?? '—' }} 票(通过线 ≥{{ n.requiredCount }})</span>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="empty-cell">暂无进度数据</div>
          </template>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import { pageHistory, getApprovalProgress, downloadResolutionDoc } from '@/api/history'
import { del } from '@/api/request'
import { appStatusText, businessTypeText, appStatusBadge } from '@/utils/dict'
import { fmtDateTime } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()
const role = computed(() => userStore.userInfo?.roles?.[0] || 'customer_manager')

// 审批人角色:支行行长/部门总经理/分管行长/小组成员/行长
const isApprover = computed(() =>
  ['branch_manager', 'committee_member', 'president', 'dept_gm', 'vice_president'].includes(role.value)
)

const records = ref<any[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 10
const listLoading = ref(false)
const listError = ref(false)

// ---------- 筛选(§13.2:申请号/状态/客户名称;工作台统计卡可带 query 跳转) ----------
const route = useRoute()
const filters = reactive({ applicationNo: '', status: '', keyword: '' })
// 审批中=复合多状态(与工作台「审批中/在途」统计卡跳转的 query 值一致,便于回显)
const IN_PROGRESS_STATUS = 'ROUTING,SUBMITTED,SUBMITTING,APPROVED_LEVEL,PROCESSING,VOTING,COMMITTEE_PASS,PRESIDENT_DECISION'
const statusOptions = [
  { label: '全部', value: '' },
  { label: '审批中', value: IN_PROGRESS_STATUS },
  { label: '草稿', value: 'DRAFT' },
  { label: '已通过', value: 'APPROVED' },
  { label: '终态', value: 'FINAL' },
  { label: '已否决', value: 'REJECTED' },
  { label: '已关闭', value: 'CLOSED' }
]

// 支持工作台统计卡跳转:读 route.query 初始化筛选并查询
function initFromQuery() {
  filters.applicationNo = String(route.query.applicationNo || '')
  filters.status = String(route.query.status || '')
  filters.keyword = String(route.query.keyword || '')
}

async function load() {
  listLoading.value = true
  listError.value = false
  try {
    const data = await pageHistory({
      pageNum: pageNum.value,
      pageSize,
      applicationNo: filters.applicationNo || undefined,
      status: filters.status || undefined,
      keyword: filters.keyword || undefined
    })
    records.value = data.records || []
    total.value = Number(data.total) || 0
  } catch {
    records.value = []
    total.value = 0
    listError.value = true
  } finally {
    listLoading.value = false
  }
}
function onPage(p: number) {
  pageNum.value = p
  load()
}
function onSearch() {
  pageNum.value = 1
  load()
}
function onReset() {
  filters.applicationNo = ''
  filters.status = ''
  filters.keyword = ''
  pageNum.value = 1
  load()
}
// 删除未提交草稿(后端 DELETE /ccr/applications/{id} 物理删除;§2026-08-26)
async function onDelete(row: any) {
  try {
    await ElMessageBox.confirm(`确认删除申请「${row.applicationNo}」吗?该申请为未提交草稿,删除后不可恢复。`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await del(`/ccr/applications/${row.id}`)
    ElMessage.success('已删除')
    load()
  } catch {
    ElMessage.error('删除失败')
  }
}

function fmtTime(t: string) {
  return fmtDateTime(t, false)
}
function statusText(s: string) {
  return appStatusText(s)
}

// 审批进度(§链路可视化):弹窗展示链路各节点流转状态 + 表决 n/6
const progressVisible = ref(false)
const progressLoading = ref(false)
const progressError = ref(false)
const progress = ref<any>(null)
async function openProgress(row: any) {
  progress.value = null
  progressError.value = false
  progressVisible.value = true
  progressLoading.value = true
  try {
    progress.value = await getApprovalProgress(row.id)
  } catch {
    progressError.value = true
    progress.value = { applicationNo: row.applicationNo, nodes: [] }
  } finally {
    progressLoading.value = false
  }
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

// 档案:进入单笔申请档案(§14.4)
function goArchive(row: any) {
  router.push(`/history/archive/${row.id}`)
}
// 重新发起(§12.10):仅"被否决/表决未通过"且客户经理视角显示;审批人视角(§12.9)仅档案查看
function canReapply(row: any) {
  return !isApprover.value && ['REJECTED', 'PARTIAL_APPROVED'].includes(row.status)
}
// 关联重提:跳转申请页并携带原申请 id(与申请页约定 reapply 参数)
function goReapply(row: any) {
  const path = row.businessType === 'DEPOSIT' ? '/application/deposit' : '/application/loan'
  router.push({ path, query: { reapply: row.id } })
}
// 继续编辑(草稿重新发起):跳转申请页并携带 edit 参数,加载草稿继续调整后提交
function goEdit(row: any) {
  const path = row.businessType === 'DEPOSIT' ? '/application/deposit' : '/application/loan'
  router.push({ path, query: { edit: row.id } })
}
// 决议书(仅已签发决议的申请显示):下载 Word 决议书
function downloadResolution(row: any) {
  downloadResolutionDoc(row.id)
}

onMounted(() => {
  initFromQuery()
  load()
})
</script>

<style scoped>
.filter-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.filter-bar .form-input { width: 180px; }
.filter-bar .el-select { width: 160px; }
.table { border-radius: var(--radius-sm); overflow-x: auto; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.progress-head { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.progress-no { font-weight: 600; }
.progress-nodes { padding-left: 4px; }
.progress-node { display: flex; }
.node-rail { display: flex; flex-direction: column; align-items: center; width: 20px; margin-right: 12px; }
.node-dot { width: 12px; height: 12px; border-radius: 50%; background: #d9d9d9; flex-shrink: 0; margin-top: 2px; }
.node-line { width: 2px; flex: 1; min-height: 28px; background: #e8e8e8; }
.node--DONE .node-dot { background: var(--color-success); }
.node--CURRENT .node-dot { background: var(--color-primary); box-shadow: 0 0 0 3px rgba(24, 144, 255, 0.2); }
.node-body { flex: 1; padding-bottom: 22px; }
.node-title { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 500; }
.node-state { font-size: 12px; font-weight: 400; padding: 1px 8px; border-radius: 10px; }
.state--DONE { color: var(--color-success); background: var(--color-success-light); }
.state--CURRENT { color: var(--color-primary); background: var(--color-primary-light); }
.state--PENDING, .state--SKIPPED { color: var(--color-text-sub); background: rgba(144, 147, 153, 0.12); }
.node-meta { margin-top: 4px; font-size: 12px; color: var(--color-text-sub); display: flex; gap: 12px; }
.node-meta.vote { display: block; margin-top: 8px; }
.vote-text { font-size: 12px; color: #606266; margin-top: 4px; display: inline-block; }
</style>
