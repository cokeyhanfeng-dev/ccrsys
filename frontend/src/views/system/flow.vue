<template>
  <div>
    <div class="section-head">
      <div class="section-title">流程与审批人员配置</div>
      <div class="section-tip">
        流程定义发布/停用 + 各审批节点实际处理人指派(§12.17:按人/角色/部门/组,支持代理人与有效期;
        解析顺序 人员级→组级→部门+角色级→角色级;配置变更仅影响新提交流程,已流转实例不受影响)。
        LPR / 权限矩阵阈值维护见「参数管理」。
      </div>
    </div>

    <div class="tabs">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="btn"
        :class="activeTab === t.key ? 'btn--primary' : 'btn--ghost'"
        @click="activeTab = t.key"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- ========== 流程定义 ========== -->
    <div v-if="activeTab === 'flow'" class="card">
      <div class="card__head">
        <span>流程定义(Warm-Flow flow_definition)</span>
      </div>
      <table class="table">
        <thead>
          <tr><th>流程编码</th><th>流程名称</th><th>版本</th><th>发布状态</th><th>激活状态</th><th>创建时间</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="f in definitions" :key="f.id">
            <td>{{ f.flow_code }}</td>
            <td>{{ f.flow_name }}</td>
            <td>{{ f.version }}</td>
            <td>
              <span :class="f.is_publish === 1 ? 'badge badge--success' : 'badge badge--neutral'">
                {{ f.is_publish === 1 ? '已发布' : '未发布' }}
              </span>
            </td>
            <td>
              <span :class="f.activity_status === 1 ? 'badge badge--success' : 'badge badge--danger'">
                {{ f.activity_status === 1 ? '激活' : '挂起' }}
              </span>
            </td>
            <td>{{ fmtTime(f.create_time) }}</td>
            <td>
              <button v-if="f.is_publish !== 1" class="btn btn--text" @click="publish(f.id)">发布</button>
              <button v-else class="btn btn--text" @click="unpublish(f.id)">停用</button>
            </td>
          </tr>
          <tr v-if="!definitions.length"><td colspan="7" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ========== 节点审批人员指派(§12.17) ========== -->
    <div v-if="activeTab === 'assignee'" class="assignee-layout">
      <!-- 节点列表 -->
      <div class="card node-list">
        <div class="card__head"><span>审批节点</span></div>
        <table class="table">
          <thead>
            <tr><th>节点</th><th>当前指派数</th></tr>
          </thead>
          <tbody>
            <tr
              v-for="n in nodes"
              :key="n.nodeCode"
              :class="{ 'node-row--active': selectedNode?.nodeCode === n.nodeCode }"
              class="node-row"
              @click="selectNode(n)"
            >
              <td>
                <div>{{ n.nodeName || nodeText(n.nodeCode) }}</div>
                <div class="node-code">{{ n.nodeCode }}</div>
              </td>
              <td><span class="badge badge--info">{{ n.assigneeCount ?? 0 }}</span></td>
            </tr>
            <tr v-if="!nodes.length"><td colspan="2" class="empty-cell">暂无节点</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 指派明细 -->
      <div class="card assignee-detail">
        <div class="card__head">
          <span>
            指派明细{{ selectedNode ? `:${selectedNode.nodeName || nodeText(selectedNode.nodeCode)}` : '(请先选择节点)' }}
          </span>
          <div style="display:flex;gap:8px">
            <button class="btn btn--secondary" :disabled="!selectedNode" @click="openResolve">解析预览</button>
            <button class="btn btn--primary" :disabled="!selectedNode" @click="openAssigneeCreate">＋ 新增指派</button>
          </div>
        </div>
        <table class="table">
          <thead>
            <tr>
              <th>指派方式</th><th>指派对象</th><th>关系</th><th>主指派</th><th>代理人</th><th>代理有效期</th>
              <th>配置有效期</th><th>排序</th><th>状态</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in assignees" :key="a.id">
              <td><span :class="typeBadge(a.assigneeType)">{{ typeText(a.assigneeType) }}</span></td>
              <td>{{ a.assigneeCode }}</td>
              <td>{{ a.relation === 'AND' ? 'AND(全员)' : 'OR(任一)' }}</td>
              <td>{{ a.isPrimary === '1' ? '是' : '否' }}</td>
              <td>{{ a.delegateTo || '—' }}</td>
              <td>{{ rangeText(a.delegateStart, a.delegateEnd) }}</td>
              <td>{{ rangeText(a.validFrom, a.validTo) }}</td>
              <td class="num">{{ a.sort ?? '—' }}</td>
              <td><span :class="validStatus(a).cls">{{ validStatus(a).text }}</span></td>
              <td>
                <button class="btn btn--text" @click="openAssigneeEdit(a)">编辑</button>
                <button class="btn btn--text" @click="openDelegate(a)">设代理</button>
                <button class="btn btn--text" @click="removeAssignee(a)">删除</button>
              </td>
            </tr>
            <tr v-if="!assignees.length">
              <td colspan="10" class="empty-cell">{{ selectedNode ? '该节点暂无指派' : '请先选择左侧节点' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 新增/编辑指派弹窗 -->
    <div class="modal" v-if="assigneeDialog.show">
      <div class="modal__card">
        <div class="modal__title">{{ assigneeDialog.isEdit ? '编辑指派' : '新增指派' }}</div>
        <div class="modal__body">
          <div class="form-field">
            <label class="form-field__label">审批节点 <span class="req">*</span></label>
            <select class="form-select" v-model="assigneeDialog.form.nodeCode" :disabled="assigneeDialog.isEdit">
              <option v-for="n in nodes" :key="n.nodeCode" :value="n.nodeCode">
                {{ n.nodeName || nodeText(n.nodeCode) }}({{ n.nodeCode }})
              </option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">指派方式 <span class="req">*</span></label>
            <select class="form-select" v-model="assigneeDialog.form.assigneeType">
              <option value="PERSON">按人</option>
              <option value="ROLE">按角色</option>
              <option value="DEPT">按部门</option>
              <option value="GROUP">按人员组</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">指派对象 <span class="req">*</span></label>
            <select v-if="assigneeDialog.form.assigneeType === 'PERSON'" class="form-select" v-model="assigneeDialog.form.assigneeCode">
              <option value="" disabled>请选择人员</option>
              <option v-for="u in users" :key="u.username" :value="u.username">{{ u.nickName }}({{ u.username }})</option>
            </select>
            <select v-else-if="assigneeDialog.form.assigneeType === 'ROLE'" class="form-select" v-model="assigneeDialog.form.assigneeCode">
              <option value="" disabled>请选择角色</option>
              <option v-for="r in roles" :key="r.roleCode" :value="r.roleCode">{{ r.roleName }}({{ r.roleCode }})</option>
            </select>
            <select v-else-if="assigneeDialog.form.assigneeType === 'DEPT'" class="form-select" v-model="assigneeDialog.form.assigneeCode">
              <option value="" disabled>请选择部门/机构</option>
              <option v-for="d in depts" :key="d.id" :value="d.orgCode">{{ d.deptName }}({{ d.orgCode }})</option>
            </select>
            <input v-else class="form-input" v-model="assigneeDialog.form.assigneeCode" placeholder="人员组编码" />
          </div>
          <div class="form-field">
            <label class="form-field__label">同层关系</label>
            <select class="form-select" v-model="assigneeDialog.form.relation">
              <option value="OR">OR(任一处理即可)</option>
              <option value="AND">AND(需全员处理)</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">是否主指派人</label>
            <select class="form-select" v-model="assigneeDialog.form.isPrimary">
              <option value="1">是</option>
              <option value="0">否</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">配置有效期(空=长期)</label>
            <div style="display:flex;gap:4px;align-items:center">
              <input class="form-input" v-model="assigneeDialog.form.validFrom" type="date" />
              <span>至</span>
              <input class="form-input" v-model="assigneeDialog.form.validTo" type="date" />
            </div>
          </div>
          <div class="form-field">
            <label class="form-field__label">排序(同节点解析顺序)</label>
            <input class="form-input" v-model="assigneeDialog.form.sort" type="number" />
          </div>
          <div class="form-field">
            <label class="form-field__label">备注</label>
            <input class="form-input" v-model="assigneeDialog.form.remark" />
          </div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="assigneeDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="saveAssignee">保存</button>
        </div>
      </div>
    </div>

    <!-- 代理设置弹窗 -->
    <div class="modal" v-if="delegateDialog.show">
      <div class="modal__card">
        <div class="modal__title">设置代理人(暂代)</div>
        <div class="modal__body">
          <div class="form-field">
            <label class="form-field__label">主指派人</label>
            <input class="form-input" :value="delegateDialog.row?.assigneeCode" disabled />
          </div>
          <div class="form-field">
            <label class="form-field__label">代理人 <span class="req">*</span></label>
            <select class="form-select" v-model="delegateDialog.form.delegateTo">
              <option value="" disabled>请选择代理人</option>
              <option v-for="u in users" :key="u.username" :value="u.username">{{ u.nickName }}({{ u.username }})</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">代理有效期(空=长期,到期自动回退)</label>
            <div style="display:flex;gap:4px;align-items:center">
              <input class="form-input" v-model="delegateDialog.form.delegateStart" type="datetime-local" />
              <span>至</span>
              <input class="form-input" v-model="delegateDialog.form.delegateEnd" type="datetime-local" />
            </div>
          </div>
          <div class="section-tip">有效期内新生成待办投递给代理人,主指派人保留可见;不影响已生成待办。操作写审计日志。</div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="delegateDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="saveDelegate">保存</button>
        </div>
      </div>
    </div>

    <!-- 解析预览弹窗 -->
    <div class="modal" v-if="resolveDialog.show">
      <div class="modal__card">
        <div class="modal__title">解析预览(选节点+机构 → 实际处理人)</div>
        <div class="modal__body">
          <div class="form-field">
            <label class="form-field__label">审批节点 <span class="req">*</span></label>
            <select class="form-select" v-model="resolveDialog.nodeCode">
              <option v-for="n in nodes" :key="n.nodeCode" :value="n.nodeCode">
                {{ n.nodeName || nodeText(n.nodeCode) }}({{ n.nodeCode }})
              </option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">申请机构 <span class="req">*</span></label>
            <select class="form-select" v-model="resolveDialog.orgId">
              <option value="" disabled>请选择机构</option>
              <option v-for="d in depts" :key="d.id" :value="d.id">{{ d.deptName }}({{ d.orgCode }})</option>
            </select>
          </div>
          <div style="margin-bottom:12px">
            <button class="btn btn--primary" @click="runResolve">解析</button>
          </div>
          <template v-if="resolveDialog.done">
            <div v-if="resolveDialog.result.length" class="resolve-result">
              <div class="resolve-result__title">解析出的处理人({{ resolveDialog.result.length }}人):</div>
              <span v-for="(h, i) in resolveDialog.result" :key="i" class="resolve-chip">{{ h }}</span>
            </div>
            <div v-else class="resolve-empty">无处理人,将按角色兜底</div>
            <div class="section-tip" style="margin-top:8px">配置变更仅影响新提交流程,已流转实例不受影响。</div>
          </template>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="resolveDialog.show = false">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listFlowDefinitions, publishFlowDefinition, unpublishFlowDefinition,
  listFlowNodes, listAssignees, createAssignee, updateAssignee, deleteAssignee,
  delegateAssignee, resolveAssignees,
  listRoles, listUsers, listDepts,
  type FlowNode, type NodeAssignee, type SysDept, type SysRole
} from '@/api/system'

const tabs = [
  { key: 'flow', label: '流程定义' },
  { key: 'assignee', label: '节点指派' }
]
const activeTab = ref('flow')

function fmtTime(t: string) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '—'
}
function nodeText(code: string) {
  const map: Record<string, string> = {
    BRANCH_MANAGER: '支行行长',
    DEPT_GENERAL_MANAGER: '部门总经理',
    VICE_PRESIDENT: '分管行长',
    SIX_PEOPLE_GROUP: '六人小组',
    PRESIDENT: '总行行长'
  }
  return map[code] || code || '—'
}

// ---------- 流程定义 ----------
const definitions = ref<any[]>([])
async function loadDefinitions() {
  try {
    definitions.value = await listFlowDefinitions()
  } catch {
    definitions.value = []
  }
}
async function publish(id: number) {
  await publishFlowDefinition(id)
  ElMessage.success('已发布')
  loadDefinitions()
}
async function unpublish(id: number) {
  await unpublishFlowDefinition(id)
  ElMessage.success('已停用')
  loadDefinitions()
}

// ---------- 节点与指派 ----------
const nodes = ref<FlowNode[]>([])
const selectedNode = ref<FlowNode | null>(null)
const assignees = ref<NodeAssignee[]>([])
const users = ref<any[]>([])
const roles = ref<SysRole[]>([])
const depts = ref<SysDept[]>([])

async function loadNodes() {
  try {
    nodes.value = await listFlowNodes()
  } catch {
    nodes.value = []
  }
}
async function selectNode(n: FlowNode) {
  selectedNode.value = n
  await loadAssignees()
}
async function loadAssignees() {
  if (!selectedNode.value) {
    assignees.value = []
    return
  }
  try {
    assignees.value = await listAssignees({ nodeCode: selectedNode.value.nodeCode })
  } catch {
    assignees.value = []
  }
}
async function loadRefs() {
  try {
    users.value = await listUsers({ status: 'ENABLE' })
  } catch {
    users.value = []
  }
  try {
    roles.value = await listRoles()
  } catch {
    roles.value = []
  }
  try {
    depts.value = (await listDepts()).filter((d) => d.status === 'ENABLE')
  } catch {
    depts.value = []
  }
}

// ---------- 指派展示辅助 ----------
function typeText(t: string) {
  const map: Record<string, string> = { PERSON: '按人', ROLE: '按角色', DEPT: '按部门', GROUP: '按组' }
  return map[t] || t || '—'
}
function typeBadge(t: string) {
  const map: Record<string, string> = {
    PERSON: 'badge badge--success',
    ROLE: 'badge badge--info',
    DEPT: 'badge badge--warning',
    GROUP: 'badge badge--neutral'
  }
  return map[t] || 'badge badge--neutral'
}
function rangeText(from?: string, to?: string) {
  if (!from && !to) return '长期'
  return `${from ? fmtTime(from).slice(0, 10) : '…'} ~ ${to ? fmtTime(to).slice(0, 10) : '…'}`
}
function validStatus(a: NodeAssignee) {
  const today = new Date().toISOString().slice(0, 10)
  if (a.validFrom && String(a.validFrom).slice(0, 10) > today) return { text: '未生效', cls: 'badge badge--neutral' }
  if (a.validTo && String(a.validTo).slice(0, 10) < today) return { text: '已过期', cls: 'badge badge--danger' }
  return { text: '生效中', cls: 'badge badge--success' }
}

// ---------- 新增/编辑指派 ----------
const assigneeDialog = reactive({ show: false, isEdit: false, form: {} as any })
function blankAssigneeForm() {
  return {
    nodeCode: selectedNode.value?.nodeCode || '',
    assigneeType: 'PERSON',
    assigneeCode: '',
    relation: 'OR',
    isPrimary: '0',
    validFrom: '',
    validTo: '',
    sort: 1,
    remark: ''
  }
}
function openAssigneeCreate() {
  assigneeDialog.isEdit = false
  assigneeDialog.form = blankAssigneeForm()
  assigneeDialog.show = true
}
function openAssigneeEdit(a: NodeAssignee) {
  assigneeDialog.isEdit = true
  assigneeDialog.form = {
    id: a.id,
    nodeCode: a.nodeCode,
    assigneeType: a.assigneeType,
    assigneeCode: a.assigneeCode,
    relation: a.relation || 'OR',
    isPrimary: a.isPrimary || '0',
    validFrom: a.validFrom ? String(a.validFrom).slice(0, 10) : '',
    validTo: a.validTo ? String(a.validTo).slice(0, 10) : '',
    sort: a.sort ?? 1,
    remark: a.remark || ''
  }
  assigneeDialog.show = true
}
async function saveAssignee() {
  const f = assigneeDialog.form
  if (!f.nodeCode || !f.assigneeType || !f.assigneeCode) {
    ElMessage.warning('节点/指派方式/指派对象必填')
    return
  }
  const payload: any = {
    nodeCode: f.nodeCode,
    assigneeType: f.assigneeType,
    assigneeCode: f.assigneeCode,
    relation: f.relation,
    isPrimary: f.isPrimary,
    validFrom: f.validFrom || null,
    validTo: f.validTo || null,
    sort: f.sort,
    remark: f.remark
  }
  if (assigneeDialog.isEdit) {
    await updateAssignee(f.id, payload)
  } else {
    await createAssignee(payload)
  }
  assigneeDialog.show = false
  ElMessage.success('已保存(仅影响新提交流程)')
  loadAssignees()
  loadNodes()
}
async function removeAssignee(a: NodeAssignee) {
  await ElMessageBox.confirm(
    `确认删除该指派(${typeText(a.assigneeType)}:${a.assigneeCode})?仅影响新提交流程,已流转实例不受影响。`,
    '删除确认',
    { type: 'warning' }
  )
  await deleteAssignee(a.id!)
  ElMessage.success('已删除')
  loadAssignees()
  loadNodes()
}

// ---------- 代理设置 ----------
const delegateDialog = reactive({ show: false, row: null as NodeAssignee | null, form: {} as any })
function openDelegate(a: NodeAssignee) {
  delegateDialog.row = a
  delegateDialog.form = {
    delegateTo: a.delegateTo || '',
    delegateStart: a.delegateStart ? String(a.delegateStart).slice(0, 16) : '',
    delegateEnd: a.delegateEnd ? String(a.delegateEnd).slice(0, 16) : ''
  }
  delegateDialog.show = true
}
async function saveDelegate() {
  const f = delegateDialog.form
  if (!f.delegateTo) {
    ElMessage.warning('代理人必填')
    return
  }
  await delegateAssignee(delegateDialog.row!.id!, {
    delegateTo: f.delegateTo,
    delegateStart: f.delegateStart || undefined,
    delegateEnd: f.delegateEnd || undefined
  })
  delegateDialog.show = false
  ElMessage.success('代理已设置')
  loadAssignees()
}

// ---------- 解析预览 ----------
const resolveDialog = reactive({
  show: false,
  nodeCode: '',
  orgId: '' as number | '',
  done: false,
  result: [] as string[]
})
function openResolve() {
  resolveDialog.nodeCode = selectedNode.value?.nodeCode || ''
  resolveDialog.orgId = ''
  resolveDialog.done = false
  resolveDialog.result = []
  resolveDialog.show = true
}
async function runResolve() {
  if (!resolveDialog.nodeCode || resolveDialog.orgId === '') {
    ElMessage.warning('请选择节点与机构')
    return
  }
  const list = await resolveAssignees({ nodeCode: resolveDialog.nodeCode, orgId: resolveDialog.orgId })
  resolveDialog.result = (list || []).map((h: any) =>
    typeof h === 'string' ? h : h.userName || h.nickName || h.username || h.assigneeName || h.assigneeCode || JSON.stringify(h)
  )
  resolveDialog.done = true
}

onMounted(() => {
  loadDefinitions()
  loadNodes()
  loadRefs()
})
</script>

<style scoped>
.section-head { margin-bottom: 16px; }
.section-title { font-size: var(--fs-h2); font-weight: 600; margin-bottom: 6px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
.tabs { display: flex; gap: 8px; margin-bottom: 16px; }
.card { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow-sm); }
.card__head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; gap: 8px; flex-wrap: wrap; }
.table { border-radius: var(--radius); overflow: hidden; }
.req { color: var(--color-danger); }
.empty-cell { text-align: center; color: var(--color-text-light); padding: 24px 0; }
.assignee-layout { display: flex; gap: 16px; align-items: flex-start; }
.node-list { flex: 0 0 300px; }
.assignee-detail { flex: 1; overflow-x: auto; }
.node-row { cursor: pointer; }
.node-row--active { background: var(--color-primary-light, #eff6ff); }
.node-code { font-size: 12px; color: var(--color-text-light); }
.resolve-result__title { margin-bottom: 8px; font-weight: 600; }
.resolve-chip { display: inline-block; background: var(--color-primary-light, #eff6ff); color: var(--color-primary); border-radius: 4px; padding: 2px 10px; margin: 0 6px 6px 0; font-size: 13px; }
.resolve-empty { color: var(--color-danger); font-weight: 600; }
</style>
