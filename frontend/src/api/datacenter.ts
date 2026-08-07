import { get } from './request'

// 数据中心接口封装(§9.6 F8;全角色可见)

/** 批次落地监控行:各表最新批次的数据日期/行数/状态/耗时/指纹 */
export interface BatchLandingRow {
  tableName?: string
  batchNo?: string
  dataDate?: string
  rowCount?: number
  status?: string
  costMs?: number
  fingerprint?: string
  [key: string]: any
}

/** 数据源时效行:status OK/STALE */
export interface SourceStatusRow {
  sourceCode?: string
  sourceName?: string
  dataDate?: string
  status?: string
  [key: string]: any
}

/** 批次落地监控 */
export const listBatches = () => get<BatchLandingRow[]>('/ccr/datacenter/batches')

/** 数据源时效看板 */
export const listSourceStatus = () => get<SourceStatusRow[]>('/ccr/datacenter/source-status')
