import { get, post, put, request } from './request'
import { nodeLabel } from '@/utils/dict'

/**
 * 申请向导(贷款/存款)接口封装
 * 以后端 ccr-application 模块 Controller/DTO 为准:
 * CcrApplicationController / GroupQueryController / CustomerController / OtherLoanImportController
 */

// ---------- 请求类型(与后端 DTO 对齐) ----------

/** 集团申请成员录入(MemberInput) */
export interface MemberInput {
  memberCustomerNo: string
  requestAmount: number | string
  currency: string
  memberRole?: string
}

/** 担保措施(CcrGuaranteeMeasure 接收键) */
export interface GuaranteeMeasureInput {
  measureType: string
  guarantorCustomerNo?: string
  collateralNo?: string
  guaranteeAmount?: number | string
  currency?: string
  extJson?: Record<string, any>
}

/** 贷款担保切分分项(guarantees 列表元素) */
export interface GuaranteeInput {
  requestedRate: number | string
  productCode: string
  termValue: number | string
  termUnit: string
  amount: number | string
  currency?: string
  originalRate?: number | string
  memberCustomerNo?: string
  contractBusinessKey?: string
  plannedContractFlag?: string
  guaranteeType?: string
  measures?: GuaranteeMeasureInput[]
}

/** 存款分项结构化录入(DepositItemInput) */
export interface DepositItemInput {
  memberCustomerNo?: string
  productCode: string
  termValue?: number | string
  termUnit?: string
  amount: number | string
  currency?: string
  requestedRate: number | string
  originalRate?: number | string
  depositAccountNo?: string
  plannedAccountFlag?: string
}

/** 拟达成贡献度承诺(CommitmentInput) */
export interface CommitmentInput {
  pricingItemNo?: string
  metricCode: string
  targetType: string
  baselineValue?: number | string
  targetValue: number | string
  unit?: string
  metricScope?: string
  memberCustomerNo?: string
}

/** 创建/保存申请请求体(CcrApplication) */
export interface ApplicationPayload {
  businessType: 'LOAN' | 'DEPOSIT'
  customerScope: 'INDIVIDUAL' | 'CORPORATE_SINGLE' | 'GROUP'
  customerNo?: string | null
  groupNo?: string | null
  members?: MemberInput[] | null
  guarantees?: GuaranteeInput[] | null
  depositItems?: DepositItemInput[] | null
  commitments?: CommitmentInput[] | null
  relatedPersons?: Array<{
    personName: string
    certNo?: string
    relationType: string
    relatedCustomerNo?: string
  }> | null
  otherLoans?: Array<{
    lenderName: string
    creditAmount?: number | string
    usedAmount?: number | string
    balanceAmount?: number | string
    annualRate?: number | string
    inputMode?: string
  }> | null
  applicantUserId?: number
  applicantOrgId?: number
  orgId?: number
  applicationRemark?: string
  versionNo?: number
  /** 客户信息人工修正快照(JSON 字符串;数仓带出后人工调整,新增客户后台拉不出时手工填写;审批详情优先展示) */
  customerInfoJson?: string | null
  /** 授信协议补录/修正快照(JSON 字符串;存量=协议带出可修正,新增=手工补录;审批详情授信信息区优先展示) */
  creditInfoJson?: string | null
  /** 集团补录/申请额度快照(JSON 字符串;集团对公全套 + 本次申请额度 + 手工补录成员;审批详情优先展示) */
  groupInfoJson?: string | null
}

// ---------- 响应类型 ----------

export interface ApplicationEntity {
  id: number
  applicationNo: string
  businessType: string
  customerScope: string
  customerNo?: string
  groupNo?: string
  status: string
  versionNo: number
  applicationRemark?: string
  sourceApplicationId?: number
}

/** 申请详情聚合(ApplicationDetailResponse) */
export interface ApplicationDetail {
  application: ApplicationEntity
  members: Array<{
    memberCustomerNo: string
    requestAmount?: number
    currency?: string
    memberRole?: string
    memberLimitAmount?: number
  }>
  pricingItems: Array<{
    id: number
    pricingItemNo: string
    memberCustomerNo?: string
    pricingCarrierType?: string
    productCode?: string
    termValue?: number
    termUnit?: string
    pricingAmount?: number
    currency?: string
    originalRate?: number
    requestedRate?: number
    status?: string
    inheritFlag?: string
  }>
  contractRelations: Array<{
    pricingItemId: number
    contractBusinessKey?: string
    loanContractNo?: string
    plannedContractFlag?: string
  }>
  depositRelations: Array<{
    pricingItemId: number
    plannedAccountFlag?: string
  }>
  guaranteePackages: Array<{
    guaranteePackage: { pricingItemId: number; mainGuaranteeType?: string }
    measures: Array<{
      measureType?: string
      guarantorCustomerNo?: string
      collateralNo?: string
      guaranteeAmount?: number
      currency?: string
      extJson?: Record<string, any>
    }>
  }>
  commitments: Array<{
    metricCode: string
    targetType: string
    baselineValue?: number
    targetValue?: number
    unit?: string
    metricScope?: string
    memberCustomerNo?: string
  }>
}

/** 路由预览(RoutePreviewResponse) */
export interface RoutePreview {
  applicationId: number
  groupCreditTotal?: number
  lprVersionId?: number
  lprVersionCode?: string
  items: Array<{
    pricingItemId: number
    pricingItemNo: string
    memberCustomerNo?: string
    productCode?: string
    requestedRate?: number
    rateDirection?: string
    startNodeCode?: string
    finalNodeCode?: string
    routeChain?: string[]
    hardBoundaryPass?: boolean
    hardBoundaryRate?: number
    lprVersionCode?: string
    message?: string
    errorCode?: number
    errorMessage?: string
  }>
}

/** 提交前校验(SubmitCheckResponse) */
export interface SubmitCheck {
  applicationId: number
  baselineSource?: string
  diffs: Array<{
    datasetCode: string
    baselineDataDt?: string
    latestDataDt?: string
    changed?: boolean
  }>
  qualityPrecheck: Array<{
    ruleCode: string
    level: 'PASS' | 'WARN' | 'BLOCK'
    subjectId?: string
    message?: string
  }>
  hardBoundaries: Array<{
    pricingItemId: number
    pricingItemNo?: string
    productCode?: string
    requestedRate?: number
    pass?: boolean
    boundaryRate?: number
    message?: string
  }>
  blockSubmit?: boolean
}

/** 提交结果(SubmitResponse) */
export interface SubmitResult {
  applicationId: number
  applicationNo: string
  status: string
  submitted?: boolean
  submitTime?: string
  items: Array<{
    pricingItemId: number
    pricingItemNo?: string
    status?: string
    currentNodeCode?: string
    routeCode?: string
    routeChain?: string[]
  }>
}

// ---------- 展示辅助(统一委托 @/utils/dict,避免多套映射) ----------

/** 流程节点编码 → 中文岗位名(ccr-approval RouteChains) */
export function nodeName(code?: string): string {
  return nodeLabel(code, '暂无数据')
}

// ---------- 客户/集团查询 ----------

/** 客户姓名模糊查询(对公+对私) */
export function searchCustomers(name: string) {
  return get<any[]>('/ccr/customers', { name })
}

/** 客户详情:基本信息+本行融资+当前贡献度+他行融资 */
/** 客户经理可访问的启用机构列表(开户机构下拉):数据源 ccr_sys_dept,接口 /ccr/customers/open-orgs */
export function getOpenOrgs<T = any[]>(): Promise<T> {
  return get<T>('/ccr/customers/open-orgs')
}

export function getCustomerDetail(customerNo: string) {
  return get<any>(`/ccr/customers/${customerNo}`)
}

/** 客户业务视图(账户/授信/合同/借据/担保/贡献度,最新批次) */
export function getCustomerBusinessView(customerNo: string) {
  return get<any>(`/ccr/customers/${customerNo}/business-view`)
}

/** 集团 + 集团授信概况 */
export function getGroup(groupNo: string) {
  return get<any>(`/ccr/groups/${groupNo}`)
}

/** 存款账号反查数仓(后端哈希匹配,命中返回账户信息,未命中返回 null) */
export function lookupDepositAccount(customerNo: string, accountNo: string) {
  return get<any>(`/ccr/customers/${customerNo}/deposit-account-lookup`, { accountNo })
}

/** 集团有效成员及额度 */
export function getGroupMembers(groupNo: string) {
  return get<any[]>(`/ccr/groups/${groupNo}/members`)
}

/** 集团联想(§13.1;按集团号/集团名模糊,申请页集团下拉) */
export function suggestGroups<T = any[]>(keyword: string): Promise<T> {
  return get<T>('/ccr/groups/suggest', { keyword })
}

/** 成员额度/用信分项/合同/借据/担保视图 */
export function getMemberCreditView(customerNo: string) {
  return get<any>(`/ccr/members/${customerNo}/credit-view`)
}

// ---------- 申请创建/保存/提交 ----------

/** 创建草稿 */
export function createApplication(payload: ApplicationPayload) {
  return post<ApplicationEntity>('/ccr/applications', payload)
}

/** 保存草稿(必带 versionNo;后端仅更新主单字段,分项以创建时为准) */
// 注意:id 为雪花主键(>2^53),统一按 number|string 接收、模板字符串拼 URL,严禁 Number() 转换(丢精度导致"申请不存在")
export function saveApplication(id: number | string, payload: ApplicationPayload) {
  return put<ApplicationEntity>(`/ccr/applications/${id}`, payload)
}

/** 申请详情聚合 */
export function getApplicationDetail(id: number | string) {
  return get<ApplicationDetail>(`/ccr/applications/${id}`)
}

/** 逐分项路由预览 */
export function routePreview(id: number | string) {
  return post<RoutePreview>(`/ccr/applications/${id}/route-preview`)
}

/** 提交前校验(数据批次差异+质量预校验+硬边界) */
export function submitCheck(id: number | string) {
  return post<SubmitCheck>(`/ccr/applications/${id}/submit-check`)
}

/** 正式提交(幂等) */
export function submitApplication(id: number | string) {
  return post<SubmitResult>(`/ccr/applications/${id}/submit`)
}

/** 关联重提:基于终态原申请生成新草稿 */
export function reapplyApplication(id: number | string) {
  return post<ApplicationEntity>(`/ccr/applications/${id}/reapply`)
}

/** 他行融资明细 Excel 导入解析 */
export function importOtherLoans(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return request<any[]>({ url: '/ccr/other-loans/import', method: 'post', data: formData })
}

/** 附件上传(单文件,材料附件步骤) */
export async function uploadAttachment(applicationId: string | number, file: File) {
  const fd = new FormData()
  fd.append('file', file)
  const resp = await fetch(`/api/ccr/applications/${applicationId}/attachments`, {
    method: 'POST',
    headers: { Authorization: localStorage.getItem('ccr_token') || '' },
    body: fd
  })
  const res = await resp.json()
  if (res.code !== 200) throw new Error(res.msg || '附件上传失败')
  return res.data
}

/** 附件列表 */
export function listAttachments(applicationId: string | number) {
  return get<any[]>(`/ccr/applications/${applicationId}/attachments`)
}
