import { get } from './request'

// 贡献度跟踪(承诺)接口封装(W3-C)

/** 承诺计划列表(按角色数据权限;operatorId 必填,roleCode 决定数据范围) */
export const listCommitmentPlans = (operatorId: number, roleCode?: string) =>
  get<any[]>('/ccr/commitments/plans', roleCode ? { operatorId, roleCode } : { operatorId })

/** 跟踪策略列表 */
export const listTrackingPolicies = (metricCode?: string) =>
  get<any[]>('/ccr/commitments/policies', metricCode ? { metricCode } : {})

/** 策略试算(§11.7):传入历史计划,返回命中策略与预警判定 */
export const simulatePolicy = (planId: number) =>
  get<Record<string, any>>('/ccr/commitments/policies/simulate', { planId })
