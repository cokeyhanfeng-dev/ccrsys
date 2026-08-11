import { get } from './request'

// F2 新增接口封装:已办列表/委员匿名意见/产品边界/承诺计划详情与月报
// 身份口径:一律取登录态,不传 operatorId/roleCode

/** 已办列表(§11.4):当前登录人办理过的审批任务 */
export function listApprovalDone<T = any[]>(): Promise<T> {
  return get<T>('/ccr/approval/done')
}

/** 批次委员匿名意见(§12.7,仅行长/审计):[{pricingItemId, opinions:[{anonymNo,voteChoice,voteComment,submitTime}]}] */
export function listRoundOpinions<T = any[]>(roundId: number | string): Promise<T> {
  return get<T>(`/ccr/vote-rounds/${roundId}/opinions`)
}

/** 产品标准与业务硬边界(公开只读端点 /ccr/products/rate-limits,登录即可;原 /system/flow/thresholds/product-limit 仅 admin/config_reviewer,客户经理访问 403) */
export function listProductLimits<T = any[]>(status?: string): Promise<T> {
  return get<T>('/ccr/products/rate-limits', status ? { status } : {})
}

/** 承诺计划详情(指标完成进度+评估历史) */
export function getCommitmentPlanDetail<T = any>(planId: number | string): Promise<T> {
  return get<T>(`/ccr/commitments/plans/${planId}`)
}

/** 承诺月报(§12.11):month=yyyy-MM,orgId 可选(缺省按登录人机构) */
export function getCommitmentMonthlyReport<T = any>(month: string, orgId?: number | string): Promise<T> {
  return get<T>('/ccr/commitments/monthly-report', orgId ? { month, orgId } : { month })
}
