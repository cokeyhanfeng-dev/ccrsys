<template>
  <div>
    <div class="section-head">
      <div class="section-title">{{ isApprover ? '历史审批' : '历史申请' }}</div>
      <div class="section-tip">
        {{ isApprover ? '本人审批/表决/决策过的申请(审批人视角,按数据权限过滤)' : '本人历史申请(客户经理视角)' }}
      </div>
    </div>

    <div class="card">
      <table class="table">
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
            <td>{{ row.groupNo ? `集团 ${row.groupNo}` : row.customerNo || '—' }}</td>
            <td>{{ fmtTime(row.submitTime || row.createTime) }}</td>
            <td><span :class="badgeClass(row.status)">{{ statusText(row.status) }}</span></td>
            <td>{{ fmtTime(row.finalTime) }}</td>
            <td>
              <button class="btn btn--text" @click="goArchive(row)">档案</button>
              <button v-if="canReapply(row)" class="btn btn--text" @click="goReapply(row)">重新发起</button>
            </td>
          </tr>
          <tr v-if="!records.length"><td colspan="7" class="empty-cell">暂无数据</td></tr>
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
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { pageHistory } from '@/api/history'
import { appStatusText, businessTypeText } from '@/utils/dict'

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

async function load() {
  try {
    const data = await pageHistory(pageNum.value, pageSize)
    records.value = data.records || []
    total.value = data.total || 0
  } catch {
    records.value = []
    total.value = 0
  }
}
function onPage(p: number) {
  pageNum.value = p
  load()
}

function fmtTime(t: string) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '—'
}
function statusText(s: string) {
  return appStatusText(s)
}
function badgeClass(s: string) {
  const map: Record<string, string> = {
    APPROVED: 'badge badge--success',
    PARTIAL_APPROVED: 'badge badge--success',
    REJECTED: 'badge badge--danger',
    PROCESSING: 'badge badge--info',
    SUBMITTING: 'badge badge--info',
    CLOSED: 'badge badge--neutral',
    DRAFT: 'badge badge--neutral'
  }
  return map[s] || 'badge badge--neutral'
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

onMounted(load)
</script>

<style scoped>
.table { border-radius: var(--radius-sm); overflow-x: auto; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
