import { get } from './request'

// 数据中心接口封装(§9.6 F8;全角色可见)

/** 批次落地监控行:各表最新批次的数据日期/行数/落地时间(后端返回 table/sourceName/latestDataDt/batchRows/landedTime) */
export interface BatchLandingRow {
  table?: string
  sourceName?: string
  latestDataDt?: string
  batchRows?: number
  landedTime?: string
  [key: string]: any
}

/** 数据源时效行:status OK/STALE(后端返回 table/sourceName/latestDataDt/delayDays/thresholdDays/status) */
export interface SourceStatusRow {
  table?: string
  sourceName?: string
  latestDataDt?: string
  delayDays?: number
  thresholdDays?: number
  status?: string
  [key: string]: any
}

/** 批次落地监控 */
export const listBatches = () => get<BatchLandingRow[]>('/ccr/datacenter/batches')

/** 数据源时效看板 */
export const listSourceStatus = () => get<SourceStatusRow[]>('/ccr/datacenter/source-status')
