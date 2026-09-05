<template>
  <div>
    <div class="section-head">
      <div class="section-title">机构管理</div>
      <InfoTip>机构树维护(总行 → 部门/支行 → 网点)。机构编码为层级前缀数字码(唯一、禁改);新增机构默认停用,需手动启用后方可被绑定与业务选择;停用/删除前置校验存量业务(未完结申请/在途审批任务/未关闭承诺)。</InfoTip>
    </div>

    <div class="dept-layout">
      <!-- 左侧机构树 -->
      <div class="card dept-tree">
        <!-- 工具区统一 card-toolbar:标题在左,搜索与新增归右侧 actions -->
        <div class="card-toolbar">
          <span class="card-toolbar__title">机构树</span>
          <div class="card-toolbar__actions">
            <!-- §UI审查:机构树顶加关键字过滤搜索框 -->
            <input
              class="form-input"
              v-model="treeKeyword"
              placeholder="搜索机构名称/编码"
              style="width:170px"
              @input="onTreeSearch"
            />
            <button class="btn btn--primary" @click="openCreate(null)">＋ 新增根机构</button>
          </div>
        </div>
        <el-tree
          ref="treeRef"
          :data="tree"
          node-key="id"
          :props="{ label: 'deptName', children: 'children' }"
          :expand-on-click-node="false"
          :filter-node-method="filterNode"
          default-expand-all
          highlight-current
          @node-click="onSelect"
        >
          <template #default="{ data }">
            <span class="tree-node">
              <span class="tree-node__name" :title="data.deptName">{{ data.deptName }}</span>
              <span class="tree-node__code">{{ data.orgCode }}</span>
              <!-- 零售支行徽标(2026-09-04 综合/零售两级支行) -->
              <span v-if="data.branchType === 'RETAIL'" class="badge badge--warning">零售支行</span>
              <span :class="data.status === 'ENABLE' ? 'badge badge--success' : 'badge badge--neutral'">
                {{ data.status === 'ENABLE' ? '启用' : '停用' }}
              </span>
            </span>
          </template>
        </el-tree>
        <div v-if="!tree.length" class="empty-line">暂无机构</div>
      </div>

      <!-- 右侧机构详情/编辑 -->
      <div class="card dept-detail">
        <div class="card-toolbar">
          <span class="card-toolbar__title">机构详情</span>
          <div v-if="current" class="card-toolbar__actions">
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
              <label class="form-field__label">机构编码(org_code)</label>
              <!-- 编码唯一禁改,用只读值展示 -->
              <div class="form-static">{{ current.orgCode }}</div>
            </div>
            <div class="form-field">
              <label class="form-field__label">机构类型</label>
              <select class="form-select" v-model="editForm.orgType">
                <option v-for="t in orgTypeOptions" :key="t.value" :value="t.value">{{ t.label }}</option>
              </select>
            </div>
            <!-- 支行性质(2026-09-04 综合/零售两级支行):仅支行类型可见;零售支行须挂综合支行下 -->
            <div class="form-field" v-if="editForm.orgType === 'BRANCH'">
              <label class="form-field__label">支行性质</label>
              <select class="form-select" v-model="editForm.branchType">
                <option v-for="t in branchTypeOptions" :key="t.value" :value="t.value">{{ t.label }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">上级机构</label>
              <!-- §UI审查:上级机构下拉排除自身及全部子级,避免选到后代成环 -->
              <select class="form-select" v-model="editForm.parentId">
                <option :value="0">总行(根)</option>
                <option v-for="d in parentOptions" :key="d.id" :value="d.id">
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
        <div v-else class="empty-line">请在左侧选择机构</div>
      </div>
    </div>

    <!-- 新增机构弹窗 -->
    <div class="modal" v-if="createDialog.show">
      <div class="modal__card">
        <div class="modal__title">新增{{ createDialog.parent ? '子机构' : '根机构' }}</div>
        <div class="modal__body">
          <div class="form-field" v-if="createDialog.parent">
            <label class="form-field__label">上级机构</label>
            <!-- 上级机构由入口决定,用只读值展示 -->
            <div class="form-static">{{ createDialog.parent.deptName }}({{ createDialog.parent.orgCode }})</div>
          </div>
          <div class="form-field">
            <label class="form-field__label">机构名称 <span class="req">*</span></label>
            <input class="form-input" v-model="createDialog.form.deptName" />
            <div class="form-hint">如 XX支行</div>
          </div>
          <div class="form-field">
            <label class="form-field__label">机构类型 <span class="req">*</span></label>
            <select class="form-select" v-model="createDialog.form.orgType">
              <option v-for="t in orgTypeOptions" :key="t.value" :value="t.value">{{ t.label }}</option>
            </select>
          </div>
          <!-- 支行性质(2026-09-04 综合/零售两级支行):零售支行须挂在综合支行下创建 -->
          <div class="form-field" v-if="createDialog.form.orgType === 'BRANCH'">
            <label class="form-field__label">支行性质</label>
            <select class="form-select" v-model="createDialog.form.branchType">
              <option v-for="t in branchTypeOptions" :key="t.value" :value="t.value">{{ t.label }}</option>
            </select>
            <div class="form-hint">选「零售支行」须在综合支行下新增子机构(上级为综合支行);创建后性质不可由普通支行改。</div>
          </div>
          <div class="form-field">
            <label class="form-field__label">机构编码(org_code)</label>
            <input class="form-input" v-model="createDialog.form.orgCode" />
            <div class="form-hint">留空按上级编码前缀自动生成</div>
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
import { reactive, ref, computed, onMounted } from 'vue'
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

// 支行性质(2026-09-04 综合/零售两级支行):后端空/COMPREHENSIVE=综合,RETAIL=零售(须挂综合支行下)
const branchTypeOptions = [
  { value: 'COMPREHENSIVE', label: '综合支行' },
  { value: 'RETAIL', label: '零售支行' }
]

const tree = ref<SysDept[]>([])
const flatDepts = ref<SysDept[]>([])
const current = ref<SysDept | null>(null)
const editForm = reactive({
  deptName: '', orgType: '', parentId: 0 as number, manager: '', sortNo: 1 as number,
  branchType: 'COMPREHENSIVE' as string
})
// §UI审查:机构树关键字过滤
const treeRef = ref<any>(null)
const treeKeyword = ref('')
function filterNode(value: string, data: any) {
  if (!value) return true
  return (data.deptName || '').includes(value) || (data.orgCode || '').includes(value)
}
function onTreeSearch() {
  treeRef.value?.filter(treeKeyword.value)
}
// §UI审查:上级机构下拉选项——排除当前机构及其全部子级
const parentOptions = computed(() => {
  if (!current.value) return flatDepts.value
  const blocked = new Set<number>([current.value.id])
  let changed = true
  while (changed) {
    changed = false
    for (const d of flatDepts.value) {
      if (d.parentId != null && blocked.has(Number(d.parentId)) && !blocked.has(d.id)) {
        blocked.add(d.id)
        changed = true
      }
    }
  }
  return flatDepts.value.filter((d) => !blocked.has(d.id))
})

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
  // 支行性质:后端空/COMPREHENSIVE=综合;非支行类型不显示也不提交
  editForm.branchType = full.orgType === 'BRANCH'
    ? (full.branchType || 'COMPREHENSIVE')
    : ''
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
    sortNo: editForm.sortNo,
    // 支行性质仅支行类型提交;非支行交由后端置空(RETAIL→COMPREHENSIVE 改性质前上级须已是综合支行,后端校验)
    ...(editForm.orgType === 'BRANCH' ? { branchType: editForm.branchType || 'COMPREHENSIVE' } : {})
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
  form: { deptName: '', orgType: 'BRANCH', orgCode: '', manager: '', sortNo: 1 as number, branchType: 'COMPREHENSIVE' as string }
})
function openCreate(parent: SysDept | null) {
  createDialog.parent = parent
  createDialog.form = { deptName: '', orgType: 'BRANCH', orgCode: '', manager: '', sortNo: 1, branchType: 'COMPREHENSIVE' }
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
    sortNo: f.sortNo,
    // 支行性质仅支行类型提交;零售支行必须经「新增子机构」挂在综合支行下(后端校验上级)
    ...(f.orgType === 'BRANCH' ? { branchType: f.branchType || 'COMPREHENSIVE' } : {})
  })
  createDialog.show = false
  ElMessage.success('机构已创建(默认停用,请在详情中手动启用)')
  await load()
}

onMounted(load)
</script>

<style scoped>
/* §UI审查:树+详情窄屏换行兜底,避免挤压溢出 */
.dept-layout { display: flex; gap: 16px; align-items: flex-start; flex-wrap: wrap; }
.dept-tree { flex: 0 0 480px; max-width: 100%; max-height: calc(100vh - 220px); overflow: auto; }
.dept-detail { flex: 1 1 320px; min-width: 0; }
/* 树节点整行不横向溢出:名称可收缩省略(title 显全名),徽标固定在行尾恒可见(2026-09-05 零售支行徽标被右缘遮挡修复) */
.tree-node { display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1; }
.tree-node__name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-node__code { flex: none; color: var(--color-text-sub); font-size: 12px; white-space: nowrap; } /* §UI审查:浅灰小字改 text-sub 提对比 */
.tree-node .badge { flex: none; white-space: nowrap; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 20px; }
/* 768px 断点:树与详情纵向堆叠,双列表单转单列 */
@media (max-width: 768px) {
  .dept-tree { flex: 1 1 100%; max-height: none; }
  .form-grid { grid-template-columns: 1fr; }
}
</style>
