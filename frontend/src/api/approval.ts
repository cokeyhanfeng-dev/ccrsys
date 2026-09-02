import { get, request } from './request'

// 审批接口封装(/ccr/approval,Wave2 口径:操作人取登录人,不再传 operatorId;approve/reject 必传 versionNo)

/** 当前登录人待办(按角色过滤,无参) */
export function listApprovalTasks<T = any[]>(): Promise<T> {
  return get<T>('/ccr/approval/tasks')
}

/** 审批详情:整单维度(2026-08-29 整单交付:传 applicationId;分项+申请+客户+融资+贡献度+担保+整单路由链+资料校验+拟达成贡献度+流程轨迹(+集团成员)) */
export function getApprovalDetail<T = any>(applicationId: number | string): Promise<T> {
  return get<T>(`/ccr/approval/${applicationId}/detail`)
}

export interface ApprovalActionBody {
  // 整单交付改造(2026-08-29):审批目标为申请单 applicationId(雪花 id 19 位传字符串避免精度丢失)
  applicationId: number | string
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

/** 普通节点否决(整单交付:任一节点否决→整单一起 REJECTED/VETOED,返回流转去向与 approve 同构);Idempotency-Key 头可选 */
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

/** 节点进入自动回填结果(§2026-09-02 #460):applicable=是否适用单户通道;backfilled=本次是否实际回填;customerNo=回填后真实号 */
export interface AutoBackfillResult {
  applicable?: boolean
  backfilled?: boolean
  customerNo?: string | null
}

/** 节点进入审批页面自动回填(§2026-09-02 决策二):单户占位申请按证件号反查数仓主档,
 *  命中即整单占位→真实并级联(客户号+客户其他信息+关联人);未命中不写库不阻塞。幂等(主单已真实直接返回)。 */
export function autoBackfillCustomerNo<T = AutoBackfillResult>(applicationId: number | string): Promise<T> {
  return request<T>({
    url: `/ccr/approval/${applicationId}/auto-backfill-customer-no`,
    method: 'post'
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
