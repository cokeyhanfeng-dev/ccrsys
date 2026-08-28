<template>
  <div>
    <div class="section-head">
      <div class="section-title">用户管理</div>
      <InfoTip>系统用户维护:登录名、姓名、角色、机构、启停;编辑维护「机构-岗位绑定」多行(机构+岗位+默认,限一个默认组合,停用机构不可绑定)。</InfoTip>
    </div>

    <div class="card">
      <!-- 查询/工具区统一 card-toolbar:筛选在左,新建归右侧 actions -->
      <div class="card-toolbar">
        <div class="filter-group">
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
        <div class="card-toolbar__actions">
          <button class="btn btn--primary" @click="openCreate">＋ 新建用户</button>
        </div>
      </div>

      <!-- 表格横滚容器:窄屏横向滚动,避免列被挤压 -->
      <div class="table-scroll">
      <table class="table table--full">
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

    <!-- 新建/编辑弹窗 -->
    <div class="modal" v-if="dialog.show">
      <div class="modal__card modal__card--wide">
        <div class="modal__title">{{ dialog.isEdit ? '编辑用户' : '新建用户' }}</div>
        <div class="modal__body">
          <!-- 长表单分组:基本信息 / 机构-岗位绑定 -->
          <div class="form-group-title">基本信息</div>
          <div class="form-grid">
            <!-- §UI审查:表单 label 补 for/id 关联 -->
            <div class="form-field">
              <label class="form-field__label" for="u-username">登录名 <span class="req">*</span></label>
              <input id="u-username" class="form-input" v-model="dialog.form.username" :disabled="dialog.isEdit" autocomplete="username" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="u-nickname">姓名 <span class="req">*</span></label>
              <input id="u-nickname" class="form-input" v-model="dialog.form.nickName" autocomplete="off" />
            </div>
            <div class="form-field">
              <label class="form-field__label" for="u-role">角色 <span class="req">*</span></label>
              <select id="u-role" class="form-select" v-model="dialog.form.roleCode">
                <option v-for="r in roleOptions" :key="r.value" :value="r.value">{{ r.label }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="u-password">密码{{ dialog.isEdit ? '(留空不修改)' : '(留空用统一初始密码)' }}</label>
              <!-- §UI审查:密码框加显隐切换 -->
              <div class="pwd-field">
                <input id="u-password" class="form-input" v-model="dialog.form.password" :type="showPwd ? 'text' : 'password'" autocomplete="new-password" @input="newPwdHint = pwdHint(dialog.form.password)" />
                <button type="button" class="pwd-toggle" :aria-label="showPwd ? '隐藏密码' : '显示密码'" @click="showPwd = !showPwd">{{ showPwd ? '隐藏' : '显示' }}</button>
              </div>
              <span v-if="newPwdHint" class="pwd-hint" :class="{ 'pwd-hint--ok': newPwdHint.startsWith('✓') }">{{ newPwdHint }}</span>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="u-org">归属机构</label>
              <select id="u-org" class="form-select" v-model="dialog.form.orgId">
                <option v-for="d in depts" :key="d.id" :value="d.id">{{ d.deptName }}</option>
              </select>
            </div>
            <div class="form-field">
              <label class="form-field__label" for="u-phone">手机</label>
              <input id="u-phone" class="form-input" v-model="dialog.form.phone" autocomplete="off" />
            </div>
          </div>

          <!-- 机构-岗位绑定(§12.18 ②) -->
          <div class="binding-block">
            <!-- 分组头部复用 card-toolbar:标题+说明在左,添加按钮归右侧 -->
            <div class="card-toolbar">
              <span class="card-toolbar__title">机构-岗位绑定 <span class="req">*</span></span>
              <span class="card-toolbar__sub">必须且仅能有一条默认</span>
              <div class="card-toolbar__actions">
                <button class="btn btn--secondary" @click="addBinding">＋ 添加绑定</button>
              </div>
            </div>
            <div class="table-scroll">
            <table class="table table--full">
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
            </div>
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
import { pwdHint } from '@/utils/password'

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
// 分页(§11.12 用户较多,翻页查看)
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const dialog = reactive({
  show: false,
  isEdit: false,
  form: {} as any,
  bindings: [] as UserBinding[]
})
// 密码强度逐步提示(与后端强密码规则一致)
const newPwdHint = ref('')
// §UI审查:密码显隐切换
const showPwd = ref(false)

async function load() {
  // 查询条件变化时从第一页开始
  pageNum.value = 1
  await fetchPage()
}
async function fetchPage() {
  try {
    const data = await listUsers({
      keyword: query.keyword || undefined,
      orgId: query.orgId === '' ? undefined : query.orgId,
      roleCode: query.roleCode || undefined,
      status: query.status || undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    users.value = data?.records || []
    total.value = Number(data?.total) || 0
  } catch {
    users.value = []
    total.value = 0
  }
}
function onPage(p: number) {
  pageNum.value = p
  fetchPage()
}
async function loadDepts() {
  try {
    depts.value = await listDepts()
  } catch {
    depts.value = []
  }
}
function deptName(id: number) {
  // §UI审查:未知机构兜底「—」,不再显示原始数字 id
  return depts.value.find((d) => d.id === id)?.deptName || '—'
}
function roleName(code: string) {
  // §UI审查:未知角色兜底「—」,不再显示原始编码
  return roleOptions.value.find((r) => r.value === code)?.label || '—'
}

function openCreate() {
  dialog.isEdit = false
  const role = roleOptions.value[0]?.value || ''
  const orgId = depts.value.find((d) => d.status === 'ENABLE')?.id ?? ''
  // §UI审查:无可用机构前置提示,避免绑定行校验必败
  if (!orgId) {
    ElMessage.warning('暂无启用机构,请先在机构管理中启用机构后再创建用户')
  }
  dialog.form = { username: '', nickName: '', roleCode: role, password: '', orgId, phone: '', status: 'ENABLE' }
  dialog.bindings = [{ orgId, postCode: role, isDefault: '1' }]
  newPwdHint.value = ''
  showPwd.value = false
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
  newPwdHint.value = ''
  showPwd.value = false
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
  // 重置了密码(编辑填了新密码/新建填了密码)→ 该用户下次登录强制改密
  const pwdReset = dialog.isEdit ? !!dialog.form.password : !!dialog.form.password
  if (dialog.isEdit) {
    await updateUser(dialog.form.id, dialog.form)
    await saveUserBinding(dialog.form.id, dialog.bindings)
  } else {
    const created = await createUser(dialog.form)
    await saveUserBinding(created.id, dialog.bindings)
  }
  dialog.show = false
  ElMessage.success(pwdReset ? '保存成功,该用户下次登录需强制改密' : '保存成功')
  fetchPage() // §UI审查:保存后保持当前页,与删除「当前页剩最后一条回退一页」策略一致
}
async function toggleStatus(u: any) {
  const disabling = u.status === 'ENABLE'
  // 停用直接改登录状态,加确认防误点(UI 审查 P0-5);启用可免
  if (disabling) {
    await ElMessageBox.confirm(`确认停用用户「${u.nickName || u.username}」?停用后该用户将无法登录。`, '停用确认', { type: 'warning' })
  }
  await updateUserStatus(u.id, disabling ? 'DISABLE' : 'ENABLE')
  ElMessage.success(disabling ? '已停用' : '已启用')
  fetchPage()
}
async function handleDel(u: any) {
  await ElMessageBox.confirm(`确认删除用户「${u.nickName || u.username}」?`, '删除确认', { type: 'warning' })
  await deleteUser(u.id)
  ElMessage.success('已删除')
  // 保持当前页刷新(load() 会跳回第 1 页,删除非查询条件变化);当前页只剩最后一条则回退一页
  if (users.value.length === 1 && pageNum.value > 1) {
    pageNum.value -= 1
  }
  fetchPage()
}
onMounted(() => {
  load()
  loadDepts()
  loadRoles()
})
</script>

<style scoped>
/* req 在 card-toolbar__title 内使用,全局规则只覆盖 form-field__label 场景,此处保留 */
.req { color: var(--color-danger); }
.modal__card--wide { width: 720px; max-width: 92vw; }
.filter-group { display: flex; gap: 8px; flex-wrap: wrap; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 20px; }
/* 表格横滚:列多时窄屏横向滚动 */
.table-scroll { overflow-x: auto; }
.table-scroll .table--full { min-width: 720px; }
.binding-block .table-scroll .table--full { min-width: 520px; }
.pager { margin-top: 10px; }
/* 768px 断点:双列表单转单列 */
@media (max-width: 768px) {
  .form-grid { grid-template-columns: 1fr; }
}
/* §UI审查:密码显隐切换按钮 */
.pwd-field { position: relative; }
.pwd-field .form-input { padding-right: 52px; }
.pwd-toggle {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  border: none;
  background: transparent;
  color: var(--color-text-sub);
  font-size: 12px;
  cursor: pointer;
  padding: 4px 6px;
}
.pwd-toggle:hover { color: var(--color-primary); }
.pwd-hint { font-size: 12px; line-height: 1.6; color: var(--color-warning); }
.pwd-hint--ok { color: var(--color-success); }
.binding-block { margin-top: 16px; border-top: 1px dashed var(--color-border); padding-top: 12px; }
</style>
