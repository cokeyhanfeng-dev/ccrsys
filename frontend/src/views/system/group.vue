<template>
  <div>
    <div class="section-head">
      <div class="section-title">集团管理</div>
      <InfoTip>数仓未统计的集团与公司在此手工补录(系统级主数据,合并查询用):集团 + 批复总额度 + 集团成员(公司)。数仓已统计集团不在此维护(录入查重拦截)。</InfoTip>
    </div>

    <div class="card">
      <div class="card__head">
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <input class="form-input" v-model="query.keyword" placeholder="集团号/集团名称" style="width:200px" @keyup.enter="load" />
          <button class="btn btn--secondary" @click="load">查询</button>
        </div>
        <button class="btn btn--primary" @click="openCreate">＋ 新增集团</button>
      </div>

      <table class="table table--full">
        <thead>
          <tr><th>集团号</th><th>集团名称</th><th>类型</th><th>批复总额度(万元)</th><th>币种</th><th>状态</th><th>成员数</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="u in groups" :key="u.groupNo">
            <td>{{ u.groupNo }}</td>
            <td>{{ u.groupName }}</td>
            <td>{{ groupTypeName(u.groupType) }}</td>
            <td>{{ fmtAmt(u.approvedTotalAmount) }}</td>
            <td>{{ u.currency }}</td>
            <td>
              <span :class="u.groupStatus === 'NORMAL' ? 'badge badge--success' : 'badge badge--neutral'">
                {{ groupStatusName(u.groupStatus) }}
              </span>
            </td>
            <td>{{ u.memberCount ?? '-' }}</td>
            <td>
              <button class="btn btn--text" @click="openEdit(u)">编辑</button>
              <button class="btn btn--text" @click="openMembers(u)">成员</button>
              <button class="btn btn--text" @click="handleDel(u)">删除</button>
            </td>
          </tr>
          <tr v-if="!groups.length"><td colspan="8" class="empty-cell">暂无数据</td></tr>
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

    <!-- 新增/编辑集团弹窗 -->
    <div class="modal" v-if="dialog.show">
      <div class="modal__card">
        <div class="modal__title">{{ dialog.isEdit ? '编辑集团' : '新增集团' }}</div>
        <div class="modal__body">
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label">集团编号 <span class="req">*</span></label>
              <input class="form-input" v-model="dialog.form.groupNo" :disabled="dialog.isEdit" placeholder="如 GROUP9001" />
            </div>
            <div class="form-field">
              <label class="form-field__label">集团名称 <span class="req">*</span></label>
              <input class="form-input" v-model="dialog.form.groupName" />
            </div>
            <div class="form-field">
              <label class="form-field__label">集团类型</label>
              <select class="form-select" v-model="dialog.form.groupType">
                <option value="INDUSTRY_GROUP">产业集团</option>
                <option value="FINANCE_GROUP">金融集团</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">批复总额度(万元) <span class="req">*</span></label>
              <input class="form-input" v-model="dialog.form.approvedTotalAmount" type="number" min="0" step="0.0001" />
            </div>
            <div class="form-field">
              <label class="form-field__label">归属机构</label>
              <select class="form-select" v-model="dialog.form.managerOrgId">
                <option value="">—</option>
                <option v-for="d in depts" :key="d.id" :value="d.id">{{ d.deptName }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">币种</label>
              <select class="form-select" v-model="dialog.form.currency">
                <option value="CNY">CNY(人民币)</option>
              </select>
            </div>
            <div class="form-field form-field--full">
              <label class="form-field__label">备注</label>
              <input class="form-input" v-model="dialog.form.remark" />
            </div>
          </div>
          <div class="section-tip">手工集团在数仓无授信快照,批复总额度(万元)用于申请提交时额度勾稽与集团授信概况展示,必填且大于 0;集团号不可与数仓已有集团重复。</div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="dialog.show = false">取消</button>
          <button class="btn btn--primary" @click="saveGroup">保存</button>
        </div>
      </div>
    </div>

    <!-- 成员管理弹窗 -->
    <div class="modal" v-if="memberDialog.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">集团成员管理 — {{ memberDialog.group?.groupName || memberDialog.groupNo }}</div>
        <div class="modal__body">
          <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
            <span class="section-tip">手工补录数仓未统计的集团名下公司;客户号+公司名称必填,成员不重复。保存为全量替换。</span>
            <button class="btn btn--secondary" @click="addMemberRow">＋ 添加成员</button>
          </div>
          <table class="table table--full">
            <thead>
              <tr><th>客户号</th><th>公司名称</th><th>角色</th><th>控制关系</th><th>关系起始</th><th>关系到期(空=在团)</th><th style="width:60px">操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="(m, i) in memberDialog.members" :key="i">
                <td><input class="form-input" v-model="m.memberCustomerNo" style="width:110px" placeholder="如 CUST901" /></td>
                <td><input class="form-input" v-model="m.memberName" style="width:150px" /></td>
                <td>
                  <select class="form-select" v-model="m.memberRole" style="width:90px">
                    <option value="GENERAL">一般</option>
                    <option value="CORE">核心</option>
                  </select>
                </td>
                <td><input class="form-input" v-model="m.controlRelation" style="width:90px" placeholder="如控股" /></td>
                <td><input class="form-input" type="date" v-model="m.relationStart" style="width:140px" /></td>
                <td><input class="form-input" type="date" v-model="m.relationEnd" style="width:140px" /></td>
                <td><button class="btn btn--text" @click="memberDialog.members.splice(i, 1)">移除</button></td>
              </tr>
              <tr v-if="!memberDialog.members.length"><td colspan="7" class="empty-cell">暂无成员,点击「添加成员」补录</td></tr>
            </tbody>
          </table>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="memberDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="saveMembers">保存成员</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listDepts, listManualGroups, getManualGroupDetail, saveManualGroup, deleteManualGroup, saveManualGroupMembers,
  type ManualGroup, type ManualGroupMember, type SysDept
} from '@/api/system'
const GROUP_TYPE: Record<string, string> = { INDUSTRY_GROUP: '产业集团', FINANCE_GROUP: '金融集团' }
const GROUP_STATUS: Record<string, string> = { NORMAL: '正常', ABNORMAL: '异常', CLOSED: '已关闭' }
function nameOf(map: Record<string, string>, code?: string) {
  return (code && map[code]) || code || '-'
}
function groupTypeName(code?: string) { return nameOf(GROUP_TYPE, code) }
function groupStatusName(code?: string) { return nameOf(GROUP_STATUS, code) }
function fmtAmt(v?: number | string | null) {
  if (v === null || v === undefined || v === '') return '-'
  return Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 4 })
}

const depts = ref<SysDept[]>([])
const query = reactive({ keyword: '' })
const groups = ref<ManualGroup[]>([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

const dialog = reactive({ show: false, isEdit: false, form: {} as any })
const memberDialog = reactive({ show: false, groupNo: '', group: null as ManualGroup | null, members: [] as ManualGroupMember[] })

async function load() {
  pageNum.value = 1
  await fetchPage()
}
async function fetchPage() {
  try {
    const data = await listManualGroups({ keyword: query.keyword || undefined, pageNum: pageNum.value, pageSize: pageSize.value })
    groups.value = data?.records || []
    total.value = Number(data?.total) || 0
  } catch {
    groups.value = []
    total.value = 0
  }
}
function onPage(p: number) {
  pageNum.value = p
  fetchPage()
}

function openCreate() {
  dialog.isEdit = false
  dialog.form = { groupNo: '', groupName: '', groupType: 'INDUSTRY_GROUP', managerOrgId: '', approvedTotalAmount: null, currency: 'CNY', remark: '' }
  dialog.show = true
}
function openEdit(u: ManualGroup) {
  dialog.isEdit = true
  dialog.form = { ...u, managerOrgId: u.managerOrgId ?? '' }
  dialog.show = true
}

async function saveGroup() {
  const f = dialog.form
  if (!f.groupNo?.trim() || !f.groupName?.trim()) {
    ElMessage.warning('集团编号与集团名称必填')
    return
  }
  if (f.approvedTotalAmount === null || f.approvedTotalAmount === '' || Number(f.approvedTotalAmount) <= 0) {
    ElMessage.warning('批复总额度必填且大于 0')
    return
  }
  const data = {
    ...f,
    groupNo: f.groupNo.trim(),
    approvedTotalAmount: Number(f.approvedTotalAmount),
    managerOrgId: f.managerOrgId === '' ? null : f.managerOrgId
  }
  await saveManualGroup(data)
  dialog.show = false
  ElMessage.success('保存成功')
  fetchPage()
}

async function handleDel(u: ManualGroup) {
  await ElMessageBox.confirm(`确认删除集团「${u.groupName}」?将同时删除其全部手工成员。`, '删除确认', { type: 'warning' })
  await deleteManualGroup(u.groupNo)
  ElMessage.success('已删除')
  if (groups.value.length === 1 && pageNum.value > 1) {
    pageNum.value -= 1
  }
  fetchPage()
}

async function openMembers(u: ManualGroup) {
  memberDialog.groupNo = u.groupNo
  memberDialog.group = u
  memberDialog.members = []
  memberDialog.show = true
  try {
    const detail = await getManualGroupDetail(u.groupNo)
    memberDialog.members = (detail?.members || []).map((m) => ({ ...m }))
  } catch {
    memberDialog.members = []
  }
}
function addMemberRow() {
  memberDialog.members.push({ memberCustomerNo: '', memberName: '', memberRole: 'GENERAL', controlRelation: '', relationStart: '', relationEnd: '' })
}
async function saveMembers() {
  const rows = memberDialog.members
  const seen = new Set<string>()
  for (const m of rows) {
    if (!m.memberCustomerNo?.trim() || !m.memberName?.trim()) {
      ElMessage.warning('成员客户号与公司名称必填')
      return
    }
    const key = m.memberCustomerNo.trim()
    if (seen.has(key)) {
      ElMessage.warning(`成员客户号重复:${key}`)
      return
    }
    seen.add(key)
  }
  await saveManualGroupMembers(memberDialog.groupNo, rows.map((m) => ({ ...m, memberCustomerNo: m.memberCustomerNo.trim() })))
  memberDialog.show = false
  ElMessage.success('成员已保存')
  fetchPage()
}

onMounted(() => {
  load()
  listDepts().then((d) => (depts.value = d)).catch(() => (depts.value = []))
})
</script>

<style scoped>
.table { border-radius: var(--radius-sm); overflow-x: auto; }
.req { color: var(--color-danger); }
.modal__card--wide { width: 880px; max-width: 94vw; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 20px; }
.form-field--full { grid-column: 1 / -1; }
</style>
