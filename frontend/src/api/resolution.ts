import { get } from './request'

// 决议书查询接口封装(2026-09-08,resolution_query 专用)

/** 决议书查询页(仅「当前有效决议」):客户名称/客户号(兼集团号)/决议书编号 可组合子串模糊,分页;
 *  返回 { total, records };record 含 customerName/customerNo/groupNo/resolutionNo/executionStatus/issueTime/applicationId */
export const pageResolutions = (params: {
  pageNum: number
  pageSize: number
  customerName?: string
  customerNo?: string
  resolutionNo?: string
}) => get<{ total: number; records: any[] }>('/ccr/resolutions/query', params)
