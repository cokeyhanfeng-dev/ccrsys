import { get, request } from './request'

// 审批接口封装(/ccr/approval,Wave2 口径:操作人取登录人,不再传 operatorId;approve/reject 必传 versionNo)

/** 当前登录人待办(按角色过滤,无参) */
export function listApprovalTasks<T = any[]>(): Promise<T> {
  return get<T>('/ccr/approval/tasks')
}

/** 审批详情:分项+申请+客户+融资+贡献度+担保+路由链+资料校验+拟达成贡献度+流程轨迹(+集团成员) */
export function getApprovalDetail<T = any>(pricingItemId: number | string): Promise<T> {
  return get<T>(`/ccr/approval/${pricingItemId}/detail`)
}

export interface ApprovalActionBody {
  pricingItemId: number
  nodeCode: string
  adjustRate?: number | string | null
  comment?: string
  versionNo: number
}

/** 普通节点通过(可携带权限内调价 adjustRate);Idempotency-Key 头可选 */
export function approveTask(body: ApprovalActionBody, idempotencyKey?: string): Promise<void> {
  return request<void>({
    url: '/ccr/approval/tasks/approve',
    method: 'post',
    data: body,
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined
  })
}

/** 普通节点否决;Idempotency-Key 头可选 */
export function rejectTask(body: ApprovalActionBody, idempotencyKey?: string): Promise<void> {
  return request<void>({
    url: '/ccr/approval/tasks/reject',
    method: 'post',
    data: body,
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined
  })
}

/** 历史审批分页(按登录人角色/数据权限) */
export function pageApprovalHistory<T = any>(pageNum = 1, pageSize = 10): Promise<T> {
  return get<T>('/ccr/approval/history', { pageNum, pageSize })
}

/** 生成幂等键(浏览器原生 uuid) */
export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}
