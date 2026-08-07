<template>
  <div>
    <div class="section-head">
      <div class="section-title">用户管理</div>
      <div class="section-tip">系统用户维护:登录名、姓名、角色、机构、启停。</div>
    </div>

    <div class="card">
      <div class="card__head">
        <div style="display:flex;gap:8px">
          <input class="form-input" v-model="query.username" placeholder="用户名" style="width:180px" @keyup.enter="load" />
          <select class="form-select" v-model="query.roleCode" style="width:180px" @change="load">
            <option value="">全部角色</option>
            <option v-for="r in roleOptions" :key="r.value" :value="r.value">{{ r.label }}</option>
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
        </tbody>
      </table>
    </div>

    <!-- 新建/编辑弹窗 -->
    <div class="modal" v-if="dialog.show">
      <div class="modal__card">
        <div class="modal__title">{{ dialog.isEdit ? '编辑用户' : '新建用户' }}</div>
        <div class="modal__body">
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
            <label class="form-field__label">密码(新建时)</label>
            <input class="form-input" v-model="dialog.form.password" type="password" />
          </div>
          <div class="form-field">
            <label class="form-field__label">归属机构</label>
            <select class="form-select" v-model="dialog.form.orgId">
              <option v-for="d in depts" :key="d.id" :value="d.id">{{ d.deptName }}</option>
            </select>
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
import { get, post, put, del } from '@/api/request'

// 角色选项从后端角色表拉取(与 db/08_system.sql 角色种子一致)
const roleOptions = ref<{ value: string; label: string }[]>([])

async function loadRoles() {
  try {
    const roles = await get<any[]>('/system/roles')
    roleOptions.value = roles.map((r) => ({ value: r.roleCode, label: r.roleName }))
  } catch {
    roleOptions.value = []
  }
}

const query = reactive({ username: '', roleCode: '' })
const users = ref<any[]>([])
const depts = ref<any[]>([])
const dialog = reactive({ show: false, isEdit: false, form: {} as any })

async function load() {
  try {
    const data = await get<any[]>('/system/users', query)
    users.value = data
  } catch {
    users.value = []
  }
}
async function loadDepts() {
  try {
    depts.value = await get<any[]>('/system/depts')
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
  dialog.form = { username: '', nickName: '', roleCode: roleOptions.value[0]?.value || '', password: '123456', orgId: 1001, status: 'ENABLE' }
  dialog.show = true
}
function openEdit(u: any) {
  dialog.isEdit = true
  dialog.form = { ...u, password: '' }
  dialog.show = true
}
async function save() {
  if (dialog.isEdit) {
    await put(`/system/users/${dialog.form.id}`, dialog.form)
  } else {
    await post('/system/users', dialog.form)
  }
  dialog.show = false
  ElMessage.success('保存成功')
  load()
}
async function toggleStatus(u: any) {
  await put(`/system/users/${u.id}/status`, { status: u.status === 'ENABLE' ? 'DISABLE' : 'ENABLE' })
  ElMessage.success('状态已更新')
  load()
}
async function handleDel(u: any) {
  await ElMessageBox.confirm(`确认删除用户「${u.nickName || u.username}」?`, '删除确认', { type: 'warning' })
  await del(`/system/users/${u.id}`)
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
.section-head { margin-bottom: 16px; }
.section-title { font-size: var(--fs-h2); font-weight: 600; margin-bottom: 6px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
.card { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow-sm); }
.card__head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.table { border-radius: var(--radius); overflow: hidden; }
.req { color: var(--color-danger); }
</style>
