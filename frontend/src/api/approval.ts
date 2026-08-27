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
  // 雪花 id 为 19 位,超出 JS Number 安全整数,必须传字符串避免精度丢失
  pricingItemId: number | string
  nodeCode: string
  adjustRate?: number | string | null
  // 同申请其余分项(随整单推进的 sibling)调价利率:分项id→调整后利率,仅收录相对当前利率有变化的分项;
  // 后端按调整后利率重算矩阵路由并按新链路推进,修复合单上送时非触发分项利率修改被丢弃的问题
  rateAdjustments?: Record<string, number | string>
  comment?: string
  versionNo: number | string
}

/** 审批提交成功后的流转去向(审批提交成功提示):terminal=终审结束,nextNodeCode=下一节点 */
export interface ApprovalResult {
  terminal?: boolean
  nextNodeCode?: string | null
}

/** 普通节点通过(可携带权限内调价 adjustRate);Idempotency-Key 头可选 */
export function approveTask(body: ApprovalActionBody, idempotencyKey?: string): Promise<ApprovalResult> {
  return request<ApprovalResult>({
    url: '/ccr/approval/tasks/approve',
    method: 'post',
    data: body,
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined
  })
}

/** 普通节点否决(2026-08-27 逐项否决,返回流转去向与 approve 同构);Idempotency-Key 头可选 */
export function rejectTask(body: ApprovalActionBody, idempotencyKey?: string): Promise<ApprovalResult> {
  return request<ApprovalResult>({
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

/** 审批中客户号回填(2026-08-20 #017):占位号→真实号;body 传 customerNo 或 certNo 二选一 */
export function backfillCustomerNo(pricingItemId: number | string, body: { customerNo?: string; certNo?: string }): Promise<void> {
  return request<void>({
    url: `/ccr/approval/${pricingItemId}/backfill-customer-no`,
    method: 'post',
    data: body
  })
}

/**
 * 生成幂等键:优先浏览器原生 uuid;http://IP 明文访问(非 secure context)下
 * crypto.randomUUID 为 undefined,调用即抛 TypeError——生产审批"点通过没反应"根因
 * (2026-08-21),此处降级为时间戳+随机数兜底,保证幂等键仍全局唯一。
 */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `k-${Date.now()}-${Math.random().toString(16).slice(2)}`
}
