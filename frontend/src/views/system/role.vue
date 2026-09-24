<template>
  <div>
    <div class="section-head"><div class="section-title">角色管理</div><InfoTip content="菜单树控制页面访问。停用取消该角色的导航授权；撤销业务资格需同步调整用户岗位及节点指派。勾选菜单不会增加审批资格。" /></div>
    <div class="card">
      <div class="card-toolbar"><div class="filters"><el-input v-model="keyword" placeholder="角色名称 / 编码" clearable style="width:230px" /><el-select v-model="status" placeholder="全部状态" clearable style="width:130px"><el-option label="启用" value="ENABLE" /><el-option label="停用" value="DISABLE" /></el-select></div><el-button type="primary" @click="edit()">新增角色</el-button></div>
      <el-table v-loading="loading" :data="filtered">
        <el-table-column prop="roleName" label="角色名称" min-width="130" /><el-table-column prop="roleCode" label="角色编码" min-width="170" />
        <el-table-column label="已授权菜单" min-width="230" show-overflow-tooltip><template #default="{row}">{{ grantNames(row) }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.status==='ENABLE' ? 'success':'info'">{{ row.status==='ENABLE' ? '启用':'停用' }}</el-tag></template></el-table-column>
        <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
        <el-table-column label="操作" width="150"><template #default="{row}"><el-button link type="primary" @click="edit(row)">修改 / 授权</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
      </el-table>
    </div>
    <el-dialog class="role-config-dialog" top="6vh" v-model="opened" :title="form.id ? '修改角色与授权' : '新增角色'" width="650px" destroy-on-close>
      <el-form label-width="95px">
        <el-form-item label="角色名称" required><el-input v-model="form.roleName" maxlength="64" /></el-form-item>
        <el-form-item label="角色编码" required><el-input v-model="form.roleCode" :disabled="!!form.id" maxlength="32" placeholder="小写英文、数字、下划线" @input="clearChecks" /></el-form-item>
        <el-form-item label="导航授权"><el-radio-group v-model="form.status" :disabled="form.roleCode==='admin'"><el-radio value="ENABLE">启用</el-radio><el-radio value="DISABLE">停用</el-radio></el-radio-group></el-form-item>
        <el-form-item label="菜单权限">
          <div class="grant-panel">
            <el-alert v-if="form.roleCode==='admin'" title="管理员自动拥有全部有效菜单，基础管理入口受保护。" type="info" :closable="false" />
            <template v-else>
              <div class="grant-actions"><el-checkbox v-model="expandAll" @change="expand">展开 / 折叠</el-checkbox><el-checkbox v-model="checkAll" @change="selectAll">全选 / 全不选</el-checkbox><el-checkbox v-model="linked">父子联动</el-checkbox></div>
              <el-tree ref="treeRef" :data="grantTree" node-key="id" show-checkbox :check-strictly="!linked" :props="{label:'menuName',children:'children',disabled:'disabled'}" :default-expand-all="expandAll" />
              <div class="grant-hint">灰色菜单超出当前角色的业务范围。工作台为基础入口；目录只组织层级，具体页面需勾选授权。</div>
            </template>
          </div>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" maxlength="200" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="opened=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, put, del } from '@/api/request'
import { menuTree } from '@/utils/navigation.mjs'
import { useNavigationStore } from '@/store/navigation'
const navigation=useNavigationStore()
const roles=ref<any[]>([]),menus=ref<any[]>([]),keyword=ref(''),status=ref(''),form=ref<any>({}),opened=ref(false),loading=ref(false),saving=ref(false),treeRef=ref<any>(),expandAll=ref(true),checkAll=ref(false),linked=ref(true)
const filtered=computed(() => roles.value.filter(r => (!keyword.value || `${r.roleName} ${r.roleCode}`.includes(keyword.value)) && (!status.value || r.status===status.value)))
// 同后端业务边界，仅供配置提示；保存和访问仍由后端校验。
function eligible(path:string){const code=form.value.roleCode;if(code==='admin')return true;if(['/overview','/commitment','/history'].includes(path))return true;if(path.startsWith('/application/'))return code==='customer_manager';if(path==='/approval')return ['branch_manager','dept_gm','vice_president','secretary','committee_member','president'].includes(code);return ({'/resolution':'resolution_query','/audit':'auditor','/system/params':'config_reviewer'} as any)[path]===code}
const grantTree=computed(() => menuTree(menus.value.map(m => ({...m,disabled:m.menuType==='C' && !eligible(m.path)}))))
function grantNames(r:any){if(r.roleCode==='admin')return '全部有效菜单';const ids=(r.menuIds || '').split(',');return menus.value.filter(m => m.menuType==='C' && ids.includes(String(m.id))).map(m=>m.menuName).join('、') || '仅基础工作台'}
async function load(){loading.value=true;try{const result=await Promise.all([get<any[]>('/system/roles'),get<any[]>('/system/menus')]);roles.value=result[0];menus.value=result[1]}finally{loading.value=false}}
async function edit(row?:any){form.value=row ? {...row} : {roleCode:'',roleName:'',remark:'',status:'ENABLE'};opened.value=true;checkAll.value=false;expandAll.value=true;linked.value=true;await nextTick();treeRef.value?.setCheckedKeys((row?.menuIds || '').split(',').filter((id:string)=>menus.value.some(m=>String(m.id)===id && m.menuType==='C' && eligible(m.path))))}
function clearChecks(){treeRef.value?.setCheckedKeys([]);checkAll.value=false}
function expand(){for(const m of menus.value){const node=treeRef.value?.getNode(String(m.id));if(node)node.expanded=expandAll.value}}
function selectAll(){treeRef.value?.setCheckedKeys(checkAll.value ? menus.value.filter(m=>m.menuType==='C' && eligible(m.path)).map(m=>String(m.id)):[])}
async function save(){
 if(!/^[a-z][a-z0-9_]{0,31}$/.test(form.value.roleCode || '') || !form.value.roleName?.trim()){ElMessage.warning('请填写有效的角色编码与名称');return}
 saving.value=true
 try{const ids=[...new Set([...(treeRef.value?.getCheckedKeys() || []),...(treeRef.value?.getHalfCheckedKeys() || [])])];const payload={...form.value,menuIds:form.value.roleCode==='admin' ? '' : ids.join(',')};if(form.value.id)await put(`/system/roles/${form.value.id}`,payload);else await post('/system/roles',payload);opened.value=false;ElMessage.success('已保存');await load();await navigation.load(true)}finally{saving.value=false}
}
async function remove(row:any){try{await ElMessageBox.confirm(`确认删除角色「${row.roleName}」？`,'删除确认',{type:'warning'})}catch{return}await del(`/system/roles/${row.id}`);ElMessage.success('已删除');await load()}
onMounted(load)
</script>
<style scoped>
.filters,.grant-actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.grant-panel{width:100%;border:1px solid var(--el-border-color);border-radius:6px;padding:12px}.grant-hint{font-size:12px;color:var(--el-text-color-secondary);line-height:1.6;margin-top:8px}.grant-panel :deep(.el-tree){max-height:340px;overflow:auto}
</style>

<style>
.role-config-dialog .el-dialog__body { max-height: calc(88vh - 124px); overflow-y: auto; }
</style>
