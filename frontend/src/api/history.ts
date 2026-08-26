import { get, download } from './request'

// 历史审批/档案接口封装(W3-C)

/** 历史审批分页:{ total, records };支持筛选(申请号模糊/状态逗号分隔多值/客户名称模糊,§13.2) */
export const pageHistory = (params: {
  pageNum: number
  pageSize: number
  applicationNo?: string
  status?: string
  keyword?: string
}) => get<{ total: number; records: any[] }>('/ccr/approval/history', params)

/** 申请审批档案(§14.4 全区块 Map) */
export const getArchive = (applicationId: number | string) =>
  get<Record<string, any>>(`/ccr/approval/history/${applicationId}`)

/** 档案导出(xlsx,含水印行;仅 admin/auditor/president) */
export const exportArchive = (applicationId: number | string) =>
  download(`/ccr/approval/history/${applicationId}/export`)

/** 决议书下载(Word;仅已签发决议的申请;数据权限同档案) */
export const downloadResolutionDoc = (applicationId: number | string) =>
  download(`/ccr/approval/history/${applicationId}/resolution-doc`)

/** 审批进度(§链路可视化):链路各节点流转状态 + 表决 n/6(admin/申请人/审批人可见) */
export const getApprovalProgress = (applicationId: number | string) =>
  get<Record<string, any>>(`/ccr/approval/progress`, { applicationId })
