<template>
  <div>
    <div class="section-head">
      <div class="section-title">机构管理</div>
      <InfoTip>机构树维护(总行 → 部门/支行 → 网点,§12.18)。机构编码为层级前缀数字码(唯一、禁改);新增机构默认停用,需手动启用后方可被绑定与业务选择;停用/删除前置校验存量业务(未完结申请/在途审批任务/未关闭承诺)。</InfoTip>
    </div>

    <div class="dept-layout">
      <!-- 左侧机构树 -->
      <div class="card dept-tree">
        <div class="card__head">
          <span>机构树</span>
          <button class="btn btn--primary" @click="openCreate(null)">＋ 新增根机构</button>
        </div>
        <el-tree
          :data="tree"
          node-key="id"
          :props="{ label: 'deptName', children: 'children' }"
          :expand-on-click-node="false"
          default-expand-all
          highlight-current
          @node-click="onSelect"
        >
          <template #default="{ data }">
            <span class="tree-node">
              <span class="tree-node__name">{{ data.deptName }}</span>
              <span class="tree-node__code">{{ data.orgCode }}</span>
              <span :class="data.status === 'ENABLE' ? 'badge badge--success' : 'badge badge--neutral'">
                {{ data.status === 'ENABLE' ? '启用' : '停用' }}
              </span>
            </span>
          </template>
        </el-tree>
        <div v-if="!tree.length" class="empty-cell">暂无机构</div>
      </div>

      <!-- 右侧机构详情/编辑 -->
      <div class="card dept-detail">
        <div class="card__head">
          <span>机构详情</span>
          <div v-if="current" style="display:flex;gap:8px">
            <button class="btn btn--primary" @click="openCreate(current)">＋ 新增子机构</button>
            <button
              class="btn"
              :class="current.status === 'ENABLE' ? 'btn--secondary' : 'btn--primary'"
              @click="toggleStatus"
            >
              {{ current.status === 'ENABLE' ? '停用' : '启用' }}
            </button>
            <button class="btn btn--secondary" @click="handleDelete">删除</button>
          </div>
        </div>

        <template v-if="current">
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label">机构名称 <span class="req">*</span></label>
              <input class="form-input" v-model="editForm.deptName" />
            </div>
            <div class="form-field">
              <label class="form-field__label">机构类型</label>
              <select class="form-select" v-model="editForm.orgType">
                <option v-for="t in orgTypeOptions" :key="t.value" :value="t.value">{{ t.label }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">上级机构</label>
              <select class="form-select" v-model="editForm.parentId">
                <option :value="0">总行(根)</option>
                <option
                  v-for="d in flatDepts.filter((x) => x.id !== current?.id)"
                  :key="d.id"
                  :value="d.id"
                >
                  {{ d.deptName }}({{ d.orgCode }})
                </option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">负责人</label>
              <input class="form-input" v-model="editForm.manager" />
            </div>
            <div class="form-field">
              <label class="form-field__label">排序号</label>
              <input class="form-input" v-model="editForm.sortNo" type="number" />
            </div>
            <div class="form-field">
              <label class="form-field__label">状态</label>
              <div>
                <span :class="current.status === 'ENABLE' ? 'badge badge--success' : 'badge badge--neutral'">
                  {{ current.status === 'ENABLE' ? '启用' : '停用' }}
                </span>
              </div>
            </div>
          </div>
          <div style="margin-top:12px">
            <button class="btn btn--primary" @click="saveEdit">保存修改</button>
            <span class="section-tip" style="margin-left:8px">机构编码唯一、禁改;改上级将校验成环与编码前缀一致性。</span>
          </div>
        </template>
        <div v-else class="empty-cell">请在左侧选择机构</div>
      </div>
    </div>

    <!-- 新增机构弹窗 -->
    <div class="modal" v-if="createDialog.show">
      <div class="modal__card">
        <div class="modal__title">新增{{ createDialog.parent ? '子机构' : '根机构' }}</div>
        <div class="modal__body">
          <div class="form-field" v-if="createDialog.parent">
            <label class="form-field__label">上级机构</label>
            <input class="form-input" :value="`${createDialog.parent.deptName}(${createDialog.parent.orgCode})`" disabled />
          </div>
          <div class="form-field">
            <label class="form-field__label">机构名称 <span class="req">*</span></label>
            <input class="form-input" v-model="createDialog.form.deptName" placeholder="如 XX支行" />
          </div>
          <div class="form-field">
            <label class="form-field__label">机构类型 <span class="req">*</span></label>
            <select class="form-select" v-model="createDialog.form.orgType">
              <option v-for="t in orgTypeOptions" :key="t.value" :value="t.value">{{ t.label }}</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">机构编码(org_code)</label>
            <input class="form-input" v-model="createDialog.form.orgCode" placeholder="留空按上级编码前缀自动生成" />
          </div>
          <div class="form-field">
            <label class="form-field__label">负责人</label>
            <input class="form-input" v-model="createDialog.form.manager" />
          </div>
          <div class="form-field">
            <label class="form-field__label">排序号</label>
            <input class="form-input" v-model="createDialog.form.sortNo" type="number" />
          </div>
          <div class="section-tip">新增机构默认「停用」,保存后需手动启用,启用后方可被用户绑定与业务选择。</div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="createDialog.show = false">取消</button>
          <button class="btn btn--primary" @click="saveCreate">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listDeptTree, listDepts, createDept, updateDept, deleteDept, updateDeptStatus,
  type SysDept
} from '@/api/system'

const orgTypeOptions = [
  { value: 'HEAD', label: '总行' },
  { value: 'DEPT', label: '部门' },
  { value: 'BRANCH', label: '支行' },
  { value: 'NETWORK', label: '网点' },
  { value: 'GROUP', label: '集团管理机构' }
]

const tree = ref<SysDept[]>([])
const flatDepts = ref<SysDept[]>([])
const current = ref<SysDept | null>(null)
const editForm = reactive({ deptName: '', orgType: '', parentId: 0 as number, manager: '', sortNo: 1 as number })

async function load() {
  try {
    tree.value = await listDeptTree()
  } catch {
    tree.value = []
  }
  try {
    flatDepts.value = await listDepts()
  } catch {
    flatDepts.value = []
  }
}

function onSelect(data: SysDept) {
  // 树节点不含 parentId/manager/sortNo,用扁平列表补全详情数据(修复上级机构/负责人/排序号显示)
  const full = flatDepts.value.find((d) => d.id === data.id) ?? data
  current.value = full
  editForm.deptName = full.deptName
  editForm.orgType = full.orgType || 'BRANCH'
  editForm.parentId = full.parentId ?? 0
  editForm.manager = full.manager || ''
  editForm.sortNo = full.sortNo ?? 1
}

async function saveEdit() {
  if (!current.value) return
  if (!editForm.deptName) {
    ElMessage.warning('机构名称必填')
    return
  }
  await updateDept(current.value.id, {
    deptName: editForm.deptName,
    orgType: editForm.orgType,
    parentId: editForm.parentId,
    manager: editForm.manager,
    sortNo: editForm.sortNo
  })
  ElMessage.success('已保存')
  const keepId = current.value.id
  await load()
  reselect(keepId)
}

function reselect(id: number) {
  const hit = flatDepts.value.find((d) => d.id === id)
  if (hit) onSelect(hit)
}

// 启用/停用:停用前置校验存量业务,后端报错文案含未完结申请/在途任务/未关闭承诺笔数,由拦截器统一 toast
async function toggleStatus() {
  if (!current.value) return
  const target = current.value.status === 'ENABLE' ? 'DISABLE' : 'ENABLE'
  if (target === 'DISABLE') {
    await ElMessageBox.confirm(
      `确认停用机构「${current.value.deptName}」?停用后新增业务不可选该机构,历史数据只读。`,
      '停用确认',
      { type: 'warning' }
    )
  }
  await updateDeptStatus(current.value.id, target)
  ElMessage.success(target === 'ENABLE' ? '已启用' : '已停用')
  const keepId = current.value.id
  await load()
  reselect(keepId)
}

// 删除:有子机构/在用用户/岗位绑定/存量业务禁止,后端报错文案含笔数
async function handleDelete() {
  if (!current.value) return
  await ElMessageBox.confirm(
    `确认删除机构「${current.value.deptName}」?存在下级机构、在用用户或存量业务时将被拒绝。`,
    '删除确认',
    { type: 'warning' }
  )
  await deleteDept(current.value.id)
  ElMessage.success('已删除')
  current.value = null
  await load()
}

// 新增
const createDialog = reactive({
  show: false,
  parent: null as SysDept | null,
  form: { deptName: '', orgType: 'BRANCH', orgCode: '', manager: '', sortNo: 1 as number }
})
function openCreate(parent: SysDept | null) {
  createDialog.parent = parent
  createDialog.form = { deptName: '', orgType: 'BRANCH', orgCode: '', manager: '', sortNo: 1 }
  createDialog.show = true
}
async function saveCreate() {
  const f = createDialog.form
  if (!f.deptName || !f.orgType) {
    ElMessage.warning('机构名称与机构类型必填')
    return
  }
  await createDept({
    parentId: createDialog.parent ? createDialog.parent.id : 0,
    deptName: f.deptName,
    orgType: f.orgType,
    orgCode: f.orgCode || undefined,
    manager: f.manager,
    sortNo: f.sortNo
  })
  createDialog.show = false
  ElMessage.success('机构已创建(默认停用,请在详情中手动启用)')
  await load()
}

onMounted(load)
</script>

<style scoped>
.dept-layout { display: flex; gap: 16px; align-items: flex-start; }
.dept-tree { flex: 0 0 360px; max-height: calc(100vh - 220px); overflow: auto; }
.dept-detail { flex: 1; }
.card__head { gap: 8px; flex-wrap: wrap; }
.tree-node { display: inline-flex; align-items: center; gap: 8px; }
.tree-node__code { color: var(--color-text-light); font-size: 12px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 20px; }
.req { color: var(--color-danger); }
</style>
