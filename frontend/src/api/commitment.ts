import { get, post } from './request'

// 贡献度跟踪(承诺)接口封装(W3-C)

/** 承诺计划列表(无参:数据范围由服务端按登录人角色确定,§5.4,前端不传 operatorId/roleCode) */
export const listCommitmentPlans = () => get<any[]>('/ccr/commitments/plans')

/** 保存指标跟踪描述(§6.4/§10.3.15:承诺类型"其它"手工跟踪留痕,track_desc 覆盖式更新) */
export const saveMetricTrackDesc = (metricId: number, trackDesc: string) =>
  post<any>(`/ccr/commitments/metrics/${metricId}/track`, { trackDesc })

/** 跟踪策略列表 */
export const listTrackingPolicies = (metricCode?: string) =>
  get<any[]>('/ccr/commitments/policies', metricCode ? { metricCode } : {})

/** 策略试算(§11.7):传入历史计划,返回命中策略与预警判定 */
export const simulatePolicy = (planId: number) =>
  get<Record<string, any>>('/ccr/commitments/policies/simulate', { planId })

// ---------- 跟踪策略配置 CRUD(§11.5,补齐"参数管理"页签;状态迁移 POST 带 query 参数,故手动拼 URL) ----------

/** 新建策略(策略+首个版本+阈值一并提交,版本状态 DRAFT) */
export const createTrackingPolicy = (policy: any, version: any, thresholds: any[]) =>
  post<any>('/ccr/commitments/policies', { policy, version, thresholds })

/** 追加版本(含阈值,状态 DRAFT) */
export const createPolicyVersion = (policyId: number, version: any, thresholds: any[]) =>
  post<any>(`/ccr/commitments/policies/${policyId}/versions`, { version, thresholds })

/** 策略状态变迁(DRAFT/REVIEW/EFFECTIVE/INVALID) */
export const changePolicyStatus = (policyId: number, status: string) =>
  post<any>(`/ccr/commitments/policies/${policyId}/status?status=${encodeURIComponent(status)}`)

/** 版本状态变迁(置 EFFECTIVE 校验生效区间不重叠) */
export const changeVersionStatus = (versionId: number, status: string) =>
  post<any>(`/ccr/commitments/policies/versions/${versionId}/status?status=${encodeURIComponent(status)}`)

/** 策略版本列表 */
export const listPolicyVersions = (policyId: number) =>
  get<any[]>(`/ccr/commitments/policies/${policyId}/versions`)

/** 版本阈值列表 */
export const listPolicyThresholds = (versionId: number) =>
  get<any[]>(`/ccr/commitments/policies/versions/${versionId}/thresholds`)
