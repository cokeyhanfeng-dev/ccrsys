<template>
  <div>
    <div class="section-head">
      <div class="section-title">权限管理</div>
      <div class="section-tip">角色维护与菜单权限分配(菜单权限影响前端展示与接口访问)。</div>
    </div>

    <div class="card">
      <div class="card__head">
        <span>角色列表</span>
        <button class="btn btn--primary" @click="openCreate">＋ 新建角色</button>
      </div>
      <table class="table">
        <thead>
          <tr><th>角色编码</th><th>角色名称</th><th>菜单权限</th><th>备注</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in roles" :key="r.id">
            <td>{{ r.roleCode }}</td>
            <td>{{ r.roleName }}</td>
            <td>{{ menuText(r.menuIds) }}</td>
            <td>{{ r.remark }}</td>
            <td><span :class="r.status === 'ENABLE' ? 'badge badge--success' : 'badge badge--neutral'">{{ r.status === 'ENABLE' ? '启用' : '停用' }}</span></td>
            <td>
              <button class="btn btn--text" @click="openEdit(r)">编辑</button>
              <button class="btn btn--text" @click="handleDel(r)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 新建/编辑弹窗:角色 + 菜单权限勾选 -->
    <div class="modal" v-if="dialog.show">
      <div class="modal__card">
        <div class="modal__title">{{ dialog.isEdit ? '编辑角色' : '新建角色' }}</div>
        <div class="modal__body">
          <div class="form-field">
            <label class="form-field__label">角色编码 <span class="req">*</span></label>
            <input class="form-input" v-model="dialog.form.roleCode" :disabled="dialog.isEdit" />
          </div>
          <div class="form-field">
            <label class="form-field__label">角色名称 <span class="req">*</span></label>
            <input class="form-input" v-model="dialog.form.roleName" />
          </div>
          <div class="form-field">
            <label class="form-field__label">菜单权限</label>
            <div class="menu-checks">
              <label v-for="m in menus" :key="m.id" class="menu-check">
                <input type="checkbox" :value="m.id" v-model="selectedMenus" />
                {{ m.menuName }}
              </label>
            </div>
          </div>
          <div class="form-field">
            <label class="form-field__label">备注</label>
            <input class="form-input" v-model="dialog.form.remark" />
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

const roles = ref<any[]>([])
const menus = ref<any[]>([])
const selectedMenus = ref<number[]>([])
const dialog = reactive({ show: false, isEdit: false, form: {} as any })

async function load() {
  try {
    roles.value = await get<any[]>('/system/roles')
  } catch {
    roles.value = []
  }
  // 菜单(与实际前端侧边栏一致,对应 ccr_sys_menu 种子 db/08_system.sql)
  menus.value = [
    { id: 1, menuName: '工作台' }, { id: 2, menuName: '贷款利率申请' }, { id: 3, menuName: '存款利率申请' },
    { id: 4, menuName: '贡献度跟踪' }, { id: 5, menuName: '历史' },
    { id: 6, menuName: '用户管理' }, { id: 7, menuName: '权限管理' }, { id: 8, menuName: '流程配置' },
    { id: 9, menuName: '参数管理' }
  ]
}
function menuText(ids: string) {
  if (!ids) return '—'
  const names = ids.split(',').map((id) => menus.value.find((m) => m.id === Number(id))?.menuName).filter(Boolean)
  return names.join('、') || '—'
}
function openCreate() {
  dialog.isEdit = false
  dialog.form = { roleCode: '', roleName: '', remark: '' }
  selectedMenus.value = []
  dialog.show = true
}
function openEdit(r: any) {
  dialog.isEdit = true
  dialog.form = { ...r }
  selectedMenus.value = (r.menuIds || '').split(',').filter(Boolean).map(Number)
  dialog.show = true
}
async function save() {
  const payload = { ...dialog.form, menuIds: selectedMenus.value.join(',') }
  if (dialog.isEdit) {
    await put(`/system/roles/${dialog.form.id}`, payload)
  } else {
    await post('/system/roles', payload)
  }
  dialog.show = false
  ElMessage.success('保存成功')
  load()
}
async function handleDel(r: any) {
  await ElMessageBox.confirm(`确认删除角色「${r.roleName}」?`, '删除确认', { type: 'warning' })
  await del(`/system/roles/${r.id}`)
  ElMessage.success('已删除')
  load()
}
onMounted(load)
</script>

<style scoped>
.section-head { margin-bottom: 16px; }
.section-title { font-size: var(--fs-h2); font-weight: 600; margin-bottom: 6px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
.card { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow-sm); }
.card__head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.table { border-radius: var(--radius); overflow: hidden; }
.req { color: var(--color-danger); }
.menu-checks { display: flex; flex-wrap: wrap; gap: 8px 16px; }
.menu-check { font-size: 13px; display: inline-flex; align-items: center; gap: 4px; }
</style>
