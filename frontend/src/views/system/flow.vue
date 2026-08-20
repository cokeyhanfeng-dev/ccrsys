<template>
  <div>
    <div class="section-head">
      <div class="section-title">流程与审批人员配置</div>
      <InfoTip>
        流程定义发布/停用 + 各审批节点实际处理人指派(§12.17:按人/角色/部门/组,支持代理人与有效期;
        解析顺序 人员级→组级→部门+角色级→角色级;配置变更仅影响新提交流程,已流转实例不受影响)。
        LPR / 权限矩阵阈值维护见「参数管理」。
      </InfoTip>
    </div>

    <div class="segmented">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="segmented__item"
        :class="{ 'segmented__item--active': activeTab === t.key }"
        @click="activeTab = t.key"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- ========== 流程定义 ========== -->
    <div v-if="activeTab === 'flow'" class="card">
      <div class="notice-bar">
        <span>流程定义为 Warm-Flow 引擎的流程载体,系统启动时自动初始化“利率审批标准流程”,用于记录审批轨迹;
        实际审批流转(哪些节点、谁能终审)由「参数管理 → 权限矩阵」决定,不由本页签控制。
        需要调整审批人时,请使用「节点指派」页签。</span>
      </div>
      <div class="card__head">
        <span>流程定义(Warm-Flow flow_definition)</span>
      </div>
      <table class="table table--full">
        <thead>
          <tr><th>流程编码</th><th>流程名称</th><th>版本</th><th>发布状态</th><th>激活状态</th><th>创建时间</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="f in definitions" :key="f.id">
            <td>{{ f.flow_code }}</td>
            <td>{{ f.flow_name }}</td>
            <td>{{ f.version }}</td>
            <td>
              <span :class="f.is_publish == 1 ? 'badge badge--success' : 'badge badge--neutral'">
                {{ f.is_publish == 1 ? '已发布' : '未发布' }}
              </span>
            </td>
            <td>
              <span :class="f.activity_status == 1 ? 'badge badge--success' : 'badge badge--danger'">
                {{ f.activity_status == 1 ? '激活' : '挂起' }}
              </span>
            </td>
            <td>{{ fmtTime(f.create_time) }}</td>
            <td>
              <button class="btn btn--text" @click="viewFlow(f)">查看</button>
              <button v-if="f.is_publish != 1" class="btn btn--text" @click="publish(f.id)">发布</button>
              <button v-else class="btn btn--text" @click="unpublish(f.id)">停用</button>
            </td>
          </tr>
          <tr v-if="!definitions.length"><td colspan="7" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ========== 部门-分管行领导映射(§D16a,2026-08-14 配置界面化) ========== -->
    <div v-if="activeTab === 'deptVp'" class="card">
      <div class="notice-bar">
        <span>部门-分管行领导映射(§D16a):按分项部门归属编码(如 3202233912 公司金融部/3202233943 授信评审部/3202233991 零售金融部)
        解析对应分管行领导;一人可分管多部门,未配置时走角色兜底。变更仅影响新提交流程。</span>
      </div>
      <div class="card__head">
        <span>部门-分管行领导映射</span>
        <button class="btn btn--primary" @click="openDeptVpCreate">＋ 新增映射</button>
      </div>
      <table class="table table--full">
        <thead>
          <tr><th>部门编码</th><th>部门名称</th><th>分管行领导</th><th>状态</th><th>生效期</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="v in deptVps" :key="v.id">
            <td>{{ v.deptCode }}</td>
            <td>{{ v.deptName || '—' }}</td>
            <td>{{ v.vpNickName || v.vpUsername || v.vpUserId }}</td>
            <td>
              <span :class="v.status === 'ACTIVE' ? 'badge badge--success' : 'badge badge--neutral'">
                {{ v.status === 'ACTIVE' ? '启用' : '停用' }}
              </span>
            </td>
            <td>{{ v.validFrom || '长期' }}{{ v.validTo ? ' ~ ' + v.validTo : '' }}</td>
            <td>
              <button class="btn btn--text" @click="openDeptVpEdit(v)">编辑</button>
              <button class="btn btn--text" @click="removeDeptVp(v)">删除</button>
            </td>
          </tr>
          <tr v-if="!deptVps.length"><td colspan="6" class="empty-cell">暂无映射</td></tr>
        </tbody>
      </table>

      <!-- 新增/编辑分管行长映射弹窗 -->
      <div class="modal" v-if="deptVpDialog.show">
        <div class="modal__card">
          <div class="modal__title">{{ deptVpDialog.isEdit ? '编辑分管行长映射' : '新增分管行长映射' }}</div>
          <div class="modal__body">
            <div class="form-field">
              <label class="form-field__label">部门 <span class="req">*</span></label>
              <select class="form-select" v-model="deptVpDialog.form.deptCode">
                <option value="" disabled>请选择部门</option>
                <option v-for="d in deptOptions" :key="d.orgCode" :value="d.orgCode">{{ d.deptName }}({{ d.orgCode }})</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">分管行领导 <span class="req">*</span></label>
              <select class="form-select" v-model="deptVpDialog.form.vpUserId">
                <option value="" disabled>请选择分管行领导</option>
                <option v-for="u in vpUsers" :key="u.id" :value="u.id">{{ u.nickName }}({{ u.username }})</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">状态</label>
              <select class="form-select" v-model="deptVpDialog.form.status">
                <option value="ACTIVE">启用</option>
                <option value="INACTIVE">停用</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">生效起</label>
              <input class="form-input" v-model="deptVpDialog.form.validFrom" type="date" />
            </div>
            <div class="form-field">
              <label class="form-field__label">生效止</label>
              <input class="form-input" v-model="deptVpDialog.form.validTo" type="date" />
            </div>
          </div>
          <div class="modal__actions">
            <button class="btn btn--secondary" @click="deptVpDialog.show = false">取消</button>
            <button class="btn btn--primary" @click="saveDeptVp">保存</button>
          </div>
        </div>
      </div>
    </div>

    <!-- ========== 节点审批人员指派(§12.17) ========== -->
    <!-- 流程图查看弹窗(只读) -->
    <div class="modal" v-if="flowView.show">
      <div class="modal__card flow-viewer__card">
        <div class="modal__title">流程预览 · {{ flowView.name }}</div>
        <div class="modal__body">
          <div class="flow-diagram" v-if="flowView.nodes.length">
            <template v-for="(n, i) in flowView.nodes" :key="n.nodeCode">
              <div class="flow-node" :class="nodeClassOf(n)">
                <div class="flow-node__name">{{ nodeTextOf(n) }}</div>
                <div class="flow-node__code">{{ n.nodeCode }}</div>
              </div>
              <div v-if="i < flowView.nodes.length - 1" class="flow-edge">
                <div class="flow-edge__line"></div>
                <div class="flow-edge__label">{{ edgeLabelOf(n.nodeCode) }}</div>
              </div>
            </template>
          </div>
          <div v-else class="empty">该流程暂无节点定义</div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--primary" @click="flowView.show = false">关闭</button>
        </div>
      </div>
    </div>

    <div v-if="activeTab === 'assignee'" class="assignee-layout">
      <!-- 节点列表 -->
      <div class="card node-list">
        <div class="card__head"><span>审批节点</span></div>
        <table class="table table--full">
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
        <table class="table table--full">
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
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listFlowDefinitions, publishFlowDefinition, unpublishFlowDefinition, getFlowDefinitionDetail,
  listFlowNodes, listAssignees, createAssignee, updateAssignee, deleteAssignee,
  delegateAssignee, resolveAssignees,
  listDeptVp, createDeptVp, updateDeptVp, deleteDeptVp,
  listRoles, listUsers, listDepts,
  type FlowNode, type NodeAssignee, type DeptVp, type SysDept, type SysRole
} from '@/api/system'
import { nodeLabel, assigneeTypeText } from '@/utils/dict'

const tabs = [
  { key: 'assignee', label: '节点指派' },
  { key: 'deptVp', label: '分管行长映射' },
  { key: 'flow', label: '流程定义' }
]
const activeTab = ref('assignee')

// ---------- 流程图查看 ----------
const flowView = ref<any>({ show: false, name: '', nodes: [], skips: [] })
async function viewFlow(f: any) {
  try {
    const d = await getFlowDefinitionDetail(f.id)
    flowView.value = { show: true, name: d.flow_name || f.flow_name, nodes: d.nodes || [], skips: d.skips || [] }
  } catch {
    flowView.value = { show: true, name: f.flow_name, nodes: [], skips: [] }
  }
}
function nodeClassOf(n: any): string {
  if (n.nodeType === 0 || n.nodeCode === 'start') return 'flow-node--start'
  if (n.nodeType === 2 || n.nodeCode === 'end') return 'flow-node--end'
  return 'flow-node--mid'
}
const FLOW_NODE_NAMES: Record<string, string> = {
  start: '开始', end: '结束',
  BRANCH_MANAGER: '支行行长', DEPT_GENERAL_MANAGER: '部门总经理',
  VICE_PRESIDENT: '分管行长', SIX_PEOPLE_GROUP: '六人小组', PRESIDENT: '总行行长'
}
function nodeTextOf(n: any): string {
  return FLOW_NODE_NAMES[n.nodeCode] || n.nodeName || n.nodeCode
}
function edgeLabelOf(nowNodeCode: string): string {
  const skip = flowView.value.skips.find((k: any) => k.nowNodeCode === nowNodeCode)
  return skip?.skipName || '通过'
}

function fmtTime(t: string) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '—'
}
function nodeText(code: string) {
  return nodeLabel(code)
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
const deptVps = ref<DeptVp[]>([])
const deptVpDialog = reactive({ show: false, isEdit: false, form: {} as any })
// 分管行长下拉=启用用户中 vice_president 角色;部门下拉=机构表中部门(DEPT)机构(映射按部门归属码)
const vpUsers = computed(() => users.value.filter((u) => u.roleCode === 'vice_president'))
const deptOptions = computed(() => depts.value.filter((d) => d.orgType === 'DEPT'))

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
    // 用户管理分页化后 listUsers 返回 {total, records},指派下拉取 records(启用用户约 77,一次拉全)
    const page = await listUsers({ status: 'ENABLE', pageNum: 1, pageSize: 200 })
    users.value = (page as any)?.records || []
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

// ---------- 部门-分管行领导映射(§D16a) ----------
async function loadDeptVps() {
  try {
    deptVps.value = await listDeptVp()
  } catch {
    deptVps.value = []
  }
}
function openDeptVpCreate() {
  deptVpDialog.isEdit = false
  deptVpDialog.form = { deptCode: '', vpUserId: '', status: 'ACTIVE', validFrom: '', validTo: '' }
  deptVpDialog.show = true
}
function openDeptVpEdit(v: DeptVp) {
  deptVpDialog.isEdit = true
  deptVpDialog.form = {
    id: v.id,
    deptCode: v.deptCode,
    vpUserId: v.vpUserId,
    status: v.status,
    validFrom: v.validFrom || '',
    validTo: v.validTo || '',
    versionNo: v.versionNo
  }
  deptVpDialog.show = true
}
async function saveDeptVp() {
  const f = deptVpDialog.form
  if (!f.deptCode || !f.vpUserId) {
    ElMessage.warning('部门与分管行领导必选')
    return
  }
  const payload = {
    deptCode: f.deptCode,
    vpUserId: Number(f.vpUserId),
    status: f.status,
    validFrom: f.validFrom || undefined,
    validTo: f.validTo || undefined
  }
  try {
    if (deptVpDialog.isEdit) {
      await updateDeptVp(f.id, { ...payload, versionNo: f.versionNo })
    } else {
      await createDeptVp(payload)
    }
    deptVpDialog.show = false
    ElMessage.success('保存成功')
    await loadDeptVps()
  } catch {
    // 错误已由请求拦截器统一提示
  }
}
async function removeDeptVp(v: DeptVp) {
  try {
    await ElMessageBox.confirm(
      `确认删除该映射(部门 ${v.deptCode} → ${v.vpNickName || v.vpUsername})?仅影响新提交流程。`,
      '删除确认',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteDeptVp(v.id)
    ElMessage.success('删除成功')
    await loadDeptVps()
  } catch {
    // 错误已由请求拦截器统一提示
  }
}

// ---------- 指派展示辅助 ----------
function typeText(t: string) {
  return assigneeTypeText(t)
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
    remark: a.remark || '',
    versionNo: a.versionNo ?? 1 // 乐观锁版本,后端 update 必传
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
    await updateAssignee(f.id, { ...payload, versionNo: f.versionNo ?? 1 })
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
  loadDeptVps()
})
</script>

<style scoped>
.tabs { display: flex; gap: 8px; margin-bottom: 16px; }
.card__head { gap: 8px; flex-wrap: wrap; }
.table { border-radius: var(--radius-sm); overflow-x: auto; }
.req { color: var(--color-danger); }
.assignee-layout { display: flex; gap: 16px; align-items: flex-start; }
.node-list { flex: 0 0 300px; }
.assignee-detail { flex: 1; overflow-x: auto; }
.node-row { cursor: pointer; }
.node-row--active { background: var(--color-primary-light, #eff6ff); }
.node-code { font-size: 12px; color: var(--color-text-light); }
.resolve-result__title { margin-bottom: 8px; font-weight: 600; }
.resolve-chip { display: inline-block; background: var(--color-primary-light, #eff6ff); color: var(--color-primary); border-radius: 4px; padding: 2px 10px; margin: 0 6px 6px 0; font-size: 13px; }
.resolve-empty { color: var(--color-danger); font-weight: 600; }

/* 流程图查看器 */
.flow-viewer__card { max-width: 520px; }
.flow-diagram { display: flex; flex-direction: column; align-items: center; padding: 8px 0; }
.flow-node {
  min-width: 200px; text-align: center; padding: 10px 20px; border-radius: 10px;
  border: 1.5px solid var(--color-primary); background: var(--color-primary-light);
}
.flow-node--start, .flow-node--end { border-color: var(--color-success); background: #ecfdf5; }
.flow-node--mid { box-shadow: var(--shadow-sm); }
.flow-node__name { font-weight: 600; }
.flow-node__code { font-size: 12px; color: var(--color-text-light); margin-top: 2px; }
.flow-edge { display: flex; flex-direction: column; align-items: center; }
.flow-edge__line { width: 2px; height: 22px; background: var(--color-primary); position: relative; }
.flow-edge__line::after {
  content: ''; position: absolute; bottom: -5px; left: -4px;
  border: 5px solid transparent; border-top-color: var(--color-primary);
}
.flow-edge__label { font-size: 12px; color: var(--color-text-sub); background: #fff; padding: 0 6px; margin-top: -14px; position: relative; z-index: 1; }
</style>
