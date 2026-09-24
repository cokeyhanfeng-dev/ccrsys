<template>
  <div>
    <div class="section-head"><div class="section-title">菜单管理</div><InfoTip content="维护目录层级、页面名称、图标和排序。隐藏仅移除侧栏入口，停用会取消页面访问。业务审批资格仍按原岗位与节点校验。" /></div>
    <div class="card">
      <div class="card-toolbar">
        <el-input v-model="keyword" placeholder="搜索菜单名称" clearable style="width:240px" />
        <div class="card-toolbar__actions"><el-button @click="expanded = !expanded">展开 / 折叠</el-button><el-button type="primary" @click="edit()">新增菜单</el-button></div>
      </div>
      <el-table :key="String(expanded)" v-loading="loading" :data="filteredTree" row-key="id" :default-expand-all="expanded">
        <el-table-column prop="menuName" label="菜单名称" min-width="200" />
        <el-table-column label="图标" width="65"><template #default="{row}"><el-icon><component :is="row.icon || 'Menu'" /></el-icon></template></el-table-column>
        <el-table-column prop="sortNo" label="排序" width="70" />
        <el-table-column label="类型" width="80"><template #default="{row}"><el-tag :type="row.menuType === 'M' ? 'warning' : 'primary'">{{ row.menuType === 'M' ? '目录' : '菜单' }}</el-tag></template></el-table-column>
        <el-table-column prop="path" label="页面地址" min-width="170" />
        <el-table-column label="状态" width="90"><template #default="{row}">{{ row.status === 'ENABLE' ? '启用' : '停用' }}</template></el-table-column>
        <el-table-column label="显示" width="80"><template #default="{row}">{{ row.visible === 'SHOW' ? '显示' : '隐藏' }}</template></el-table-column>
        <el-table-column label="操作" width="195"><template #default="{row}"><el-button link type="primary" @click="edit(row)">修改</el-button><el-button v-if="row.menuType === 'M'" link type="primary" @click="edit(undefined,row.id)">新增</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
      </el-table>
    </div>
    <el-dialog class="menu-config-dialog" top="6vh" v-model="opened" :title="form.id ? '修改菜单' : '新增菜单'" width="620px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="上级目录"><el-tree-select v-model="form.parentId" :data="parentOptions" node-key="id" :props="{label:'menuName',children:'children'}" check-strictly default-expand-all style="width:100%" /></el-form-item>
        <el-form-item label="菜单类型"><el-radio-group v-model="form.menuType"><el-radio value="M">目录</el-radio><el-radio value="C">菜单</el-radio></el-radio-group></el-form-item>
        <el-form-item label="菜单名称" required><el-input v-model="form.menuName" maxlength="64" /></el-form-item>
        <el-form-item v-if="form.menuType === 'C'" label="关联页面" required><el-select v-model="form.path" filterable style="width:100%"><el-option v-for="p in pages" :key="p.path" :value="p.path" :label="p.title + ' · ' + p.path" /></el-select></el-form-item>
        <el-form-item label="菜单图标"><el-select v-model="form.icon" filterable><el-option v-for="icon in icons" :key="icon" :value="icon" :label="icon"><el-icon><component :is="icon" /></el-icon> {{ icon }}</el-option></el-select></el-form-item>
        <el-form-item label="显示排序"><el-input-number v-model="form.sortNo" :min="0" :max="9999" /></el-form-item>
        <el-form-item label="显示状态"><el-radio-group v-model="form.visible"><el-radio value="SHOW">显示</el-radio><el-radio value="HIDE">隐藏</el-radio></el-radio-group></el-form-item>
        <el-form-item label="菜单状态"><el-radio-group v-model="form.status"><el-radio value="ENABLE">启用</el-radio><el-radio value="DISABLE">停用</el-radio></el-radio-group></el-form-item>
      </el-form>
      <template #footer><el-button @click="opened=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, put, del } from '@/api/request'
import { menuTree } from '@/utils/navigation.mjs'
import { useNavigationStore } from '@/store/navigation'
const router=useRouter(), navigation=useNavigationStore()
const rows=ref<any[]>([]), keyword=ref(''), expanded=ref(true), opened=ref(false), loading=ref(false), saving=ref(false), form=ref<any>({})
const icons=['Menu','FolderOpened','HomeFilled','EditPen','Coin','Money','Stamp','Timer','Document','DocumentCopy','DataAnalysis','View','User','Key','OfficeBuilding','Share','Setting','Odometer','Monitor','Connection','Bell']
const pages=router.getRoutes().filter(r => r.path !== '/' && !r.path.includes(':') && !['/login','/change-password','/president'].includes(r.path)).map(r => ({path:r.path,title:String(r.meta.title || r.path)}))
const tree=computed(() => menuTree(rows.value))
function filterTree(nodes:any[]):any[] {return nodes.flatMap(n => {const children=filterTree(n.children);return n.menuName.includes(keyword.value) ? [n] : children.length ? [{...n,children}] : []})}
const filteredTree=computed(() => keyword.value ? filterTree(tree.value) : tree.value)
const parentOptions=computed(() => {
  function dirs(nodes:any[]):any[] {return nodes.filter(n => n.menuType==='M' && n.id !== String(form.value.id)).map(n => ({...n,children:dirs(n.children)}))}
  return [{id:'0',menuName:'主目录',children:dirs(tree.value)}]
})
async function load(){loading.value=true;try{rows.value=await get('/system/menus')}finally{loading.value=false}}
function edit(row?:any,parentId='0'){form.value=row ? {...row,parentId:String(row.parentId)} : {parentId:String(parentId),menuName:'',menuType:'M',path:'',icon:'FolderOpened',sortNo:1,visible:'SHOW',status:'ENABLE'};opened.value=true}
async function save(){
  if(!form.value.menuName?.trim() || form.value.menuType==='C' && !form.value.path){ElMessage.warning('请填写名称并选择页面');return}
  saving.value=true
  try {const payload={...form.value,path:form.value.menuType==='M' ? '' : form.value.path};if(form.value.id) await put(`/system/menus/${form.value.id}`,payload);else await post('/system/menus',payload);opened.value=false;ElMessage.success('已保存');await load();await navigation.load(true)}finally{saving.value=false}
}
async function remove(row:any){try{await ElMessageBox.confirm(`确认删除「${row.menuName}」？`,'删除确认',{type:'warning'})}catch{return}await del(`/system/menus/${row.id}`);await load();await navigation.load(true);ElMessage.success('已删除')}
onMounted(load)
</script>

<style>
.menu-config-dialog .el-dialog__body { max-height: calc(88vh - 124px); overflow-y: auto; }
</style>
