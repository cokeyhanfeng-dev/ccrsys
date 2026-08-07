import { get, download } from './request'

// 历史审批/档案接口封装(W3-C)

/** 历史审批分页:{ total, records } */
export const pageHistory = (pageNum: number, pageSize: number) =>
  get<{ total: number; records: any[] }>('/ccr/approval/history', { pageNum, pageSize })

/** 申请审批档案(§14.4 全区块 Map) */
export const getArchive = (applicationId: number | string) =>
  get<Record<string, any>>(`/ccr/approval/history/${applicationId}`)

/** 档案导出(xlsx,含水印行;仅 admin/auditor/president) */
export const exportArchive = (applicationId: number | string) =>
  download(`/ccr/approval/history/${applicationId}/export`)
