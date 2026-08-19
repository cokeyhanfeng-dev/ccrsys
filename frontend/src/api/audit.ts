import { get } from './request'

// 审计管理接口封装(§12.14;ballot-detail 仅 auditor,export-records 限 auditor/admin,后端做角色校验)

/** 实际投票人反查结果行(真实投票人/票型/匿名码对照;敏感查询后端留痕) */
export interface BallotDetailRow {
  voterName?: string
  userName?: string
  postName?: string
  orgName?: string
  ballotType?: string
  voteTime?: string
  anonymousCode?: string
  [key: string]: any
}

/** 实际投票人查询:按表决批次(roundId)+分项(pricingItemId)反查 */
export const getBallotDetail = (params: { roundId?: number | string; pricingItemId?: number | string }) =>
  get<BallotDetailRow[]>('/ccr/audit/ballot-detail', params)

/** 导出记录(档案导出留痕:导出人/机构/时间/导出对象) */
export const listExportRecords = () => get<any[]>('/ccr/audit/export-records')

/** 配置变更日志(§8A.2 配置版本查询;可按配置域/记录主键过滤) */
export const listConfigChangeLog = (params?: { configType?: string; configId?: number }) =>
  get<any[]>('/system/flow/thresholds/change-log', params || {})

/** 审计日志查询(§12.14/§15.2:登录/提交/字段级修改/配置/反查等全程留痕;可按类型/操作人/时间/关键词过滤,分页) */
export const listAuditLogs = (params?: {
  logType?: string
  operator?: string
  startTime?: string
  endTime?: string
  keyword?: string
  pageNum?: number
  pageSize?: number
}) => get<{ total: number; records: any[] }>('/ccr/audit/logs', params || {})
