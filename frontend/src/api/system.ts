import { get, post, put, del } from './request'

// 系统管理/参数管理接口封装(W3-C)
// 注意:/system/** 后端仅 admin 角色可访问(thresholds 另放行 config_reviewer;非授权角色 403,由拦截器统一提示)

// ---------- 用户/角色 ----------
export interface SysRole {
  id: number
  roleCode: string
  roleName: string
  menuIds?: string
  remark?: string
  status?: string
}

export const listRoles = () => get<SysRole[]>('/system/roles')

// ---------- LPR 阈值(版本生命周期:DRAFT→REVIEW→EFFECTIVE→INVALID,发布双人复核) ----------
export const listLpr = (status?: string) =>
  get<any[]>('/system/flow/thresholds/lpr', status ? { status } : {})
export const createLpr = (data: object) => post<number>('/system/flow/thresholds/lpr', data)
export const submitLpr = (id: number) => post(`/system/flow/thresholds/lpr/${id}/submit`)
export const publishLpr = (id: number) => post(`/system/flow/thresholds/lpr/${id}/publish`)
export const disableLpr = (id: number) => post(`/system/flow/thresholds/lpr/${id}/disable`)

// ---------- LPR 明细(§8A.3;按版本×期限×产品逐行,矩阵式编辑,保存为全量替换) ----------
export const listLprConfigs = (versionId: number) =>
  get<any[]>('/system/lpr-configs', { versionId })
export const saveLprConfigs = (configs: object[]) =>
  post('/system/lpr-configs', configs)

// ---------- 权限矩阵阈值(同构生命周期) ----------
export const listMatrix = (status?: string) =>
  get<any[]>('/system/flow/thresholds/matrix', status ? { status } : {})
export const createMatrix = (data: object) => post<number>('/system/flow/thresholds/matrix', data)
export const submitMatrix = (id: number) => post(`/system/flow/thresholds/matrix/${id}/submit`)
export const publishMatrix = (id: number) => post(`/system/flow/thresholds/matrix/${id}/publish`)
export const disableMatrix = (id: number) => post(`/system/flow/thresholds/matrix/${id}/disable`)

// ---------- 利率规则集(同构生命周期;发布前连续性校验) ----------
export const listRuleSets = (status?: string) =>
  get<any[]>('/ccr/rule/set/list', status ? { status } : {})
export const createRuleSet = (data: object) => post<number>('/ccr/rule/set', data)
export const submitRuleSet = (id: number) => post(`/ccr/rule/set/${id}/submit`)
export const publishRuleSet = (id: number) => post(`/ccr/rule/set/${id}/publish`)
export const disableRuleSet = (id: number) => post(`/ccr/rule/set/${id}/disable`)

// ---------- 权限矩阵路由试算 ----------
export const matrixRoute = (data: object) => post<any>('/ccr/rule/matrix-route', data)

// ---------- 机构管理(§12.18;新增默认停用,停用/删除前置校验存量业务并返回笔数) ----------
export interface SysDept {
  id: number
  parentId?: number
  orgCode?: string
  branchCode?: string
  deptName: string
  orgType?: string
  /** 支行性质(2026-09-04 综合/零售两级支行):仅 BRANCH 机构有意义,RETAIL零售/COMPREHENSIVE综合/空=综合 */
  branchType?: string
  manager?: string
  status?: string
  sortNo?: number
  children?: SysDept[]
}

export const listDepts = () => get<SysDept[]>('/system/depts')
export const listDeptTree = () => get<SysDept[]>('/system/depts/tree')
export const createDept = (data: object) => post<SysDept>('/system/depts', data)
export const updateDept = (id: number, data: object) => put(`/system/depts/${id}`, data)
export const deleteDept = (id: number) => del(`/system/depts/${id}`)
export const updateDeptStatus = (id: number, status: 'ENABLE' | 'DISABLE') =>
  put(`/system/depts/${id}/status`, { status })

// ---------- 用户管理(筛选:机构/角色/状态/关键字) ----------
export interface UserQuery {
  username?: string
  roleCode?: string
  orgId?: number | ''
  status?: string
  keyword?: string
}
export interface UserPage {
  total: number
  records: any[]
}
export const listUsers = (params: UserQuery & { pageNum?: number; pageSize?: number }) =>
  get<UserPage>('/system/users', params)
export const createUser = (data: object) => post<any>('/system/users', data)
export const updateUser = (id: number, data: object) => put(`/system/users/${id}`, data)
export const updateUserStatus = (id: number, status: string) => put(`/system/users/${id}/status`, { status })
export const deleteUser = (id: number) => del(`/system/users/${id}`)

// ---------- 用户机构-岗位绑定(§11.12;数组整体替换,恰一条 isDefault='1') ----------
export interface UserBinding {
  id?: number
  userId?: number
  orgId: number | ''
  postCode: string
  isDefault: string
  orgName?: string
  orgCode?: string
}
export const getUserBinding = (userId: number) => get<UserBinding[]>(`/system/users/${userId}/binding`)
export const saveUserBinding = (userId: number, bindings: UserBinding[]) =>
  put(`/system/users/${userId}/binding`, bindings)

// ---------- 产品硬边界(§8A.5/§11.9;同 LPR 生命周期,发布双人复核) ----------
export const listProductLimit = (status?: string) =>
  get<any[]>('/system/flow/thresholds/product-limit', status ? { status } : {})
export const createProductLimit = (data: object) => post<number>('/system/flow/thresholds/product-limit', data)
export const submitProductLimit = (id: number) => post(`/system/flow/thresholds/product-limit/${id}/submit`)
export const publishProductLimit = (id: number) => post(`/system/flow/thresholds/product-limit/${id}/publish`)
export const disableProductLimit = (id: number) => post(`/system/flow/thresholds/product-limit/${id}/disable`)
// 驳回意见为 query 参数(后端 @RequestParam)
export const rejectProductLimit = (id: number, opinion: string) =>
  post(`/system/flow/thresholds/product-limit/${id}/reject?opinion=${encodeURIComponent(opinion)}`)

// ---------- 产品目录(§8A.5①;启用产品为 LPR 明细/矩阵/申请页的权威下拉) ----------
// 公开只读端点(/ccr/products):申请页客户经理可读,替代 /system/product(仅 admin)
export const listEnabledProducts = (businessBigType?: string) =>
  get<any[]>('/ccr/products/enabled', businessBigType ? { businessBigType } : {})

// ---------- 产品配置中心(P2-4;/system/product/** 仅 admin) ----------
export const listProductCatalog = (businessBigType?: string, status?: string) =>
  get<any[]>('/system/product/catalog', {
    ...(businessBigType ? { businessBigType } : {}),
    ...(status ? { status } : {})
  })
export const createProduct = (data: object) => post<number>('/system/product/catalog', data)
export const updateProduct = (id: number, data: object) => put(`/system/product/catalog/${id}`, data)
// 启停为 query 参数(后端 @RequestParam)
export const changeProductStatus = (id: number, status: string) =>
  post(`/system/product/catalog/${id}/status?status=${encodeURIComponent(status)}`)
export const deleteProduct = (id: number) => del(`/system/product/catalog/${id}`)

export const listProductRoutes = (productCode?: string, status?: string) =>
  get<any[]>('/system/product/routes', {
    ...(productCode ? { productCode } : {}),
    ...(status ? { status } : {})
  })
export const createProductRoute = (data: object) => post<number>('/system/product/routes', data)
export const submitProductRoute = (id: number) => post(`/system/product/routes/${id}/submit`)
export const publishProductRoute = (id: number) => post(`/system/product/routes/${id}/publish`)
export const rejectProductRoute = (id: number, opinion: string) =>
  post(`/system/product/routes/${id}/reject?opinion=${encodeURIComponent(opinion)}`)
export const disableProductRoute = (id: number) => post(`/system/product/routes/${id}/disable`)
export const deleteProductRoute = (id: number) => del(`/system/product/routes/${id}`)

// ---------- 配置变更日志(§8A.2;configType: LPR/MATRIX/RULE_SET/PRODUCT_LIMIT) ----------
export interface ConfigChangeLog {
  id: number
  configType: string
  configId: number
  versionNo?: number
  action: string
  oldJson?: string
  newJson?: string
  opinion?: string
  operatorId?: number
  operateTime?: string
}
export const listChangeLogs = (configType?: string, configId?: number | '') =>
  get<ConfigChangeLog[]>('/system/flow/thresholds/change-log', {
    ...(configType ? { configType } : {}),
    ...(configId ? { configId } : {})
  })

// ---------- 流程定义 ----------
export const listFlowDefinitions = () => get<any[]>('/system/flow/definitions')
export const publishFlowDefinition = (id: number) => post(`/system/flow/definitions/${id}/publish`, {})
export const unpublishFlowDefinition = (id: number) => post(`/system/flow/definitions/${id}/unpublish`, {})

// ---------- 节点审批人员指派(§12.17;仅 admin) ----------
export interface FlowNode {
  nodeCode: string
  nodeName?: string
  assigneeCount?: number
}

export interface NodeAssignee {
  id?: number
  flowKey?: string
  nodeCode: string
  assigneeType: string // PERSON/GROUP/ROLE/DEPT
  assigneeCode: string
  relation?: string // AND/OR
  isPrimary?: string
  delegateTo?: string
  delegateStart?: string
  delegateEnd?: string
  validFrom?: string
  validTo?: string
  sort?: number
  remark?: string
}

export const listFlowNodes = () => get<FlowNode[]>('/system/flow/nodes')
export const listAssignees = (params?: { nodeCode?: string; flowKey?: string }) =>
  get<NodeAssignee[]>('/system/flow/assignees', params || {})
export const createAssignee = (data: object) => post<number>('/system/flow/assignees', data)
export const updateAssignee = (id: number, data: object) => put(`/system/flow/assignees/${id}`, data)
export const deleteAssignee = (id: number) => del(`/system/flow/assignees/${id}`)
export const delegateAssignee = (id: number, data: { delegateTo: string; delegateStart?: string; delegateEnd?: string }) =>
  post(`/system/flow/assignees/${id}/delegate`, data)
// 解析预览:选节点+机构→实际处理人;未命中返回空数组,前端红字提示角色兜底
export const resolveAssignees = (data: { nodeCode: string; orgId?: number | ''; asOfTime?: string }) =>
  post<any[]>('/system/flow/assignees/resolve', data)

// ---------- 部门-分管行领导映射(§D16a;一人可分管多部门,纯配置) ----------
export interface DeptVp {
  id: number
  deptCode: string
  deptName?: string
  vpUserId: string
  vpUsername?: string
  vpNickName?: string
  status: string
  validFrom?: string
  validTo?: string
  versionNo: number
  createTime?: string
}
export const listDeptVp = () => get<DeptVp[]>('/system/flow/dept-vp')
export const createDeptVp = (data: object) => post<number>('/system/flow/dept-vp', data)
export const updateDeptVp = (id: number, data: object) => put(`/system/flow/dept-vp/${id}`, data)
export const deleteDeptVp = (id: number) => del(`/system/flow/dept-vp/${id}`)

// ---------- 缓存项配置(§3.6 v2;仅 admin;DB 动态定义 + 配置化刷新,立即生效不重启) ----------
export interface CacheConfigItem {
  itemKey: string
  key?: string
  keyPattern?: string
  enabled: boolean
  ttlSeconds?: number
  description?: string
  dataLoader?: string
  loaderParam?: string
  builtin: boolean // 内置项不可删/不可改 key
  source: string // 恒为 DB(定义存于配置表)
}

export interface CacheConfigCreateData {
  itemKey: string
  cacheKey?: string
  keyPattern?: string
  enabled?: boolean
  ttlSeconds?: number
  description?: string
  dataLoader?: string
  loaderParam?: string
}

export interface CacheLoaderInfo {
  code: string
  name: string
}

export const listCacheConfigs = () => get<CacheConfigItem[]>('/system/cache-configs')
export const createCacheConfig = (data: CacheConfigCreateData) => post('/system/cache-configs', data)
export const updateCacheConfig = (itemKey: string, data: object) =>
  put(`/system/cache-configs/${itemKey}`, data)
export const deleteCacheConfig = (itemKey: string) => del(`/system/cache-configs/${itemKey}`)
export const refreshCacheConfig = (itemKey: string) =>
  post<{ count: number }>(`/system/cache-configs/${itemKey}/refresh`)
export const listCacheLoaders = () => get<CacheLoaderInfo[]>('/system/cache-configs/loaders')

/** 流程定义详情(节点+跳转,流程图查看) */
export const getFlowDefinitionDetail = (id: number | string) => get<any>(`/system/flow/definitions/${id}/detail`)

// ---------- 指标字典管理(§9;仅 admin;数仓按 ccr_metric_definition 字典推送指标数据) ----------
// 公开只读端点(/ccr/metric-definitions/enabled)供申请页/跟踪策略下拉,此处为后台管理 CRUD。
// 指标编码一经创建不可改(防历史承诺跟踪错位);停用后新承诺/新策略不可选,历史跟踪不受影响。
export interface MetricDefinition {
  id: number
  metricCode: string
  metricName: string
  valueType?: string
  metricScope?: string
  unit?: string
  currentCalcVersion?: string
  status?: string
}
export const listMetricDefinitions = (status?: string, keyword?: string) =>
  get<MetricDefinition[]>('/system/metric-definitions', {
    ...(status ? { status } : {}),
    ...(keyword ? { keyword } : {})
  })
export const createMetricDefinition = (data: object) => post<number>('/system/metric-definitions', data)
export const updateMetricDefinition = (id: number, data: object) => put(`/system/metric-definitions/${id}`, data)
// 启停为 query 参数(后端 @RequestParam)
export const changeMetricStatus = (id: number, status: string) =>
  post(`/system/metric-definitions/${id}/status?status=${encodeURIComponent(status)}`)

// ---------- 手工集团主数据(系统级;数仓未统计集团/成员手动补录,合并查询用;仅 admin) ----------
export interface ManualGroup {
  id?: number
  groupNo: string
  groupName: string
  groupType?: string
  managerOrgId?: number | ''
  groupStatus?: string
  approvedTotalAmount: number | string
  currency?: string
  remark?: string
  createTime?: string
}
export interface ManualGroupMember {
  id?: number
  groupNo?: string
  memberCustomerNo: string
  memberName: string
  memberRole?: string // CORE/GENERAL
  controlRelation?: string
  relationStart?: string
  relationEnd?: string
}
export const listManualGroups = (params: { keyword?: string; pageNum?: number; pageSize?: number }) =>
  get<{ total: number; records: ManualGroup[] }>('/system/manual-group/page', params)
export const getManualGroupDetail = (groupNo: string) =>
  get<{ group: ManualGroup; members: ManualGroupMember[] }>(`/system/manual-group/${groupNo}`)
export const saveManualGroup = (data: object) => post<ManualGroup>('/system/manual-group', data)
export const deleteManualGroup = (groupNo: string) => post(`/system/manual-group/${groupNo}/delete`)
export const saveManualGroupMembers = (groupNo: string, members: ManualGroupMember[]) =>
  post(`/system/manual-group/${groupNo}/members`, members)
export const deleteManualGroupMember = (id: number) => post(`/system/manual-group/member/${id}/delete`)
