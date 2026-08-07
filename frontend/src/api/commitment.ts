import { get } from './request'

// 贡献度跟踪(承诺)接口封装(W3-C)

/** 承诺计划列表(无参:数据范围由服务端按登录人角色确定,§5.4,前端不传 operatorId/roleCode) */
export const listCommitmentPlans = () => get<any[]>('/ccr/commitments/plans')

/** 跟踪策略列表 */
export const listTrackingPolicies = (metricCode?: string) =>
  get<any[]>('/ccr/commitments/policies', metricCode ? { metricCode } : {})

/** 策略试算(§11.7):传入历史计划,返回命中策略与预警判定 */
export const simulatePolicy = (planId: number) =>
  get<Record<string, any>>('/ccr/commitments/policies/simulate', { planId })
