<template>
  <div>
    <div class="section-head">
      <div class="section-title">用户管理</div>
      <InfoTip>系统用户维护:登录名、姓名、角色、机构、启停;编辑维护「机构-岗位绑定」多行(机构+岗位+默认,限一个默认组合,停用机构不可绑定)。</InfoTip>
    </div>

    <div class="card">
      <div class="card__head">
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <input class="form-input" v-model="query.keyword" placeholder="关键字(登录名/姓名)" style="width:180px" @keyup.enter="load" />
          <select class="form-select" v-model="query.orgId" style="width:180px" @change="load">
            <option value="">全部机构</option>
            <option v-for="d in depts" :key="d.id" :value="d.id">{{ d.deptName }}</option>
          </select>
          <select class="form-select" v-model="query.roleCode" style="width:160px" @change="load">
            <option value="">全部角色</option>
            <option v-for="r in roleOptions" :key="r.value" :value="r.value">{{ r.label }}</option>
          </select>
          <select class="form-select" v-model="query.status" style="width:120px" @change="load">
            <option value="">全部状态</option>
            <option value="ENABLE">启用</option>
            <option value="DISABLE">停用</option>
          </select>
          <button class="btn btn--secondary" @click="load">查询</button>
        </div>
        <button class="btn btn--primary" @click="openCreate">＋ 新建用户</button>
      </div>

      <table class="table">
        <thead>
          <tr><th>登录名</th><th>姓名</th><th>角色</th><th>机构</th><th>手机</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="u in users" :key="u.id">
            <td>{{ u.username }}</td>
            <td>{{ u.nickName }}</td>
            <td>{{ roleName(u.roleCode) }}</td>
            <td>{{ deptName(u.orgId) }}</td>
            <td>{{ u.phone }}</td>
            <td>
              <span :class="u.status === 'ENABLE' ? 'badge badge--success' : 'badge badge--neutral'">
                {{ u.status === 'ENABLE' ? '启用' : '停用' }}
              </span>
            </td>
            <td>
              <button class="btn btn--text" @click="openEdit(u)">编辑</button>
              <button class="btn btn--text" @click="toggleStatus(u)">{{ u.status === 'ENABLE' ? '停用' : '启用' }}</button>
              <button class="btn btn--text" @click="handleDel(u)">删除</button>
            </td>
          </tr>
          <tr v-if="!users.length"><td colspan="7" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 新建/编辑弹窗 -->
    <div class="modal" v-if="dialog.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">{{ dialog.isEdit ? '编辑用户' : '新建用户' }}</div>
        <div class="modal__body">
          <div class="form-grid">
            <div class="form-field">
              <label class="form-field__label">登录名 <span class="req">*</span></label>
              <input class="form-input" v-model="dialog.form.username" :disabled="dialog.isEdit" />
            </div>
            <div class="form-field">
              <label class="form-field__label">姓名 <span class="req">*</span></label>
              <input class="form-input" v-model="dialog.form.nickName" />
            </div>
            <div class="form-field">
              <label class="form-field__label">角色 <span class="req">*</span></label>
              <select class="form-select" v-model="dialog.form.roleCode">
                <option v-for="r in roleOptions" :key="r.value" :value="r.value">{{ r.label }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">密码{{ dialog.isEdit ? '(留空不修改)' : '(新建时)' }}</label>
              <input class="form-input" v-model="dialog.form.password" type="password" />
            </div>
            <div class="form-field">
              <label class="form-field__label">归属机构</label>
              <select class="form-select" v-model="dialog.form.orgId">
                <option v-for="d in depts" :key="d.id" :value="d.id">{{ d.deptName }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label">手机</label>
              <input class="form-input" v-model="dialog.form.phone" />
            </div>
          </div>

          <!-- 机构-岗位绑定(§12.18 ②) -->
          <div class="binding-block">
            <div class="binding-block__head">
              <span>机构-岗位绑定 <span class="req">*</span>(必须且仅能有一条默认)</span>
              <button class="btn btn--secondary" @click="addBinding">＋ 添加绑定</button>
            </div>
            <table class="table">
              <thead>
                <tr><th style="width:60px">默认</th><th>机构</th><th>岗位/角色</th><th style="width:70px">操作</th></tr>
              </thead>
              <tbody>
                <tr v-for="(b, i) in dialog.bindings" :key="i">
                  <td>
                    <input type="radio" name="binding-default" :checked="b.isDefault === '1'" @change="setDefault(i)" />
                  </td>
                  <td>
                    <select class="form-select" v-model="b.orgId">
                      <option value="" disabled>请选择机构</option>
                      <option v-for="d in depts" :key="d.id" :value="d.id" :disabled="d.status !== 'ENABLE'">
                        {{ d.deptName }}{{ d.status !== 'ENABLE' ? '(已停用)' : '' }}
                      </option>
                    </select>
                  </td>
                  <td>
                    <select class="form-select" v-model="b.postCode">
                      <option value="" disabled>请选择岗位</option>
                      <option v-for="r in roleOptions" :key="r.value" :value="r.value">{{ r.label }}</option>
                    </select>
                  </td>
                  <td>
                    <button class="btn btn--text" :disabled="dialog.bindings.length <= 1" @click="dialog.bindings.splice(i, 1)">移除</button>
                  </td>
                </tr>
                <tr v-if="!dialog.bindings.length"><td colspan="4" class="empty-cell">至少保留一条绑定</td></tr>
              </tbody>
            </table>
            <div class="section-tip">绑定校验:停用机构不可绑定;同一机构+岗位不可重复;默认机构/岗位唯一。</div>
          </div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="dialog.show = false">取消</button>
          <button class="btn btn--primary" @click="save">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listRoles, listDepts, listUsers, createUser, updateUser, updateUserStatus, deleteUser,
  getUserBinding, saveUserBinding,
  type SysDept, type UserBinding
} from '@/api/system'

// 角色选项从后端角色表拉取(与 db/08_system.sql 角色种子一致;岗位编码与角色码对齐)
const roleOptions = ref<{ value: string; label: string }[]>([])

async function loadRoles() {
  try {
    const roles = await listRoles()
    roleOptions.value = roles.map((r) => ({ value: r.roleCode, label: r.roleName }))
  } catch {
    roleOptions.value = []
  }
}

const query = reactive({ keyword: '', orgId: '' as number | '', roleCode: '', status: '' })
const users = ref<any[]>([])
const depts = ref<SysDept[]>([])
const dialog = reactive({
  show: false,
  isEdit: false,
  form: {} as any,
  bindings: [] as UserBinding[]
})

async function load() {
  try {
    users.value = await listUsers({
      keyword: query.keyword || undefined,
      orgId: query.orgId === '' ? undefined : query.orgId,
      roleCode: query.roleCode || undefined,
      status: query.status || undefined
    })
  } catch {
    users.value = []
  }
}
async function loadDepts() {
  try {
    depts.value = await listDepts()
  } catch {
    depts.value = []
  }
}
function deptName(id: number) {
  return depts.value.find((d) => d.id === id)?.deptName || id
}
function roleName(code: string) {
  return roleOptions.value.find((r) => r.value === code)?.label || code
}

function openCreate() {
  dialog.isEdit = false
  const role = roleOptions.value[0]?.value || ''
  const orgId = depts.value.find((d) => d.status === 'ENABLE')?.id ?? ''
  dialog.form = { username: '', nickName: '', roleCode: role, password: '', orgId, phone: '', status: 'ENABLE' }
  dialog.bindings = [{ orgId, postCode: role, isDefault: '1' }]
  dialog.show = true
}
async function openEdit(u: any) {
  dialog.isEdit = true
  dialog.form = { ...u, password: '' }
  try {
    const rows = await getUserBinding(u.id)
    dialog.bindings = rows.length
      ? rows.map((r) => ({ orgId: r.orgId, postCode: r.postCode, isDefault: r.isDefault }))
      : [{ orgId: u.orgId ?? '', postCode: u.roleCode || '', isDefault: '1' }]
  } catch {
    dialog.bindings = [{ orgId: u.orgId ?? '', postCode: u.roleCode || '', isDefault: '1' }]
  }
  dialog.show = true
}

function addBinding() {
  dialog.bindings.push({ orgId: '', postCode: '', isDefault: dialog.bindings.length ? '0' : '1' })
}
function setDefault(index: number) {
  dialog.bindings.forEach((b, i) => (b.isDefault = i === index ? '1' : '0'))
}

function validateBindings(): boolean {
  if (!dialog.bindings.length) {
    ElMessage.warning('至少保留一条机构-岗位绑定')
    return false
  }
  if (dialog.bindings.filter((b) => b.isDefault === '1').length !== 1) {
    ElMessage.warning('默认机构/岗位必须且仅能有一条')
    return false
  }
  const combo = new Set<string>()
  for (const b of dialog.bindings) {
    if (!b.orgId || !b.postCode) {
      ElMessage.warning('绑定行机构与岗位必填')
      return false
    }
    const key = `${b.orgId}:${b.postCode}`
    if (combo.has(key)) {
      ElMessage.warning('存在重复的机构-岗位绑定')
      return false
    }
    combo.add(key)
  }
  return true
}

async function save() {
  if (!dialog.form.username || !dialog.form.nickName || !dialog.form.roleCode) {
    ElMessage.warning('登录名/姓名/角色必填')
    return
  }
  if (!validateBindings()) return
  if (dialog.isEdit) {
    await updateUser(dialog.form.id, dialog.form)
    await saveUserBinding(dialog.form.id, dialog.bindings)
  } else {
    const created = await createUser(dialog.form)
    await saveUserBinding(created.id, dialog.bindings)
  }
  dialog.show = false
  ElMessage.success('保存成功')
  load()
}
async function toggleStatus(u: any) {
  await updateUserStatus(u.id, u.status === 'ENABLE' ? 'DISABLE' : 'ENABLE')
  ElMessage.success('状态已更新')
  load()
}
async function handleDel(u: any) {
  await ElMessageBox.confirm(`确认删除用户「${u.nickName || u.username}」?`, '删除确认', { type: 'warning' })
  await deleteUser(u.id)
  ElMessage.success('已删除')
  load()
}
onMounted(() => {
  load()
  loadDepts()
  loadRoles()
})
</script>

<style scoped>
.table { border-radius: var(--radius-sm); overflow-x: auto; }
.req { color: var(--color-danger); }
.modal__card--wide { width: 720px; max-width: 92vw; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 20px; }
.binding-block { margin-top: 16px; border-top: 1px dashed var(--color-border); padding-top: 12px; }
.binding-block__head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; font-weight: 600; }
</style>
