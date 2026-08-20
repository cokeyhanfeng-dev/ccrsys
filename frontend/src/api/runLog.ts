import { get, request, download } from './request'

// 运行日志监控接口封装(增量 014):运行报错采集查询 + 后台日志文件查看,仅 admin 角色

export interface RunErrorRow {
  id: number
  errorTime?: string
  loggerName?: string
  level?: string
  threadName?: string
  requestUri?: string
  message?: string
  stackTrace?: string
  handleStatus?: string
  createTime?: string
}

export interface RunErrorStats {
  PENDING: number
  HANDLED: number
  IGNORED: number
  total: number
}

export interface LogFileInfo {
  name: string
  size: number
  lastModified: number
}

/** 运行报错分页查询(级别/状态/时间范围/关键词过滤) */
export const pageRunErrors = (params?: {
  keyword?: string
  level?: string
  status?: string
  startTime?: string
  endTime?: string
  pageNum?: number
  pageSize?: number
}) => get<{ total: number; records: RunErrorRow[] }>('/system/run-log', params || {})

/** 运行报错详情(含完整堆栈) */
export const getRunErrorDetail = (id: number | string) =>
  get<RunErrorRow>('/system/run-log/detail', { id })

/** 运行报错统计(按处理状态计数,页面徽标) */
export const getRunErrorStats = () => get<RunErrorStats>('/system/run-log/stats')

/** 标记处理状态(PENDING/HANDLED/IGNORED) */
export const updateRunErrorStatus = (id: number | string, status: string) =>
  request({ url: '/system/run-log/status', method: 'put', params: { id, status } })

/** 筛选下拉(级别/状态/高频 logger) */
export const getRunErrorOptions = () =>
  get<{ levels: string[]; statuses: string[]; loggers: string[] }>('/system/run-log/options')

/** 后台日志文件列表 */
export const listLogFiles = () => get<LogFileInfo[]>('/system/run-log/files')

/** 日志文件尾部预览 */
export const tailLogFile = (name: string, lines = 300) =>
  get<{ name: string; lines: string[] }>('/system/run-log/files/tail', { name, lines })

/** 下载日志文件(走 request.ts download,blob + Content-Disposition 取文件名) */
export const downloadLogFile = (name: string) =>
  download(`/system/run-log/files/download?name=${encodeURIComponent(name)}`)
