<template>
  <div>
    <div class="section-head">
      <div class="section-title">权限管理</div>
      <InfoTip content="角色维护与菜单权限分配(菜单权限影响前端展示与接口访问)。" />
    </div>

    <div class="card">
      <!-- 工具区统一 card-toolbar:标题在左,新建归右侧 actions -->
      <div class="card-toolbar">
        <span class="card-toolbar__title">角色列表</span>
        <div class="card-toolbar__actions">
          <button class="btn btn--primary" @click="openCreate">＋ 新建角色</button>
        </div>
      </div>
      <!-- 表格横滚容器:窄屏横向滚动,避免列被挤压 -->
      <div class="table-scroll">
      <table class="table table--full">
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
          <!-- §UI审查:角色列表补空态行 -->
          <tr v-if="!roles.length"><td colspan="6" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
      </div>
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
  // §UI审查:补齐「缓存配置」「运行监控」两项,与侧边栏一致
  menus.value = [
    { id: 1, menuName: '工作台' }, { id: 2, menuName: '贷款利率申请' }, { id: 3, menuName: '存款利率申请' },
    { id: 10, menuName: '利率审批' }, { id: 4, menuName: '贡献度跟踪' }, { id: 5, menuName: '历史' },
    { id: 11, menuName: '数据中心' }, { id: 12, menuName: '审计管理' },
    { id: 6, menuName: '用户管理' }, { id: 7, menuName: '权限管理' }, { id: 13, menuName: '机构管理' },
    { id: 8, menuName: '流程配置' }, { id: 9, menuName: '参数管理' },
    { id: 14, menuName: '缓存配置' }, { id: 15, menuName: '运行监控' }
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
  // §UI审查:角色编码/名称标 * 必填,保存前客户端校验
  if (!dialog.form.roleCode?.trim()) {
    ElMessage.warning('角色编码必填')
    return
  }
  if (!dialog.form.roleName?.trim()) {
    ElMessage.warning('角色名称必填')
    return
  }
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
/* 表格横滚:列多时窄屏横向滚动 */
.table-scroll { overflow-x: auto; }
.table-scroll .table--full { min-width: 680px; }
.menu-checks { display: flex; flex-wrap: wrap; gap: 8px 16px; }
.menu-check { font-size: 13px; display: inline-flex; align-items: center; gap: 4px; }
</style>
